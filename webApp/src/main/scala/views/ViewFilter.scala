package wust.webApp.views

import fomanticui.SidebarOptions
import googleAnalytics.Analytics
import jquery.JQuerySelection
import monix.reactive.Observable
import monix.reactive.subjects.BehaviorSubject
import org.scalajs.dom
import outwatch.dom._
import outwatch.dom.dsl._
import rx.Var.Assignment
import rx.{Ctx, Rx, Var}
import supertagged.TaggedType
import wust.css.ZIndex
import wust.graph.{Edge, Graph}
import wust.ids.{NodeId, NodeRole, UserId}
import wust.util.algorithm
import wust.util.macros.SubObjects
import wust.webApp.Icons
import wust.webApp.state.GlobalState
import wust.webApp.outwatchHelpers._
import wust.webApp.views.GraphOperation.GraphTransformation

object ViewFilter {

  private def allTransformations(state: GlobalState)(implicit ctx: Ctx.Owner): List[ViewGraphTransformation] = List(
    Deleted.InDeletedGracePeriodParents(state),
    Deleted.OnlyDeletedParents(state),
    Deleted.NoDeletedParents(state),
    Deleted.NoDeletedButGracedParents(state),
    Assignments.OnlyAssignedTo(state),
    Assignments.OnlyNotAssigned(state),
//    Identity(state),
  )

//  def renderSidebar(state: GlobalState, sidebarContext: ValueObservable[JQuerySelection], sidebarOpenHandler: ValueObservable[String])(implicit ctx: Ctx.Owner): VNode = {
//
//    val filterItems: List[VDomModifier] = allTransformations(state).map(_.render)
//
//    div(
//      cls := "ui right vertical inverted labeled icon menu sidebar visible",
//      //      zIndex := ZIndex.overlay,
//      filterItems,
//      onDomMount.asJquery.transform(_.combineLatest(sidebarContext.observable)).foreach({ case (elem, side) =>
//        elem
//          .sidebar(new SidebarOptions {
//            transition = "overlay"
//            context = side
//          })
//        //          .sidebar("setting", "transition", "overlay")
//      }: Function[(JQuerySelection, JQuerySelection), Unit])
//    )
//  }

  def renderMenu(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {

    val filterItems: List[VDomModifier] = allTransformations(state).map(_.render)
    val filterColor = state.isFilterActive.map(active => if(active) VDomModifier( color := "green" ) else VDomModifier.empty)

    div(
      cls := "item",
      Elements.icon(Icons.filter)(marginRight := "5px"),
      filterColor,
      span(cls := "text", "Filter", cursor.default),
      div(
        cls := "menu",
        filterItems,
        // This does not work because
        div(
          cls := "item",
          Elements.icon(Icons.noFilter)(marginRight := "5px"),
          span(cls := "text", "Reset ALL filters", cursor.pointer),
          onClick(Seq.empty[UserViewGraphTransformation]) --> state.graphTransformations,
          onClick foreach { Analytics.sendEvent("filter", "reset") },
        )
      ),
    )
  }

  case class Identity(state: GlobalState) extends ViewGraphTransformation {
    val icon = Icons.noFilter
    val description = "Reset ALL filters"
    val transform = GraphOperation.Identity
    val domId = transform.tpe
  }

  object Deleted {

    case class InDeletedGracePeriodParents(state: GlobalState) extends ViewGraphTransformation {
      val icon  = Icons.delete
      val description = "Show soon auto-deleted items"
      val transform = GraphOperation.InDeletedGracePeriodParents
      val domId = transform.tpe
    }

    case class OnlyDeletedParents(state: GlobalState) extends ViewGraphTransformation {
      val icon = Icons.delete
      val description = "Show only deleted items"
      val transform = GraphOperation.OnlyDeletedParents
      val domId = transform.tpe
    }

    case class NoDeletedParents(state: GlobalState) extends ViewGraphTransformation {
      val icon = Icons.undelete
      val description = "Do not show deleted items"
      val transform = GraphOperation.NoDeletedParents
      val domId = transform.tpe
    }

    case class NoDeletedButGracedParents(state: GlobalState) extends ViewGraphTransformation {
      val icon = Icons.undelete
      val description = "Do not show older deleted items"
      val transform = GraphOperation.NoDeletedButGracedParents
      val domId = transform.tpe
    }


  }

  object Assignments {

    case class OnlyAssignedTo(state: GlobalState) extends ViewGraphTransformation {
      val icon = Icons.task
      val description = s"Show items assigned to: Me"
      val transform = GraphOperation.OnlyAssignedTo
      val domId = transform.tpe
    }

    case class OnlyNotAssigned(state: GlobalState) extends ViewGraphTransformation {
      val icon = Icons.task
      val description = "Show items that are not assigned"
      val transform = GraphOperation.OnlyNotAssigned
      val domId = transform.tpe
    }

  }

}


sealed trait ViewGraphTransformation {
  val state: GlobalState
  val transform: UserViewGraphTransformation
  val icon: VDomModifier
  val description: String
  val domId: String

  def render(implicit ctx: Ctx.Owner) = {

    val activeFilter = (doActivate: Boolean) =>  if(doActivate) {
      state.graphTransformations.map(_ :+ transform)
    } else {
      state.graphTransformations.map(_.filter(_ != transform))
    }

    div(
      cls := "item",
      div(
        cls := "ui toggle checkbox",
        input(tpe := "checkbox",
          id := domId,
          onChange.checked.map(v => activeFilter(v).now) --> state.graphTransformations,
          onChange.checked foreach { enabled => if(enabled) Analytics.sendEvent("filter", domId) },
          checked <-- state.graphTransformations.map(_.contains(transform))
        ),
        label(description, `for` := domId),
      ),
      Elements.icon(icon)(marginLeft := "5px"),
    )

  }
}

sealed trait UserViewGraphTransformation { def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation }
object GraphOperation {
  object Type extends TaggedType[String]
  type Type = Type.Type
  type GraphTransformation = Graph => Graph

  abstract class Named(implicit name: sourcecode.Name) {
    val tpe = Type(name.value)
  }

  def all: List[UserViewGraphTransformation] = macro SubObjects.list[UserViewGraphTransformation]

  case object InDeletedGracePeriodParents extends Named with UserViewGraphTransformation {
    def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation = { graph: Graph =>
      pageId.fold(graph) { pid =>
        val newEdges = graph.edges.filter {
          case e: Edge.Parent if e.targetId == pid => graph.isInDeletedGracePeriod(e.sourceId, pid)
          case _              => true
        }
        graph.copy(edges = newEdges)
      }
    }
  }

  case object OnlyDeletedParents extends Named with UserViewGraphTransformation {
    def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation = { graph: Graph =>
      pageId.fold(graph) { pid =>
        val pageIdx = graph.idToIdx(pid)
        val newEdges = graph.edges.filter {
          case e: Edge.Parent if e.targetId == pid => graph.isDeletedNow(e.sourceId, pid) || graph.isInDeletedGracePeriod(e.sourceId, pid)
          case _              => true
        }
        graph.copy(edges = newEdges)
      }
    }
  }

  case object NoDeletedParents extends Named with UserViewGraphTransformation {
    def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation = { graph: Graph =>
      pageId.fold(graph) { pid =>
        val newEdges = graph.edges.filter {
          case e: Edge.Parent if e.targetId == pid => !graph.isDeletedNow(e.sourceId, pid)
          case _              => true
        }
        graph.copy(edges = newEdges)
      }
    }
  }

  case object NoDeletedButGracedParents extends Named with UserViewGraphTransformation {
    def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation = { graph: Graph =>
      pageId.fold(graph) { pid =>
        val newEdges = graph.edges.filter {
          case e: Edge.Parent if e.targetId == pid => !graph.isDeletedNow(e.sourceId, pid) || graph.isInDeletedGracePeriod(e.sourceId, pid)
          case _              => true
        }
        graph.copy(edges = newEdges)
      }
    }
  }

  case object OnlyAssignedTo extends Named with UserViewGraphTransformation {
    def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation = { graph: Graph =>
      val assignedNodeIds = graph.edges.collect {
        case e: Edge.Assigned if e.sourceId == userId => e.targetId
      }
      val newEdges = graph.edges.filter {
        case e: Edge.Parent if graph.nodesById(e.sourceId).role == NodeRole.Task => assignedNodeIds.contains(e.sourceId)
        case _              => true
      }
      graph.copy(edges = newEdges)
    }
  }

  case object OnlyNotAssigned extends Named with UserViewGraphTransformation {
    def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation = { graph: Graph =>
      val assignedNodeIds = graph.edges.collect {
        case e: Edge.Assigned => e.targetId
      }
      val newEdges = graph.edges.filterNot {
        case e: Edge.Parent => assignedNodeIds.contains(e.sourceId)
        case _              => false
      }
      graph.copy(edges = newEdges)
    }
  }

  case object Identity extends Named with UserViewGraphTransformation {
    def transformWithViewData(pageId: => Option[NodeId], userId: => UserId): GraphTransformation = identity[Graph]
  }
}
