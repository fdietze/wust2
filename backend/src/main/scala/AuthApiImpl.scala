package wust.backend

import com.roundeights.hasher.Hasher
import wust.api._
import wust.backend.DbConversions._
import wust.backend.auth._
import wust.db.Db
import wust.util.RichFuture

import scala.concurrent.{ExecutionContext, Future}

class AuthApiImpl(holder: StateHolder[State, ApiEvent], dsl: GuardDsl, db: Db, jwt: JWT)(implicit ec: ExecutionContext) extends AuthApi {
  import holder._

  private def passwordDigest(password: String) = Hasher(password).bcrypt

  private def applyAuthenticationOnState(state: State, auth: Future[Option[JWTAuthentication]]): Future[State] = auth.map {
    case auth @ Some(_) => state.copy(auth = auth)
    case None           => State.initial
  }

  def register(name: String, password: String): Future[Boolean] = { (state: State) =>
    val digest = passwordDigest(password)
    val (auth, success) = state.auth.map(_.user) match {
      case Some(user) if user.isImplicit =>
        //TODO: propagate name change to the respective groups
        val activated = db.user.activateImplicitUser(user.id, name, digest).map(_.map(u => jwt.generateAuthentication(u)))
        (activated.map(_.orElse(state.auth)), activated.map(_.isDefined))
      case _ =>
        val newAuth = db.user(name, digest).map(_.map(u => jwt.generateAuthentication(u)))
        (newAuth, newAuth.map(_.isDefined))
    }

    StateEffect(applyAuthenticationOnState(state, auth), success)
  }

  def login(name: String, password: String): Future[Boolean] = { (state: State) =>
    val digest = passwordDigest(password)
    val implicitAuth = state.auth.filter(_.user.isImplicit)
    val result = db.user.getUserAndDigest(name).map(_ match {
      case Some((user, userDigest)) if (digest.hash = userDigest) =>
        //TODO integrate result into response?
        implicitAuth.foreach { implicitAuth =>
          //TODO propagate new groups into state?
          //TODO: propagate name change to the respective groups and the connected clients
          db.user.mergeImplicitUser(implicitAuth.user.id, user.id).log
        }

        (Some(jwt.generateAuthentication(user)), true)
      case _ => (implicitAuth, false)
    })

    val auth = result.map(_._1)
    val success = result.map(_._2)
    StateEffect(applyAuthenticationOnState(state, auth), success)
  }

  def loginToken(token: Authentication.Token): Future[Boolean] = { (state: State) =>
    val auth = jwt.authenticationFromToken(token).map { auth =>
      for (valid <- db.user.checkIfEqualUserExists(auth.user))
        yield if (valid) Option(auth) else None
    }.getOrElse(Future.successful(None))

    StateEffect(applyAuthenticationOnState(state, auth), auth.map(_.isDefined))
  }

  def logout(): Future[Boolean] = { (state: State) =>
    val auth = Future.successful(None)
    StateEffect(applyAuthenticationOnState(state, auth), Future.successful(true))
  }
}
