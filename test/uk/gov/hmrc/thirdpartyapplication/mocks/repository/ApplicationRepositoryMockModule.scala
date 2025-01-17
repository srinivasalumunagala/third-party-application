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

package uk.gov.hmrc.thirdpartyapplication.mocks.repository

import akka.japi.Option.Some
import org.mockito.captor.{ArgCaptor, Captor}
import org.mockito.verification.VerificationMode
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import uk.gov.hmrc.http.NotFoundException
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.models.db._
import uk.gov.hmrc.thirdpartyapplication.models.HasSucceeded
import uk.gov.hmrc.thirdpartyapplication.domain.models._
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.Future
import scala.concurrent.Future.{failed, successful}
import uk.gov.hmrc.thirdpartyapplication.domain.models._

trait ApplicationRepositoryMockModule extends MockitoSugar with ArgumentMatchersSugar {
  protected trait BaseApplicationRepoMock {
    def aMock: ApplicationRepository

    def verify = MockitoSugar.verify(aMock)

    def verify(mode: VerificationMode) = MockitoSugar.verify(aMock,mode)

    def verifyZeroInteractions() = MockitoSugar.verifyZeroInteractions(aMock)

    object Fetch {
      def thenReturn(applicationData: ApplicationData) =
        when(aMock.fetch(eqTo(applicationData.id))).thenReturn(successful(Some(applicationData)))

      def thenReturnNone() =
        when(aMock.fetch(*[ApplicationId])).thenReturn(successful(None))

      def thenReturnNoneWhen(applicationId: ApplicationId) =
        when(aMock.fetch(eqTo(applicationId))).thenReturn(successful(None))

      def thenFail(failWith: Throwable) =
        when(aMock.fetch(*[ApplicationId])).thenReturn(failed(failWith))

      def verifyCalledWith(applicationId: ApplicationId) =
        ApplicationRepoMock.verify.fetch(eqTo(applicationId))

      def verifyFetch(): ApplicationId = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationId]
        ApplicationRepoMock.verify.fetch(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor.value
      }
    }

    object FetchByClientId {
      def thenReturnWhen(clientId: ClientId)(applicationData: ApplicationData) =
        when(aMock.fetchByClientId(eqTo(clientId))).thenReturn(successful(Some(applicationData)))

      def thenReturnNone() =
        when(aMock.fetchByClientId(*[ClientId])).thenReturn(successful(None))

      def thenReturnNoneWhen(clientId: ClientId) =
        when(aMock.fetchByClientId(eqTo(clientId))).thenReturn(successful(None))

      def thenFail(failWith: Throwable) =
          when(aMock.fetchByClientId(*[ClientId])).thenReturn(failed(failWith))
    }

    object Save {
      private val defaultFn = (a :ApplicationData) => successful(a)
      def thenAnswer(fn: ApplicationData => Future[ApplicationData] = defaultFn) =
        when(aMock.save(*)).thenAnswer(fn)

      def thenReturn(applicationData: ApplicationData) =
        when(aMock.save(*)).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.save(*)).thenReturn(failed(failWith))

      def verifyCalledWith(applicationData: ApplicationData) =
        ApplicationRepoMock.verify.save(eqTo(applicationData))

      def verifyNeverCalled() =
        ApplicationRepoMock.verify(never).save(*)

      def verifyCalled(): ApplicationData = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationData]
        ApplicationRepoMock.verify.save(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor.value
      }

      def verifyCalled(mode: VerificationMode): Captor[ApplicationData] = {
        val applicationDataArgumentCaptor = ArgCaptor[ApplicationData]
        ApplicationRepoMock.verify(mode).save(applicationDataArgumentCaptor)
        applicationDataArgumentCaptor
      }

      def verifyNothingSaved(): Unit = {
        verifyCalled(never)
      }
    }

    object FetchStandardNonTestingApps {
      def thenReturn(apps: ApplicationData*) =
        when(aMock.fetchStandardNonTestingApps()).thenReturn(successful(apps.toList))
    }

    object FetchByName {
      def thenReturnEmptyList() =
        when(aMock.fetchApplicationsByName(*)).thenReturn(successful(List.empty))

      def thenReturn(apps: ApplicationData*) =
        when(aMock.fetchApplicationsByName(*)).thenReturn(successful(apps.toList))

      def thenReturnWhen(name: String)(apps: ApplicationData*) =
        when(aMock.fetchApplicationsByName(eqTo(name))).thenReturn(successful(apps.toList))

      def thenReturnEmptyWhen(requestedName: String) = thenReturnWhen(requestedName)()

      def verifyCalledWith(duplicateName: String) =
        ApplicationRepoMock.verify.fetchApplicationsByName(eqTo(duplicateName))

      def veryNeverCalled() =
        ApplicationRepoMock.verify(never).fetchApplicationsByName(*)

    }

    object Delete {
      def verifyCalledWith(id: ApplicationId) =
        ApplicationRepoMock.verify.delete(eqTo(id))

      def thenReturnHasSucceeded() =
        when(aMock.delete(*[ApplicationId])).thenReturn(successful(HasSucceeded))
    }

    object FetchByServerToken {
      def thenReturnWhen(serverToken: String)(applicationData: ApplicationData) =
        when(aMock.fetchByServerToken(eqTo(serverToken))).thenReturn(successful(Some(applicationData)))

      def thenReturnNoneWhen(serverToken: String) =
      when(aMock.fetchByServerToken(eqTo(serverToken))).thenReturn(successful(None))

    }

    object FetchVerifiableUpliftBy {
      def thenReturnNoneWhen(verificationCode: String) =
        when(aMock.fetchVerifiableUpliftBy(eqTo(verificationCode))).thenReturn(successful(None))

      def thenReturnWhen(verificationCode: String)(applicationData: ApplicationData) =
        when(aMock.fetchVerifiableUpliftBy(eqTo(verificationCode))).thenReturn(successful(Some(applicationData)))

    }

    object FetchAllForContent {
      def thenReturnEmptyWhen(apiContext: ApiContext) =
      when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiContext: ApiContext)(apps: ApplicationData*) =
      when(aMock.fetchAllForContext(eqTo(apiContext))).thenReturn(successful(apps.toList))

    }

    object FetchAllForEmail {
      def thenReturnWhen(emailAddress: String)(apps: ApplicationData*) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(apps.toList))

      def thenReturnEmptyWhen(emailAddress: String) =
        when(aMock.fetchAllForEmailAddress(eqTo(emailAddress))).thenReturn(successful(List.empty))
    }

    object fetchAllForUserId {
      def thenReturnWhen(userId: UserId)(apps: ApplicationData*) =
        when(aMock.fetchAllForUserId(eqTo(userId))).thenReturn(successful(apps.toList))
    }

    object FetchAllForApiIdentifier {
      def thenReturnEmptyWhen(apiIdentifier: ApiIdentifier) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(List.empty))

      def thenReturnWhen(apiIdentifier: ApiIdentifier)(apps: ApplicationData*) =
        when(aMock.fetchAllForApiIdentifier(eqTo(apiIdentifier))).thenReturn(successful(apps.toList))
    }

    object FetchAllWithNoSubscriptions {
      def thenReturn(apps: ApplicationData*) =
      when(aMock.fetchAllWithNoSubscriptions()).thenReturn(successful(apps.toList))

      def thenReturnNone() =
        when(aMock.fetchAllWithNoSubscriptions()).thenReturn(successful(Nil))
    }

    object RecordApplicationUsage {
      def thenReturnWhen(applicationId: ApplicationId)(applicationData: ApplicationData) =
        when(aMock.recordApplicationUsage(eqTo(applicationId))).thenReturn(successful(applicationData))
    }

    object RecordServerTokenUsage {
      def thenReturnWhen(applicationId: ApplicationId)(applicationData: ApplicationData) =
        when(aMock.recordServerTokenUsage(eqTo(applicationId))).thenReturn(successful(applicationData))

      def verifyCalledWith(applicationId: ApplicationId) =
        ApplicationRepoMock.verify.recordServerTokenUsage(eqTo(applicationId))
    }

    object UpdateIpAllowlist {
      def verifyCalledWith(applicationId: ApplicationId, newIpAllowlist: IpAllowlist) =
        ApplicationRepoMock.verify.updateApplicationIpAllowlist(eqTo(applicationId),eqTo(newIpAllowlist))

      def thenReturnWhen(applicationId: ApplicationId, newIpAllowlist: IpAllowlist)(updatedApplicationData: ApplicationData) =
        when(aMock.updateApplicationIpAllowlist(eqTo(applicationId), eqTo(newIpAllowlist))).thenReturn(successful(updatedApplicationData))
    }

    object SearchApplications {
      def thenReturn(data: PaginatedApplicationData) =
        when(aMock.searchApplications(*)).thenReturn(successful(data))
    }

    object ProcessAll {
      def thenReturn() = {
        when(aMock.processAll(*)).thenReturn(successful(()))
      }

      def verify() = {
        val captor = ArgCaptor[ApplicationData => Unit]
        ApplicationRepoMock.verify.processAll(captor)
        captor.value
      }
    }

    object RecordClientSecretUsage {
      def verifyNeverCalled() =
        ApplicationRepoMock.verify(never).recordClientSecretUsage(*[ApplicationId],*)

      def thenReturnWhen(applicationId: ApplicationId, clientSecretId: String)(applicationData: ApplicationData) =
        when(aMock.recordClientSecretUsage(eqTo(applicationId),eqTo(clientSecretId))).thenReturn(successful(applicationData))

      def thenFail(failWith: Throwable) =
        when(aMock.recordClientSecretUsage(*[ApplicationId],*)).thenReturn(failed(failWith))
    }

    object UpdateApplicationRateLimit {
      def thenReturn(applicationId: ApplicationId, rateLimit: RateLimitTier)(updatedApplication: ApplicationData) =
        when(aMock.updateApplicationRateLimit(eqTo(applicationId), eqTo(rateLimit))).thenReturn(successful(updatedApplication))

      def verifyCalledWith(applicationId: ApplicationId, rateLimit: RateLimitTier) =
        ApplicationRepoMock.verify.updateApplicationRateLimit(eqTo(applicationId), eqTo(rateLimit))
    }

    object AddClientSecret {
      def thenReturn(applicationId: ApplicationId)(updatedApplication: ApplicationData) = {
        when(aMock.addClientSecret(eqTo(applicationId), *)).thenReturn(successful(updatedApplication))
      }
    }

    object UpdateClientSecretHash {
      def thenReturn(applicationId: ApplicationId, clientSecretId: String)(updatedApplication: ApplicationData) = {
        when(aMock.updateClientSecretHash(eqTo(applicationId), eqTo(clientSecretId), *)).thenReturn(successful(updatedApplication))
      }

      def verifyCalledWith(applicationId: ApplicationId, clientSecretId: String) =
        ApplicationRepoMock.verify.updateClientSecretHash(eqTo(applicationId), eqTo(clientSecretId), *)
    }

    object DeleteClientSecret {
      def succeeds(application: ApplicationData, clientSecretId: String) = {
        val otherClientSecrets = application.tokens.production.clientSecrets.filterNot(_.id == clientSecretId)
        val updatedApplication =
          application
            .copy(tokens =
              ApplicationTokens(Token(application.tokens.production.clientId, application.tokens.production.accessToken, otherClientSecrets)))

        when(aMock.deleteClientSecret(eqTo(application.id), eqTo(clientSecretId))).thenReturn(successful(updatedApplication))
      }

      def clientSecretNotFound(applicationId: ApplicationId, clientSecretId: String) =
        when(aMock.deleteClientSecret(eqTo(applicationId), eqTo(clientSecretId)))
          .thenThrow(new NotFoundException(s"Client Secret Id [$clientSecretId] not found in Application [${applicationId.value}]"))

      def verifyNeverCalled() = ApplicationRepoMock.verify(never).deleteClientSecret(*[ApplicationId], *)
    }
  }


  object ApplicationRepoMock extends BaseApplicationRepoMock {

    val aMock = mock[ApplicationRepository]
  }

  object LenientApplicationRepoMock extends BaseApplicationRepoMock {
    val aMock = mock[ApplicationRepository](withSettings.lenient())
  }
}
