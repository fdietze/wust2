package wust.graph

import wust.ids._
import wust.util.collection.RichCollection

import scala.collection.breakOut

case class GraphChanges(
  addNodes: collection.Set[Node] = Set.empty,
  addEdges: collection.Set[Edge] = Set.empty,
  // we do not really need a connection for deleting (ConnectionId instead), but we want to revert it again.
  delEdges: collection.Set[Edge] = Set.empty
) {
  def withAuthor(userId: UserId, timestamp: EpochMilli = EpochMilli.now): GraphChanges = {
    val existingAuthors: Set[NodeId] = addEdges.collect { case edge: Edge.Author => edge.nodeId }(breakOut)
    copy(
      addEdges = addEdges ++ addNodes.flatMap { node =>
        (if (existingAuthors(node.id)) Set.empty[Edge] else Set[Edge](Edge.Author(node.id, EdgeData.Author(timestamp), userId)))
      }
    )
  }

  def merge(other: GraphChanges): GraphChanges = {
    GraphChanges.from(
      addNodes = addNodes ++ other.addNodes,
      addEdges = addEdges ++ other.addEdges,
      delEdges = delEdges -- other.addEdges ++ other.delEdges
      //FIXME: why was i here? inconsistent changes? .filter(c => !otherAddNodeIds(c.sourceId) && !otherAddNodeIds(c.targetId))
    )
  }

  def filter(p: NodeId => Boolean): GraphChanges =
    GraphChanges(
      addNodes = addNodes.filter(n => p(n.id)),
      addEdges = addEdges.filter(e => p(e.sourceId) && p(e.targetId)),
      delEdges = delEdges.filter(e => p(e.sourceId) && p(e.targetId))
    )

  def filterCheck(p: NodeId => Boolean, checkIdsFromEdge: Edge => List[NodeId]): GraphChanges = {
    // for each edge we need to check a certain number of ids.
    // checkIdsFromEdge returns exactly the ids we need to have permissions for (checked via p)
    GraphChanges(
      addNodes = addNodes.filter(n => p(n.id)),
      addEdges = addEdges.filter(e => checkIdsFromEdge(e).forall(p)),
      delEdges = delEdges.filter(e => checkIdsFromEdge(e).forall(p))
    )
  }

  lazy val consistent: GraphChanges = copy(addEdges = addEdges -- delEdges)

  def involvedNodeIds: collection.Set[NodeId] =
    addNodes.map(_.id) ++
      addEdges.flatMap(e => e.sourceId :: e.targetId :: Nil) ++
      delEdges.flatMap(e => e.sourceId :: e.targetId :: Nil)

  private val allProps = addNodes :: addEdges :: delEdges :: Nil

  lazy val isEmpty: Boolean = allProps.forall(s => s.isEmpty)
  def nonEmpty: Boolean = !isEmpty
  lazy val size: Int = allProps.foldLeft(0)(_ + _.size)

  override def toString = toPrettyString()

  def toPrettyString(graph:Graph = Graph.empty) = {
    // the graph can provide additional information about the edges

    val addNodeLookup = addNodes.by(_.id)

    def id(nid: NodeId): String = {
      val str = (
        for {
          node <- addNodeLookup.get(nid).orElse(graph.lookup.nodesByIdGet(nid))
        } yield node.str
      ).fold("")(str => s"${ "\"" }$str${ "\"" }")
      s"[${ nid.shortHumanReadable }]$str"
    }

    val sb = new StringBuilder
    sb ++= s"GraphChanges(\n"
    if(addNodes.nonEmpty) sb ++= s"  addNodes: ${ addNodes.map(n => s"${ id(n.id) }:${ n.tpe }/${n.role}  ${n.id.toBase58}  ${n.id.toUuid}").mkString("\n            ") }\n"
    if(addEdges.nonEmpty) sb ++= s"  addEdges: ${ addEdges.map(e => s"${ id(e.sourceId) } -${ e.data }-> ${ id(e.targetId) }").mkString("\n            ") }\n"
    if(delEdges.nonEmpty) sb ++= s"  delEdges: ${ delEdges.map(e => s"${ id(e.sourceId) } -${ e.data }-> ${ id(e.targetId) }").mkString("\n            ") }\n"
    sb ++= ")"

    sb.result()
  }
}
object GraphChanges {

  case class Import(changes: GraphChanges, topLevelNodeIds: Seq[NodeId], focusNodeId: Option[NodeId])

  val empty = new GraphChanges()

  def from(
    addNodes: Iterable[Node] = Set.empty,
    addEdges: Iterable[Edge] = Set.empty,
    delEdges: Iterable[Edge] = Set.empty
  ) =
    GraphChanges(
      addNodes.toSet,
      addEdges.toSet,
      delEdges.toSet
    )

  def addMarkdownMessage(string: String): GraphChanges = addNode(Node.MarkdownMessage(string))
  def addMarkdownTask(string: String): GraphChanges = addNode(Node.MarkdownTask(string))

  def addNode(content: NodeData.Content, role: NodeRole) = GraphChanges(addNodes = Set(Node.Content(content, role)))
  def addNode(node: Node) = GraphChanges(addNodes = Set(node))
  def addNodeWithParent(node: Node, parentId: ParentId) =
    GraphChanges(addNodes = Set(node), addEdges = Set(Edge.Child(parentId, ChildId(node.id))))
  def addNodeWithParent(node: Node, parentIds: Iterable[ParentId]) =
    GraphChanges(addNodes = Set(node), addEdges = parentIds.map(parentId => Edge.Child(parentId, ChildId(node.id)))(breakOut))
  def addNodesWithParents(nodes: Iterable[Node], parentIds: Iterable[ParentId]) =
    GraphChanges(addNodes = nodes.toSet, addEdges = nodes.flatMap(node => parentIds.map(parentId => Edge.Child(parentId, ChildId(node.id))))(breakOut))
  def addNodeWithDeletedParent(node: Node, parentIds: Iterable[ParentId], deletedAt: EpochMilli) =
    GraphChanges(addNodes = Set(node), addEdges = parentIds.map(parentId => Edge.Child(parentId, EdgeData.Child(Some(deletedAt), None), ChildId(node.id)))(breakOut))

  def addToParent(nodeId: ChildId, parentId: ParentId): GraphChanges = addToParent(List(nodeId), parentId)
  def addToParent(nodeIds: Iterable[ChildId], parentId: ParentId): GraphChanges= GraphChanges(
    addEdges = nodeIds.map { channelId =>
      Edge.Child(parentId, channelId)
    }(breakOut)
  )

  def addToParents(nodeId: ChildId, parentIds: Iterable[ParentId]): GraphChanges = GraphChanges(
    addEdges = parentIds.map { parentId =>
      Edge.Child(parentId, nodeId)
    }(breakOut)
  )

  val newProjectName = "Untitled Project"
  def newProject(nodeId: NodeId, userId: UserId, title: String = newProjectName): GraphChanges = {
    val post = new Node.Content(
      nodeId,
      NodeData.Markdown(title),
      NodeRole.Project,
      NodeMeta(accessLevel = NodeAccess.Inherited)
    )
    GraphChanges(addNodes = Set(post), addEdges = Set(Edge.Pinned(nodeId, userId), Edge.Notify(nodeId, userId), Edge.Member(nodeId, EdgeData.Member(AccessLevel.ReadWrite), userId)))
  }

  def pin(nodeId:NodeId, userId: UserId) = GraphChanges.connect(Edge.Pinned)(nodeId, userId)

  def undelete(childIds: Iterable[ChildId], parentIds: Iterable[ParentId]): GraphChanges = connect(Edge.Child)(parentIds, childIds)
  def undelete(childId: ChildId, parentIds: Iterable[ParentId]): GraphChanges = undelete(childId :: Nil, parentIds)
  def undelete(childId: ChildId, parentId: ParentId): GraphChanges = undelete(childId :: Nil, parentId :: Nil)

  def delete(childIds: Iterable[ChildId], parentIds: Set[ParentId]): GraphChanges =
    childIds.foldLeft(empty)((acc, nextNode) => acc merge delete(nextNode, parentIds))
  def delete(childId: ChildId, parentIds: Iterable[ParentId], deletedAt: EpochMilli = EpochMilli.now): GraphChanges = GraphChanges(
    addEdges = parentIds.map(
      parentId => Edge.Child.delete(parentId, childId, deletedAt)
    )(breakOut)
  )
  def delete(childId: ChildId, parentId: ParentId): GraphChanges = GraphChanges(
    addEdges = Set(Edge.Child.delete(parentId, childId))
  )

  def deleteFromGraph(childId: ChildId, graph:Graph, timestamp: EpochMilli = EpochMilli.now) = {
    GraphChanges(
      addEdges = graph.parentEdgeIdx(graph.idToIdx(childId)).flatMap { edgeIdx =>
        val edge = graph.edges(edgeIdx).asInstanceOf[Edge.Child]
        if (edge.data.deletedAt.isDefined) None
        else Some(edge.copy(data = edge.data.copy(deletedAt = Some(timestamp))))
      }(breakOut)
    )
  }

  class ConnectFactory[SOURCEID, TARGETID, EDGE <: Edge](edge: (SOURCEID, TARGETID) => EDGE, toGraphChanges: collection.Set[EDGE] => GraphChanges) {
    def apply(sourceId: SOURCEID, targetId: TARGETID): GraphChanges =
      if(sourceId != targetId) toGraphChanges(Set(edge(sourceId, targetId))) else empty
    def apply(sourceId: SOURCEID, targetIds: Iterable[TARGETID]): GraphChanges =
      toGraphChanges(targetIds.collect { case targetId if targetId != sourceId => edge(sourceId, targetId) }(breakOut))
    def apply(sourceIds: Iterable[SOURCEID], targetId: TARGETID): GraphChanges
    =
      toGraphChanges(sourceIds.collect { case sourceId if sourceId != targetId => edge(sourceId, targetId) }(breakOut))
    def apply(sourceIds: Iterable[SOURCEID], targetIds: Iterable[TARGETID]): GraphChanges
    =
      toGraphChanges(sourceIds.flatMap(sourceId => targetIds.collect { case targetId if targetId != sourceId => edge(sourceId, targetId) })(breakOut))
  }

  class ConnectFactoryWithData[SOURCE, TARGET, DATA, EDGE <: Edge](edge: (SOURCE, DATA, TARGET) => EDGE, toGraphChanges: collection.Set[EDGE] => GraphChanges) {
    def apply(sourceId: SOURCE, data: DATA, targetId: TARGET): GraphChanges =
      if(sourceId != targetId) toGraphChanges(Set(edge(sourceId, data, targetId))) else empty
    def apply(sourceId: SOURCE, data: DATA, targetIds: Iterable[TARGET]): GraphChanges =
      toGraphChanges(targetIds.collect { case targetId if targetId != sourceId => edge(sourceId, data, targetId) }(breakOut))
    def apply(sourceIds: Iterable[SOURCE], data: DATA, targetId: TARGET): GraphChanges
    =
      toGraphChanges(sourceIds.collect { case sourceId if sourceId != targetId => edge(sourceId, data, targetId) }(breakOut))
    def apply(sourceIds: Iterable[SOURCE], data: DATA, targetIds: Iterable[TARGET]): GraphChanges
    =
      toGraphChanges(sourceIds.flatMap(sourceId => targetIds.collect { case targetId if targetId != sourceId => edge(sourceId, data, targetId) })(breakOut))
  }

  def connect[SOURCE <: NodeId, TARGET <: NodeId, EDGE <: Edge](edge: (SOURCE, TARGET) => EDGE) = new ConnectFactory(edge, (edges: collection.Set[Edge]) => GraphChanges(addEdges = edges))
  def connect[SOURCE <: NodeId, TARGET <: NodeId, DATA <: EdgeData, EDGE <: Edge](edge: (SOURCE, DATA, TARGET) => EDGE) = new ConnectFactoryWithData(edge, (edges: collection.Set[Edge]) => GraphChanges(addEdges = edges))
  def disconnect[SOURCE <: NodeId, TARGET <: NodeId, EDGE <: Edge](edge: (SOURCE, TARGET) => EDGE) = new ConnectFactory(edge, (edges: collection.Set[Edge]) => GraphChanges(delEdges = edges))
  def disconnect[SOURCE <: NodeId, TARGET <: NodeId, DATA <: EdgeData, EDGE <: Edge](edge: (SOURCE, DATA, TARGET) => EDGE) = new ConnectFactoryWithData(edge, (edges: collection.Set[Edge]) => GraphChanges(delEdges = edges))

  def changeSource[SOURCE <: NodeId, TARGET <: NodeId, EDGE <: Edge](edge: (SOURCE, TARGET) => EDGE)(targetIds: Iterable[TARGET], oldSourceIds: Iterable[SOURCE], newSourceIds: Iterable[SOURCE]): GraphChanges = {
    val disconnect: GraphChanges = GraphChanges.disconnect(edge)(oldSourceIds, targetIds)
    val connect: GraphChanges = GraphChanges.connect(edge)(newSourceIds, targetIds)
    disconnect merge connect
  }
  def changeTarget[SOURCE <: NodeId, TARGET <: NodeId, EDGE <: Edge](edge: (SOURCE, TARGET) => EDGE)(sourceIds: Iterable[SOURCE], oldTargetIds: Iterable[TARGET], newTargetIds: Iterable[TARGET]): GraphChanges = {
    val disconnect: GraphChanges = GraphChanges.disconnect(edge)(sourceIds, oldTargetIds)
    val connect: GraphChanges = GraphChanges.connect(edge)(sourceIds, newTargetIds)
    disconnect merge connect
  }

  //TODO: The GrahpChange.moveInto function needs tests!
  //TODO: unify with moveInto used for drag&drop?
  def moveInto(graph: Graph, subjectIds: Iterable[ChildId], newParentIds: Iterable[ParentId]): GraphChanges =
    newParentIds.foldLeft(GraphChanges.empty) { (changes, targetId) => changes merge GraphChanges.moveInto(graph, subjectIds, targetId) }
  def moveInto(graph: Graph, subjectId: ChildId, newParentId: ParentId): GraphChanges = moveInto(graph, subjectId :: Nil, newParentId)
  def moveInto(graph: Graph, subjectIds: Iterable[ChildId], newParentId: ParentId): GraphChanges = {
    // TODO: only keep deepest parent in transitive chain
    val newParentships: collection.Set[Edge] = subjectIds
      .filterNot(_ == newParentId) // avoid creating self-loops
      .map { subjectId =>
        // if subject was not deleted in one of its parents => keep it
        // if it was deleted, take the latest deletion date
        val subjectIdx = graph.idToIdx(subjectId)
        val deletedAt = if(subjectIdx == -1) None else graph.latestDeletedAt(subjectIdx)

        Edge.Child(newParentId, EdgeData.Child(deletedAt, None), subjectId)
      }(breakOut)

    val cycleFreeSubjects: Iterable[NodeId] = subjectIds.filterNot(subject => subject == newParentId || (graph.ancestors(newParentId) contains subject)) // avoid self loops and cycles
    val removeParentships = ((
      for {
        subject <- cycleFreeSubjects
        parent <- graph.parents(subject)
        subjectRole = graph.nodesById(subject).role
        parentRole = graph.nodesById(parent).role
        // moving nodes should keep its tags. Except for tags itself.
        if subjectRole == NodeRole.Tag || (subjectRole != NodeRole.Tag && parentRole != NodeRole.Tag)
      } yield Edge.Child(ParentId(parent), ChildId(subject))
    )(breakOut): collection.Set[Edge]) -- newParentships

    GraphChanges(addEdges = newParentships, delEdges = removeParentships)
  }


  // these moveInto methods are only used for drag&drop right now. Can we unify them with the above moveInto?
  @inline def moveInto(nodeId: ChildId, newParentId: ParentId, graph:Graph): GraphChanges = moveInto(nodeId :: Nil, newParentId :: Nil, graph)
  @inline def moveInto(nodeId: Iterable[ChildId], newParentId: ParentId, graph:Graph): GraphChanges = moveInto(nodeId, newParentId :: Nil, graph)
  @inline def moveInto(nodeId: ChildId, newParentIds: Iterable[ParentId], graph:Graph): GraphChanges = moveInto(nodeId :: Nil, newParentIds, graph)
  @inline def moveInto(nodeIds: Iterable[ChildId], newParentIds: Iterable[ParentId], graph:Graph): GraphChanges = {
    GraphChanges.moveInto(graph, nodeIds, newParentIds)
  }
  def movePinnedChannel(channelId: ChildId, targetChannelId: Option[ParentId], graph: Graph, userId: UserId): GraphChanges = {
    val channelIdx = graph.idToIdx(channelId)
    val directParentsInChannelTree = graph.parentsIdx(channelIdx).collect {
      case parentIdx if graph.anyAncestorIsPinned(graph.nodeIds(parentIdx) :: Nil, userId) => ParentId(graph.nodeIds(parentIdx))
    }

    val disconnect: GraphChanges = GraphChanges.disconnect(Edge.Child)(directParentsInChannelTree, channelId)
    val connect: GraphChanges = targetChannelId.fold(GraphChanges.empty) {
      targetChannelId => GraphChanges.connect(Edge.Child)(targetChannelId, channelId)
    }
    disconnect merge connect
  }

  @inline def linkOrCopyInto(edge: Edge.LabeledProperty, nodeId: NodeId, graph:Graph): GraphChanges = {
    graph.nodesById(edge.propertyId) match {
      case node: Node.Content if node.role == NodeRole.Neutral =>
        val copyNode = node.copy(id = NodeId.fresh)
        GraphChanges(
          addNodes = Set(copyNode),
          addEdges = Set(edge.copy(nodeId = nodeId, propertyId = PropertyId(copyNode.id)))
        )
      case node =>
        GraphChanges(
          addEdges = Set(edge.copy(nodeId = nodeId))
        )
    }
  }

  @inline def linkInto(nodeId: ChildId, tagId: ParentId, graph:Graph): GraphChanges = linkInto(nodeId :: Nil, tagId, graph)
  @inline def linkInto(nodeId: ChildId, tagIds: Iterable[ParentId], graph:Graph): GraphChanges = linkInto(nodeId :: Nil, tagIds, graph)
  @inline def linkInto(nodeIds: Iterable[ChildId], tagId: ParentId, graph:Graph): GraphChanges = linkInto(nodeIds, tagId :: Nil, graph)
  def linkInto(nodeIds: Iterable[ChildId], tagIds: Iterable[ParentId], graph:Graph): GraphChanges = {
    // tags will be added with the same (latest) deletedAt date, which the node already has for other parents
    nodeIds.foldLeft(GraphChanges.empty) { (currentChange, nodeId) =>
      val subjectIdx = graph.idToIdx(nodeId)
      val deletedAt = if(subjectIdx == -1) None else graph.latestDeletedAt(subjectIdx)
      currentChange merge GraphChanges.connect((s, d, t) => new Edge.Child(s, d, t))(tagIds, EdgeData.Child(deletedAt, None), nodeIds)
    }
  }


  @inline def linkOrMoveInto(nodeId: ChildId, newParentId: ParentId, graph:Graph, link:Boolean): GraphChanges = linkOrMoveInto(nodeId :: Nil, newParentId :: Nil, graph, link)
  @inline def linkOrMoveInto(nodeId: Iterable[ChildId], newParentId: ParentId, graph:Graph, link:Boolean): GraphChanges = linkOrMoveInto(nodeId, newParentId :: Nil, graph, link)
  @inline def linkOrMoveInto(nodeId: ChildId, newParentIds: Iterable[ParentId], graph:Graph, link:Boolean): GraphChanges = linkOrMoveInto(nodeId :: Nil, newParentIds, graph, link)
  @inline def linkOrMoveInto(nodeId: Iterable[ChildId], newParentId: Iterable[ParentId], graph:Graph, link:Boolean): GraphChanges = {
    if(link) linkInto(nodeId, newParentId, graph)
    else moveInto(nodeId, newParentId, graph)
  }


  @inline def assign(nodeId: NodeId, userId: UserId): GraphChanges = {
    GraphChanges.connect(Edge.Assigned)(nodeId, userId)
  }

  def connectWithProperty(sourceId:NodeId, propertyName:String, targetId:NodeId) = {
    connect(Edge.LabeledProperty)(sourceId, EdgeData.LabeledProperty(propertyName), PropertyId(targetId))
  }
}
