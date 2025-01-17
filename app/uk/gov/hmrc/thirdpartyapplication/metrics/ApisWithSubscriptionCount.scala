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

package uk.gov.hmrc.thirdpartyapplication.metrics

import com.google.inject.Singleton
import javax.inject.Inject
import play.api.Logger
import uk.gov.hmrc.metrix.domain.MetricSource
import uk.gov.hmrc.thirdpartyapplication.domain.models.ApiIdentifier
import uk.gov.hmrc.thirdpartyapplication.repository.SubscriptionRepository
import uk.gov.hmrc.thirdpartyapplication.util.MetricsHelper

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@Singleton
class ApisWithSubscriptionCount @Inject()(val subscriptionRepository: SubscriptionRepository) extends MetricSource with MetricsHelper {
  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] = {
    Logger.info("Pomegranate - Starting - ApisWithSubscriptionCount.metrics() about to calculate subscriptionCount map")
    def subscriptionCountKey(apiName: String): String = s"apisWithSubscriptionCountV1.$apiName"

    val result = numberOfSubscriptionsByApi.map(subscriptionCounts => subscriptionCounts.map(count => subscriptionCountKey(count._1) -> count._2))
    result.onComplete({
        case Success(v) =>
          Logger.info(s"Pomegranate - Future.success - ApisWithSubscriptionCount.metrics() - api versions are: ${v.keys.size}" )

        case Failure(e) =>
          Logger.info(s"Pomegranate - Future.failure - ApisWithSubscriptionCount.metrics() - error is: ${e.toString}" )
    })
    Logger.info("Pomegranate - Finish - ApisWithSubscriptionCount.metrics()")
    result
  }

  def numberOfSubscriptionsByApi(implicit ec: ExecutionContext): Future[Map[String, Int]] = {

    def apiName(apiIdentifier: ApiIdentifier): String = s"""${sanitiseGrafanaNodeName(apiIdentifier.context.value)}.${sanitiseGrafanaNodeName(apiIdentifier.version.value)}"""

    subscriptionRepository.findAll()
      .map(subscriptions => subscriptions.map(subscription => apiName(subscription.apiIdentifier) -> subscription.applications.size).toMap)
  }
}
