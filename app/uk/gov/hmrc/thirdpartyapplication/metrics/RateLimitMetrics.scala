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
import uk.gov.hmrc.thirdpartyapplication.domain.models.RateLimitTier.RateLimitTier
import uk.gov.hmrc.thirdpartyapplication.repository.ApplicationRepository

import scala.concurrent.{ExecutionContext, Future}

@Singleton
class RateLimitMetrics @Inject()(val applicationRepository: ApplicationRepository) extends MetricSource {
  override def metrics(implicit ec: ExecutionContext): Future[Map[String, Int]] =
    numberOfApplicationsByRateLimit.map(
      applicationCounts =>
        applicationCounts.map(rateLimit => {
          Logger.info(s"[METRIC] Number of Applications for Rate Limit ${rateLimit._1}: ${rateLimit._2}")
          applicationsByRateLimitKey(rateLimit._1) -> rateLimit._2
        }))

  def numberOfApplicationsByRateLimit(implicit ec: ExecutionContext): Future[Map[Option[RateLimitTier], Int]] =
    applicationRepository.fetchAll().map(applications => applications.groupBy(_.rateLimitTier).mapValues(_.size))

  private def applicationsByRateLimitKey(rateLimit: Option[RateLimitTier]): String = {
    val rateLimitString = if(rateLimit.isDefined) rateLimit.get.toString else "UNKNOWN"
    s"applicationsByRateLimit.$rateLimitString"
  }
}
