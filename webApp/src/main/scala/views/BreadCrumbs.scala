package wust.webApp.views

import googleAnalytics.Analytics
import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.dom.dsl.styles.extra._
import rx._
import wust.graph.Node
import wust.ids._
import wust.util._
import wust.webApp.outwatchHelpers._
import wust.webApp.state.GlobalState
import wust.webApp.views.Components._

object BreadCrumbs {

  /** options */
  private val showOwn = false

  private def intersperse[T](list: List[T], co: T): List[T] = list match {
    case one :: two :: rest => one :: co :: intersperse(two :: rest, co)
    case one :: Nil         => one :: co :: Nil
    case Nil                => Nil
  }

  private def cycleIndicator(rotate : Boolean) = {
    //"\u21ba"
    img(
      cls := "cycle-indicator",
      rotate.ifTrue[VDomModifier](transform := "rotate(180deg)"),
      src:="halfCircle.svg",
    )
  }

  def apply(state: GlobalState): VNode = {
    div.staticRx(keyValue) { implicit ctx =>
      VDomModifier(
        cls := "breadcrumbs",
        Rx {
          val page = state.page()
          val user = state.user()
          val graph = state.graph()
          page.parentId.map { (parentId: NodeId) =>
            val parentDepths = graph.parentDepths(parentId)
            val distanceToNodes = parentDepths.toList.sortBy { case (depth, _) => -depth }
            val elements = distanceToNodes.flatMap { case (distance, gIdToNodeIds) =>
              // when distance is 0, we are either showing ourselves (i.e. id) or
              // a cycle that contains ourselves. The latter case we want to draw, the prior not.
              if(!showOwn && distance == 0 && gIdToNodeIds.size == 1 && gIdToNodeIds.head._2.size == 1)
                None
              else {
                val sortedByGroupId = gIdToNodeIds.toList.sortBy(_._1)
                Some(span(
                  // "D:" + distance + " ",
                  sortedByGroupId.map { case (gId, nodes) =>
                    // sort nodes within a group by their length towards the root node
                    // this ensures that e.g. „Channels“ comes first
                    val sortedNodes = nodes.sortBy(graph.parentDepth(_))
                    span(
                      cls := "breadcrumb",
                      if(gId != -1) cycleIndicator(false) else "",
                      sortedNodes.map { (n: NodeId) =>
                        graph.nodesByIdGet(n) match {
                          case Some(node) if (showOwn || n != parentId) => nodeTag(state, node)(cursor.pointer)
                          case _                                        => VDomModifier.empty
                        }
                      },
                      if(gId != -1) cycleIndicator(true) else "",
                    )
                  }.toSeq
                ))
              }
            }
            div(intersperse(elements, span("/", cls := "divider")))
          }
        },
        registerDraggableContainer(state),
        onClick foreach { Analytics.sendEvent("breadcrumbs", "click") },
      )
    }
  }
}
