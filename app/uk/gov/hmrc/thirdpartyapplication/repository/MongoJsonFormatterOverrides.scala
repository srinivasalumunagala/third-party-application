/*
 * Copyright 2021 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.thirdpartyapplication.repository

import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import play.api.libs.json._
import uk.gov.hmrc.thirdpartyapplication.domain.models.IpAllowlist
import play.api.libs.functional.syntax._

object MongoJsonFormatterOverrides {
  implicit val dateFormat = ReactiveMongoFormats.dateTimeFormats

  // Non-standard format compared to companion object
  val ipAllowlistReads: Reads[IpAllowlist] = (
    ((JsPath \ "required").read[Boolean] or Reads.pure(false)) and
    ((JsPath \ "allowlist").read[Set[String]]or Reads.pure(Set.empty[String]))
  )(IpAllowlist.apply _)
  implicit val formatIpAllowlist = OFormat(ipAllowlistReads, Json.writes[IpAllowlist])
}