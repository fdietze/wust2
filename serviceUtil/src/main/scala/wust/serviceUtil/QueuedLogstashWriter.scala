package wust.serviceUtil

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.coding.Gzip
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model.HttpHeader.ParsingResult
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.ActorMaterializer
import akka.util.ByteString
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import monix.eval.Task
import monix.execution.{Ack, Cancelable, Scheduler}
import monix.reactive.subjects.ConcurrentSubject
import perfolation._
import scribe.writer.Writer
import scribe.{LogRecord, MDC}

import scala.collection.breakOut
import scala.concurrent.duration._

final case class LogstashRecord(message: String,
  service: String,
  level: String,
  value: Double,
  throwable: Option[String],
  fileName: String,
  className: String,
  methodName: Option[String],
  lineNumber: Option[Int],
  thread: String,
  `@timestamp`: String,
  mdc: Map[String, String])

final case class HttpRequestException(statusCode: Int, reason: String) extends Exception(s"Got unexpected http response: $statusCode - $reason")

class QueuedLogstashWriter(
  url: String,
  service: String,
  additionalFields: Map[String, String] = Map.empty,
  headers: List[(String,String)] = Nil)(implicit
  scheduler: Scheduler,
  system: ActorSystem,
  materializer: ActorMaterializer
) extends Writer {

  private val logSubject = ConcurrentSubject.publish[LogstashRecord]

  private val akkaHeaders = headers.flatMap { case (key, value) =>
    HttpHeader.parse(key, value) match {
      case ParsingResult.Ok(header, _) => Some(header)
      case ParsingResult.Error(err) =>
        scribe.warn(s"Error parsing header ($key: $value): $err")
        None
    }
  }

  override def write[M](record: LogRecord[M], output: String): Unit = {
    val logstashRecord = recordToLogstash(record)
    logSubject.onNext(logstashRecord)
  }

  private def recordToLogstash[M](record: LogRecord[M]): LogstashRecord = {
    val l = record.timeStamp
    val timestamp = s"${l.t.F}T${l.t.T}.${l.t.L}${l.t.z}"
    LogstashRecord(
      message = record.message,
      service = service,
      level = record.level.name,
      value = record.value,
      throwable = record.throwable.map(LogRecord.throwable2String(None, _)),
      fileName = record.fileName,
      className = record.className,
      methodName = record.methodName,
      lineNumber = record.line,
      thread = record.thread.getName,
      `@timestamp` = timestamp,
      mdc = MDC.map
    )
  }

  private def sendLogs(records: Seq[LogstashRecord]): Task[Unit] = {
    if (records.isEmpty) Task.pure(())
    else {
      val jsonBytes: Array[Byte] = records.flatMap { record: LogstashRecord =>
        val jsonObj = record.asJson.asObject.get
        val jsonWithFields = additionalFields.foldLeft(jsonObj) { (obj, field) =>
          obj.add(field._1, Json.fromString(field._2))
        }
        val json = Json.fromJsonObject(jsonWithFields).noSpaces
        (json + "\n").getBytes
      }(breakOut)


      val gzipHeader = `Content-Encoding`.apply(HttpEncodings.gzip)
      val gzipped = Gzip.encode(ByteString.apply(jsonBytes))

      Task.deferFuture(
        Marshal(gzipped).to[MessageEntity].flatMap { entity =>
          Http().singleRequest(HttpRequest(method = HttpMethods.POST, uri = url, headers = gzipHeader :: akkaHeaders, entity = entity))
        }
      ).flatMap { response =>
        response.discardEntityBytes()
        if (response.status.isSuccess()) Task.pure(())
        else Task.raiseError(HttpRequestException(response.status.intValue, response.status.reason))
      }
    }
  }

  def start(): Cancelable = logSubject
    .bufferTimedAndCounted(timespan = 10.seconds, maxCount = 100)
    .subscribe(
      { logs =>
        MonixUtils.retryWithBackoff(sendLogs(logs), maxRetries = 3, initialDelay = 5.seconds).runAsync {
          case Right(_) => ()
          case Left(t) => println(s"Failed to send logs to logstash, dropping ${logs.size} log records: $t")
        }

        Ack.Continue
      },
      err => println(s"Error while logging to logstash, cannot continue: $err")
    )
}
