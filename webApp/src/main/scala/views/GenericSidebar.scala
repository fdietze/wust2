package wust.webApp.views

import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.webApp.state.{GlobalState, ScreenSize}
import wust.webApp.outwatchHelpers._
import Elements._
import wust.webApp.{BrowserDetect, Ownable}

object GenericSidebar {

  case class Config(mainModifier: VDomModifier = VDomModifier.empty, openModifier: VDomModifier = VDomModifier.empty, overlayOpenModifier: VDomModifier = VDomModifier.empty, expandedOpenModifier: VDomModifier = VDomModifier.empty, closedModifier: Option[VDomModifier] = None)

  def left(sidebarOpen: Var[Boolean], config: Ownable[Config]): VNode = apply(isRight = false, sidebarOpen = sidebarOpen, config = config)
  def right(sidebarOpen: Var[Boolean], config: Ownable[Config]): VNode = apply(isRight = true, sidebarOpen = sidebarOpen, config = config)

  private def apply(isRight: Boolean, sidebarOpen: Var[Boolean], config: Ownable[Config]): VNode = {
    def openSwipe = (if (isRight) onSwipeLeft(true) else onSwipeRight(true)) --> sidebarOpen
    def closeSwipe = (if (isRight) onSwipeRight(false) else onSwipeLeft(false)) --> sidebarOpen
    def directionOverlayModifier = if (isRight) cls := "overlay-right-sidebar" else cls := "overlay-left-sidebar"
    def directionExpandedModifier = if (isRight) cls := "expanded-right-sidebar" else cls := "expanded-left-sidebar"
    def directionSidebarModifier = if (isRight) cls := "right-sidebar" else cls := "left-sidebar"

    def closedSidebar(config: Config) = VDomModifier(
      cls := "sidebar-close",
      config.closedModifier.map { closedModifier =>
        VDomModifier(
          cls := "sidebar",
          directionSidebarModifier,
          openSwipe,
          closedModifier
        )
      }
    )

    def openSidebar(config: Config) = VDomModifier(
      cls := "sidebar sidebar-open",
      directionSidebarModifier,
      config.openModifier
    )

    def overlayOpenSidebar(config: Config) = VDomModifier(
      cls := "overlay-sidebar",
      directionOverlayModifier,
      onClick.onlyOwnEvents(false) --> sidebarOpen,
      onSwipeLeft(false) --> sidebarOpen,
      closeSwipe,

      div(
        openSidebar(config),
        config.overlayOpenModifier,
      )
    )

    def sidebarWithOverlay(config: Config)(implicit ctx: Ctx.Owner): VDomModifier = VDomModifier(
      closedSidebar(config),
      Rx {
        sidebarOpen() match {
          case true  => div(overlayOpenSidebar(config))
          case false => VDomModifier.empty
        }
      }
    )

    def sidebarWithExpand(config: Config)(implicit ctx: Ctx.Owner): VDomModifier = VDomModifier(
      cls := "expanded-sidebar",
      directionExpandedModifier,
      closeSwipe,
      div(
        Rx {
          sidebarOpen() match {
            case true  => VDomModifier(openSidebar(config), config.expandedOpenModifier)
            case false => VDomModifier(closedSidebar(config))
          }
        }
      )
    )

    // TODO make static again. currently not because outer subscriptions on the callsite do not like us not rerendering (looking at you: GraphChangesAutomationUI). MainView is fine with it.
    div(config.flatMap(config => Ownable { implicit ctx =>
      VDomModifier(
        if (BrowserDetect.isMobile) sidebarWithOverlay(config)
        else sidebarWithExpand(config),
        config.mainModifier
      )
    }))
  }

}
