package wust.webApp

import org.scalajs.dom.experimental.permissions.PermissionState
import outwatch.AsVDomModifier
import outwatch.dom._
import outwatch.dom.dsl._
import org.scalajs.dom.{Event, window}
import org.scalajs.dom

import scala.scalajs.js
import rx._
import wust.sdk.{BaseColors, NodeColor}
import wust.webApp.views._
import wust.api.AuthUser
import fontAwesome._
import fontAwesome.freeSolid._
import fontAwesome.freeRegular
import wust.css.Styles
import wust.webApp.outwatchHelpers._
import wust.graph._
import wust.ids._
import wust.webApp.views.{LoginView, PageStyle, View, ViewList, ViewConfig}
import wust.webApp.views.Elements._
import wust.util.RichBoolean
import wust.sdk.{ChangesHistory, NodeColor}
import wust.webApp.views.graphview.GraphView

object Topbar {
  def apply(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = div(
    paddingRight := "5px",
    height := "35px",
    backgroundColor <-- state.pageStyle.map(_.sidebarBgColor),
    color := "white",
    transition := "background-color 0.5s", // fades on page change
    cls := "topbar",
    Styles.flex,
    flexDirection.row,
    justifyContent.spaceBetween,
    alignItems.center,

    header(state).apply(marginRight := "10px"),
    appUpdatePrompt(state).apply(marginRight := "10px"),
    beforeInstallPrompt().apply(marginRight := "10px"),
    //    undoRedo(state)(ctx)(marginRight.auto),
    Rx { (state.page().parentIds.nonEmpty).ifTrue[VDomModifier](viewSwitcher(state).apply(marginLeft.auto, marginRight.auto)) },
    FeedbackForm(state)(ctx)(marginLeft.auto),
    Rx { (state.screenSize() != ScreenSize.Small).ifTrue[VDomModifier](authentication(state)) }
  )

  def banner(state: GlobalState)(implicit ctx: Ctx.Owner) = div(
    padding := "5px 5px",
    fontSize := "14px",
    fontWeight.bold,
    "Woost",
    color := "white",
    textDecoration := "none",
    onClick(ViewList.defaultViewConfig) --> state.viewConfig,
    onClick --> sideEffect {
      Analytics.sendEvent("logo", "clicked")
    },
    cursor.pointer
  )

  val betaSign = div(
    "beta",
    backgroundColor := "#F2711C",
    color := "white",
    borderRadius := "3px",
    padding := "0px 5px",
    fontWeight.bold,
    style("transform") := "rotate(-7deg)",

    marginRight := "5px"
  )

  def header(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    div(
      Styles.flex,
      alignItems.center,

      hamburger(state),
      Rx {
        (state.screenSize() != ScreenSize.Small).ifTrueSeq[VDomModifier](Seq(
          banner(state),
          betaSign,
        ))
      },
      syncStatus(state)(ctx)(fontSize := "12px"),
    )
  }

  def hamburger(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    import state.sidebarOpen
    div(
      padding := "10px",
      fontSize := "20px",
      width := "40px",
      textAlign.center,
      faBars,
      cursor.pointer,
      // TODO: stoppropagation is needed because of https://github.com/OutWatch/outwatch/pull/193
      onClick --> sideEffect { ev =>
        Analytics.sendEvent("hamburger", if(sidebarOpen.now) "close" else "open")
        sidebarOpen() = !sidebarOpen.now;
        ev.stopPropagation()
      }
    )
  }

  def syncStatus(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    val syncingIcon = fontawesome.layered(
      fontawesome.icon(freeSolid.faCircle, new Params {
        styles = scalajs.js.Dictionary[String]("color" -> "#4EBA4C")
      }),
      fontawesome.icon(
        freeSolid.faSync,
        new Params {
          transform = new Transform {size = 10.0 }
          classes = scalajs.js.Array("fa-spin")
          styles = scalajs.js.Dictionary[String]("color" -> "white")
        }
      ))

    val syncedIcon = fontawesome.layered(
      fontawesome.icon(freeSolid.faCircle, new Params {
        styles = scalajs.js.Dictionary[String]("color" -> "#4EBA4C")
      }),
      fontawesome.icon(freeSolid.faCheck, new Params {
        transform = new Transform {size = 10.0 }
        styles = scalajs.js.Dictionary[String]("color" -> "white")
      }))

    val offlineIcon = fontawesome.layered(
      fontawesome.icon(freeSolid.faCircle, new Params {
        styles = scalajs.js.Dictionary[String]("color" -> "tomato")
      }),
      fontawesome.icon(freeSolid.faBolt, new Params {
        transform = new Transform {size = 10.0 }
        styles = scalajs.js.Dictionary[String]("color" -> "white")
      }))

    val syncStatusIcon = Rx {
      (state.isOnline(), state.isSynced()) match {
        case (true, true)  => span(syncedIcon, title := "Everything is up to date")
        case (true, false) => span(syncingIcon, title := "Syncing changes...")
        case (false, _)    => span(offlineIcon, color := "tomato", title := "Disconnected")
      }
    }

    div(
      syncStatusIcon
    )
  }

  def appUpdatePrompt(state: GlobalState)(implicit ctx: Ctx.Owner) =
    div(state.appUpdateIsAvailable.map { _ =>
      button(cls := "tiny ui primary button", "update", onClick --> sideEffect {
        window.location.reload(flag = false)
      })
    })

  def beforeInstallPrompt()(implicit ctx: Ctx.Owner) = {
    val prompt: Rx[Option[dom.Event]] = Rx.create(Option.empty[dom.Event]) {
      observer: Var[Option[dom.Event]] =>
        dom.window.addEventListener(
          "beforeinstallprompt", { e: dom.Event =>
            e.preventDefault(); // Prevents immediate prompt display
            dom.console.log("BEFOREINSTALLPROMPT: ", e)
            observer() = Some(e)
          }
        );
    }

    div(
      Rx {
        prompt().map { e =>
          button(cls := "tiny ui primary button", "install", onClick --> sideEffect {
            e.asInstanceOf[js.Dynamic].prompt();
            ()
          })
        }
      }
    )
  }

  def undoRedo(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    val historySink = state.eventProcessor.history.action
    div(
      state.eventProcessor.changesHistory
        .startWith(Seq(ChangesHistory.empty))
        .combineLatestMap(state.view.toObservable) { (history, view) =>
          div(
            if(view.isContent)
              VDomModifier(
                Styles.flex,
                style("justify-content") := "space-evenly", //TODO dom-types
                button(
                  cls := "ui button",
                  faUndo,
                  padding := "5px 10px",
                  marginRight := "2px",
                  fontSize.small,
                  title := "Undo last change",
                  onClick(ChangesHistory.Undo) --> historySink,
                  disabled := !history.canUndo
                ),
                button(
                  cls := "ui button",
                  faRedo,
                  padding := "5px 10px",
                  fontSize.small,
                  title := "Redo last undo change",
                  onClick(ChangesHistory.Redo) --> historySink,
                  disabled := !history.canRedo
                )
              )
            else Seq.empty[VDomModifier]
          )
        }
    )
  }

  def viewSwitcher(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    import scala.reflect.{ClassTag, classTag}
    def MkLabel[T: ClassTag](theId: String, view: View, targetView: View,
      pS: PageStyle, icon: IconDefinition) = {
      label(`for` := theId, icon, onClick(targetView) --> state.view, cursor.pointer,
        view match {
          case v if classTag[T].runtimeClass.isInstance(v) =>
            Seq(
              color := "#111111",
              //borderTop(2 px, solid, pS.bgLightColor)
              backgroundColor := pS.bgColor)
          case _                                           => Seq[VNode]()
        }
      )
    }

    def MkInput[T: ClassTag](theId: String, view: View, pS: PageStyle) = {
      input(display.none, id := theId, `type` := "radio", name := "viewbar",
        view match {
          case v if classTag[T].runtimeClass.isInstance(v) => Seq(checked := true)
          case _                                           => Seq[VNode]()
        },
        onInput --> sideEffect {
          Analytics.sendEvent("viewswitcher", "switch", view.viewKey)
        }
      )
    }

    div(
      cls := "viewbar",
      Styles.flex,
      flexDirection.row,
      justifyContent.spaceBetween,
      alignItems.center,

      Rx {
        Seq(MkInput[ChatView.type]("v1", state.view(), state.pageStyle()),
          MkLabel[ChatView.type]("v1", state.view(), ChatView, state.pageStyle(), freeRegular.faComments),
          MkInput[KanbanView.type]("v2", state.view(), state.pageStyle()),
          MkLabel[KanbanView.type]("v2", state.view(), KanbanView, state.pageStyle(), freeSolid.faColumns),
          MkInput[GraphView.type]("v3", state.view(), state.pageStyle()),
          MkLabel[GraphView.type]("v3", state.view(), GraphView, state.pageStyle(), freeBrands.faCloudsmith))
      }
    )

  }

  def authentication(state: GlobalState)(implicit ctx: Ctx.Owner): VDomModifier =
    state.user.map {
      case user: AuthUser.Assumed  => login(state)
      case user: AuthUser.Implicit => login(state)
      case user: AuthUser.Real     => div(
        Styles.flex,
        alignItems.center,
        Avatar.user(user.id)(
          height := "20px", cls := "avatar",
          onClick(UserSettingsView: View) --> state.view,
          onClick --> sideEffect{
            Analytics.sendEvent("topbar", "avatar")
          },
          cursor.pointer
        ),
        span(user.name, padding := "0 5px"),
        logout(state))
    }

  def login(state: GlobalState)(implicit ctx: Ctx.Owner) =
    div(
      span(
        onClick(state.viewConfig.now.overlayView(SignupView)) --> state.viewConfig,
        onClick --> sideEffect {
          Analytics.sendEvent("topbar", "signup")
        },
        "Signup",
        color := "white",
        cursor.pointer
      ),
      " or ",
      span(
        onClick(state.viewConfig.now.overlayView(LoginView)) --> state.viewConfig,
        onClick --> sideEffect {
          Analytics.sendEvent("topbar", "login")
        },
        "Login",
        color := "white",
        cursor.pointer
      )
    )

  def logout(state: GlobalState) =
    button(
      cls := "tiny compact ui inverted grey button",
      "Logout",
      onClick --> sideEffect {
        Client.auth.logout().foreach { _ =>
          state.viewConfig() = state.viewConfig.now.copy(page = Page.empty).overlayView(LoginView)
        }
        Analytics.sendEvent("topbar", "logout")
      }
    )

}
