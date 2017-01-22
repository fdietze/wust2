package frontend

import boopickle.Default._

import diode._, Action._
import framework._
import api._, graph._

object TypePicklers {
  implicit val channelPickler = implicitly[Pickler[Channel]]
  implicit val eventPickler = implicitly[Pickler[ApiEvent]]
  implicit val authPickler = implicitly[Pickler[Authorize]]
  implicit val errorPickler = implicitly[Pickler[ApiError]]
}
import TypePicklers._

object Action {
    implicit object actionType extends ActionType[ApiEvent]
}
import Action._

case class BadRequestException(error: ApiError) extends Exception

object Client extends WebsocketClient[Channel, ApiEvent, ApiError, Authorize] {
  val api = wire[Api]
  val auth = wire[AuthApi]

  override def fromError(error: ApiError) = BadRequestException(error)
  override def receive(event: ApiEvent) = AppCircuit.dispatch(event)
}
