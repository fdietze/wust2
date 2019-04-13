package wust.backend

import wust.api._
import wust.backend.auth.JWT
import sloth._
import covenant.core.api._
import covenant.ws.api._
import covenant.http.api._
import akka.http.scaladsl.model._

import scala.concurrent.{ExecutionContext, Future}

object Dsl extends ApiDsl[ApiEvent, ApiError, State] {
  override def applyEventsToState(state: State, events: Seq[ApiEvent]): State =
    State.applyEvents(state, events)
  override def unhandledException(t: Throwable): ApiError = ApiError.InternalServerError
}

class ApiConfiguration(
    guardDsl: GuardDsl,
    val eventDistributor: EventDistributor[ApiEvent, State]
)(implicit ec: ExecutionContext)
    extends WsApiConfiguration[ApiEvent, ApiError, State]
    with HttpApiConfiguration[ApiEvent, ApiError, State] {
  override def initialState: State = State.initial
  override def isStateValid(state: State): Boolean = state.auth match {
    case Some(auth: Authentication.Verified) => !JWT.isExpired(auth)
    case _                                   => true
  }

  override def serverFailure(error: ServerFailure): ApiError = error match {
    case ServerFailure.HandlerError(t) =>
      scribe.warn("Server got unexpected error from request handler", t)
      ApiError.InternalServerError
    case ServerFailure.DeserializerError(t) =>
      scribe.warn("Server cannot deserialize request", t)
      ApiError.IncompatibleApi
    case ServerFailure.PathNotFound(path) =>
      scribe.warn(s"Server cannot map request path: $path")
      ApiError.IncompatibleApi
  }

  override def dsl: ApiDsl[ApiEvent, ApiError, State] = Dsl

  override def scopeOutgoingEvents(events: List[ApiEvent]): ScopedEvents[ApiEvent] = {
    val (privateEvents, publicEvents) = ApiEvent.separateToPrivateAndPublicEvents(events)
    ScopedEvents(privateEvents = privateEvents, publicEvents = publicEvents)
  }
  override def adjustIncomingEvents(state: State, events: List[ApiEvent]): Future[List[ApiEvent]] =
    Future.successful(events)

  override def requestToState(request: HttpRequest): Future[State] = {
    request.headers.find(_.is("authorization")) match {
      case Some(authHeader) =>
        guardDsl.validAuthFromToken(Authentication.Token(authHeader.value)).flatMap {
          case auth @ Some(_) => Future.successful(State(auth))
          case None           => Future.failed(new Exception("Invalid Authorization Header"))
        }
      case None => Future.successful(State(auth = None))
    }
  }
  override def publishEvents(events: List[ApiEvent]): Unit = eventDistributor.publish(events)
}
