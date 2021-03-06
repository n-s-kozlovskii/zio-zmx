/*
 * Copyright 2017-2019 John A. De Goes and the ZIO Contributors
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

package zio

import zio.clock.Clock
import zio.console._
import zio.duration._
import zio.test.Assertion._
import zio.test._
import zio.test.environment.TestClock
import zio.zmx.{ Label, _ }

object MetricServiceSpec extends DefaultRunnableSpec {

  val config = MetricsConfig(maximumSize = 20, bufferSize = 5, timeout = 5.seconds, None, None)

  def testMetricsService[E](label: String)(
    assertion: => ZIO[TestClock with Clock with Metrics with Console, E, TestResult]
  ): ZSpec[TestClock with Clock with Console, E] =
    testM(label)(assertion.provideSomeLayer[TestClock with Clock with Console](Metrics.live(config)))

  def spec =
    suite("MetricService Spec")(
      testMetricsService("Send exactly #bufferSize metrics") {
        for {
          // counts the number of published metrics
          ref     <- ZRef.make(0)
          metrics <- ZIO.access[Metrics](_.get)
          _       <- metrics.listen(list => ref.update(_ + list.size).map(_ => Chunk.empty))
          // completely fill the aggregation buffer
          _       <- ZIO.foreachPar((1 to config.bufferSize).toSet)(_ =>
                       metrics.counter("test-zmx", 1.0, 1.0, Label("test", "zmx"))
                     )
          // wait some time (shorter than config.timeout)
          // -> metrics get published because the buffer is full
          _       <- TestClock.adjust(config.timeout.dividedBy(2))
          count   <- ref.get
        } yield assert(count)(equalTo(config.bufferSize))
      },
      testMetricsService("Send (#bufferSize - 1) metrics") {
        for {
          // count the number of published metrics
          ref     <- ZRef.make(0)
          metrics <- ZIO.access[Metrics](_.get)
          _       <- metrics.listen(list => ref.update(_ + list.size).map(_ => Chunk.empty))
          // completely fill the aggregation buffer except one element
          _       <- ZIO.foreachPar((1 until config.bufferSize).toSet)(_ =>
                       metrics.counter("test-zmx", 1.0, 1.0, Label("test", "zmx"))
                     )
          // wait some time (shorter than config.timeout)
          // -> metrics get not published because the buffer is not yet full
          _       <- TestClock.adjust(config.timeout.dividedBy(2))
          count1  <- ref.get
          // wait some more time
          _       <- TestClock.adjust(config.timeout)
          // currently buffered metrics are published by now
          count2  <- ref.get
        } yield assert(count1)(equalTo(0)) && assert(count2)(equalTo(config.bufferSize - 1))
      },
      testMetricsService("Send (#maximumSize + 2) metrics") {
        for {
          // count the number of published metrics
          ref     <- ZRef.make(0)
          metrics <- ZIO.access[Metrics](_.get)
          _       <- metrics.listen(list => ref.update(_ + list.size).map(_ => Chunk.empty))
          // completely fill the overall buffer except one
          _       <- ZIO.foreachPar((1 until config.maximumSize).toSet)(_ =>
                       metrics.counter("test-zmx", 1.0, 1.0, Label("test", "zmx"))
                     )
          // try to send two more metrics (without adjusting the clock)
          // -> the first one is still accepted, the second one gets rejected
          sent1   <- metrics.counter("test-zmx", 1.0, 1.0, Label("test", "zmx"))
          sent2   <- metrics.counter("test-zmx", 1.0, 1.0, Label("test", "zmx"))
        } yield assert(sent1)(equalTo(true)) && assert(sent2)(equalTo(false))
      }
    )
}
