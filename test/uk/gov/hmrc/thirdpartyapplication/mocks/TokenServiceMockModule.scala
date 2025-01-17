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

package uk.gov.hmrc.thirdpartyapplication.mocks

import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.thirdpartyapplication.domain.models.Token
import uk.gov.hmrc.thirdpartyapplication.services.TokenService

trait TokenServiceMockModule extends MockitoSugar with ArgumentMatchersSugar {

  protected trait BaseTokenServiceMock {
    def aMock: TokenService

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock, mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object CreateEnvironmentToken {
      def thenReturn(environmentToken: Token) = {
        when(aMock.createEnvironmentToken()).thenReturn(environmentToken)
      }

      def verifyCalled() = {
        TokenServiceMock.verify.createEnvironmentToken()
      }
    }
  }

  object TokenServiceMock extends BaseTokenServiceMock {
    val aMock = mock[TokenService](withSettings.lenient())
  }
}
