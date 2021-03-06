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
package models.json

import scalaz._
import scalaz.NonEmptyList._
import scalaz.Validation
import scalaz.Validation._
import Scalaz._
import net.liftweb.json._
import net.liftweb.json.scalaz.JsonScalaz._
import java.util.Date
import java.nio.charset.Charset
import controllers.funnel.FunnelErrors._
import controllers.Constants._
import controllers.funnel.SerializationBase
import models.{ PredefCloudResult, PredefCloudSpec, PredefCloudAccess }

/**
 * @author ram
 *
 */
class PredefCloudResultSerialization(charset: Charset = UTF8Charset) extends SerializationBase[PredefCloudResult] {
  protected val JSONClazKey = controllers.Constants.JSON_CLAZ
  protected val IdKey = "id"
  protected val NameKey = "name"
  protected val AccountIdKey = "accounts_id"
  protected val SpecKey = "spec"
  protected val AccessKey = "access"
  protected val IdealKey = "ideal"
  protected val PerformanceKey = "performance"  

  override implicit val writer = new JSONW[PredefCloudResult] {

    import PredefCloudSpecSerialization.{ writer => PredefCloudSpecWriter }
    import PredefCloudAccessSerialization.{ writer => PredefCloudAccessWriter }

    override def write(h: PredefCloudResult): JValue = {
      JObject(
        JField(IdKey, toJSON(h.id)) ::
          JField(NameKey, toJSON(h.name)) ::
          JField(AccountIdKey, toJSON(h.accounts_id)) ::
          JField(JSONClazKey, toJSON("Megam::PredefCloud")) ::
          JField(SpecKey, toJSON(h.spec)(PredefCloudSpecWriter)) ::
          JField(AccessKey, toJSON(h.access)(PredefCloudAccessWriter)) ::
          JField(IdealKey, toJSON(h.ideal)) ::
          JField(PerformanceKey, toJSON(h.performance)) ::
           Nil)
    }
  }

  override implicit val reader = new JSONR[PredefCloudResult] {

    import PredefCloudSpecSerialization.{ reader => PredefCloudSpecReader }
    import PredefCloudAccessSerialization.{ reader => PredefCloudAccessReader }

    override def read(json: JValue): Result[PredefCloudResult] = {
      val idField = field[String](IdKey)(json)
      val nameField = field[String](NameKey)(json)
      val accountIdField = field[String](AccountIdKey)(json)
      val specField = field[PredefCloudSpec](SpecKey)(json)(PredefCloudSpecReader)
      val accessField = field[PredefCloudAccess](AccessKey)(json)(PredefCloudAccessReader)
      val idealField = field[String](IdealKey)(json)
      val perfField = field[String](PerformanceKey)(json)
      

      (idField |@| nameField |@| accountIdField |@| specField |@| accessField |@| idealField |@| perfField ) {
        (id: String, name: String, accountId: String, spec: PredefCloudSpec, access: PredefCloudAccess,
        ideal: String, perf: String) =>
          new PredefCloudResult(id, name, accountId, spec, access, ideal, perf)
      }
    }
  }
}