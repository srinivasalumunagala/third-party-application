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

package uk.gov.hmrc.thirdpartyapplication.domain.models

import play.api.libs.json.Json

case class Totp(secret: String, id: String)

object Totp {
  implicit val format = Json.format[Totp]
}

case class TotpId(production: String)

object TotpId {
  implicit val format = Json.format[TotpId]
}

case class TotpSecret(production: String)

object TotpSecret {
  implicit val format = Json.format[TotpSecret]
}