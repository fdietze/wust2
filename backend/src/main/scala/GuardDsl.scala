package wust.backend

import wust.ids._
import wust.api._
import wust.db.Db
import wust.backend.auth._
import wust.graph._
import DbConversions._

import scala.concurrent.{ ExecutionContext, Future }
import cats.implicits._
import scala.util.control.NonFatal

class GuardDsl(jwt: JWT, db: Db)(implicit ec: ExecutionContext) extends ApiDsl {

  private def createImplicitAuth(userId: UserId, userName: String): Future[Option[Authentication.Verified]] = {
    db.user
      .createImplicitUser(userId, userName)
      .map(_.map(user => jwt.generateAuthentication(user)))
  }

  implicit class GuardedOps[F[+_] : ApiData.MonadError](factory: ApiFunction.Factory[F]) {
    private def requireUserT[T, U <: User](f: (State, U) => Future[F[T]])(userf: PartialFunction[User, U]): ApiFunction[T] = factory { state =>
      Some(state.auth.user)
        .collect(userf andThen (f(state, _)))
        .getOrElse(Future.successful(ApiData.MonadError.raiseError(ApiError.Unauthorized)))
    }

    def requireImplicitUser[T](f: (State, User.Implicit) => Future[F[T]]): ApiFunction[T] = requireUserT[T, User.Implicit](f) { case u: User.Implicit => u }
    def requireAssumedUser[T](f: (State, User.Assumed) => Future[F[T]]): ApiFunction[T] = requireUserT[T, User.Assumed](f) { case u: User.Assumed => u }
    def requireRealUser[T](f: (State, User.Real) => Future[F[T]]): ApiFunction[T] = requireUserT[T, User.Real](f) { case u: User.Real => u }
    def requireDbUser[T](f: (State, User.Persisted) => Future[F[T]]): ApiFunction[T] = requireUserT[T, User.Persisted](f) { case u: User.Persisted => u }

    def assureDbUser[T](f: (State, User.Persisted) => Future[F[T]]): ApiFunction[T] = ApiFunction.redirect(requireDbUser(f)) { state =>
      val auth = state.auth.user match {
        case user: User.Assumed => createImplicitAuth(user.id, user.name)
        case _ => Future.successful(None)
      }

      auth.map(auth => Transforms(Seq.empty ++ auth.map(ApiEvent.LoggedIn(_))))
      // auth.map(auth => Transforms(auth.map(ApiEvent.LoggedIn(_)).toSeq)) //TODO drunk compiler?
    }
  }


  def isGroupMember[T, F[_] : ApiData.MonadError](groupId: GroupId, userId: UserId)(code: => Future[F[T]])(implicit ec: ExecutionContext): Future[F[T]] = {
    (for {
      isMember <- db.group.isMember(groupId, userId) if isMember
      result <- code
    } yield result).recover { case NonFatal(_) => ApiData.MonadError.raiseError(ApiError.Forbidden) }
  }

  def hasAccessToPost[T, F[_] : ApiData.MonadError](postId: PostId, userId: UserId)(code: => Future[F[T]])(implicit ec: ExecutionContext): Future[F[T]] = {
    (for {
      hasAccess <- db.group.hasAccessToPost(userId, postId) if hasAccess
      result <- code
    } yield result).recover { case NonFatal(_) => ApiData.MonadError.raiseError(ApiError.Forbidden) }
  }
}
