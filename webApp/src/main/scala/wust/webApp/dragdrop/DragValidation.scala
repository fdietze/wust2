package wust.webApp.dragdrop

import wust.util.algorithm.dfs
import wust.facades.draggable._
import wust.facades.googleanalytics.Analytics
import org.scalajs.dom
import wust.util._
import wust.webApp.dragdrop.DragActions._
import wust.webApp.state.GlobalState
import wust.webApp.views.DragComponents.{ readDragContainer, readDragPayload, readDragTarget, readDraggableDraggedAction }
import wust.webUtil.JSDefined
import wust.ids.NodeId
import wust.graph.Graph

import scala.scalajs.js

object DragValidation {

  def extractSortInformation(e: SortableEvent, lastDragOverContainerEvent: DragOverContainerEvent): (js.UndefOr[DragContainer], js.UndefOr[DragPayload], js.UndefOr[DragContainer]) = {
    val overContainerWorkaround = e.dragEvent.asInstanceOf[js.Dynamic].overContainer.asInstanceOf[js.UndefOr[dom.html.Element]] // https://github.com/Shopify/draggable/issues/256
    val overContainer = overContainerWorkaround.orElse(lastDragOverContainerEvent.overContainer)
    val sourceContainerWorkaround = e.dragEvent.asInstanceOf[js.Dynamic].sourceContainer.asInstanceOf[dom.html.Element] // TODO: report as feature request
    val payloadOpt = readDragPayload(e.dragEvent.source)
    // containers are written by registerDragContainer
    val targetContainerOpt = overContainer.flatMap(readDragContainer)
    val sourceContainerOpt = readDragContainer(sourceContainerWorkaround)
    (sourceContainerOpt, payloadOpt, targetContainerOpt)
  }

  def validateSortInformation(e: SortableSortEvent, lastDragOverContainerEvent: DragOverContainerEvent, ctrl: Boolean, shift: Boolean): Unit = {
    extractSortInformation(e, lastDragOverContainerEvent) match {
      case (JSDefined(sourceContainer), JSDefined(payload), JSDefined(overContainer)) =>
        (sourceContainer, overContainer) match {
          case (sourceContainer: SortableContainer, overContainer: SortableContainer) if (sortAction.isDefinedAt((payload, sourceContainer, overContainer, ctrl, shift))) =>
            scribe.debug(s"valid sort action: $payload: $sourceContainer -> $overContainer")
          case (sourceContainer: DragContainer, overContainer: DragContainer) =>
            e.cancel()
            scribe.debug(s"sort not allowed: $payload: $sourceContainer -> $overContainer (trying drag instead...)")
            validateDragInformation(e.dragEvent, ctrl, shift)
        }
      case (sourceContainer, payload, overContainer) =>
        e.cancel()
        scribe.debug(s"incomplete sort information: $payload: $sourceContainer -> $overContainer")
    }
  }

  def validateDragInformation(e: DragOverEvent, ctrl: Boolean, shift: Boolean): Unit = {
    val targetOpt = readDragTarget(e.over)
    val payloadOpt = readDragPayload(e.originalSource)
    (payloadOpt, targetOpt) match {
      case (JSDefined(payload), JSDefined(target)) =>
        if (dragAction.isDefinedAt((payload, target, ctrl, shift))) {
          scribe.debug(s"valid drag action: $payload -> $target)")
        } else {
          e.cancel()
          scribe.debug(s"drag not allowed: $payload -> $target)")
        }
      case (payload, target) =>
        e.cancel()
        scribe.debug(s"incomplete drag information: $payload -> $target)")
    }
  }

  def performSort(e: SortableStopEvent, currentOverContainerEvent: DragOverContainerEvent, currentOverEvent: DragOverEvent, ctrl: Boolean, shift: Boolean): Unit = {
    extractSortInformation(e, currentOverContainerEvent) match {
      case (JSDefined(sourceContainer), JSDefined(payload), JSDefined(overContainer)) =>
        (sourceContainer, overContainer) match {
          case (sourceContainer: SortableContainer, overContainer: SortableContainer) =>
            if (!wouldCreateContainmentCycle(payload.nodeIds, Seq(overContainer.parentId), GlobalState.graph.now)) {
              // target is null, since sort actions do not look at the target. The target moves away automatically.
              val successful = sortAction.runWith { calculateChange =>
                GlobalState.submitChanges(calculateChange(e, GlobalState.graph.now, GlobalState.user.now.id))
              }((payload, sourceContainer, overContainer, ctrl, shift))

              if (successful) {
                scribe.debug(s"sort action successful: $payload: $sourceContainer -> $overContainer")
                Analytics.sendEvent("drag", "sort", s"${sourceContainer.productPrefix}-${payload.productPrefix}-${overContainer.productPrefix}${ctrl.ifTrue(" +ctrl")}${shift.ifTrue(" +shift")}")
              } else {
                scribe.debug(s"sort action not defined: $payload: $sourceContainer -> $overContainer (trying drag instead...)")
                performDrag( e, currentOverEvent, ctrl, shift)
              }
            } else {
              scribe.debug(s"sort action would create cycle, canceling: $payload: $sourceContainer -> $overContainer")
            }
          case (sourceContainer: DragContainer, overContainer: DragContainer) =>
            scribe.debug(s"sort action not defined: $payload: $sourceContainer -> $overContainer (trying drag instead...)")
            performDrag( e, currentOverEvent, ctrl, shift)
        }

      case (sourceContainerOpt, payloadOpt, overContainerOpt) =>
        scribe.debug(s"incomplete sort information: $payloadOpt: $sourceContainerOpt -> $overContainerOpt")
    }
  }

  def wouldCreateContainmentCycle(payloadIds: Seq[NodeId], targetIds: Seq[NodeId], graph: Graph): Boolean = {
    targetIds.exists { targetId =>
      val targetIdx = graph.idToIdxOrThrow(targetId)
      payloadIds.exists { payloadId =>
        // We are creating a cycle. In some collaborative cases the payload can get lost. That's why we do nothing here.
        // Creating loops is also not really interesting! ;)
        // When we create a cycle, we make payload's existence dependence on the accessibility of a child.
        // -> 1. child is still visible somewhere else -> OK (we don't know, because our state can be outdated while dragging)
        // -> 2. child is not -> payload lost
        val payloadIdx = graph.idToIdxOrThrow(payloadId)
        dfs.exists(_(payloadIdx), dfs.withStart, graph.childrenIdx, isFound = _ == targetIdx)
      }
    }
  }

  def performDrag(e: SortableStopEvent, currentOverEvent: DragOverEvent, ctrl: Boolean, shift: Boolean): Unit = {
    scribe.debug("performing drag...")
    val afterDraggedActionOpt = readDraggableDraggedAction(e.dragEvent.originalSource)
    val payloadOpt = readDragPayload(e.dragEvent.originalSource)
    val targetOpt = readDragTarget(currentOverEvent.over)
    (payloadOpt, targetOpt) match {
      case (JSDefined(payload), JSDefined(target)) =>
        if (!wouldCreateContainmentCycle(payload.nodeIds, target.nodeIds, GlobalState.graph.now)) {
          val successful = dragAction.runWith { calculateChange =>
            GlobalState.submitChanges(calculateChange(GlobalState.rawGraph.now, GlobalState.user.now.id))
          }((payload, target, ctrl, shift))

          if (successful) {
            scribe.debug(s"drag action successful: $payload -> $target")
            Analytics.sendEvent("drag", "drop", s"${payload.productPrefix}-${target.productPrefix}${ctrl.ifTrue(" +ctrl")}${shift.ifTrue(" +shift")}")
            afterDraggedActionOpt.foreach{ action =>
              scribe.debug(s"performing afterDraggedAction...")
              action.apply()
            }
          } else {
            scribe.debug(s"drag action not defined: $payload -> $target ${ctrl.ifTrue(" +ctrl")}${shift.ifTrue(" +shift")}, defined($payload, $target, $ctrl, $shift): ${dragAction.isDefinedAt((payload, target, ctrl, shift))}")
            Analytics.sendEvent("drag", "nothandled", s"${payload.productPrefix}-${target.productPrefix} ${ctrl.ifTrue(" +ctrl")}${shift.ifTrue(" +shift")}")
          }
        } else {
          scribe.debug(s"drag action would create cycle, canceling: $payload -> $target ${ctrl.ifTrue(" +ctrl")}${shift.ifTrue(" +shift")}")
        }
      case (payload, target) =>
        scribe.debug(s"incomplete drag information: $payload -> $target)")
    }
  }
}
