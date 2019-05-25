package wust.webApp.views

import wust.sdk.{BaseColors, NodeColor}
import wust.css.Styles
import wust.webApp.dragdrop._
import googleAnalytics.Analytics
import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.dom.dsl.styles.extra._
import rx._
import wust.api.AuthUser
import wust.graph.{Node, Page, Graph}
import wust.ids._
import wust.util._
import wust.webApp.Ownable
import wust.webApp.outwatchHelpers._
import wust.webApp.state.GlobalState
import wust.webApp.views.Components._
import scala.collection.breakOut
import fontAwesome.freeSolid

object BreadCrumbs {

  /** options */
  private val showOwn = true

  private def intersperse[T](list: List[T], co: T): List[T] = list match {
    case one :: two :: rest => one :: co :: intersperse(two :: rest, co)
//    case one :: Nil         => one :: co :: Nil
//    case Nil                => Nil
    case short => short
  }
  private def intersperseWhile[T](list: List[T], co: T, cond: T => Boolean): List[T] = list match {
    case one :: two :: rest if cond(one) => one :: co :: intersperseWhile(two :: rest, co, cond)
    case _ :: two :: rest => intersperseWhile(two :: rest, co, cond)
    //    case one :: Nil         => one :: co :: Nil
    //    case Nil                => Nil
    case short => short
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
    div.thunkStatic(uniqueKey)(Ownable { implicit ctx =>
      modifier(state, None, state.page.map(_.parentId), nid => state.urlConfig.update(_.focus(Page(nid))))
    })
  }
  def apply(state: GlobalState, filterUpTo: Option[NodeId], parentIdRx: Rx[Option[NodeId]], parentIdAction: NodeId => Unit)(implicit ctx: Ctx.Owner): VNode = {
   div.thunkStatic(uniqueKey)(Ownable { implicit ctx =>
      modifier(state, filterUpTo, parentIdRx, parentIdAction)
   })
  }

  def apply(state: GlobalState, graph: Graph, user: AuthUser, filterUpTo: Option[NodeId], parentId: Option[NodeId], parentIdAction: NodeId => Unit, hideIfSingle:Boolean = false)(implicit ctx: Ctx.Owner): VNode = {
    div(
      modifier(state, graph, user, filterUpTo, parentId = parentId, parentIdAction = parentIdAction, hideIfSingle)
    )
  }

  private def modifier(state: GlobalState, graph: Graph, user: AuthUser, filterUpTo: Option[NodeId], parentId: Option[NodeId], parentIdAction: NodeId => Unit, hideIfSingle:Boolean = false)(implicit ctx: Ctx.Owner): VDomModifier = {
    VDomModifier(
      cls := "breadcrumbs",
      parentId.map { (parentId: NodeId) =>
        val parentDepths: Map[Int, Map[Int, Seq[NodeId]]] = graph.parentDepths(parentId)
        val distanceToNodes: Seq[(Int, Map[Int, Seq[NodeId]])] = parentDepths.toList.sortBy { case (depth, _) => -depth }
        def elementNodes = distanceToNodes.flatMap { case (distance, gIdToNodeIds) =>
          // when distance is 0, we are either showing ourselves (i.e. id) or
          // a cycle that contains ourselves. The latter case we want to draw, the prior not.
          if(!showOwn && distance == 0 && gIdToNodeIds.size == 1 && gIdToNodeIds.head._2.size == 1)
            None
          else {
            val sortedByGroupId = gIdToNodeIds.toList.sortBy(_._1)
            Some(
              // "D:" + distance + " ",
              sortedByGroupId.flatMap { case (gId, nodes) =>
                // sort nodes within a group by their length towards the root node
                // this ensures that e.g. „Channels“ comes first
                nodes.sortBy(n => graph.parentDepth(graph.idToIdxOrThrow(n)))
              }
            )
          }
        }.flatten

        val elements: List[VDomModifier] = filterUpTo.fold(elementNodes)(id => elementNodes.dropWhile(_ != id)).map { nid =>
          val onClickFocus = VDomModifier(
            cursor.pointer,
            onClick foreach { e =>
              parentIdAction(nid)
              e.stopPropagation()
            }
          )
          graph.nodesById(nid) match {
            // hiding the stage/tag prevents accidental zooming into stages/tags, which in turn prevents to create inconsistent state.
            // example of unwanted inconsistent state: task is only child of stage/tag, but child of nothing else.
            case Some(node) if (showOwn || nid != parentId) && node.role != NodeRole.Stage && node.role != NodeRole.Tag =>
              (node.role match {
                case NodeRole.Message | NodeRole.Task | NodeRole.Note =>
                  nodeCardAsOneLineText(node)(onClickFocus)
                case _                                => // usually NodeRole.Project
                  nodeTag(state, node, dragOptions = nodeId => drag(DragItem.BreadCrumb(nodeId)))(
                    onClickFocus,
                    backgroundColor := BaseColors.sidebarBgHighlight.copy(h = NodeColor.hue(node.id)).toHex,
                    border := "1px solid rgba(0,0,0,0.2)",
                  )
              }).apply(cls := "breadcrumb", VDomModifier.ifTrue(graph.isDeletedNowInAllParents(nid))(cls := "node-deleted"))

            case _                                                  => VDomModifier.empty
          }
        }(breakOut)

        VDomModifier.ifTrue(!hideIfSingle || elements.length > 1)(
          intersperseWhile(elements, span(freeSolid.faAngleRight, cls := "divider"), (mod: VDomModifier) => !mod.isInstanceOf[outwatch.dom.EmptyModifier.type])
        )
      },
      registerDragContainer(state),
      onClick foreach { Analytics.sendEvent("breadcrumbs", "click") },
    )
  }

  private def modifier(state: GlobalState, filterUpTo: Option[NodeId], parentIdRx: Rx[Option[NodeId]], parentIdAction: NodeId => Unit)(implicit ctx: Ctx.Owner): VDomModifier = {
    Rx {
      val parentId = parentIdRx()
      val user = state.user()
      val graph = state.graph()

      modifier(state, graph, user, filterUpTo, parentId = parentId, parentIdAction = parentIdAction)
    }
  }
}
