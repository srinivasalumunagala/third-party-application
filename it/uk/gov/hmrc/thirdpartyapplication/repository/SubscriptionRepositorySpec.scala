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

package uk.gov.hmrc.thirdpartyapplication.repository

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, Materializer}
import uk.gov.hmrc.thirdpartyapplication.ApplicationStateUtil
import org.scalatest.concurrent.Eventually
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.indexes.Index
import reactivemongo.api.indexes.IndexType.Ascending
import uk.gov.hmrc.mongo.{MongoConnector, MongoSpecSupport}
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifierSyntax._
import uk.gov.hmrc.thirdpartyapplication.models.db.{ApplicationData, ApplicationTokens}
import uk.gov.hmrc.thirdpartyapplication.util.AsyncHmrcSpec
import uk.gov.hmrc.time.DateTimeUtils

import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Random.nextString
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifier

class SubscriptionRepositorySpec extends AsyncHmrcSpec with MongoSpecSupport with IndexVerification
  with BeforeAndAfterEach with BeforeAndAfterAll with ApplicationStateUtil with Eventually with TableDrivenPropertyChecks {

  implicit val s : ActorSystem = ActorSystem("test")
  implicit val m : Materializer = ActorMaterializer()

  private val reactiveMongoComponent = new ReactiveMongoComponent {
    override def mongoConnector: MongoConnector = mongoConnectorForTest
  }

  private val subscriptionRepository = new SubscriptionRepository(reactiveMongoComponent)
  private val applicationRepository = new ApplicationRepository(reactiveMongoComponent)

  override def beforeEach() {
    List(applicationRepository, subscriptionRepository).foreach { db =>
      await(db.drop)
      await(db.ensureIndexes)
    }
  }

  override protected def afterAll() {
    List(applicationRepository, subscriptionRepository).foreach { db =>
      await(db.drop)
    }
  }

  "add" should {

    "create an entry" in {
      val applicationId = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")

      val result = await(subscriptionRepository.add(applicationId, apiIdentifier))

      result shouldBe HasSucceeded
    }

    "create multiple subscriptions" in {
      val application1 = ApplicationId.random
      val application2 = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))

      val result = await(subscriptionRepository.add(application2, apiIdentifier))

      result shouldBe HasSucceeded
    }
  }

  "remove" should {
    "delete the subscription" in {
      val application1 = ApplicationId.random
      val application2 = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))
      await(subscriptionRepository.add(application2, apiIdentifier))

      val result = await(subscriptionRepository.remove(application1, apiIdentifier))

      result shouldBe HasSucceeded
      await(subscriptionRepository.isSubscribed(application1, apiIdentifier)) shouldBe false
      await(subscriptionRepository.isSubscribed(application2, apiIdentifier)) shouldBe true
    }

    "not fail when deleting a non-existing subscription" in {
      val application1 = ApplicationId.random
      val application2 = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(application1, apiIdentifier))

      val result = await(subscriptionRepository.remove(application2, apiIdentifier))

      result shouldBe HasSucceeded
      await(subscriptionRepository.isSubscribed(application1, apiIdentifier)) shouldBe true
    }
  }


  "find all" should {
    "retrieve all versions subscriptions" in {
      val application1 = ApplicationId.random
      val application2 = ApplicationId.random
      val apiIdentifierA = "some-context-a".asIdentifier("1.0.0")
      val apiIdentifierB = "some-context-b".asIdentifier("1.0.2")
      await(subscriptionRepository.add(application1, apiIdentifierA))
      await(subscriptionRepository.add(application2, apiIdentifierA))
      await(subscriptionRepository.add(application2, apiIdentifierB))
      val retrieved = await(subscriptionRepository.findAll())
      retrieved shouldBe List(
        subscriptionData("some-context-a".asContext, "1.0.0".asVersion, application1, application2),
        subscriptionData("some-context-b".asContext, "1.0.2".asVersion, application2))
    }
  }

  "isSubscribed" should {

    "return true when the application is subscribed" in {
      val applicationId = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")
      await(subscriptionRepository.add(applicationId, apiIdentifier))

      val isSubscribed = await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier))

      isSubscribed shouldBe true
    }

    "return false when the application is not subscribed" in {
      val applicationId = ApplicationId.random
      val apiIdentifier = "some-context".asIdentifier("1.0.0")

      val isSubscribed = await(subscriptionRepository.isSubscribed(applicationId, apiIdentifier))

      isSubscribed shouldBe false
    }
  }

  "getSubscriptions" should {
    val application1 = ApplicationId.random
    val application2 = ApplicationId.random
    val api1 = "some-context".asIdentifier("1.0")
    val api2 = "some-context".asIdentifier("2.0")
    val api3 = "some-context".asIdentifier("3.0")

    "return the subscribed APIs" in {
      await(subscriptionRepository.add(application1, api1))
      await(subscriptionRepository.add(application1, api2))
      await(subscriptionRepository.add(application2, api3))

      val result = await(subscriptionRepository.getSubscriptions(application1))

      result shouldBe List(api1, api2)
    }

    "return empty when the application is not subscribed to any API" in {
      val result = await(subscriptionRepository.getSubscriptions(application1))

      result shouldBe List.empty
    }
  }

  "getSubscriptionsForDeveloper" should {
    val developerEmail = "john.doe@example.com"

    "return only the APIs that the user's apps are subscribed to, without duplicates" in {
      val app1 = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List(developerEmail))
      await(applicationRepository.save(app1))
      val app2 = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List(developerEmail))
      await(applicationRepository.save(app2))
      val someoneElsesApp = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("someone-else@example.com"))
      await(applicationRepository.save(someoneElsesApp))

      val helloWorldApi1 = "hello-world".asIdentifier("1.0")
      val helloWorldApi2 = "hello-world".asIdentifier("2.0")
      val helloVatApi = "hello-vat".asIdentifier("1.0")
      val helloAgentsApi = "hello-agents".asIdentifier("1.0")
      await(subscriptionRepository.add(app1.id, helloWorldApi1))
      await(subscriptionRepository.add(app1.id, helloVatApi))
      await(subscriptionRepository.add(app2.id, helloWorldApi2))
      await(subscriptionRepository.add(app2.id, helloVatApi))
      await(subscriptionRepository.add(someoneElsesApp.id, helloAgentsApi))

      val result: Set[ApiIdentifier] = await(subscriptionRepository.getSubscriptionsForDeveloper(developerEmail))

      result shouldBe Set(helloWorldApi1, helloWorldApi2, helloVatApi)
    }

    "return empty when the user's apps are not subscribed to any API" in {
      val app = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List(developerEmail))
      await(applicationRepository.save(app))

      val result: Set[ApiIdentifier] = await(subscriptionRepository.getSubscriptionsForDeveloper(developerEmail))

      result shouldBe Set.empty
    }

    "return empty when the user is not a collaborator of any apps" in {
      val app = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("someone-else@example.com"))
      await(applicationRepository.save(app))
      val api = "hello-world".asIdentifier("1.0")
      await(subscriptionRepository.add(app.id, api))

      val result: Set[ApiIdentifier] = await(subscriptionRepository.getSubscriptionsForDeveloper(developerEmail))

      result shouldBe Set.empty
    }
  }

  "getSubscribers" should {
    val application1 = ApplicationId.random
    val application2 = ApplicationId.random
    val api1 = "some-context".asIdentifier("1.0")
    val api2 = "some-context".asIdentifier("2.0")
    val api3 = "some-context".asIdentifier("3.0")

    def saveSubscriptions(): HasSucceeded = {
      await(subscriptionRepository.add(application1, api1))
      await(subscriptionRepository.add(application1, api2))
      await(subscriptionRepository.add(application2, api2))
      await(subscriptionRepository.add(application2, api3))
    }

    "return an empty set when the API doesn't have any subscribers" in {
      saveSubscriptions()

      val applications: Set[ApplicationId] = await(subscriptionRepository.getSubscribers("some-context".asIdentifier("4.0")))

      applications should have size 0
    }

    "return the IDs of the applications subscribed to the given API" in {
      saveSubscriptions()
      val scenarios = Table(
        ("apiIdentifier", "expectedApplications"),
        ("some-context".asIdentifier("1.0"), Seq(application1)),
        ("some-context".asIdentifier("2.0"), Seq(application1, application2)),
        ("some-context".asIdentifier("3.0"), Seq(application2))
      )

      forAll(scenarios) { (apiIdentifier, expectedApplications) =>
        val applications: Set[ApplicationId] = await(subscriptionRepository.getSubscribers(apiIdentifier))
        applications should contain only (expectedApplications: _*)
      }
    }
  }

  "The 'subscription' collection" should {
    "have all the indexes" in {
      val expectedIndexes = Set(
        Index(key = Seq("applications" -> Ascending), name = Some("applications"), unique = false, background = true),
        Index(key = Seq("apiIdentifier.context" -> Ascending), name = Some("context"), unique = false, background = true),
        Index(key =
          Seq("apiIdentifier.context" -> Ascending, "apiIdentifier.version" -> Ascending), name = Some("context_version"), unique = true, background = true),
        Index(key = Seq("_id" -> Ascending), name = Some("_id_"), unique = false, background = false))

      verifyIndexesVersionAgnostic(subscriptionRepository, expectedIndexes)
    }
  }

  "Get API Version Collaborators" should {
    "return email addresses" in {

      val app1 = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("match1@example.com", "match2@example.com"))
      await(applicationRepository.save(app1))

      val app2 = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("match3@example.com"))
      await(applicationRepository.save(app2))

      val doNotMatchApp = anApplicationData(id = ApplicationId.random, clientId = generateClientId, user = List("donotmatch@example.com"))
      await(applicationRepository.save(doNotMatchApp))

      val api1 = "some-context-api1".asIdentifier("1.0")
      await(subscriptionRepository.add(app1.id, api1))
      await(subscriptionRepository.add(app2.id, api1))

      val doNotMatchApi = "some-context-donotmatchapi".asIdentifier("1.0")
      await(subscriptionRepository.add(doNotMatchApp.id, doNotMatchApi))

      val result = await(subscriptionRepository.searchCollaborators(api1.context, api1.version, None))

      val expectedEmails = app1.collaborators.map(c => c.emailAddress) ++ app2.collaborators.map(c => c.emailAddress)
      result.toSet shouldBe expectedEmails
    }

    "filter by collaborators and api version" in {

      val matchEmail = "match@example.com"
      val partialEmailToMatch = "match"
      val app1 = anApplicationData(
        id = ApplicationId.random,
        clientId = generateClientId,
        user = List(matchEmail, "donot@example.com"))

      await(applicationRepository.save(app1))

      val api1 = "some-context-api".asIdentifier("1.0")
      await(subscriptionRepository.add(app1.id, api1))

      val result = await(subscriptionRepository.searchCollaborators(api1.context, api1.version, Some(partialEmailToMatch)))

      result.toSet shouldBe Set(matchEmail)
    }
  }

  def subscriptionData(apiContext: ApiContext, version: ApiVersion, applicationIds: ApplicationId*) = {
    SubscriptionData(
      ApiIdentifier(apiContext, version),
      Set(applicationIds: _*))
  }

  def anApplicationData(id: ApplicationId,
                        clientId: ClientId = ClientId("aaa"),
                        state: ApplicationState = testingState(),
                        access: Access = Standard(List.empty, None, None),
                        user: List[String] = List("user@example.com"),
                        checkInformation: Option[CheckInformation] = None): ApplicationData = {

    aNamedApplicationData(id, s"myApp-${id.value}", clientId, state, access, user, checkInformation)
  }

  def aNamedApplicationData(id: ApplicationId,
                            name: String,
                            clientId: ClientId = ClientId("aaa"),
                            state: ApplicationState = testingState(),
                            access: Access = Standard(List.empty, None, None),
                            user: List[String] = List("user@example.com"),
                            checkInformation: Option[CheckInformation] = None): ApplicationData = {

    val collaborators = user.map(email => Collaborator(email, Role.ADMINISTRATOR, UserId.random)).toSet

    ApplicationData(
      id,
      name,
      name.toLowerCase,
      collaborators,
      Some("description"),
      "myapplication",
      ApplicationTokens(Token(clientId, generateAccessToken)),
      state,
      access,
      DateTimeUtils.now,
      Some(DateTimeUtils.now),
      checkInformation = checkInformation)
  }

  private def generateClientId = {
    ClientId.random
  }

  private def generateAccessToken = {
    val testAccessTokenLength = 5
    nextString(testAccessTokenLength)
  }

}
