/* 
** Copyright [2012-2013] [Megam Systems]
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
** http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/
package controllers

import scalaz._
import Scalaz._
import scalaz.NonEmptyList._

import scalaz.Validation._
import play.api._
import play.api.mvc._
import play.api.mvc.SimpleResult
import models._
import controllers.stack._
import controllers.stack.APIAuthElement
import controllers.funnel.FunnelResponse
import controllers.funnel.FunnelErrors._
import org.megam.common.amqp._
/**
 * @author ram
 *
 */
object Requests extends Controller with APIAuthElement  {
  
  /*
   * parse.tolerantText to parse the RawBody 
   * get requested body and put into the riak bucket
   */
    def post = StackAction(parse.tolerantText) { implicit request =>
    play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Requests", "post:Entry"))

    (Validation.fromTryCatch[SimpleResult] {
      reqFunneled match {
        case Success(succ) => {
          val freq = succ.getOrElse(throw new Error("Request wasn't funneled. Verify the header."))
          val email = freq.maybeEmail.getOrElse(throw new Error("Email not found (or) invalid."))
          val clientAPIBody = freq.clientAPIBody.getOrElse(throw new Error("Body not found (or) invalid."))
          play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Request", "request funneled."))
          models.Requests.createforExistNode(clientAPIBody) match {
            case Success(succ) =>
              /*This isn't correct. Revisit, as the testing progresses.
               We need to trap success/fialures.
               */
              val tuple_succ = succ.getOrElse(("Nah", "Bah"))              
              MessageObjects.Publish(tuple_succ._1).dop.flatMap { x =>
                play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Requests", "published successfully."))
                Status(CREATED)(FunnelResponse(CREATED, """Request initiation instruction submitted successfully.
            |
            |The Request is working for you. It will be ready shortly.""", "Megam::Request").toJson(true)).successNel[Throwable]
              } match {
                //this is only a temporary hack.
                case Success(succ_cpc) => succ_cpc
                case Failure(err) =>
                  Status(BAD_REQUEST)(FunnelResponse(BAD_REQUEST, """Request initiation submission failed.
            |
            |Retry again, our queue servers are crowded""", "Megam::Request").toJson(true))
              }
            case Failure(err) => {
              val rn: FunnelResponse = new HttpReturningError(err)
              Status(rn.code)(rn.toJson(true))
            }
          }
        }
        case Failure(err) => {
          val rn: FunnelResponse = new HttpReturningError(err)
          Status(rn.code)(rn.toJson(true))
        }
      }
    }).fold(succ = { a: SimpleResult => a }, fail = { t: Throwable => Status(BAD_REQUEST)(t.getMessage) })
  }
  
  /*
   * GET: findByNodeName: Show requests for a  node name per user(by email)
   * Email grabbed from header
   * Output: JSON (RequestResults)  
   **/
  def show(id: String) = StackAction(parse.tolerantText) { implicit request =>
    play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Requests", "show:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("nodename", id))

    (Validation.fromTryCatch[SimpleResult] {
      reqFunneled match {
        case Success(succ) => {
          val freq = succ.getOrElse(throw new Error("Request wasn't funneled. Verify the header."))
          val email = freq.maybeEmail.getOrElse(throw new Error("Email not found (or) invalid."))
          play.api.Logger.debug(("%-20s -->[%s]").format("controllers.Node", "request funneled."))

          models.Requests.findByNodeName(List(id).some) match {
            case Success(succ) =>
              Ok(RequestResults.toJson(succ, true))
            case Failure(err) =>
              val rn: FunnelResponse = new HttpReturningError(err)
              Status(rn.code)(rn.toJson(true))
          }
        }
        case Failure(err) => {
          val rn: FunnelResponse = new HttpReturningError(err)
          Status(rn.code)(rn.toJson(true))
        }
      }
    }).fold(succ = { a: SimpleResult => a }, fail = { t: Throwable => Status(BAD_REQUEST)(t.getMessage) })

  }

  /*
   * GET: findbyEmail: List all the requests per email
   * Email grabbed from header.
   * Output: JSON (NodeResult)
   */
  def list = StackAction(parse.tolerantText) { implicit request =>
    (Validation.fromTryCatch[SimpleResult] {
      reqFunneled match {
        case Success(succ) => {
          val freq = succ.getOrElse(throw new Error("Request wasn't funneled. Verify the header."))
          val email = freq.maybeEmail.getOrElse(throw new Error("Email not found (or) invalid."))
          models.Requests.findByEmail(email) match {
            case Success(succ) => Ok(RequestResults.toJson(succ, true))
            case Failure(err) =>
              val rn: FunnelResponse = new HttpReturningError(err)
              Status(rn.code)(rn.toJson(true))
          }
        }
        case Failure(err) => {
          val rn: FunnelResponse = new HttpReturningError(err)
          Status(rn.code)(rn.toJson(true))
        }
      }
    }).fold(succ = { a: SimpleResult => a }, fail = { t: Throwable => Status(BAD_REQUEST)(t.getMessage) })
  }

}