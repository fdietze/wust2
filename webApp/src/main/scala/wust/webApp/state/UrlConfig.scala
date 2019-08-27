package wust.webApp.state

import acyclic.file
import wust.api.Authentication
import wust.graph.Page
import wust.ids.{View, NodeId}

final case class UrlConfig(view: Option[View], pageChange: PageChange, redirectTo: Option[View], shareOptions: Option[ShareOptions], invitation: Option[Authentication.Token], focusId: Option[NodeId]) {
  private val canRedirectTo: View => Boolean = {
    case View.Login | View.Signup => false
    case _ => true
  }

  def focusWithRedirect(newView: View): UrlConfig = copy(view = Some(newView), redirectTo = view.filter(canRedirectTo) orElse redirectTo)

  def redirect: UrlConfig = copy(view = redirectTo, redirectTo = None)

  @inline def focus(view: Option[View]): UrlConfig = copy(view = view, redirectTo = None)
  @inline def focus(view: View): UrlConfig = copy(view = Some(view), redirectTo = None)
  @inline def focus(page: Page, view: View): UrlConfig = focus(page, Some(view))
  @inline def focus(page: Page, view: View, needsGet: Boolean): UrlConfig = focus(page, Some(view), needsGet)
  def focus(page: Page, view: Option[View] = None, needsGet: Boolean = true): UrlConfig = copy(pageChange = PageChange(page, needsGet = needsGet), view = view, redirectTo = None)
}

object UrlConfig {
  val default = UrlConfig(view = None, pageChange = PageChange(Page.empty), redirectTo = None, shareOptions = None, invitation = None, focusId = None)
}
