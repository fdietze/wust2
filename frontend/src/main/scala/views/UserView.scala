package frontend.views

import org.scalajs.dom._
import rx._
import scalatags.rx.all._
import scalatags.JsDom.all._
import frontend.{ Client, GlobalState }
import api.User
import boopickle.Default._
import autowire._
import scalajs.concurrent.JSExecutionContext.Implicits.queue

object UserView {
  val inputText = input(`type` := "text")
  val inputPassword = input(`type` := "password")
  def buttonClick(name: String, handler: => Any) = button(name, onclick := handler _)

  val userField = inputText(placeholder := "user name").render
  val passwordField = inputPassword(placeholder := "password").render
  val registerButton = buttonClick("register", Client.auth.register(userField.value, passwordField.value))
  def loginButton(currentUser: WriteVar[Option[User]]) = buttonClick("login", Client.auth.login(userField.value, passwordField.value))
  def logoutButton(currentUser: WriteVar[Option[User]]) = buttonClick("logout", Client.auth.logout())

  def loginMask(currentUser: WriteVar[Option[User]]) = div(userField, passwordField, loginButton(currentUser), registerButton)
  def userProfile(currentUser: WriteVar[Option[User]], user: User) = div(user.toString, logoutButton(currentUser))

  def apply(state: GlobalState)(implicit ctx: Ctx.Owner) = {
    state.currentUser.rx.map {
      case Some(user) => userProfile(state.currentUser, user).render
      case None       => loginMask(state.currentUser).render
    }
  }
}
