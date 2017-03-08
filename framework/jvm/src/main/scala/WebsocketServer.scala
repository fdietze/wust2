package framework

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import java.nio.ByteBuffer

import akka.NotUsed
import akka.actor._
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.model.ws.{ Message, BinaryMessage }
import akka.stream.{ ActorMaterializer, OverflowStrategy }
import akka.stream.scaladsl._
import akka.util.ByteString
import autowire.Core.{ Request, Router }
import boopickle.Default._

import framework.message._

object WebsocketSerializer {
  def serialize[T: Pickler](msg: T): Message = {
    val bytes = Pickle.intoBytes(msg)
    BinaryMessage(ByteString(bytes))
  }

  def deserialize[T: Pickler](bm: BinaryMessage.Strict): T = {
    val bytes = bm.getStrictData.asByteBuffer
    val msg = Unpickle[T].fromBytes(bytes)
    msg
  }
}

object WebsocketFlow {
  def apply[Channel, Event, Error, AuthToken, User](
    messages: Messages[Channel, Event, Error, AuthToken],
    handler: RequestHandler[Channel, Event, Error, AuthToken, User])(implicit system: ActorSystem): Flow[Message, Message, NotUsed] = {

    import WebsocketSerializer._
    import messages._

    val connectedClientActor = system.actorOf(Props(new ConnectedClient(messages, handler)))

    val incoming: Sink[Message, NotUsed] =
      Flow[Message].map {
        case bm: BinaryMessage.Strict =>
          val msg = deserialize[ClientMessage](bm)
          scribe.info(s"<-- $msg")
          msg
        //TODO: streamed?
      }.to(Sink.actorRef[ClientMessage](connectedClientActor, ConnectedClient.Stop))

    val outgoing: Source[Message, NotUsed] =
      Source.actorRef[Any](10, OverflowStrategy.fail) //TODO why 10?
        .mapMaterializedValue { outActor =>
          connectedClientActor ! ConnectedClient.Connect(outActor)
          NotUsed
        }.map {
          //TODO no any, proper serialize map
          case msg: ServerMessage =>
            scribe.info(s"--> $msg")
            WebsocketSerializer.serialize(msg)
          case other: Message => other
        }

    Flow.fromSinkAndSource(incoming, outgoing)
  }
}

class WebsocketServer[Channel, Event, Error, AuthToken, User](
    val messages: Messages[Channel, Event, Error, AuthToken],
    handler:      RequestHandler[Channel, Event, Error, AuthToken, User]) {

  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()

  def websocketHandler = handleWebSocketMessages(WebsocketFlow(messages, handler))

  def run(route: Route, interface: String, port: Int): Future[ServerBinding] = {
    Http().bindAndHandle(route, interface = interface, port = port)
  }
}
