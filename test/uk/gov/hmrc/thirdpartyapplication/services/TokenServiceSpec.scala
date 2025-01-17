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

package uk.gov.hmrc.thirdpartyapplication.services

import uk.gov.hmrc.thirdpartyapplication.domain.models.Token
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec

class TokenServiceSpec extends AsyncHmrcSpec {

  trait Setup {
    val underTest = new TokenService()
  }

  "createEnvironmentToken" should {
    "create a valid environment token" in new Setup {
      val result: Token = underTest.createEnvironmentToken()

      result.clientId.value.length shouldBe 28
      result.accessToken.length shouldBe 32
      result.clientSecrets shouldBe empty
    }

    "generate different values each time it is called" in new Setup {
      val firstResult: Token = underTest.createEnvironmentToken()
      val secondResult: Token = underTest.createEnvironmentToken()

      firstResult.clientId should not equal secondResult.clientId
      firstResult.accessToken should not equal secondResult.accessToken
    }
  }
}
