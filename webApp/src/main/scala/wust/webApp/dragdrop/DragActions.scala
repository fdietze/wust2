package wust.webApp.dragdrop

import wust.util.algorithm.dfs
import wust.facades.draggable.SortableStopEvent
import wust.graph.{ Edge, GraphChanges, _ }
import wust.ids.{ UserId, _ }
import wust.ids.EdgeData
import wust.webApp.state.FeatureState
import wust.webApp.state.GlobalState

object DragActions {

  // These partial functions describe what happens, but also what is allowed to drag from where to where
  // Be aware: Other functions rely on its partiality (isDefinedAt), therefore do not make them a full function
  // The booleans: Ctrl-pressed, Shift-pressed

  val sortAction: PartialFunction[(DragPayload, SortableContainer, SortableContainer, Boolean, Boolean), (SortableStopEvent, Graph, UserId) => GraphChanges] = {
    // First, Sort actions:
    import DragContainer._
    import Sorting._
    {
      // Reorder or nest Stages
      case (payload: DragItem.Stage, from: Kanban.AreaForColumns, into: Kanban.AreaForColumns, ctrl, false) =>
        (sortableStopEvent, graph, userId) =>
          //        val move = GraphChanges.changeTarget[NodeId, NodeId, Edge.Parent](Edge.Parent)(Some(dragging.nodeId), Some(from.parentId), Some(into.parentId))
          def addColumn = sortingChanges(graph, userId, sortableStopEvent, payload.nodeId, from, into)
          def disconnectColumn: GraphChanges = if (from.parentId != into.parentId)
            GraphChanges.disconnect(Edge.Child)(ParentId(from.parentId), ChildId(payload.nodeId))
          else GraphChanges.empty

          (from, into, GlobalState.view.now) match {
            case (_: Kanban.ColumnArea, _: Kanban.ColumnArea, View.Kanban) => FeatureState.use(Feature.ReorderColumnsInKanban)
            case (_, _: Kanban.Column, View.Kanban) => FeatureState.use(Feature.NestColumnsInKanban)
            case _ =>
          }

          if (ctrl)
            addColumn
          else
            addColumn merge disconnectColumn

      // Task between Columns
      case (payload: DragItem.Task, from: Kanban.Column, into: Kanban.Column, ctrl, false) =>
        (sortableStopEvent, graph, userId) =>

          def removeProperty = GraphChanges.removeProperty(payload.nodeId, key = from.groupKey, propertyValueId = from.propertyValueId)
          def setProperty = GraphChanges.addProperty(payload.nodeId, key = into.groupKey, propertyValueId = into.propertyValueId, showOnCard = false)


          if(from.nodeId != into.nodeId)
            FeatureState.use(Feature.DragTaskToDifferentColumnInKanban)
          else
            FeatureState.use(Feature.ReorderTaskInKanban)

          if (ctrl)
            setProperty
          else
            removeProperty merge setProperty

      // e.g. Subtask into Column
      //TODO: copying from inbox to column and vice versa does not work. the encoding of being in the inbox is parent-edge to project. encoding of being in a column is parent-edge to project and parent-edge to column. Inclusion in both cannot be encoded with this.
      case (payload: DragItem.Task, from: Kanban.Inbox, intoColumn: Kanban.Column, ctrl, false) =>
        (sortableStopEvent, graph, userId) =>
          //        val move = GraphChanges.changeTarget(Edge.Parent)(Some(payload.nodeId), stageParents, Some(intoColumn.parentId))
          
          def setProperty = GraphChanges.addProperty(payload.nodeId, key = intoColumn.groupKey, propertyValueId = intoColumn.propertyValueId, showOnCard = false)
          def addTargetWorkspace = GraphChanges.connect(Edge.Child)(ParentId(intoColumn.workspace), ChildId(payload.nodeId))
          def disconnect: GraphChanges = if (from.parentId != intoColumn.workspace)
            GraphChanges.disconnect(Edge.Child)(ParentId(from.parentId), ChildId(payload.nodeId))
          else GraphChanges.empty


          GlobalState.view.now match {
            case View.Kanban => FeatureState.use(Feature.DragTaskToDifferentColumnInKanban)
            case _           =>
          }

          if (ctrl)
            setProperty merge addTargetWorkspace
          else
            setProperty merge addTargetWorkspace merge disconnect

      // e.g. Card from Column into other Card/Inbox
      //TODO: copying from inbox to column and vice versa does not work. the encoding of being in the inbox is parent-edge to project. encoding of being in a column is parent-edge to project and parent-edge to column. Inclusion in both cannot be encoded with this.
      case (payload: DragItem.Task, fromColumn: Kanban.Column, into: Kanban.Workspace, ctrl, false) =>
        (sortableStopEvent, graph, userId) =>

          // disconnect fromColumn all stage parents
          val addTargetWorkspace = sortingChanges(graph, userId, sortableStopEvent, payload.nodeId, fromColumn, into)

          def disconnectFromWorkspace: GraphChanges = if (fromColumn.workspace != into.parentId) // into is a Workspace
            GraphChanges.disconnect(Edge.Child)(ParentId(fromColumn.workspace), ChildId(payload.nodeId))
          else GraphChanges.empty

          def removeProperty = GraphChanges.removeProperty(payload.nodeId, key = fromColumn.groupKey, propertyValueId = fromColumn.propertyValueId)

          (into, GlobalState.view.now) match {
            // case (_: Kanban.Inbox, View.List) => FeatureState.use(Feature.DragTaskToDifferentColumnInChecklist)
            case (_: Kanban.Inbox, View.Kanban) => FeatureState.use(Feature.DragTaskToDifferentColumnInKanban)
            case _                              =>
          }

          if (ctrl)
            addTargetWorkspace
          else
            addTargetWorkspace merge removeProperty merge disconnectFromWorkspace

      // e.g. Card from Card/Inbox into Card/Inbox
      case (payload: DragItem.Task, from: Kanban.Workspace, into: Kanban.Workspace, ctrl, false) =>
        (sortableStopEvent, graph, userId) =>
          // disconnect from all stage parents
          val addTargetWorkspace = sortingChanges(graph, userId, sortableStopEvent, payload.nodeId, from, into)
          def disconnectFromWorkspace: GraphChanges = if (from.parentId != into.parentId)
            GraphChanges.disconnect(Edge.Child)(ParentId(from.parentId), ChildId(payload.nodeId))
          else GraphChanges.empty

          (from, into, GlobalState.view.now) match {
            case (_: Kanban.Inbox, _: Kanban.Inbox, View.List) => FeatureState.use(Feature.ReorderTaskInChecklist)
            case (_: Kanban.Inbox, _: Kanban.Inbox, View.Kanban) => FeatureState.use(Feature.ReorderTaskInKanban)
            case _ =>
          }

          if (ctrl)
            addTargetWorkspace
          else
            addTargetWorkspace merge disconnectFromWorkspace

    }
  }

  val dragAction: PartialFunction[(DragPayload, DragTarget, Boolean, Boolean), (Graph, UserId) => GraphChanges] = {
    import DragItem._
    import wust.graph.GraphChanges._
    {
      case (payload: ContentNodeConnect, target: ContentNodeConnect, ctrl, false) => (graph, userId) => addProperty(nodeId = payload.nodeId, payload.propertyName, propertyValueId = target.nodeId, showOnCard = false)

      case (payload: ContentNode, target: ContentNode, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)
      case (payload: ContentNode, target: Thread, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), target.nodeIds.map(ParentId(_)), graph, ctrl)
      case (payload: ContentNode, target: Workspace, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)
      case (payload: ContentNode, target: Channel, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)
      case (payload: ContentNode, target: Sidebar.type, false, false) => (graph, userId) => pin(payload.nodeId, userId)

      case (payload: ContentNode, target: Tag, false, false) => (graph, userId) => linkInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph)
      case (payload: ContentNode, target: BreadCrumb, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)

      case (payload: SelectedNode, target: ContentNode, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)
      case (payload: SelectedNodes, target: ContentNode, ctrl, false) => (graph, userId) => linkOrMoveInto(payload.nodeIds.map(ChildId(_)), ParentId(target.nodeId), graph, ctrl)
      case (payload: SelectedNodes, target: Workspace, ctrl, false) => (graph, userId) => linkOrMoveInto(payload.nodeIds.map(ChildId(_)), ParentId(target.nodeId), graph, ctrl)
      case (payload: SelectedNodes, target: Channel, ctrl, false) => (graph, userId) => linkOrMoveInto(payload.nodeIds.map(ChildId(_)), ParentId(target.nodeId), graph, ctrl)

      case (payload: Channel, target: Channel, false, false) => (graph, userId) => movePinnedChannel(ChildId(payload.nodeId), payload.parentId.map(ParentId(_)), Some(ParentId(target.nodeId)), graph, userId)
      case (payload: Channel, target: Channel, true, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), Some(ParentId(target.nodeId)), graph, true)
      case (payload: Channel, target: Sidebar.type, false, false) => (graph, userId) => movePinnedChannel(ChildId(payload.nodeId), payload.parentId.map(ParentId(_)), None, graph, userId)
      case (payload: Channel, target: ContentNode, ctrl, false) => (graph, userId) => movePinnedChannel(ChildId(payload.nodeId), payload.parentId.map(ParentId(_)), Some(ParentId(target.nodeId)), graph, userId)

      case (payload: Property, target: ContentNode, false, false) => (graph, userId) => linkOrCopyInto(payload.edge, target.nodeId, graph)

      case (payload: Tag, target: ContentNode, false, false) => (graph, userId) => addProperty(target.nodeId, PropertyKey.tag, payload.nodeId, showOnCard = true)
      case (payload: Tag, target: Tag, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)
      case (payload: Tag, target: TagBar, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)
      case (payload: Tag, target: Channel, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, true) // tags are always linked

      case (payload: Stage, target: Channel, ctrl, false) => (graph, userId) => linkOrMoveInto(ChildId(payload.nodeId), ParentId(target.nodeId), graph, ctrl)

      case (payload: User, target: ContentNode, false, false) => (graph, userId) => assign(target.nodeId, payload.userId)
    }
  }

}
