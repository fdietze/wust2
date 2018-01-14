package wust.backend

import scala.concurrent.{ExecutionContext, Future}
import wust.api.{ApiEvent, ApiError}, ApiError.HandlerFailure
import cats.implicits._

//TODO move to sloth project as opionionated dsl?

object ApiData {
  case class Action[T](result: Either[HandlerFailure, T], events: Seq[ApiEvent])
  case class Effect[T](state: State, action: Action[T])

  implicit val apiActionFunctor = cats.derive.functor[ApiData.Action]
  implicit val apiEffectFunctor = cats.derive.functor[ApiData.Effect]
}

sealed trait ApiFunction[T] {
  def apply(state: Future[State])(implicit ec: ExecutionContext): ApiFunction.ReturnValue[T]
}
object ApiFunction {
  case class ReturnValue[T](state: Future[State], action: Future[ApiData.Action[T]])
  object ReturnValue {
    def apply[T](effect: Future[ApiData.Effect[T]])(implicit ec: ExecutionContext): ReturnValue[T] = ReturnValue(effect.map(_.state), effect.map(_.action))
  }

  case class Action[T](f: State => Future[ApiData.Action[T]]) extends ApiFunction[T] {
    def apply(state: Future[State])(implicit ec: ExecutionContext) = ReturnValue(state, state.flatMap(f))
  }
  case class IndependentAction[T](f: () => Future[ApiData.Action[T]]) extends ApiFunction[T] {
    def apply(state: Future[State])(implicit ec: ExecutionContext) = ReturnValue(state, f())
  }
  case class Effect[T](f: State => Future[ApiData.Effect[T]]) extends ApiFunction[T] {
    def apply(state: Future[State])(implicit ec: ExecutionContext) = ReturnValue(state.flatMap(f))
  }
  case class IndependentEffect[T](f: () => Future[ApiData.Effect[T]]) extends ApiFunction[T] {
    def apply(state: Future[State])(implicit ec: ExecutionContext) = ReturnValue(f())
  }

  //TODO: why do we need these Future[F[T]] types and implicits?
  type FutureAction[T] = Future[ApiData.Action[T]]
  type FutureEffect[T] = Future[ApiData.Effect[T]]
  implicit def futureApiActionFunctor(implicit ec: ExecutionContext) = cats.derive.functor[FutureAction]
  implicit def futureApiEffectFunctor(implicit ec: ExecutionContext) = cats.derive.functor[FutureEffect]

  implicit def apiFunctionFunctor(implicit ec: ExecutionContext) = cats.derive.functor[ApiFunction]
}

trait ApiDsl {
  object Action {
    def apply[T](f: State => Future[ApiData.Action[T]]): ApiFunction[T] = ApiFunction.Action(f)
    def apply[T](f: => Future[ApiData.Action[T]]): ApiFunction[T] = ApiFunction.IndependentAction(() => f)
  }
  object Effect {
    def apply[T](f: State => Future[ApiData.Effect[T]]): ApiFunction[T] = ApiFunction.Effect(f)
    def apply[T](f: => Future[ApiData.Effect[T]]): ApiFunction[T] = ApiFunction.IndependentEffect(() => f)
  }

  object Returns {
    def apply[T](state: State, result: T, events: Seq[ApiEvent] = Seq.empty): ApiData.Effect[T] = ApiData.Effect(state, ApiData.Action(Right(result), events))
    def apply[T](result: T, events: Seq[ApiEvent]): ApiData.Action[T] = ApiData.Action(Right(result), events)
    def apply[T](result: T): ApiData.Action[T] = Returns(result, Seq.empty)
  }

  object Failure {
    def apply[T](state: State, failure: HandlerFailure, events: Seq[ApiEvent] = Seq.empty): ApiData.Effect[T] = ApiData.Effect(state, ApiData.Action(Left(failure), events))
    def apply[T](failure: HandlerFailure, events: Seq[ApiEvent]): ApiData.Action[T] = ApiData.Action(Left(failure), events)
    def apply[T](failure: HandlerFailure): ApiData.Action[T] = Failure(failure, Seq.empty)
  }

  implicit def ValueIsAction[T](value: T): ApiData.Action[T] = Returns(value)
  implicit def FailureIsAction[T](failure: HandlerFailure): ApiData.Action[T] = Failure(failure)
  implicit def FutureValueIsAction[T](value: Future[T])(implicit ec: ExecutionContext): Future[ApiData.Action[T]] = value.map(ValueIsAction)
  implicit def FutureFailureIsAction[T](failure: Future[HandlerFailure])(implicit ec: ExecutionContext): Future[ApiData.Action[T]] = failure.map(FailureIsAction)
}
object ApiDsl extends ApiDsl
