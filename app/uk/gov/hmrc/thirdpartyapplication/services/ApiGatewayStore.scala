/*
 * Copyright 2020 HM Revenue & Customs
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

import java.security.SecureRandom
import java.util.UUID

import akka.actor.ActorSystem
import javax.inject.{Inject, Singleton}
import play.api.Logger
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.thirdpartyapplication.connector.{AwsApiGatewayConnector, Wso2ApiStoreConnector}
import uk.gov.hmrc.thirdpartyapplication.models.RateLimitTier.{BRONZE, RateLimitTier}
import uk.gov.hmrc.thirdpartyapplication.models.db.ApplicationData
import uk.gov.hmrc.thirdpartyapplication.models.{Wso2Api, _}
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.scheduled.Retrying

import scala.collection._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

trait ApiGatewayStore {

  def createApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                       (implicit hc: HeaderCarrier): Future[EnvironmentToken]

  def removeSubscription(app: ApplicationData, api: APIIdentifier)
                        (implicit hc: HeaderCarrier): Future[HasSucceeded]

  def addSubscription(app: ApplicationData, api: APIIdentifier)
                     (implicit hc: HeaderCarrier): Future[HasSucceeded]

  def deleteApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                       (implicit hc: HeaderCarrier): Future[HasSucceeded]

  def getSubscriptions(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                      (implicit hc: HeaderCarrier): Future[Seq[APIIdentifier]]

  def resubscribeApi(originalApis: Seq[APIIdentifier],
                     wso2Username: String,
                     wso2Password: String,
                     wso2ApplicationName: String,
                     api: APIIdentifier,
                     rateLimitTier: RateLimitTier)
                    (implicit hc: HeaderCarrier): Future[HasSucceeded]

  def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)
                       (implicit hc: HeaderCarrier): Future[HasSucceeded]

  def checkApplicationRateLimitTier(wso2Username: String, wso2Password: String, wso2ApplicationName: String, expectedRateLimitTier: RateLimitTier)
                                   (implicit hc: HeaderCarrier): Future[HasSucceeded]

}

@Singleton
class AwsApiGatewayStore @Inject()(awsApiGatewayConnector: AwsApiGatewayConnector)(implicit val actorSystem: ActorSystem) extends ApiGatewayStore {

  private def generateEnvironmentToken(): EnvironmentToken = {
    val randomBytes: Array[Byte] = new Array[Byte](16) // scalastyle:off magic.number
    new SecureRandom().nextBytes(randomBytes)
    val accessToken = randomBytes.map("%02x".format(_)).mkString
    EnvironmentToken((Random.alphanumeric take 28).mkString, "", accessToken)
  }

  override def createApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                                (implicit hc: HeaderCarrier): Future[EnvironmentToken] = {
    val environmentToken = generateEnvironmentToken()
    for {
      _ <- awsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, environmentToken.accessToken, BRONZE)(hc)
    } yield environmentToken
  }

  override def checkApplicationRateLimitTier(wso2Username: String, wso2Password: String, wso2ApplicationName: String, expectedRateLimitTier: RateLimitTier)
                                            (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

  override def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)
                                (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    awsApiGatewayConnector.createOrUpdateApplication(app.wso2ApplicationName, app.tokens.production.accessToken, rateLimitTier)(hc)
  }

  override def deleteApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                                (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    awsApiGatewayConnector.deleteApplication(wso2ApplicationName)(hc)
  }

  override def addSubscription(app: ApplicationData,
                               api: APIIdentifier)
                              (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

  override def removeSubscription(app: ApplicationData, api: APIIdentifier)
                                 (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

  override def resubscribeApi(originalApis: Seq[APIIdentifier],
                              wso2Username: String,
                              wso2Password: String,
                              wso2ApplicationName: String,
                              api: APIIdentifier,
                              rateLimitTier: RateLimitTier)
                             (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

  override def getSubscriptions(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                               (implicit hc: HeaderCarrier): Future[Seq[APIIdentifier]] = {
    Future.successful(Seq.empty)
  }
}

@Singleton
class RealApiGatewayStore @Inject()(wso2APIStoreConnector: Wso2ApiStoreConnector,
                                    awsApiGatewayConnector: AwsApiGatewayConnector,
                                    subscriptionRepository: SubscriptionRepository)
                                   (implicit val actorSystem: ActorSystem)
  extends ApiGatewayStore with Retrying {

  val IgnoredContexts: Seq[String] = Seq("sso-in/sso", "web-session/sso-api")

  val resubscribeMaxRetries = 5

  override def createApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                                (implicit hc: HeaderCarrier): Future[EnvironmentToken] = {

    for {
      _ <- wso2APIStoreConnector.createUser(wso2Username, wso2Password)
      cookie <- wso2APIStoreConnector.login(wso2Username, wso2Password)
      _ <- wso2APIStoreConnector.createApplication(cookie, wso2ApplicationName)
      environmentToken <- wso2APIStoreConnector.generateApplicationKey(cookie, wso2ApplicationName)
      _ <- wso2APIStoreConnector.logout(cookie)
      _ <- awsApiGatewayConnector.createOrUpdateApplication(wso2ApplicationName, environmentToken.accessToken, BRONZE)(hc)
    } yield environmentToken
  }

  override def checkApplicationRateLimitTier(wso2Username: String, wso2Password: String, wso2ApplicationName: String, expectedRateLimitTier: RateLimitTier)
                                            (implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    def check(cookie: String) = {
      wso2APIStoreConnector.getApplicationRateLimitTier(cookie, wso2ApplicationName).flatMap { actualTier =>
        if (actualTier == expectedRateLimitTier) {
          Future.successful(HasSucceeded)
        } else {
          Future.failed(new RuntimeException(s"Rate limit tier did not change for application $wso2ApplicationName. " +
            s"Expected $expectedRateLimitTier, but found $actualTier."))
        }
      }
    }

    for {
      cookie <- wso2APIStoreConnector.login(wso2Username, wso2Password)
      _ <- retry(check(cookie), 100.milliseconds, 1)
      _ <- wso2APIStoreConnector.logout(cookie)
    } yield HasSucceeded
  }

  override def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)
                                (implicit hc: HeaderCarrier): Future[HasSucceeded] = {

    for {
      _ <- withLogin(app.wso2Username, app.wso2Password) {
        wso2APIStoreConnector.updateApplication(_, app.wso2ApplicationName, rateLimitTier)
      }
      apiIdentifiers <- subscriptionRepository.getSubscriptions(app.id)
      _ = apiIdentifiers.map(api => Wso2Api.create(api).name)
      result <- awsApiGatewayConnector.createOrUpdateApplication(app.wso2ApplicationName, app.tokens.production.accessToken, rateLimitTier)(hc)
    } yield result
  }

  override def deleteApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                                (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    withLogin(wso2Username, wso2Password) {
      wso2APIStoreConnector.deleteApplication(_, wso2ApplicationName)
    } flatMap { _ =>
      awsApiGatewayConnector.deleteApplication(wso2ApplicationName)(hc)
    }
  }

  override def addSubscription(app: ApplicationData,
                               api: APIIdentifier)
                              (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    if (IgnoredContexts.contains(api.context)) {
      // API-3955: There are a couple of APIs that do not exist in WSO2 (only in AWS) - trying to subscribe to them will cause an error
      Logger.warn(s"Ignoring subscription to ${api.context}")
      Future.successful(HasSucceeded)
    } else {
      val wso2Api = Wso2Api.create(api)

      for {
        _ <- withLogin(app.wso2Username, app.wso2Password) {
          wso2APIStoreConnector.addSubscription(_, app.wso2ApplicationName, wso2Api, app.rateLimitTier, 0)
        }
        apiIdentifiers <- subscriptionRepository.getSubscriptions(app.id)
        _ = wso2Api.name +: apiIdentifiers.map(api => Wso2Api.create(api).name)
      } yield HasSucceeded
    }
  }

  override def removeSubscription(app: ApplicationData, api: APIIdentifier)
                                 (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    val wso2Api = Wso2Api.create(api)

    for {
      _ <- withLogin(app.wso2Username: String, app.wso2Password) {
        wso2APIStoreConnector.removeSubscription(_, app.wso2ApplicationName, wso2Api, 0)
      }
      apiIdentifiers <- subscriptionRepository.getSubscriptions(app.id)
      _ = apiIdentifiers.map(api => Wso2Api.create(api).name).filterNot(_ == wso2Api.name)
    } yield HasSucceeded
  }

  override def resubscribeApi(originalApis: Seq[APIIdentifier],
                              wso2Username: String,
                              wso2Password: String,
                              wso2ApplicationName: String,
                              api: APIIdentifier,
                              rateLimitTier: RateLimitTier)
                             (implicit hc: HeaderCarrier): Future[HasSucceeded] =
    withLogin(wso2Username: String, wso2Password) { cookie =>
      val wso2Api: Wso2Api = Wso2Api.create(api)

      object Wso2ApiState extends Enumeration {
        type Wso2ApiState = Value
        val API_ADDED, API_REMOVED = Value
      }

      import Wso2ApiState._

      def isApiUpdatedInWso2Store(wso2Apis: Seq[Wso2Api], expectedWso2ApiState: Wso2ApiState): Boolean = expectedWso2ApiState match {
        case API_ADDED => wso2Apis.contains(wso2Api)
        case API_REMOVED => !wso2Apis.contains(wso2Api)
      }

      def check(expectedWso2ApiState: Wso2ApiState) = {
        wso2APIStoreConnector.getSubscriptions(cookie, wso2ApplicationName).flatMap { apis: Seq[Wso2Api] =>
          if (isApiUpdatedInWso2Store(apis, expectedWso2ApiState)) {
            Future.successful(HasSucceeded)
          } else {

            // NOTE: in case of failure, you would expect this code to rollback WSO2 Store to the original subscriptions.
            // But since the rollback could fail, we decided it needs to be fixed manually.

            Future.failed(new RuntimeException(s"Application $wso2ApplicationName has $wso2Api subscription in a wrong state. " +
              s"Expected $expectedWso2ApiState. " +
              s"The subscriptions of application $wso2ApplicationName are now incorrect. Please fix them manually. " +
              s"Original subscriptions: $originalApis, current subscriptions: $apis."))
          }
        }
      }

      // NOTE: The steps below need to be executed sequentially, otherwise WSO2 Store could fail.
      // NOTE: When subscriptions are added or removed in WSO2 Store, sometimes the requests fail with a MySQL deadlock.
      // Thus, we retry those requests to WSO2 Store.

      for {
        _ <- wso2APIStoreConnector.removeSubscription(cookie, wso2ApplicationName, wso2Api, resubscribeMaxRetries)
        _ <- retry(check(Wso2ApiState.API_REMOVED), 100.milliseconds, 1)
        _ <- wso2APIStoreConnector.addSubscription(cookie, wso2ApplicationName, wso2Api, Some(rateLimitTier), resubscribeMaxRetries)
        _ <- retry(check(Wso2ApiState.API_ADDED), 100.milliseconds, 1)
      } yield HasSucceeded

    }

  override def getSubscriptions(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                               (implicit hc: HeaderCarrier): Future[Seq[APIIdentifier]] =
    for {
      cookie <- wso2APIStoreConnector.login(wso2Username, wso2Password)
      subscriptions <- wso2APIStoreConnector.getSubscriptions(cookie, wso2ApplicationName)
      _ <- wso2APIStoreConnector.logout(cookie)
    } yield subscriptions.map(APIIdentifier.create)

  private def withLogin[A](wso2Username: String, wso2Password: String)(action: String => Future[A])
                          (implicit hc: HeaderCarrier): Future[HasSucceeded] =
    for {
      cookie <- wso2APIStoreConnector.login(wso2Username, wso2Password)
      _ <- action(cookie)
      logoutSucceeded <- wso2APIStoreConnector.logout(cookie)
    } yield logoutSucceeded

}

@Singleton
class StubApiGatewayStore @Inject()() extends ApiGatewayStore {

  def dummyEnvironmentToken = EnvironmentToken(s"dummy-${UUID.randomUUID()}", "dummyValue", "dummyValue")

  lazy val stubApplications: concurrent.Map[String, mutable.ListBuffer[APIIdentifier]] = concurrent.TrieMap()

  override def createApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                                (implicit hc: HeaderCarrier) = Future.successful {
    stubApplications += (wso2ApplicationName -> mutable.ListBuffer.empty)
    dummyEnvironmentToken
  }

  override def removeSubscription(app: ApplicationData, api: APIIdentifier)
                                 (implicit hc: HeaderCarrier) = Future.successful {
    stubApplications.get(app.wso2ApplicationName).map(subscriptions => subscriptions -= api)
    HasSucceeded
  }

  override def addSubscription(app: ApplicationData,
                               api: APIIdentifier)
                              (implicit hc: HeaderCarrier) = Future.successful {
    stubApplications.putIfAbsent(app.wso2ApplicationName, mutable.ListBuffer.empty)
    stubApplications.get(app.wso2ApplicationName).map(subscriptions => subscriptions += api)
    HasSucceeded
  }

  override def deleteApplication(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                                (implicit hc: HeaderCarrier) = Future.successful {
    stubApplications -= wso2ApplicationName
    HasSucceeded
  }

  override def getSubscriptions(wso2Username: String, wso2Password: String, wso2ApplicationName: String)
                               (implicit hc: HeaderCarrier) = Future.successful {
    stubApplications.getOrElse(wso2ApplicationName, Nil)
  }

  override def resubscribeApi(originalApis: Seq[APIIdentifier], wso2Username: String, wso2Password: String,
                              wso2ApplicationName: String, api: APIIdentifier, rateLimitTier: RateLimitTier)
                             (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

  override def updateApplication(app: ApplicationData, rateLimitTier: RateLimitTier)
                                (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }

  override def checkApplicationRateLimitTier(wso2Username: String, wso2Password: String, wso2ApplicationName: String, expectedRateLimitTier: RateLimitTier)
                                            (implicit hc: HeaderCarrier): Future[HasSucceeded] = {
    Future.successful(HasSucceeded)
  }
}
