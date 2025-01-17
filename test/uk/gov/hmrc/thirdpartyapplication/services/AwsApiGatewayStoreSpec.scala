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

import akka.actor.ActorSystem
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector._
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier._
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future.successful
import scala.util.Random.nextString
import uk.gov.hmrc.thirdpartyapplication.domain.models._

class AwsApiGatewayStoreSpec extends AsyncHmrcSpec with ApplicationStateUtil {

  implicit val actorSystem: ActorSystem = ActorSystem("test")

  trait Setup {
    implicit val hc: HeaderCarrier = HeaderCarrier()
    val mockAwsApiGatewayConnector: AwsApiGatewayConnector = mock[AwsApiGatewayConnector]
    val underTest = new AwsApiGatewayStore(mockAwsApiGatewayConnector)

    val applicationName = "myapplication"
    val serverToken: String = nextString(2)
    val app = ApplicationData(
      ApplicationId.random,
      "MyApp",
      "myapp",
      Set.empty,
      Some("description"),
      applicationName,
      ApplicationTokens(
      Token(ClientId.random, serverToken)),
      testingState(),
      createdOn = DateTimeUtils.now,
      lastAccess = Some(DateTimeUtils.now))
  }

  "createApplication" should {
    "create an application in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(eqTo(applicationName), *, eqTo(BRONZE))(eqTo(hc)))
        .thenReturn(successful(HasSucceeded))

      await(underTest.createApplication(applicationName, serverToken))

      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(eqTo(applicationName), *, eqTo(BRONZE))(eqTo(hc))
    }
  }

  "updateApplication" should {
    "update rate limiting tier in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.createOrUpdateApplication(applicationName, serverToken, SILVER)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest updateApplication(app, SILVER))

      verify(mockAwsApiGatewayConnector).createOrUpdateApplication(applicationName, serverToken, SILVER)(hc)
    }

  }

  "deleteApplication" should {
    "delete an application in AWS" in new Setup {
      when(mockAwsApiGatewayConnector.deleteApplication(applicationName)(hc)).thenReturn(successful(HasSucceeded))

      await(underTest.deleteApplication(applicationName))

      verify(mockAwsApiGatewayConnector).deleteApplication(applicationName)(hc)
    }

  }

}
