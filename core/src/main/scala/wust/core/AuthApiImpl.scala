package wust.core

import cats.syntax.functor._
import com.roundeights.hasher.Hasher
import wust.api.ApiEvent.{NewGraphChanges, ReplaceNode}
import wust.api._
import wust.core.DbConversions._
import wust.core.Dsl._
import wust.core.auth.{JWT, OAuthClientServiceLookup}
import wust.core.config.ServerConfig
import wust.db.{Data, Db, SuccessResult}
import wust.graph.{Edge, GraphChanges, Node}
import wust.ids._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

class AuthApiImpl(dsl: GuardDsl, db: Db, jwt: JWT, emailFlow: AppEmailFlow, oAuthClientServiceLookup: OAuthClientServiceLookup)(implicit ec: ExecutionContext) extends AuthApi[ApiFunction] {
  import dsl._

  private def isValidEmail(email: String) = email.contains("@")

  def resetPassword(email: String): ApiFunction[Boolean] = Action { state =>
    db.user.getUserByVerifiedMail(email).map {
      case Some(user) =>
        val resetToken = jwt.generatePasswordResetToken(user)
        emailFlow.sendPasswordReset(email = email, resetJwt = resetToken)
        Returns(true)
      case None => Returns(false)
    }
  }

  def changePassword(password: Password): ApiFunction[Boolean] = Effect.requireRealUser { (state, user) =>
    val digest = passwordDigest(password)
    db.ctx.transaction { implicit ec =>
      db.user.changePassword(user.id, digest)
        .map(_ => Returns(true))
    }
  }

  //TODO: some email or name or password checks?
  def register(name: String, email: String, password: Password): ApiFunction[AuthResult] = Effect { state =>
    if (isValidEmail(email)) {
      val digest = passwordDigest(password)
      state.auth.map(_.user) match {
        case Some(AuthUser.Implicit(prevUserId, _, _, _)) =>
          val user = db.ctx.transaction { implicit ec =>
            db.user.activateImplicitUser(prevUserId, name = name, email = email, passwordDigest = digest)
          }.recover { case NonFatal(t) => None }
          resultOnVerifiedAuthAfterRegister(user, email = email, replaces = Some(prevUserId))
        case Some(AuthUser.Assumed(userId)) =>
          val user = db.ctx.transaction { implicit ec =>
            db.user.create(userId, name = name, email = email, passwordDigest = digest)
          }.map(Some(_)).recover{ case NonFatal(t) => None }
          resultOnVerifiedAuthAfterRegister(user, email = email)
        case _ =>
          val user = db.ctx.transaction { implicit ec =>
            db.user.create(UserId.fresh, name = name, email = email, passwordDigest = digest)
          }.map(Some(_)).recover { case NonFatal(t) => None }
          resultOnVerifiedAuthAfterRegister(user, email = email)
      }
    } else Future.successful(Returns(AuthResult.InvalidEmail))
  }

  def login(email: String, password: Password): ApiFunction[AuthResult] = Effect { state =>
    val digest = passwordDigest(password)
    db.user.getUserAndDigestByEmail(email).flatMap {
      case Some((user, userDigest)) if (digest.hash = userDigest) =>
        state.auth.flatMap(_.dbUserOpt) match {
          case Some(AuthUser.Implicit(prevUserId, _, _, _)) =>
            db.ctx.transaction { implicit ec =>
              db.user.mergeImplicitUser(prevUserId, user.id).map {
                case true =>
                  resultOnVerifiedAuthAfterLogin(user, replaces = Some(prevUserId)).map { _ =>
                    AuthResult.Success
                  }
                case false =>
                  scribe.warn("Failed to merge implicit user for login: " + user)
                  Returns.error(ApiError.InternalServerError)
              }
            }
          case _ => Future.successful(resultOnVerifiedAuthAfterLogin(user).map { _ =>
            AuthResult.Success
          })
        }

      case Some(_) => Future.successful(Returns(AuthResult.BadPassword))
      case None    => Future.successful(Returns(AuthResult.BadEmail))
    }
  }

  def loginReturnToken(email: String, password: Password): ApiFunction[Option[Authentication.Verified]] = Effect { state =>
    val digest = passwordDigest(password)
    db.user.getUserAndDigestByEmail(email).flatMap {
      case Some((user, userDigest)) if (digest.hash = userDigest) =>
        state.auth.flatMap(_.dbUserOpt) match {
          case Some(AuthUser.Implicit(prevUserId, _, _, _)) =>
            db.ctx.transaction { implicit ec =>
              db.user.mergeImplicitUser(prevUserId, user.id).map {
                case true =>
                  resultOnVerifiedAuthAfterLogin(user, replaces = Some(prevUserId)).map { auth => Some(auth) }
                case false =>
                  scribe.warn("Failed to merge implicit user for login: " + user)
                  Returns.error(ApiError.InternalServerError)
              }
            }
          case _ => Future.successful(resultOnVerifiedAuthAfterLogin(user).map { auth => Some(auth) })
        }

      case Some(_) => Future.successful(Returns(None))
      case None    => Future.successful(Returns(None))
    }
  }

  def loginToken(token: Authentication.Token): ApiFunction[Boolean] = Effect { state =>
    val newAuth = validAuthFromToken(token)
    resultOnVerifiedAuth(newAuth)
  }

  def verifyToken(token: Authentication.Token): ApiFunction[Option[Authentication.Verified]] = Action {
    validAuthFromToken(token)
  }

  def assumeLogin(user: AuthUser.Assumed): ApiFunction[Boolean] = Effect { _ =>
    db.user.checkIfUserAlreadyExists(user.id).map { alreadyExists =>
      resultOnAssumedAuth(!alreadyExists, Authentication.Assumed(user))
    }
  }

  def logout(): ApiFunction[Boolean] = Effect { _ =>
    val newAuth = Authentication.Assumed.fresh
    Future.successful(resultOnAssumedAuth(true, newAuth))
  }

  def createImplicitUserForApp(): ApiFunction[Option[Authentication.Verified]] = Action { _ =>
    val userId = UserId.fresh
    val implUser = db.ctx.transaction { implicit ec =>
      db.user.createImplicitUser(userId, userId.toBase58)
    }

    implUser.map { auth =>
      Some(jwt.generateAuthentication(auth))
    }
  }

  def issuePluginToken(): ApiFunction[Authentication.Verified] = Action.assureDbUser { (_, user) =>
    //TODO generate special token for plugins to allow onBehalf changes
    Future.successful(jwt.generateAuthentication(user))
  }

  override def getUserDetail(userId: UserId): ApiFunction[Option[UserDetail]] = Action.requireUser { (_, user) =>
    user match {
      case user: AuthUser.Assumed => Future.successful(Returns(None))
      case user: AuthUser.Persisted if user.id == userId => db.user.getUserDetail(user.id).map(detail => Returns(detail.map(forClient(_)))) // currently only allow to access own user details
      case _ => Future.successful(Returns.error(ApiError.Forbidden))
    }
  }

  override def updateUserEmail(userId: UserId, newEmail: String): ApiFunction[Boolean] = Action.requireRealUser { (_, user) =>
    if (userId == user.id) { // currently only allow to change own user details
      if (isValidEmail(newEmail)) {
        val result = db.user.updateUserEmail(user.id, newEmail)

        result.map { success =>
          if (success) sendEmailVerification(UserDetail(userId, Some(newEmail), verified = false))
          Returns(success)
        }
      } else Future.successful(false)
    } else Future.successful(Returns.error(ApiError.Forbidden))
  }

  override def resendEmailVerification(userId: UserId): ApiFunction[Unit] = Action.requireRealUser { (_, user) =>
    if (userId == user.id) { // currently only allow to change own user details
      db.user.getUserDetail(user.id).map {
        case Some(userDetail) => sendEmailVerification(userDetail)
        case None             => ()
      }
    } else Future.successful(Returns.error(ApiError.Forbidden))
  }

  // TODO: we just assume, this inviteTargetMail user does not exist. Actually we should check, whether we have a user with this email already in our db. We don't want to send invite mail to existing user. We should add them as member and set invite edge. Currently the frontend does this distinction and we just trust it.
  override def invitePerMail(inviteTargetMail: String, nodeId: NodeId): ApiFunction[Unit] = Effect.requireRealUser { (state, dbUser) =>
    db.user.getUserDetail(dbUser.id).flatMap{
      case Some(Data.UserDetail(userId, Some(inviterEmail), true)) => // only allow verified user with an email to send out invitations
        db.node.get(dbUser.id, nodeId).flatMap{
          case Some(node: Data.Node) => forClient(node) match { // this node exists and the inviter dbUser has access to it
            case node: Node.Content => // make sure we only allow this for content nodes and not for users
              // create an implicit user for the invite target
              // make him a member of this node and create an invite edge
              val invitedUserId = UserId.fresh
              val invitedName = inviteTargetMail.split("@").head
              val invitedEdges = Array[Edge](Edge.Member(nodeId = node.id, EdgeData.Member(AccessLevel.ReadWrite), userId = invitedUserId), Edge.Invite(nodeId = node.id, userId = invitedUserId))
              db.ctx.transaction { implicit ec =>
                for {
                  invitedUser <- db.user.createImplicitUser(invitedUserId, invitedName)
                  SuccessResult <- db.edge.create(invitedEdges.map(forDb(_)))
                  invitedAuthToken = jwt.generateInvitationToken(forClientAuth(invitedUser).asInstanceOf[AuthUser.Implicit])
                } yield {
                  emailFlow.sendEmailInvitation(
                    email = inviteTargetMail,
                    invitedJwt = invitedAuthToken,
                    inviterName = dbUser.name,
                    inviterEmail = inviterEmail,
                    node = node
                  )
                  val changes = GraphChanges(addEdges = invitedEdges)
                  Returns((), Seq(NewGraphChanges.forAll(invitedUser.toNode, changes))) // we make inivitedUser the author of this graphchange, so receivers get the new user node.
                }
              }
            case _ => Future.successful(Returns.error(ApiError.Forbidden))
          }
          case _ => Future.successful(Returns.error(ApiError.Forbidden))
        }
      case _ => Future.successful(Returns.error(ApiError.Forbidden))
    }
  }

  def acceptInvitation(token: Authentication.Token): ApiFunction[Unit] = Effect.assureDbUser { (_, user) =>
    jwt.invitationUserFromToken(token) match {
      case Some(tokenUser: AuthUser.Implicit) => db.ctx.transaction { implicit ec =>
        for {
            Some(_) <- db.user.checkIfEqualUserExists(tokenUser)
            true <- db.user.mergeImplicitUser(implicitId = tokenUser.id, userId = user.id)
          } yield Returns((), Seq(ReplaceNode(tokenUser.id, user.toNode)))
      }
      case _ => Future.successful(Returns.error(ApiError.Forbidden))
    }
  }

  private def sendEmailVerification(userDetail: UserDetail): Unit = if (!userDetail.verified) userDetail.email.foreach { email =>
    emailFlow.sendEmailVerification(userDetail.userId, email)
  }

  private def passwordDigest(password: Password) = Hasher(password.string).bcrypt

  private def authChangeEvents(auth: Authentication): Seq[ApiEvent] = {
    val authEvent = auth match {
      case auth: Authentication.Assumed  => ApiEvent.AssumeLoggedIn(auth)
      case auth: Authentication.Verified => ApiEvent.LoggedIn(auth)
    }

    authEvent :: Nil
  }

  private def resultOnAssumedAuth(success: Boolean, auth: Authentication.Assumed): ApiData.Effect[Boolean] = {
    if (success) Returns(true, authChangeEvents(auth)) else Returns(false)
  }

  private def resultOnVerifiedAuth(auth: Future[Option[Authentication.Verified]]): Future[ApiData.Effect[Boolean]] = auth.map {
    case Some(auth) => Returns(true, authChangeEvents(auth))
    case None       => Returns(false)
  }

  private def resultOnVerifiedAuthAfterRegister(res: Future[Option[Data.User]], email: String, replaces: Option[UserId] = None): Future[ApiData.Effect[AuthResult]] = res.map {
    case Some(u) =>
      sendEmailVerification(UserDetail(u.id, Some(email), verified = false))

      val replacements = replaces.map(id => ReplaceNode(id, u.toNode))
      Returns(AuthResult.Success, authChangeEvents(jwt.generateAuthentication(forClientAuth(u))) ++ replacements)
    case None => Returns(AuthResult.BadEmail)
  }

  private def resultOnVerifiedAuthAfterLogin(user: Data.User, replaces: Option[UserId] = None): ApiData.Effect[Authentication.Verified] = {
    val replacements = replaces.map(id => ReplaceNode(id, user.toNode))
    val auth: Authentication.Verified = jwt.generateAuthentication(forClientAuth(user))

    Returns(auth, authChangeEvents(auth) ++ replacements)
  }

  override def getOAuthConnectUrl(service: OAuthClientService): ApiFunction[Option[String]] = Action.requireDbUser { (_, user) =>
    Future.successful(oAuthClientServiceLookup.getUrl(user.id, service))
  }

  override def getOAuthClients(): ApiFunction[Seq[OAuthClientService]] = Action.requireDbUser { (_, user) =>
    db.oAuthClients.getAll(user.id)
  }

  override def deleteOAuthClient(service: OAuthClientService): ApiFunction[Boolean] = Action.requireDbUser { (_, user) =>
    db.oAuthClients.delete(user.id, service)
  }
}
