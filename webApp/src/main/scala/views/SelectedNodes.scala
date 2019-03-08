package wust.webApp.views

import fontAwesome._
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.{Styles, ZIndex}
import wust.graph._
import wust.ids._
import wust.util._
import wust.webApp.Icons
import wust.webApp.dragdrop.DragItem
import wust.webApp.outwatchHelpers._
import wust.webApp.state.{GlobalState, NodePermission, ScreenSize}
import wust.webApp.views.Components._
import wust.webApp.views.Elements._

import scala.collection.breakOut

object SelectedNodes {
  import SharedViewElements.SelectedNodeBase

  def apply[T <: SelectedNodeBase](state: GlobalState, nodeActions:(List[T], Boolean) => List[VNode] = (_:List[T], _: Boolean) => Nil, singleNodeActions:(T, Boolean) => List[VNode] = (_:List[T], _: Boolean) => Nil, selected:Var[Set[T]])(implicit ctx: Ctx.Owner): VNode = {

    val selectedNodes: Var[Set[T]] = selected.mapRead { selectedNodes =>
      selectedNodes().filter(data => state.graph().lookup.contains(data.nodeId))
    }

    div(
      emitterRx(selected).map(_.map(_.nodeId)(breakOut): List[NodeId]) --> state.selectedNodes,

      Rx {
        val graph = state.graph()
        val sortedNodeIds = selectedNodes().toList //.sortBy(data => graph.nodeCreated(graph.idToIdx(data.nodeId)): Long)
        val canWriteAll = NodePermission.canWriteAll(state.user(), graph, sortedNodeIds.map(_.nodeId))
        VDomModifier(
          sortedNodeIds match {
            case Nil => VDomModifier.empty
            case nonEmptyNodeIds => VDomModifier(
              cls := "selectednodes",
              Styles.flex,
              alignItems.center,

              clearSelectionButton(selectedNodes),
              div(nonEmptyNodeIds.size, marginRight := "10px", fontWeight.bold),

              Rx {
                (state.screenSize() != ScreenSize.Small).ifTrue[VDomModifier](nodeList(state, nonEmptyNodeIds.map(_.nodeId), selectedNodes, state.graph()))
              }, // grow, so it can be grabbed

              div(marginLeft.auto),
              (nonEmptyNodeIds.size == 1).ifTrueSeq(singleNodeActions(nonEmptyNodeIds.head, canWriteAll).map(_(cls := "actionbutton"))),
              nodeActions(nonEmptyNodeIds, canWriteAll).map(_(cls := "actionbutton")),
            )
          }
        )
      },
      registerDragContainer(state),
      keyed,
      zIndex := ZIndex.selected,
      onGlobalEscape(Set.empty[T]) --> selectedNodes,
    )
  }

  private def nodeList[T <: SelectedNodeBase](state:GlobalState, selectedNodeIds:List[NodeId], selectedNodes: Var[Set[T]], graph:Graph)(implicit ctx: Ctx.Owner) = {
    div(
      cls := "nodelist",
      drag(payload = DragItem.SelectedNodes(selectedNodeIds)),
      onAfterPayloadWasDragged.foreach{ selectedNodes() = Set.empty[T] },

      Styles.flex,
//      alignItems.center,
      flexWrap.wrap,
      selectedNodeIds.map { nodeId =>
          val node = graph.nodesById(nodeId)
          selectedNodeCard(state, selectedNodes, node)
        }
    )
  }

  def deleteAllButton[T <: SelectedNodeBase](state:GlobalState, selectedNodesList:List[T], selectedNodes: Var[Set[T]], allSelectedNodesAreDeleted: Rx[Boolean])(implicit ctx: Ctx.Owner): VNode = {
    div(
      div(
        cls := "fa-fw",
        Rx {
          if (allSelectedNodesAreDeleted()) SharedViewElements.undeleteButton
          else SharedViewElements.deleteButton
        }
      ),

      onClick foreach{_ =>
        val changes =
          if (allSelectedNodesAreDeleted.now)
            selectedNodesList.foldLeft(GraphChanges.empty)((c, t) => c merge GraphChanges.undelete(ChildId(t.nodeId), t.directParentIds.map(ParentId(_))))
          else
            selectedNodesList.foldLeft(GraphChanges.empty)((c, t) => c merge GraphChanges.delete(ChildId(t.nodeId), t.directParentIds.map(ParentId(_))))

        state.eventProcessor.changes.onNext(changes)
        selectedNodes() = Set.empty[T]
      }
    )
  }

  private def clearSelectionButton[T](selectedNodes: Var[Set[T]]) = {
    closeButton(
      cls := "actionbutton",
      onClick foreach {
        selectedNodes() = Set.empty[T]
      }
    )
  }

  private def selectedNodeCard[T <: SelectedNodeBase](state:GlobalState, selectedNodes: Var[Set[T]], node: Node)(implicit ctx: Ctx.Owner) = {
    nodeCard(node,contentInject = Seq[VDomModifier](
      Styles.flex,
      alignItems.center,
      span(
        "×",
        cls := "actionbutton",
        margin := "0",
        onClick.stopPropagation foreach {
          selectedNodes.update(_.filterNot(data => data.nodeId == node.id))
        }
      ),
    ),
      maxLength = Some(20)
    )(
      drag(DragItem.SelectedNode(node.id)),
      cls := "draggable"
    )
  }
}
