package wust.api.serialize

import wust.graph._
import wust.api._
import boopickle.Default._
import wust.graph.EdgeComponents._

object Boopickle extends wust.ids.serialize.Boopickle {

  implicit val postMetaPickler: Pickler[NodeMeta] = generatePickler[NodeMeta]
  implicit val connectionPickler: Pickler[Edge] = generatePickler[Edge]
  implicit val postPickler: Pickler[Node] = generatePickler[Node]
  implicit val userAssumedPickler: Pickler[AuthUser.Assumed] = generatePickler[AuthUser.Assumed]
  implicit val userPersistedPickler: Pickler[AuthUser.Persisted] =
    generatePickler[AuthUser.Persisted]
  implicit val userPickler: Pickler[AuthUser] = generatePickler[AuthUser]
  implicit val graphPickler: Pickler[Graph] = generatePickler[Graph]
  implicit val graphChangesPickler: Pickler[GraphChanges] = generatePickler[GraphChanges]

  implicit val authenticationPickler: Pickler[Authentication] = generatePickler[Authentication]

  implicit val apiEventScopePickler: Pickler[ApiEvent.Scope] = generatePickler[ApiEvent.Scope]
  implicit val apiEventPickler: Pickler[ApiEvent] = generatePickler[ApiEvent]

  implicit val apiErrorPickler: Pickler[ApiError] = generatePickler[ApiError]

  implicit val pluginUserAuthenticationPickler: Pickler[PluginUserAuthentication] = generatePickler[PluginUserAuthentication]
}
