package wust.backend

import scala.concurrent.{ExecutionContext, Future}
import scala.annotation.tailrec
import scala.util.control.NonFatal
import wust.api.{ApiEvent, ApiError}, ApiError.HandlerFailure
import cats.implicits._
import monix.reactive.Observable

//TODO move to sloth/mycelium/apidsl project as opionionated dsl?

//TODO better availablity of types not in obj, package object because of type alias? but currently name clash with ApiDsl.Effect/Action => rename
object ApiData {
  case class Action[+T](data: Either[HandlerFailure, T], asyncEvents: Observable[Seq[ApiEvent]] = Observable.empty)
  case class Effect[+T](events: Seq[ApiEvent], action: Action[T])

  implicit def ActionIsEffect[T](action: Action[T]): Effect[T] = Effect(Seq.empty, action)

  type MonadError[F[_]] = cats.MonadError[F, HandlerFailure]
  def MonadError[F[_]](implicit m: cats.MonadError[F, HandlerFailure]) = m

  implicit def actionMonadError(implicit ec :ExecutionContext): MonadError[Action] = new MonadError[Action] {
    def pure[A](a: A): Action[A] = Action(Right(a))
    def raiseError[A](e: HandlerFailure): Action[A] = Action(Left(e))
    def handleErrorWith[A](e: Action[A])(f: HandlerFailure => Action[A]): Action[A] = e.data.fold(f, _ => e)
    def flatMap[A, B](e: Action[A])(f: A => Action[B]): Action[B] = e.data.fold[Action[B]]((err) => e.copy(data = Left(err)), { res =>
      val action = f(res)
      val newEvents = Observable.concat(e.asyncEvents, action.asyncEvents)
      action.copy(asyncEvents = newEvents)
    })
    @tailrec
    def tailRecM[A, B](a: A)(f: A => Action[Either[A,B]]): Action[B] = {
      val action = f(a)
      action.data match {
        case Left(e) => action.copy(data = Left(e))
        case Right(Left(next)) => tailRecM(next)(f)
        case Right(Right(b)) => action.copy(data = Right(b))
      }
    }
  }

  implicit def effectMonadError(implicit ec :ExecutionContext): MonadError[Effect] = new MonadError[Effect] {
    def pure[A](a: A): Effect[A] = ActionIsEffect(MonadError[Action].pure(a))
    def raiseError[A](e: HandlerFailure): Effect[A] = ActionIsEffect(MonadError[Action].raiseError(e))
    def handleErrorWith[A](e: Effect[A])(f: HandlerFailure => Effect[A]): Effect[A] = e.action.data.fold(f, _ => e)
    def flatMap[A, B](e: Effect[A])(f: A => Effect[B]): Effect[B] = e.action.data.fold[Effect[B]](err => e.copy(action = e.action.copy(data = Left(err))), { res =>
      val effect = f(res)
      val newEvents = e.events ++ effect.events
      effect.copy(events = newEvents)
    })
    @tailrec
    def tailRecM[A, B](a: A)(f: A => Effect[Either[A,B]]): Effect[B] = {
      val effect = f(a)
      effect.action.data match {
        case Left(e) => effect.copy(action = effect.action.copy(data = Left(e)))
        case Right(Left(next)) => tailRecM(next)(f)
        case Right(Right(b)) => effect.copy(action = effect.action.copy(data = Right(b)))
      }
    }
  }
}

case class ApiFunction[T](run: Future[State] => ApiFunction.Response[T]) extends AnyVal
object ApiFunction {
  import ApiData._

  case class ReturnValue[T](result: Either[HandlerFailure, T], events: Seq[ApiEvent])
  case class Response[T](state: Future[State], value: Future[ReturnValue[T]], asyncEvents: Observable[Seq[ApiEvent]])
  object Response {
    private val handleUserException: PartialFunction[Throwable, ApiError.HandlerFailure] = {
      case NonFatal(e) =>
        scribe.error(s"Exception in API method", e)
        ApiError.InternalServerError
    }
    private val handleDelayedUserException: PartialFunction[Throwable, Observable[Seq[ApiEvent]]] = {
      case NonFatal(e) =>
        scribe.error(s"Exception in API delayed events", e)
        Observable.empty
    }

    def action[T](oldState: Future[State], rawAction: Future[Action[T]])(implicit ec: ExecutionContext): Response[T] = {
      val action = rawAction.recover(handleUserException andThen (err => Action(Left(err))))
      val safeDelayEvents = Observable.fromFuture(action).flatMap(_.asyncEvents).onErrorRecoverWith(handleDelayedUserException)
      Response(oldState, action.map(action => ReturnValue(action.data, Seq.empty)), safeDelayEvents)
    }
    def effect[T](oldState: Future[State], rawEffect: Future[Effect[T]])(implicit ec: ExecutionContext): Response[T] = {
      val effect = rawEffect.recover(handleUserException andThen (err => ActionIsEffect(Action(Left(err)))))
      val newState = applyEventsToState(oldState, effect.map(_.events))
      val safeDelayEvents = Observable.fromFuture(effect).flatMap(_.action.asyncEvents).onErrorRecoverWith(handleDelayedUserException)
      Response(newState, effect.map(e => ReturnValue(e.action.data, e.events)), safeDelayEvents)
    }
  }
  trait Factory[F[_]] {
    def apply[T](f: State => Future[F[T]])(implicit ec: ExecutionContext): ApiFunction[T]
    def apply[T](f: => Future[F[T]])(implicit ec: ExecutionContext): ApiFunction[T]
  }

  def redirect[T](api: ApiFunction[T])(f: State => Future[Seq[ApiEvent]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction { state =>
    val events = state.flatMap(f)
    def newState = applyEventsToState(state, events)
    val response = api.run(newState)
    val newValue = for {
      events <- events
      value <- response.value
    } yield value.copy(events = events ++ value.events)

    response.copy(value = newValue)
  }

  protected def applyEventsToState(state: Future[State], events: Future[Seq[ApiEvent]])(implicit ec: ExecutionContext): Future[State] = for {
    events <- events
    state <- state
  } yield if (events.isEmpty) state else State.applyEvents(state, events)

  implicit val apiReturnValueFunctor = cats.derive.functor[ReturnValue]
  implicit def apiResponseFunctor(implicit ec: ExecutionContext) = cats.derive.functor[Response]
  implicit def apiFunctionFunctor(implicit ec: ExecutionContext) = cats.derive.functor[ApiFunction]
}

trait ApiDsl {
  object Action extends ApiFunction.Factory[ApiData.Action] {
    def apply[T](f: State => Future[ApiData.Action[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.action(s, s.flatMap(f)))
    def apply[T](f: => Future[ApiData.Action[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.action(s, f))
  }
  object Effect extends ApiFunction.Factory[ApiData.Effect] {
    def apply[T](f: State => Future[ApiData.Effect[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.effect(s, s.flatMap(f)))
    def apply[T](f: => Future[ApiData.Effect[T]])(implicit ec: ExecutionContext): ApiFunction[T] = ApiFunction(s => ApiFunction.Response.effect(s, f))
  }
  object Returns {
    def apply[T](result: T, events: Seq[ApiEvent], asyncEvents: Observable[Seq[ApiEvent]]): ApiData.Effect[T] = ApiData.Effect(events, apply(result, asyncEvents))
    def apply[T](result: T, events: Seq[ApiEvent]): ApiData.Effect[T] = ApiData.Effect(events, apply(result))
    def apply[T](result: T, asyncEvents: Observable[Seq[ApiEvent]]): ApiData.Action[T] = ApiData.Action(Right(result), asyncEvents)
    def apply[T](result: T): ApiData.Action[T] = ApiData.Action(Right(result))

    def error(failure: HandlerFailure, events: Seq[ApiEvent]): ApiData.Effect[Nothing] = ApiData.Effect(events, error(failure))
    def error(failure: HandlerFailure): ApiData.Action[Nothing] = ApiData.Action(Left(failure))
  }


  implicit def ValueIsAction[T](value: T): ApiData.Action[T] = Returns(value)
  implicit def FailureIsAction[T](failure: HandlerFailure): ApiData.Action[T] = Returns.error(failure)
  implicit def FutureValueIsAction[T](value: Future[T])(implicit ec: ExecutionContext): Future[ApiData.Action[T]] = value.map(ValueIsAction)
  implicit def FutureFailureIsAction[T](failure: Future[HandlerFailure])(implicit ec: ExecutionContext): Future[ApiData.Action[T]] = failure.map(FailureIsAction)
}
object ApiDsl extends ApiDsl
