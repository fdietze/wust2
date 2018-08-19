package wust.webApp.views

import googleAnalytics.Analytics
import outwatch.dom._
import outwatch.dom.dsl._
import rx.Ctx
import wust.css.Styles
import wust.webApp.GlobalState
import wust.webApp.MainViewParts.newChannelButton
import wust.webApp.outwatchHelpers._

object NewChannelView {
  def apply(state: GlobalState)(implicit ctx: Ctx.Owner) = {
    div(
      Styles.flex,
      justifyContent.spaceAround,
      flexDirection.column,
      alignItems.center,
      newChannelButton(state)(ctx)(padding := "20px", marginBottom := "10%")(
        onClick --> sideEffect { Analytics.sendEvent("view:newchannel", "newchannel") }
      )
    )
  }
}
