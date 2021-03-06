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
package models

import scalaz._
import Scalaz._
import scalaz.effect.IO
import scalaz.syntax.SemigroupOps
import scalaz.NonEmptyList._
import scalaz.EitherT._
import scalaz.Validation._
import com.twitter.util.Time
import net.liftweb.json._
import java.nio.charset.Charset
import com.stackmob.scaliak._
import com.basho.riak.client.query.indexes.{ RiakIndexes, IntIndex, BinIndex }
import com.basho.riak.client.http.util.{ Constants => RiakConstants }
import org.megam.common.riak.{ GSRiak, GunnySack }
import org.megam.common.uid.UID
import models.cache._
import controllers.funnel.FunnelErrors._
import controllers.Constants._
import net.liftweb.json.scalaz.JsonScalaz.{ Result, UncategorizedError }
import controllers.stack.MConfig
/**
 * @author rajthilak
 * authority
 */

case class AppDefnsResult(id: String, node_id: String, appdefns: NodeAppDefns, created_at: String) {
val json = "{\"id\": \"" + id + "\",\"node_id\":\"" + node_id + "\",\"appdefns\":" + appdefns.json + ",\"created_at\":\"" + created_at + "\"}"
  
  def toJValue: JValue = {
    import net.liftweb.json.scalaz.JsonScalaz.toJSON
    import models.json.AppDefnsResultSerialization
    val acctser = new AppDefnsResultSerialization()
    toJSON(this)(acctser.writer)
  }

  def toJson(prettyPrint: Boolean = false): String = if (prettyPrint) {
    pretty(render(toJValue))
  } else {
    compactRender(toJValue)
  }

}

object AppDefnsResult {
  
  def apply = new AppDefnsResult(new String(), new String(), new NodeAppDefns(new String(),new String(), new String(), new String()), new String())
  
  //def apply(timetokill: String): AppDefnsResult = AppDefnsResult(timetokill, new String(), new String(), new String())
   
  def fromJValue(jValue: JValue)(implicit charset: Charset = UTF8Charset): Result[AppDefnsResult] = {
    import net.liftweb.json.scalaz.JsonScalaz.fromJSON
    import models.json.AppDefnsResultSerialization
    val acctser = new AppDefnsResultSerialization()
    fromJSON(jValue)(acctser.reader)
  }

  def fromJson(json: String): Result[AppDefnsResult] = (Validation.fromTryCatch {
    parse(json)
  } leftMap { t: Throwable =>
    UncategorizedError(t.getClass.getCanonicalName, t.getMessage, List())
  }).toValidationNel.flatMap { j: JValue => fromJValue(j) }

}


case class AppDefnsInput(node_id: String, appdefns: NodeAppDefns) {
  play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns:json", appdefns.json))
  val json = "\",\"node_id\":\"" + node_id + "\",\"appdefns\":" + appdefns.json 
}

object AppDefns {

  implicit val formats = DefaultFormats    
  
  private def riak: GSRiak = GSRiak(MConfig.riakurl, "appdefns")

  val metadataKey = "AppDefns"
  val newnode_metadataVal = "App Definition Creation"
  val newnode_bindex = BinIndex.named("appdefnsId")

  /**
   * A private method which chains computation to make GunnySack when provided with an input json, Option[node_id].
   * parses the json, and converts it to nodeinput, if there is an error during parsing, a MalformedBodyError is sent back.
   * After that flatMap on its success and the GunnySack object is built.
   * If the node_id is send by the Node model. It then yield the GunnySack object.
   */
  private def mkGunnySack(input: String): ValidationNel[Throwable, Option[GunnySack]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns", "mkGunnySack:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns:json", input))

    //Does this failure get propagated ? I mean, the input json parse fails ? I don't think so.
    //This is a potential bug.
    val ripNel: ValidationNel[Throwable, AppDefnsInput] = (Validation.fromTryCatch {
      parse(input).extract[AppDefnsInput]
    } leftMap { t: Throwable => new MalformedBodyError(input, t.getMessage) }).toValidationNel //capture failure

    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns:rip", ripNel))

    for {
      rip <- ripNel
      uir <- (UID(MConfig.snowflakeHost, MConfig.snowflakePort, "adf").get leftMap { ut: NonEmptyList[Throwable] => ut })
    } yield {
      //TO-DO: do we need a match for aor, and uir to filter the None case. confirm it during function testing.
      val bvalue = Set(rip.node_id)
      val json = "{\"id\": \"" + (uir.get._1 + uir.get._2) + rip.json + ",\"created_at\":\""+ Time.now.toString +"\"}"

      new GunnySack((uir.get._1 + uir.get._2), json, RiakConstants.CTYPE_TEXT_UTF8, None,
        Map(metadataKey -> newnode_metadataVal), Map((newnode_bindex, bvalue))).some
    }
  }

  /*
   * create new Node with the 'name' of the node provide as input.
   * A index name accountID will point to the "accounts" bucket
   */
  def create(input: String): ValidationNel[Throwable, Option[Tuple3[String, String, NodeAppDefns]]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns", "create:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("json", input))

    (mkGunnySack(input) leftMap { err: NonEmptyList[Throwable] =>
      err
    }).flatMap { gs: Option[GunnySack] =>
      (riak.store(gs.get) leftMap { t: NonEmptyList[Throwable] => t }).
        flatMap { maybeGS: Option[GunnySack] =>
          val adf_result = parse(gs.get.value).extract[AppDefnsResult]
          play.api.Logger.debug(("%-20s -->[%s]%nwith%n----%n%s").format("AppDefns.created successfully", "input", input))
          maybeGS match {
            case Some(thatGS) => (thatGS.key, adf_result.node_id, adf_result.appdefns).some.successNel[Throwable]
            case None => {
              play.api.Logger.warn(("%-20s -->[%s]").format("AppDefns.created success", "Scaliak returned => None. Thats OK."))
              (gs.get.key, adf_result.node_id, adf_result.appdefns).some.successNel[Throwable]
            }
          }
        }
    }
  }
  
  /**
   * List all the defns for the nodenamelist.
   */
  def findByReqName(defNameList: Option[List[String]]): ValidationNel[Error, AppDefnsResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefinition", "findByReqName:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("nodeNameList", defNameList))
    (defNameList map {
      _.map { defName =>
        play.api.Logger.debug(("%-20s -->[%s]").format("Name", defName))
        (riak.fetch(defName) leftMap { t: NonEmptyList[Throwable] =>
          new ServiceUnavailableError(defName, (t.list.map(m => m.getMessage)).mkString("\n"))
        }).toValidationNel.flatMap { xso: Option[GunnySack] =>
          xso match {
            case Some(xs) => {
              (Validation.fromTryCatch {
                parse(xs.value).extract[AppDefnsResult]
              } leftMap { t: Throwable =>
                new ResourceItemNotFound(defName, t.getMessage)
              }).toValidationNel.flatMap { j: AppDefnsResult =>
                Validation.success[Error, AppDefnsResults](nels(j.some)).toValidationNel  
              }
            }
            case None => Validation.failure[Error, AppDefnsResults](new ResourceItemNotFound(defName, "")).toValidationNel
          }
        }
      } // -> VNel -> fold by using an accumulator or successNel of empty. +++ => VNel1 + VNel2
    } map {
      _.foldRight((AppDefnsResults.empty).successNel[Error])(_ +++ _)
    }).head //return the folded element in the head. 

  }
  
  /*
   * An IO wrapped finder using an email. Upon fetching the node results for an email, 
   * the nodeids are listed in bucket `Requests`.
   * Using a "requestid" as key, return a list of ValidationNel[List[RequestResult]] 
   * Takes an email, and returns a Future[ValidationNel, List[Option[RequestResult]]]
   */
  def findByEmail(email: String): ValidationNel[Throwable, AppDefnsResults] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns", "findByEmail:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("email", email))
    val res = eitherT[IO, NonEmptyList[Throwable], ValidationNel[Error, AppDefnsResults]] {
      ((((for {
        nelnr <- (Nodes.findByEmail(email) leftMap { t: NonEmptyList[Throwable] => t }) //captures failure on the left side, success on right ie the component before the (<-)
      } yield {
        play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns", "fetched nodes by email"))
        //this is ugly, since what we receive from Nodes always contains one None. We need to filter
        //that. This is justa  hack for now. It calls for much more elegant soln.
        (nelnr.list filter (nelwop => nelwop.isDefined) map { nelnor: Option[NodeResult] =>
          val bindex = BinIndex.named("")
          val bvalue = Set("")
          val metadataVal = "Nodes-name"
          play.api.Logger.debug(("%-20s -->[%s]").format("models.Definition", nelnor))
          new GunnySack("nodeId", nelnor.get.id, RiakConstants.CTYPE_TEXT_UTF8,
            None, Map(metadataKey -> metadataVal), Map((bindex, bvalue)))
        })
      }) leftMap { t: NonEmptyList[Throwable] => t } flatMap (({ gs: List[GunnySack] =>
        gs.map { ngso: GunnySack => riak.fetchIndexByValue(ngso) }
      }) map {
        _.foldLeft((List[String]()).successNel[Throwable])(_ +++ _)
      })) map { nm: List[String] =>
        (if (!nm.isEmpty) findByReqName(nm.some) else
          new ResourceItemNotFound(email, "definitions = nothing found.").failureNel[AppDefnsResults])
      }).disjunction).pure[IO]
    }.run.map(_.validation).unsafePerformIO
    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns", res))
    res.getOrElse(new ResourceItemNotFound(email, "definitions = nothing found.").failureNel[AppDefnsResults])
  }


  /**
   * Find by the appdefns id.
   */
  /*def findByAppDefnsId(id: String): ValidationNel[Throwable, Option[AppDefnsResult]] = {
    play.api.Logger.debug(("%-20s -->[%s]").format("models.AppDefns", "findByAppDefns:Entry"))
    play.api.Logger.debug(("%-20s -->[%s]").format("accounts id", id))
    val metadataKey = "Field"
    val metadataVal = "1002"
    val bindex = BinIndex.named("")
    val bvalue = Set("")
    val fetchValue = riak.fetchIndexByValue(new GunnySack("accountId", id,
      RiakConstants.CTYPE_TEXT_UTF8, None, Map(metadataKey -> metadataVal), Map((bindex, bvalue))))

    fetchValue match {
      case Success(msg) => {
        val key = msg match {
          case List(x) => x
        }
        findByEmail(key)
      }
      case Failure(err) => Validation.failure[Throwable, Option[AppDefnsResult]](
        new ServiceUnavailableError(id, (err.list.map(m => m.getMessage)).mkString("\n"))).toValidationNel
    }
  }*/

  /*implicit val sedimentAppDefnsEmail = new Sedimenter[ValidationNel[Throwable, Option[AppDefnsResult]]] {
    def sediment(maybeASediment: ValidationNel[Throwable, Option[AppDefnsResult]]): Boolean = {
      val notSed = maybeASediment.isSuccess
      play.api.Logger.debug("%-20s -->[%s]".format("|^/^|-->ACT:sediment:", notSed))
      notSed
    }
  }*/

}