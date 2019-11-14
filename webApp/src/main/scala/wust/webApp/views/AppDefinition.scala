package wust.webApp.views

import outwatch.reactive.SinkObserver
import outwatch.dom.VDomModifier
import wust.webApp.state.PresentationMode
import rx._
import wust.ids.View.ListWithChat

trait AppDefinition {
  def header(state: SinkObserver[AppDefinition.State])(implicit ctx: Ctx.Owner): VDomModifier = VDomModifier.empty

  def landing(state: SinkObserver[AppDefinition.State])(implicit ctx: Ctx.Owner): VDomModifier
  def app(state: SinkObserver[AppDefinition.State])(implicit ctx: Ctx.Owner): VDomModifier
}
object AppDefinition {
  sealed trait State
  object State {
    case object Landing extends State
    case object App extends State
  }

  def fromMode(mode: PresentationMode): Option[AppDefinition] = Some(mode) collect {
    case PresentationMode.Doodle => DoodleView
    case PresentationMode.ThreadTracker => ThreadTrackerView
  }
}