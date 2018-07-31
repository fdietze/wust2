// package wust.webApp.views

// import org.scalajs.dom.window.location

// import wust.graph.{ User, Group }
// import wust.webApp.{ Client, GlobalState }
// import wust.util.tags._
// import wust.ids._

// import scala.concurrent.Future
// import scala.collection.mutable
// import scala.scalajs.concurrent.JSExecutionContext.Implicits.queue
// import wust.util.Analytics

//object UserView {
//  import Elements._

//  val userField = input(tpe := "text", placeholder := "user name").render
//  val passwordField = inputPassword(placeholder := "password").render
//  def handleAuthResponse(topic: String, success: Boolean) = {
//    if (success) {
//      userField.value = ""
//      passwordField.value = ""
//      Analytics.sendEvent("auth", topic, "success")
//    } else {
//      Analytics.sendEvent("auth", topic, "failure")
//    }
//  }

//  val registerButton = buttonClick(
//    "register",
//    Client.auth.register(userField.value, passwordField.value).call().foreach(handleAuthResponse("register", _))
//  )
//  val loginButton = buttonClick(
//    "login",
//    Client.auth.login(userField.value, passwordField.value).call().foreach(handleAuthResponse("login", _))
//  )
//  val logoutButton = buttonClick(
//    "logout",
//    Client.auth.logout().call().foreach(handleAuthResponse("logout", _))
//  )

//  //TODO: show existing in backend to revoke?
//  //TODO: instead of this local var, get all tokens from backend
//  private val createdGroupInvites = Var[Map[Group, String]](Map.empty)
//  def groupInvite(group: Group)(implicit ctx: Ctx.Owner) =
//    div(
//      group.id.toString,
//      Rx {
//        val invites = createdGroupInvites()
//        span(
//          invites.get(group).map(token => aUrl(s"${location.host + location.pathname}#graph?invite=$token")).getOrElse(span()),
//          buttonClick(
//            "regenerate invite link",
//            Client.api.recreateGroupInviteToken(group.id).call().foreach {
//              case Some(token) => createdGroupInvites() = invites + (group -> token)
//              case None        =>
//            }
//          )
//        ).render
//      }
//    )

//  val registerMask = div(Styles.flex, div(userField, br(), passwordField), registerButton)

//  def groupProfile(groups: Seq[Group])(implicit ctx: Ctx.Owner) = div(groups.map(groupInvite): _*)

//  def topBarUserStatus(state: GlobalState)(implicit ctx: Ctx.Owner) = Rx {
//    (state.currentUser() match {
//      case Some(user) if !user.isImplicit => span(user.name, logoutButton)
//      case _                              => registerMask(loginButton)
//    }).render
//  }
//}
