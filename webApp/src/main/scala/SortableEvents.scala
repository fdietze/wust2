package wust.webApp

import acyclic.skipped
import monix.execution.{Ack, Scheduler}
import wust.util._
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalajs.dom
import org.scalajs.dom.console
import org.scalajs.dom.ext.KeyCode
import shopify.draggable._
import wust.graph.{Edge, GraphChanges}
import wust.ids.NodeId
import wust.webApp.DragItem.{payloadDecoder, targetDecoder}
import wust.webApp.views.Elements._
import wust.webApp.outwatchHelpers._

import scala.scalajs.js


class SortableEvents(state: GlobalState, draggable: Draggable) {
  private val sortableStartEvent = PublishSubject[SortableStartEvent]
  private val sortableStopEvent = PublishSubject[SortableStopEvent]
  private val sortableSortEvent = PublishSubject[SortableSortEvent]

  draggable.on[SortableStartEvent]("sortable:start", sortableStartEvent.onNext _)
  draggable.on[SortableSortEvent]("sortable:sort", sortableSortEvent.onNext _)
  draggable.on[SortableStopEvent]("sortable:stop", sortableStopEvent.onNext _)

  //  draggable.on("sortable:start", (e:SortableEvent) => console.log("sortable:start", e))
  //  draggable.on("sortable:sort", () => console.log("sortable:sort"))
  //  draggable.on("sortable:sorted", () => console.log("sortable:sorted"))
  //  draggable.on("sortable:stop", () => console.log("sortable:stop"))

  val sortableActions:PartialFunction[(SortableEvent, DragPayload, DragContainer, DragContainer, Boolean, Boolean),Unit] = {
    import DragContainer._
    def graph = state.graph.now

    {
      case (e, dragging: DragItem.Kanban.ToplevelColumn, from: Kanban.ColumnArea, into: Kanban.ColumnArea, false, false) =>
        console.log("reordering columns")
        // TODO: persist ordering

      case (e, dragging: DragItem.Kanban.SubColumn, from: Kanban.Column, into: Kanban.ColumnArea, false, false) =>
        val move = GraphChanges.changeTarget(Edge.Parent)(dragging.nodeId :: Nil, from.parentIds, into.parentIds)

        val moveStaticInParents = if(graph.isStaticParentIn(dragging.nodeId, from.parentIds)) {
          GraphChanges.changeTarget(Edge.StaticParentIn)(dragging.nodeId :: Nil, from.parentIds, into.parentIds)
        } else GraphChanges.empty

        val alreadyInParent = into.parentIds.exists(parentId => graph.children(parentId).contains(dragging.nodeId))
        if (alreadyInParent) {
          //          console.log("already in parent! removing dom element:", e.dragEvent.originalSource)
          defer(removeDomElement(e.dragEvent.originalSource))
        }
        state.eventProcessor.enriched.changes.onNext(move merge moveStaticInParents)

      case (e, dragging: DragItem.Kanban.Item, from: Kanban.Area, into: Kanban.Column, false, false) =>
        val move = GraphChanges.changeTarget(Edge.Parent)(dragging.nodeId :: Nil, from.parentIds, into.parentIds)

        val moveStaticInParents = if(graph.isStaticParentIn(dragging.nodeId, from.parentIds)) {
          GraphChanges.changeTarget(Edge.StaticParentIn)(dragging.nodeId :: Nil, from.parentIds, into.parentIds)
        } else GraphChanges.empty

        val alreadyInParent = graph.children(into.nodeId).contains(dragging.nodeId)
        if (alreadyInParent) {
//          console.log("already in parent! removing dom element:", e.dragEvent.originalSource)
          defer(removeDomElement(e.dragEvent.originalSource))
        }
        state.eventProcessor.enriched.changes.onNext(move merge moveStaticInParents)

      case (e, dragging: DragItem.Kanban.SubItem, from: Kanban.Area, into: Kanban.NewColumnArea, false, false) =>
        val move = GraphChanges.changeTarget(Edge.Parent)(dragging.nodeId :: Nil, from.parentIds, into.parentIds)
        // always make new columns static
        val moveStaticInParents = GraphChanges.changeTarget(Edge.StaticParentIn)(dragging.nodeId :: Nil, from.parentIds, into.parentIds)

        val alreadyInParent = into.parentIds.exists(parentId => graph.children(parentId).contains(dragging.nodeId))
        if (alreadyInParent || dragging.isInstanceOf[DragItem.Kanban.Card]) { // remove card, as it will be replaced by a column via graph events
//          console.log("already in parent! removing dom element:", e.dragEvent.originalSource)
          defer(removeDomElement(e.dragEvent.originalSource))
        }
        state.eventProcessor.enriched.changes.onNext(move merge moveStaticInParents)

      case (e, dragging: DragItem.Kanban.SubItem, from: Kanban.Area, into: Kanban.IsolatedNodes, false, false) =>
        val move = GraphChanges.changeTarget(Edge.Parent)(dragging.nodeId :: Nil, from.parentIds, into.parentIds)
        val removeStatic = GraphChanges.disconnect(Edge.StaticParentIn)(dragging.nodeId, from.parentIds)
        // will be reintroduced by change event, so we delete the original node:
        state.eventProcessor.enriched.changes.onNext(move merge removeStatic)
        defer(removeDomElement(e.dragEvent.originalSource))
    }
  }

//  val ctrlDown = keyDown(KeyCode.Ctrl)
  val ctrlDown = Observable(false) // TODO: somehow revert sortableSortEvent when pressing ctrl
  //  ctrlDown.foreach(down => println(s"ctrl down: $down"))

  //TODO: keyup-event for Shift does not work in chrome. It reports Capslock.
  val shiftDown = Observable(false)
  //  val shiftDown = keyDown(KeyCode.Shift)
  //  shiftDown.foreach(down => println(s"shift down: $down"))


  sortableStartEvent.foreachTry { e =>
    val source = decodeFromAttr[DragPayload](e.dragEvent.source, DragItem.payloadAttrName)
    source match {
      case Some(DragItem.DisableDrag) => e.cancel()
      case _ =>
    }
  }

  sortableSortEvent.withLatestFrom2(ctrlDown,shiftDown)((e,ctrl,shift) => (e,ctrl,shift)).foreachTry { case (e,ctrl,shift) =>
    val overContainerWorkaround = e.dragEvent.asInstanceOf[js.Dynamic].overContainer.asInstanceOf[dom.html.Element] // https://github.com/Shopify/draggable/issues/256
    val sourceContainerWorkaround = e.dragEvent.asInstanceOf[js.Dynamic].sourceContainer.asInstanceOf[dom.html.Element] // TODO: report as feature request
    val dragging = decodeFromAttr[DragPayload](e.dragEvent.source, DragItem.payloadAttrName)
    val overContainer = decodeFromAttr[DragContainer](overContainerWorkaround, DragContainer.attrName)
    val sourceContainer = decodeFromAttr[DragContainer](sourceContainerWorkaround, DragContainer.attrName).orElse(overContainer)

    // white listing allowed sortable actions
    (dragging, sourceContainer, overContainer) match {
      case (Some(dragging), Some(sourceContainer), Some(overContainer)) if sortableActions.isDefinedAt((e, dragging, sourceContainer, overContainer, ctrl, shift)) => // allowed
//      case (Some(dragging), Some(sourceContainer), Some(overContainer)) => println(s"not allowed: $sourceContainer -> $dragging -> $overContainer"); e.cancel()
      case _ => e.cancel() // not allowed
    }
  }


  sortableStopEvent.withLatestFrom2(ctrlDown,shiftDown)((e,ctrl,shift) => (e,ctrl,shift)).foreachTry { case (e,ctrl,shift) =>
    if(e.newContainer != e.oldContainer) {
      val dragging = decodeFromAttr[DragPayload](e.dragEvent.source, DragItem.payloadAttrName)
      val oldContainer = decodeFromAttr[DragContainer](e.oldContainer, DragContainer.attrName)
      val newContainer = decodeFromAttr[DragContainer](e.newContainer, DragContainer.attrName)
      (dragging, oldContainer, newContainer) match {
        case (Some(dragging), Some(oldContainer), Some(newContainer)) =>
          println(s"Sorting: $oldContainer -> $dragging -> $newContainer${ctrl.ifTrue(" +ctrl")}${shift.ifTrue(" +shift")}")
          sortableActions.applyOrElse((e, dragging, oldContainer, newContainer, ctrl, shift), (other:(SortableEvent, DragPayload, DragContainer, DragContainer, Boolean, Boolean)) => println(s"sort combination not handled."))
        case other => println(s"incomplete drag action: $other")
      }
    }
  }
}
