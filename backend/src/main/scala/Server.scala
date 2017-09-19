package wust.backend

import java.nio.ByteBuffer

import akka.http.scaladsl.server.Directives._
import akka.actor.ActorSystem
import autowire.Core.Request
import boopickle.Default._
import wust.api._
import wust.ids._
import wust.backend.auth._
import wust.backend.config.Config
import wust.db.Db
import wust.framework._
import wust.framework.state.StateHolder
import wust.util.{ Pipe, RichFuture }

import scala.concurrent.{ ExecutionContext, Future }

import scala.util.{ Success, Failure }
import scala.util.control.NonFatal

case class RequestEvent(events: Seq[ApiEvent], postGroups: Map[PostId, Set[GroupId]])

class ApiRequestHandler(distributor: EventDistributor, stateInterpreter: StateInterpreter, api: StateHolder[State, ApiEvent] => PartialFunction[Request[ByteBuffer], Future[ByteBuffer]])(implicit ec: ExecutionContext) extends RequestHandler[ApiEvent, RequestEvent, ApiError, State] {
  import stateInterpreter._

  override def initialState = State.initial

  override def validate(state: State) = stateInterpreter.validate(state)

  override def onRequest(holder: StateHolder[State, ApiEvent], request: Request[ByteBuffer]) = {
    val handler = api(holder).lift
    handler(request).toRight(NotFound(request.path))
  }

  override val toError: PartialFunction[Throwable, ApiError] = {
    case ApiException(error) => error
    case NonFatal(e) =>
      scribe.error("request handler threw exception")
      scribe.error(e)
      InternalServerError
  }

  override def publishEvents(sender: EventSender[RequestEvent], events: Seq[ApiEvent]) = distributor.publish(sender, events)

  override def filterOwnEvents(event: ApiEvent) = event match {
    case NewGraphChanges(_) => false
    case _ => true
  }

  override def transformIncomingEvent(event: RequestEvent, state: State): Future[Seq[ApiEvent]] = stateInterpreter.triggeredEvents(state, event)

  override def applyEventsToState(events: Seq[ApiEvent], state: State) = stateInterpreter.applyEventsToState(state, events)

  override def onClientInteraction(state: State, newState: State) = stateChangeEvents(state, newState)

  override def onClientConnect(sender: EventSender[RequestEvent], state: State) = {
    scribe.info(s"client started: $state")
    distributor.subscribe(sender)
    Future.successful(state)
  }

  override def onClientDisconnect(sender: EventSender[RequestEvent], state: State) = {
    scribe.info(s"client stopped: $state")
    distributor.unsubscribe(sender)
  }
}

object WebsocketFactory {
  import DbConversions._

  def apply(config: Config)(implicit ec: ExecutionContext, system: ActorSystem) = {
    val db = Db(config.db)
    val jwt = JWT(config.auth.secret, config.auth.tokenLifetime)
    val stateInterpreter = new StateInterpreter(db, jwt)

    def api(holder: StateHolder[State, ApiEvent]) = {
      val dsl = GuardDsl(jwt, db, config.auth.enableImplicit)
      AutowireServer.route[Api](new ApiImpl(holder, dsl, db)) orElse
        AutowireServer.route[AuthApi](new AuthApiImpl(holder, dsl, db, jwt))
    }

    val handler = new ApiRequestHandler(new EventDistributor(db), stateInterpreter, api _)
    new WebsocketServer(handler)
  }
}

object Server {
  import ExecutionContext.Implicits.global

  def run(config: Config, port: Int) = {
    implicit val system = ActorSystem("server")
    val ws = WebsocketFactory(config)
    val route = (path("ws") & get) {
      ws.websocketHandler
    } ~ (path("health") & get) {
      complete("ok")
    }

    ws.run(route, "0.0.0.0", port).foreach { binding =>
      scribe.info(s"Server online at ${binding.localAddress}")
    }
  }
}
