package wust.webApp.views

import googleAnalytics.Analytics
import monix.reactive.Observer
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.api.{AuthResult, AuthUser}
import wust.webApp._
import wust.webApp.outwatchHelpers._
import wust.webApp.state.{GlobalState, PageChange, View}
import wust.webApp.views.Elements._
import cats.effect.IO
import org.scalajs.dom
import wust.graph.Page
import wust.util._
import Components._

import scala.concurrent.Future
import scala.scalajs.js
import scala.util.{Failure, Success}


// an html view for the authentication. That is login and signup.
object AuthView {
  private case class UserValue(username: String = "", email: String = "", password: String = "")
  private val userValue = Var(UserValue())

  def apply(state: GlobalState)(
      header: String,
      submitText: String,
      needUserName: Boolean,
      submitAction: UserValue => Future[Option[String]],
      alternativeHeader: String,
      alternativeView: View,
      alternativeText: String,
      autoCompletePassword: String
  )(implicit ctx: Ctx.Owner): VNode = {
    val errorMessageHandler = Handler.unsafe[String]
    var element: dom.html.Form = null
    def actionSink() = {
      if (element.asInstanceOf[js.Dynamic].reportValidity().asInstanceOf[Boolean]) submitAction(userValue.now).onComplete {
        case Success(None)        =>
          userValue() = UserValue()
          state.viewConfig() = state.viewConfig.now.redirect
        case Success(Some(vnode)) =>
          errorMessageHandler.onNext(vnode)
        case Failure(t)           =>
          errorMessageHandler.onNext(s"Unexpected error: $t")
      }
    }

    div(
      onSubmit.foreach(_.preventDefault()),
      padding := "10px",
      maxWidth := "400px",
      maxHeight := "400px",
      margin := "auto",
      form(
        onDomMount foreach { e => element = e.asInstanceOf[dom.html.Form] },
        onSubmit.preventDefault --> Observer.empty, // prevent reloading the page on form submit

        h2(header),
        needUserName.ifTrue[VDomModifier](div(
          cls := "ui fluid input",
          keyed,
          input(
            placeholder := "Username",
            value <-- userValue.map(_.username),
            tpe := "text",
            required := true,
            attr("autocomplete") := "username",
            display.block,
            margin := "auto",
            onInput.value foreach { str => userValue.update(_.copy(username = str)) },
            onDomMount.asHtml --> inNextAnimationFrame { e => if(userValue.now.username.isEmpty) e.focus() }
          )
        )),
        div(
          cls := "ui fluid input",
          keyed,
          input(
            placeholder := "Email",
            value <-- userValue.map(_.email),
            tpe := "email",
            required := true,
            display.block,
            margin := "auto",
            onInput.value foreach { str => userValue.update(_.copy(email = str)) },
            onDomMount.asHtml --> inNextAnimationFrame { e => if(!needUserName || userValue.now.username.nonEmpty) e.focus() }
          )
        ),
        div(
          cls := "ui fluid input",
          keyed,
          input(
            placeholder := "Password",
            value <-- userValue.map(_.password),
            tpe := "password",
            required := true,
            attr("autocomplete") := autoCompletePassword,
            display.block,
            margin := "auto",
            onInput.value foreach { str => userValue.update(_.copy(password = str)) },
            onEnter foreach actionSink(),
            onDomMount.asHtml --> inNextAnimationFrame { e => if((!needUserName || userValue.now.username.nonEmpty) && userValue.now.email.nonEmpty) e.focus() }
          )
        ),
        discardContentMessage(state),
        button(
          cls := "ui fluid primary button",
          submitText,
          display.block,
          margin := "auto",
          marginTop := "5px",
          onClick foreach actionSink()
        ),
        errorMessageHandler.map { errorMessage =>
          div(
            cls := "ui negative message",
            div(cls := "header", s"$submitText failed"),
            p(errorMessage)
          )
        },
        div(cls := "ui divider"),
        h3(alternativeHeader, textAlign := "center"),
        state.viewConfig.map { cfg =>
          div(
            onClick(cfg.copy(view = Some(alternativeView))) --> state.viewConfig,
            cls := "ui fluid button",
            alternativeText,
            display.block,
            margin := "auto",
            cursor.pointer
          )
        },
        h4("Having Problems with Login or Signup?", textAlign := "center", marginTop := "40px"),
        div("Please contact ", woostTeamEmailLink, textAlign := "center"),
        marginBottom := "20px",
      )
    )
  }

  def discardContentMessage(state:GlobalState)(implicit ctx:Ctx.Owner) = {
    Rx {
      state.user() match {
        // User.Implicit user means, that the user already created content, else it would be User.Assumed.
        case AuthUser.Implicit(_, name, _) => UI.message(
          msgType = "warning",
          header = Some("Discard created content?"),
          content = Some(VDomModifier(
            span("You already created content as an unregistered user. If you login or register, the content will be moved into your account. If you don't want to keep it you can "),
            a(
              href := "#",
              color := "tomato",
              marginLeft := "auto",
              "discard all content now",
              onClick.preventDefault foreach {
                if(dom.window.confirm("This will delete all your content, you created as an unregistered user. Do you want to continue?")) {
                  Client.auth.logout()
                }
                ()
              }
              ),
            "."
          ))
      )
        case _ => VDomModifier.empty

      }
    },
  }

  def login(state: GlobalState)(implicit ctx: Ctx.Owner) = {
    hotjar.pageView("/login")
    apply(state)(
      header = "Login",
      submitText = "Login",
      needUserName = false,
      submitAction = {userValue =>
        hotjar.pageView("/login/submit")
        Client.auth.login(userValue.email, userValue.password).map {
          case AuthResult.BadPassword => Some("Wrong Password")
          case AuthResult.BadEmail    => Some("Email address does not exist")
          case AuthResult.Success     =>
            Analytics.sendEvent("auth", "login")
            None
        }
      },
      alternativeHeader = "New to Woost?",
      alternativeView = View.Signup,
      alternativeText = "Create an account",
      autoCompletePassword = "current-password"
    )
  }

  def signup(state: GlobalState)(implicit ctx: Ctx.Owner) = {
    hotjar.pageView("/signup")
    apply(state)(
      header = "Create an account",
      submitText = "Signup",
      needUserName = true,
      submitAction = {userValue =>
        hotjar.pageView("/signup/submit")
        Client.auth.register(name = userValue.username, email = userValue.email, password = userValue.password).map {
          case AuthResult.BadPassword => Some("Insufficient password")
          case AuthResult.BadEmail    => Some("Email address already taken")
          case AuthResult.Success     => 
            Analytics.sendEvent("auth", "signup")
            None
        }
      },
      alternativeHeader = "Already have an account?",
      alternativeView = View.Login,
      alternativeText = "Login",
      autoCompletePassword = "new-password"
    )
  }
}
