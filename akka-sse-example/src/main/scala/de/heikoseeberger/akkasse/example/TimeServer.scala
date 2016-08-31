/*
 * Copyright 2015 Heiko Seeberger
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

package de.heikoseeberger.akkasse.example

import akka.NotUsed
import akka.actor.{ ActorSystem, Cancellable, Props }
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.StatusCodes.PermanentRedirect
import akka.http.scaladsl.server.Directives
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.Source
import de.heikoseeberger.akkasse.{ EventStreamElement, EventStreamMarshalling, ServerSentEvent }
import java.time.LocalTime
import java.time.format.DateTimeFormatter

import akka.actor.Actor.Receive
import akka.event.Logging
import akka.http.javadsl.model.headers.AccessControlAllowOrigin
import akka.http.scaladsl.model.HttpHeader
import akka.http.scaladsl.model.headers.{ HttpOriginRange, RawHeader }
import akka.stream.actor.ActorPublisher
import spray.json.JsValue

import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

object TimeServer {
  implicit val system = ActorSystem()
  implicit val mat    = ActorMaterializer()

  def main(args: Array[String]): Unit = {
    Http().bindAndHandle(route, "127.0.0.1", 9000)
  }

  case class Data(value: Map[String, String] = Map.empty) extends AnyVal {
    def ++(other: Map[String, String]) = Data(value ++ other)
  }

  case class Updated(data: Data)

  var data: Data = Data()

  def route = {
    import Directives._
    import EventStreamMarshalling._
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import spray.json.DefaultJsonProtocol._
    import spray.json._

    def health = (path("h") & get) { complete("OK") }
    
    def assets =
      getFromResourceDirectory("web") ~ pathSingleSlash(
          get(redirect("index.html", PermanentRedirect))
      )

    val watcher = system.actorOf(Props[DataWatcher])

    def source: Source[EventStreamElement, NotUsed] =
      Source
        .fromPublisher(ActorPublisher[Data](watcher))
//        .tick(1 seconds, 1 seconds, NotUsed)
        .map(dateTimeToServerSentEvent)
        .keepAlive(1 seconds, () => ServerSentEvent.Heartbeat)

    def update =
      (path("update") & post) {
        logRequestResult("update", Logging.WarningLevel) {
          entity(as[JsValue]) { json: JsValue =>
            val map = json.asJsObject.fields map {
              case (k, vJ) => (k, vJ.toString())
            }
            data = data ++ map
            watcher ! Updated(data)
            complete(s"$data")
          }
        }
      }

    def events = path("events") {
      get {
        respondWithDefaultHeaders {
          AccessControlAllowOrigin.create(HttpOriginRange.*)
        } {
          complete {
            source
          }
        }
      }
    }

    health ~ update ~ assets ~ events
  }

  def dateTimeToServerSentEvent(data: Data): ServerSentEvent =
    ServerSentEvent(s"$data")

  class DataWatcher extends ActorPublisher[Data] {
    override def receive: Receive = {
      case Updated(data) => onNext(data)
    }
  }
}
