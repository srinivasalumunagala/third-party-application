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

import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import javax.inject.{Inject, Singleton}
import org.joda.time.DateTime
import play.api.libs.json.Json._
import play.api.libs.json.{JsObject, _}
import play.modules.reactivemongo.ReactiveMongoComponent
import reactivemongo.api.ReadConcern.Available
import reactivemongo.api.commands.Command.CommandWithPackRunner
import reactivemongo.api.{FailoverStrategy, ReadPreference}
import reactivemongo.bson.{BSONDateTime, BSONObjectID}
import reactivemongo.play.json._
import uk.gov.hmrc.mongo.ReactiveRepository
import uk.gov.hmrc.mongo.json.ReactiveMongoFormats
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.domain.models.AccessType.AccessType
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.domain.models.State.State
import uk.gov.hmrc.thirdpartyapplication.models._
import uk.gov.hmrc.thirdpartyapplication.util.MetricsHelper
import uk.gov.hmrc.thirdpartyapplication.util.mongo.IndexHelper._
import uk.gov.hmrc.thirdpartyapplication.domain.models._

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class ApplicationRepository @Inject()(mongo: ReactiveMongoComponent)(implicit val mat: Materializer, val ec: ExecutionContext)
  extends ReactiveRepository[ApplicationData, BSONObjectID]("application", mongo.mongoConnector.db,
    ApplicationData.format, ReactiveMongoFormats.objectIdFormats)
    with MetricsHelper {

  import MongoJsonFormatterOverrides._

  private val subscriptionsLookup: JsObject = Json.obj(
    f"$$lookup" -> Json.obj(
      "from" -> "subscription",
      "localField" -> "id",
      "foreignField" -> "applications",
      "as" -> "subscribedApis"))

  private val applicationProjection = Json.obj(f"$$project" -> Json.obj(
    "id" -> true,
    "name" -> true,
    "normalisedName" -> true,
    "collaborators" -> true,
    "description" -> true,
    "wso2ApplicationName" -> true,
    "tokens" -> true,
    "state" -> true,
    "access" -> true,
    "createdOn" -> true,
    "lastAccess" -> true,
    "rateLimitTier" -> true,
    "environment" -> true))

  override def indexes = List(
    createSingleFieldAscendingIndex(
      indexFieldKey = "state.verificationCode",
      indexName = Some("verificationCodeIndex")
    ),
    createAscendingIndex(
      indexName = Some("stateName_stateUpdatedOn_Index"),
      isUnique = false,
      isBackground = true,
      indexFieldsKey = List("state.name", "state.updatedOn"): _*
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "id",
      indexName = Some("applicationIdIndex"),
      isUnique = true
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "normalisedName",
      indexName = Some("applicationNormalisedNameIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "lastAccess",
      indexName = Some("lastAccessIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "tokens.production.clientId",
      indexName = Some("productionTokenClientIdIndex"),
      isUnique = true
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "access.overrides",
      indexName = Some("accessOverridesIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "access.accessType",
      indexName = Some("accessTypeIndex")
    ),
    createSingleFieldAscendingIndex(
      indexFieldKey = "collaborators.emailAddress",
      indexName = Some("collaboratorsEmailAddressIndex")
    )
  )

  def save(application: ApplicationData): Future[ApplicationData] = {
    findAndUpdate(Json.obj("id" -> application.id.value.toString), Json.toJson(application).as[JsObject], upsert = true, fetchNewObject = true)
      .map(_.result[ApplicationData].head)
  }

  def updateApplicationRateLimit(applicationId: ApplicationId, rateLimit: RateLimitTier): Future[ApplicationData] =
    updateApplication(applicationId, Json.obj("$set" -> Json.obj("rateLimitTier" -> rateLimit.toString)))

  def updateApplicationIpAllowlist(applicationId: ApplicationId, ipAllowlist: IpAllowlist): Future[ApplicationData] =
    updateApplication(applicationId, Json.obj("$set" -> Json.obj("ipAllowlist" -> ipAllowlist)))

  def recordApplicationUsage(applicationId: ApplicationId): Future[ApplicationData] =
    updateApplication(applicationId, Json.obj("$currentDate" -> Json.obj("lastAccess" -> Json.obj("$type" -> "date"))))

  def recordServerTokenUsage(applicationId: ApplicationId): Future[ApplicationData] =
    updateApplication(applicationId, Json.obj("$currentDate" -> Json.obj(
      "lastAccess" -> Json.obj("$type" -> "date"),
      "tokens.production.lastAccessTokenUsage" -> Json.obj("$type" -> "date"))))

  def updateCollaboratorId(applicationId: ApplicationId, collaboratorEmailAddress: String, collaboratorUser: UserId): Future[Option[ApplicationData]] =  {
    val qry = Json.obj("$and" -> Json.arr(
                  Json.obj("id" -> applicationId.value.toString),
                  Json.obj("collaborators" -> 
                    Json.obj("$elemMatch" -> 
                      Json.obj(
                        "emailAddress" -> collaboratorEmailAddress,
                        "userId" -> Json.obj("$exists" -> false)
                      )
                    )
                  )
              ))
    val updateStatement = Json.obj("$set" -> Json.obj("collaborators.$.userId" -> collaboratorUser))

    findAndUpdate(qry, updateStatement, fetchNewObject = true) map {
      _.result[ApplicationData]
    }
  }

  def updateApplication(applicationId: ApplicationId, updateStatement: JsObject): Future[ApplicationData] =
    findAndUpdate(Json.obj("id" -> applicationId.value.toString), updateStatement, fetchNewObject = true) map {
      _.result[ApplicationData].head
    }

  def updateClientSecretField(applicationId: ApplicationId, clientSecretId: String, fieldName: String, fieldValue: String): Future[ApplicationData] =
    findAndUpdate(
      Json.obj("id" -> applicationId.value.toString, "tokens.production.clientSecrets.id" -> clientSecretId),
      Json.obj("$set" -> Json.obj(s"tokens.production.clientSecrets.$$.$fieldName" -> fieldValue)),
      fetchNewObject = true)
      .map(_.result[ApplicationData].head)

  def addClientSecret(applicationId: ApplicationId, clientSecret: ClientSecret): Future[ApplicationData] =
    updateApplication(applicationId, Json.obj("$push" -> Json.obj("tokens.production.clientSecrets" -> Json.toJson(clientSecret))))

  def updateClientSecretName(applicationId: ApplicationId, clientSecretId: String, newName: String): Future[ApplicationData] =
    updateClientSecretField(applicationId, clientSecretId, "name", newName)

  def updateClientSecretHash(applicationId: ApplicationId, clientSecretId: String, hashedSecret: String): Future[ApplicationData] =
    updateClientSecretField(applicationId, clientSecretId, "hashedSecret", hashedSecret)

  def recordClientSecretUsage(applicationId: ApplicationId, clientSecretId: String): Future[ApplicationData] =
    findAndUpdate(
      Json.obj("id" -> applicationId, "tokens.production.clientSecrets.id" -> clientSecretId),
      Json.obj("$currentDate" -> Json.obj("tokens.production.clientSecrets.$.lastAccess" -> Json.obj("$type" -> "date"))),
      fetchNewObject = true)
      .map(_.result[ApplicationData].head)

  def deleteClientSecret(applicationId: ApplicationId, clientSecretId: String): Future[ApplicationData] = {
    findAndUpdate(
      Json.obj("id" -> applicationId.value.toString),
      Json.obj("$pull" -> Json.obj("tokens.production.clientSecrets" -> Json.obj("id" -> clientSecretId))),
      fetchNewObject = true)
      .map(_.result[ApplicationData].head)
  }

  def fetchStandardNonTestingApps(): Future[List[ApplicationData]] = {
    find(s"$$and" -> Json.arr(
      Json.obj("state.name" -> Json.obj(f"$$ne" -> State.TESTING)),
      Json.obj("access.accessType" -> Json.obj(f"$$eq" -> AccessType.STANDARD))
    ))
  }

  def fetch(id: ApplicationId): Future[Option[ApplicationData]] = find("id" -> id.value).map(_.headOption)

  def fetchApplicationsByName(name: String): Future[List[ApplicationData]] = {
    val query: (String, JsValueWrapper) = f"$$and" -> Json.arr(
      Json.obj("normalisedName" -> name.toLowerCase)
    )

    find(query)
  }

  def fetchVerifiableUpliftBy(verificationCode: String): Future[Option[ApplicationData]] = {
    find("state.verificationCode" -> verificationCode).map(_.headOption)
  }

  def fetchAllByStatusDetails(state: State.State, updatedBefore: DateTime): Future[List[ApplicationData]] = {
    find("state.name" -> state, "state.updatedOn" -> Json.obj(f"$$lte" -> BSONDateTime(updatedBefore.getMillis)))
  }

  def fetchByClientId(clientId: ClientId): Future[Option[ApplicationData]] = {
    find("tokens.production.clientId" -> clientId.value).map(_.headOption)
  }

  def fetchByServerToken(serverToken: String): Future[Option[ApplicationData]] = {
    find("tokens.production.accessToken" -> serverToken).map(_.headOption)
  }

  def fetchAllForUserId(userId: UserId): Future[List[ApplicationData]] = {
    find("collaborators.userId" -> userId.value)
  }

  def fetchAllForUserIdAndEnvironment(userId: UserId, environment: String): Future[List[ApplicationData]] = {
    find("collaborators.userId" -> userId.value, "environment" -> environment)
  }

  def fetchAllForEmailAddress(emailAddress: String): Future[List[ApplicationData]] = {
    find("collaborators.emailAddress" -> emailAddress)
  }

  def fetchAllForEmailAddressAndEnvironment(emailAddress: String, environment: String): Future[List[ApplicationData]] = {
    find("collaborators.emailAddress" -> emailAddress, "environment" -> environment)
  }

  def searchApplications(applicationSearch: ApplicationSearch): Future[PaginatedApplicationData] = {
    val filters = applicationSearch.filters.map(filter => convertFilterToQueryClause(filter, applicationSearch))
    val sort = convertToSortClause(applicationSearch.sort)

    val pagination = List(
      Json.obj(f"$$skip" -> (applicationSearch.pageNumber - 1) * applicationSearch.pageSize),
      Json.obj(f"$$limit" -> applicationSearch.pageSize))

    runApplicationQueryAggregation(commandQueryDocument(filters, pagination, sort))
  }

  private def matches(predicates: (String, JsValueWrapper)): JsObject = Json.obj(f"$$match" -> Json.obj(predicates))

  private def sorting(clause: (String, JsValueWrapper)): JsObject = Json.obj(f"$$sort" -> Json.obj(clause))

  private def convertFilterToQueryClause(applicationSearchFilter: ApplicationSearchFilter, applicationSearch: ApplicationSearch): JsObject = {
    def applicationStatusMatch(state: State): JsObject = matches("state.name" -> state.toString)

    def accessTypeMatch(accessType: AccessType): JsObject = matches("access.accessType" -> accessType.toString)

    def specificAPISubscription(apiContext: ApiContext, apiVersion: Option[ApiVersion]) = {
      apiVersion.fold(
        matches("subscribedApis.apiIdentifier.context" -> apiContext)
      )( value =>
        matches("subscribedApis.apiIdentifier" -> Json.obj("context" -> apiContext, "version" -> value))
      )
    }

    applicationSearchFilter match {
      // API Subscriptions
      case NoAPISubscriptions => matches("subscribedApis" -> Json.obj(f"$$size" -> 0))
      case OneOrMoreAPISubscriptions => matches("subscribedApis" -> Json.obj(f"$$gt" -> Json.obj(f"$$size" -> 0)))
      case SpecificAPISubscription => specificAPISubscription(applicationSearch.apiContext.get, applicationSearch.apiVersion)

      // Application Status
      case Created => applicationStatusMatch(State.TESTING)
      case PendingGatekeeperCheck => applicationStatusMatch(State.PENDING_GATEKEEPER_APPROVAL)
      case PendingSubmitterVerification => applicationStatusMatch(State.PENDING_REQUESTER_VERIFICATION)
      case Active => applicationStatusMatch(State.PRODUCTION)

      // Terms of Use
      case TermsOfUseAccepted => matches("checkInformation.termsOfUseAgreements" -> Json.obj(f"$$gt" -> Json.obj(f"$$size" -> 0)))
      case TermsOfUseNotAccepted =>
        matches(
          f"$$or" ->
            Json.arr(
              Json.obj("checkInformation" -> Json.obj(f"$$exists" -> false)),
              Json.obj("checkInformation.termsOfUseAgreements" -> Json.obj(f"$$exists" -> false)),
              Json.obj("checkInformation.termsOfUseAgreements" -> Json.obj(f"$$size" -> 0))))

      // Access Type
      case StandardAccess => accessTypeMatch(AccessType.STANDARD)
      case ROPCAccess => accessTypeMatch(AccessType.ROPC)
      case PrivilegedAccess => accessTypeMatch(AccessType.PRIVILEGED)

      // Text Search
      case ApplicationTextSearch => regexTextSearch(List("id", "name", "tokens.production.clientId"), applicationSearch.textToSearch.getOrElse(""))

      // Last Use Date
      case lastUsedBefore: LastUseBeforeDate => lastUsedBefore.toMongoMatch
      case lastUsedAfter: LastUseAfterDate => lastUsedAfter.toMongoMatch
      case _  => Json.obj() // Only here to complete the match
    }
  }

  private def convertToSortClause(sort: ApplicationSort): List[JsObject] = sort match {
    case NameAscending => List(sorting("name" -> 1))
    case NameDescending => List(sorting("name" -> -1))
    case SubmittedAscending => List(sorting("createdOn" -> 1))
    case SubmittedDescending => List(sorting("createdOn" -> -1))
    case LastUseDateAscending => List(sorting("lastAccess" -> 1))
    case LastUseDateDescending => List(sorting("lastAccess" -> -1))
    case NoSorting => List()
    case _ => List(sorting("name" -> 1))
  }

  private def regexTextSearch(fields: List[String], searchText: String): JsObject =
    matches(f"$$or" -> fields.map(field => Json.obj(field -> Json.obj(f"$$regex" -> searchText, f"$$options" -> "i"))))

  private def runApplicationQueryAggregation(commandDocument: JsObject): Future[PaginatedApplicationData] = {
    val runner = CommandWithPackRunner(JSONSerializationPack, FailoverStrategy())
    runner
      .apply(collection.db, runner.rawCommand(commandDocument))
      .one[JsObject](ReadPreference.nearest)
      .flatMap(processResults[PaginatedApplicationData])
  }

  private def processResults[T](json: JsObject)(implicit fjs: Reads[T]): Future[T] = {
    // TODO: I don't think this is returning more than 1 batch (~100?) worth of data.
    (json \ "cursor" \ "firstBatch" \ 0).validate[T] match {
      case JsSuccess(result, _) => Future.successful(result)
      case JsError(errors) => Future.failed(new RuntimeException((json \ "errmsg").asOpt[String].getOrElse(errors.mkString(","))))
    }
  }

  private def commandQueryDocument(filters: List[JsObject], pagination: List[JsObject], sort: List[JsObject]): JsObject = {
    val totalCount = Json.arr(Json.obj(f"$$count" -> "total"))
    val filteredPipelineCount = Json.toJson(subscriptionsLookup +: filters :+ Json.obj(f"$$count" -> "total"))
    val paginatedFilteredAndSortedPipeline = Json.toJson((subscriptionsLookup +: filters) ++ sort ++ pagination :+ applicationProjection)

    Json.obj(
      "aggregate" -> "application",
      "cursor" -> Json.obj(),
      "pipeline" -> Json.arr(Json.obj(
        f"$$facet" -> Json.obj(
          "totals" -> totalCount,
          "matching" -> filteredPipelineCount,
          "applications" -> paginatedFilteredAndSortedPipeline))))
  }

  def fetchAllForContext(apiContext: ApiContext): Future[List[ApplicationData]] =
    searchApplications(ApplicationSearch(1, Int.MaxValue, List(SpecificAPISubscription), apiContext = Some(apiContext))).map(_.applications)

  def fetchAllForApiIdentifier(apiIdentifier: ApiIdentifier): Future[List[ApplicationData]] =
    searchApplications(ApplicationSearch(1, Int.MaxValue, List(SpecificAPISubscription), apiContext = Some(apiIdentifier.context),
      apiVersion = Some(apiIdentifier.version))).map(_.applications)

  def fetchAllWithNoSubscriptions(): Future[List[ApplicationData]] =
    searchApplications(new ApplicationSearch(filters = List(NoAPISubscriptions))).map(_.applications)

  def fetchAll(): Future[List[ApplicationData]] = searchApplications(new ApplicationSearch()).map(_.applications)

  def processAll(function: ApplicationData => Unit): Future[Unit] = {
    import reactivemongo.akkastream.{State, cursorProducer}

    val sourceOfApps: Source[ApplicationData, Future[State]] =
      collection.find(Json.obj(), Option.empty[ApplicationData]).cursor[ApplicationData]().documentSource()

    sourceOfApps.runWith(Sink.foreach(function)).map(_ => ())
  }

  def delete(id: ApplicationId): Future[HasSucceeded] = {
    remove("id" -> id.value).map(_ => HasSucceeded)
  }

  def documentsWithFieldMissing(fieldName: String): Future[Int] = {
    collection.count(Some(Json.obj(fieldName -> Json.obj(f"$$exists" -> false))), None, 0, None, Available).map(_.toInt)
  }

  def getApplicationWithSubscriptionCount(): Future[Map[String, Int]] = {

    collection.aggregateWith[ApplicationWithSubscriptionCount]()(_ => {
      import collection.BatchCommands.AggregationFramework._

      val lookup = Lookup(
        from = "subscription",
        localField = "id",
        foreignField = "applications",
        as = "subscribedApis"
      )

      val unwind = UnwindField("subscribedApis")

      val group: PipelineOperator = this.collection.BatchCommands.AggregationFramework.Group(
        Json.parse("""{
                     |      "id" : "$id",
                     |      "name": "$name"
                     |    }""".stripMargin))(
        "count" -> SumAll
      )
      (lookup, List[PipelineOperator](unwind, group))
    }).fold(Nil: List[ApplicationWithSubscriptionCount])((acc, cur) => cur :: acc)
      .map(_.map(r=>s"applicationsWithSubscriptionCountV1.${sanitiseGrafanaNodeName(r._id.name)}" -> r.count).toMap)
  }
}

sealed trait ApplicationModificationResult

final case class SuccessfulApplicationModificationResult(numberOfDocumentsUpdated: Int) extends ApplicationModificationResult

final case class UnsuccessfulApplicationModificationResult(message: Option[String]) extends ApplicationModificationResult