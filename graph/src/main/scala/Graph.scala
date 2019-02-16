package wust.graph

import wust.ids._
import wust.util._
import wust.util.algorithm._
import wust.util.collection._
import wust.util.time.time

import scala.collection.{ breakOut, mutable }
import scala.collection.immutable
import scala.collection
import flatland._

object Graph {
  val empty = new Graph(Array.empty, Array.empty)
  val doneText: String = "Done"
  val doneTextLower: String = doneText.toLowerCase

  def apply(nodes: Iterable[Node] = Nil, edges: Iterable[Edge] = Nil): Graph = {
    new Graph(nodes.toArray, edges.toArray)
  }

  @inline implicit def graphToGraphLookup(graph: Graph): GraphLookup = graph.lookup
}

//TODO: this is only a case class because julius is too  lazy to write a custom encoder/decoder for boopickle and circe
final case class Graph(nodes: Array[Node], edges: Array[Edge]) {

  // because it is a case class, we overwrite equals and hashcode, because we do not want comparisons here.
  override def hashCode(): Int = super.hashCode()
  override def equals(that: Any): Boolean = super.equals(that)

  lazy val lookup = GraphLookup(this, nodes, edges)

  @inline def isEmpty: Boolean = nodes.isEmpty
  @inline def nonEmpty: Boolean = !isEmpty
  @inline def size: Int = nodes.length
  @inline def length: Int = size

  @inline def nodeStr(nodeIdx: Int): String = {
    val node = nodes(nodeIdx)
    s"""[${node.id.shortHumanReadable}]"${node.str}\""""
  }
  @inline def nodeStrDetail(nodeIdx: Int): String = {
    val node = nodes(nodeIdx)
    s"""$nodeIdx ${nodeStr(nodeIdx)}:${node.tpe}/${node.role}"""
  }

  @inline def edgeStr(edgeIdx: Int): String = {
    val edge = edges(edgeIdx)
    val sourceIdx = lookup.edgesIdx.a(edgeIdx)
    val targetIdx = lookup.edgesIdx.b(edgeIdx)
    s"""${nodeStr(sourceIdx)} -${edge.data.toString}-> ${nodeStr(targetIdx)}"""
  }

  def toDetailedString: String = {
    def nodeStr(nodeIdx: Int): String = {
      val node = nodes(nodeIdx)
      s"${nodeStrDetail(nodeIdx)}:${node.meta.accessLevel}  ${node.id.toBase58}  ${node.id.toUuid}"
    }

    s"Graph(\n" +
      s"${nodes.indices.map(nodeStr).mkString("\t", "\n\t", "\n")}\n" +
      s"${edges.indices.map(edgeStr).mkString("\t", "\n\t", "\n")}" +
      ")"
  }

  override def toString: String = {
    s"Graph(nodes: ${nodes.length}, edges: ${edges.length})"
  }

  def debug(node: Node): String = lookup.idToIdxGet(node.id).map(debug).toString
  def debug(nodeId: NodeId): String = lookup.idToIdxGet(nodeId).map(debug).toString
  def debug(nodeIds: Iterable[NodeId]): String = nodeIds.map(debug).mkString(", ")
  def debug(nodeIdx: Int): String = nodeStr(nodeIdx)
  def debug(nodesIdx: Seq[Int]): String = nodesIdx.map(debug).mkString(", ")

  def subset(p: Int => Boolean): ArraySet = {
    val set = ArraySet.create(nodes.length)
    nodes.foreachIndex{ i =>
      if (p(i)) set.add(i)
    }
    set
  }

  @deprecated("Be aware that you are constructing a new graph here.", "")
  def pageContent(page: Page): Graph = {
    val pageChildren = page.parentId.fold(Seq.empty[NodeId])(lookup.descendants)
    this.filterIds(pageChildren.toSet)
  }

  @deprecated("Be aware that you are constructing a new graph here.", "")
  def filterIds(p: NodeId => Boolean): Graph = filter(node => p(node.id))
  @deprecated("Be aware that you are constructing a new graph here.", "")
  def filter(p: Node => Boolean): Graph = {
    // we only want to call p once for each node
    // and not trigger the pre-caching machinery of nodeIds
    val filteredNodes = nodes.filter(p)

    @inline def nothingFiltered = filteredNodes.length == nodes.length

    if (nothingFiltered) this
    else {
      val filteredNodeIds: Set[NodeId] = filteredNodes.map(_.id)(breakOut)
      new Graph(
        nodes = filteredNodes,
        edges = edges.filter(e => filteredNodeIds(e.sourceId) && filteredNodeIds(e.targetId))
      )
    }
  }

  @deprecated("Be aware that you are constructing a new graph here.", "")
  def filterNotIds(p: NodeId => Boolean): Graph = filterIds(id => !p(id))
  @deprecated("Be aware that you are constructing a new graph here.", "")
  def filterNot(p: Node => Boolean): Graph = filter(node => !p(node))

  def applyChangesWithUser(user: Node.User, c: GraphChanges): Graph = {
    val addNodes = if (c.addNodes.exists(_.id == user.id)) c.addNodes else c.addNodes ++ Set(user) // do not add author of change if the node was updated, the author might be outdated.
    changeGraphInternal(addNodes = addNodes, addEdges = c.addEdges, deleteEdges = c.delEdges)
  }
  def applyChanges(c: GraphChanges): Graph = changeGraphInternal(addNodes = c.addNodes, addEdges = c.addEdges, deleteEdges = c.delEdges)

  def replaceNode(oldNodeId: NodeId, newNode: Node): Graph = {
    val newNodes = Array.newBuilder[Node]
    newNodes += newNode
    this.nodes.foreach { n =>
      if (n.id != oldNodeId && n.id != newNode.id) {
        newNodes += n
      }
    }

    val newEdges = this.edges.map { e =>
      if (e.sourceId == oldNodeId && e.targetId == oldNodeId) e.copyId(sourceId = newNode.id, targetId = newNode.id)
      else if (e.sourceId == oldNodeId) e.copyId(sourceId = newNode.id, targetId = e.targetId)
      else if (e.targetId == oldNodeId) e.copyId(sourceId = e.sourceId, targetId = newNode.id)
      else e
    }

    Graph(newNodes.result(), newEdges)
  }

  private def changeGraphInternal(addNodes: collection.Set[Node], addEdges: collection.Set[Edge], deleteEdges: collection.Set[Edge] = Set.empty): Graph = {
    val nodesBuilder = mutable.ArrayBuilder.make[Node]()
    val edgesBuilder = mutable.ArrayBuilder.make[Edge]()
    // nodesBuilder.sizeHint(nodes.length + addNodes.size)
    // edgesBuilder.sizeHint(edges.length + addEdges.size)

    val addNodeIds: Set[NodeId] = addNodes.map(_.id)(breakOut)
    val addEdgeIds: Set[(NodeId, String, NodeId)] = addEdges.collect {
      // we filter out edges without a unique constraint.
      // this needs to correspond how it is defined in the database.
      case e if !e.isInstanceOf[Edge.Author] => (e.sourceId, e.data.tpe, e.targetId)
    }(breakOut)
    val deleteEdgeIds: Set[(NodeId, String, NodeId)] = deleteEdges.map { e => (e.sourceId, e.data.tpe, e.targetId) }(breakOut)
    val updatedEdgeIds = addEdgeIds ++ deleteEdgeIds

    nodes.foreach { node =>
      if (!addNodeIds(node.id)) nodesBuilder += node
    }
    addNodes.foreach { node =>
      nodesBuilder += node
    }
    edges.foreach { edge =>
      if (!updatedEdgeIds((edge.sourceId, edge.data.tpe, edge.targetId))) edgesBuilder += edge
    }
    addEdges.foreach { edge =>
      edgesBuilder += edge
    }

    new Graph(
      nodes = nodesBuilder.result(),
      edges = edgesBuilder.result()
    )
  }

  @deprecated("Be aware that you are constructing a new graph here.", "")
  def removeNodes(nids: Iterable[NodeId]): Graph = filterNotIds(nids.toSet)

  @deprecated("Be aware that you are constructing a new graph here.", "")
  def removeEdges(es: Iterable[Edge]): Graph = new Graph(nodes = nodes, edges = edges.filterNot(es.toSet))

  @deprecated("Be aware that you are constructing a new graph here.", "")
  def addNodes(newNodes: Iterable[Node]): Graph = new Graph(nodes = nodes ++ newNodes, edges = edges)

  @deprecated("Be aware that you are constructing a new graph here.", "")
  def addEdges(newEdges: Iterable[Edge]): Graph = new Graph(nodes = nodes, edges = edges ++ newEdges)
}

final case class RoleStats(roleCounts: List[(NodeRole, Int)]) {
  lazy val mostCommonRole: NodeRole = roleCounts.maxBy(_._2)._1
  lazy val active: List[(NodeRole, Int)] = roleCounts.filter(_._2 > 0)
  def contains(role: NodeRole): Boolean = active.exists(_._1 == role)
}

final case class GraphLookup(graph: Graph, nodes: Array[Node], edges: Array[Edge]) {

  @inline private def n = nodes.length
  @inline private def m = edges.length

  def createArraySet(ids: Iterable[NodeId]): ArraySet = {
    val marker = ArraySet.create(n)
    ids.foreach { id =>
      val idx = idToIdx(id)
      if (idx != -1)
        marker.add(idx)
    }
    marker
  }

  def createImmutableBitSet(ids: Iterable[NodeId]): immutable.BitSet = {
    val builder = immutable.BitSet.newBuilder
    ids.foreach { id =>
      val idx = idToIdx(id)
      if (idx != -1)
        builder += idx
    }
    builder.result()
  }

  private val _idToIdx = mutable.HashMap.empty[NodeId, Int]
  _idToIdx.sizeHint(n)
  val nodeIds = new Array[NodeId](n)

  nodes.foreachIndexAndElement { (i, node) =>
    val nodeId = node.id
    _idToIdx(nodeId) = i
    nodeIds(i) = nodeId
  }

  def idToIdxOrThrow(id: NodeId): Int = _idToIdx.getOrElse(id, throw new Exception(s"Id '$id' not found in graph"))
  val idToIdx: collection.Map[NodeId, Int] = _idToIdx.withDefaultValue(-1)
  @inline def idToIdxGet(nodeId: NodeId): Option[Int] = {
    val idx = idToIdx(nodeId)
    if (idx == -1) None
    else Some(idx)
  }
  @inline def nodesById(nodeId: NodeId): Node = nodes(idToIdx(nodeId))
  @inline def nodesByIdGet(nodeId: NodeId): Option[Node] = {
    val idx = idToIdx(nodeId)
    if (idx == -1) None
    else Some(nodes(idx))
  }

  @inline def contains(nodeId: NodeId): Boolean = idToIdx.isDefinedAt(nodeId)

  assert(idToIdx.size == nodes.length, s"nodes are not distinct by id: ${graph.toDetailedString}")

  private val emptyNodeIdSet = Set.empty[NodeId]
  private val consistentEdges = ArraySet.create(edges.length)
  val edgesIdx = InterleavedArray.create[Int](edges.length)

  // TODO: have one big triple nested array for all edge lookups?

  // To avoid array builders for each node, we collect the node degrees in a
  // loop and then add the indices in a second loop. This is twice as fast
  // than using one loop with arraybuilders. (A lot less allocations)
  private val outDegree = new Array[Int](n)
  private val parentsDegree = new Array[Int](n)
  private val childrenDegree = new Array[Int](n)
  private val messageChildrenDegree = new Array[Int](n)
  private val taskChildrenDegree = new Array[Int](n)
  private val projectChildrenDegree = new Array[Int](n)
  private val tagChildrenDegree = new Array[Int](n)
  private val tagParentsDegree = new Array[Int](n)
  private val notDeletedParentsDegree = new Array[Int](n)
  private val notDeletedChildrenDegree = new Array[Int](n)
  private val inDeletedGracePeriodDegree = new Array[Int](n)
  private val futureDeletedParentsDegree = new Array[Int](n)
  private val authorshipDegree = new Array[Int](n)
  private val membershipsForNodeDegree = new Array[Int](n)
  private val notifyByUserDegree = new Array[Int](n)
  private val pinnedNodeDegree = new Array[Int](n)
  private val inviteNodeDegree = new Array[Int](n)
  private val expandedNodesDegree = new Array[Int](n)
  private val assignedNodesDegree = new Array[Int](n)
  private val assignedUsersDegree = new Array[Int](n)
  private val propertiesDegree = new Array[Int](n)
  private val automatedDegree = new Array[Int](n)
  private val automatedReverseDegree = new Array[Int](n)
  private val derivedFromTemplateDegree = new Array[Int](n)
  private val derivedFromTemplateReverseDegree = new Array[Int](n)

  private val now = EpochMilli.now
  private val remorseTimeForDeletedParents: EpochMilli = EpochMilli(now - (24 * 3600 * 1000))

  edges.foreachIndexAndElement { (edgeIdx, edge) =>
    val sourceIdx = idToIdx(edge.sourceId)
    if (sourceIdx != -1) {
      val targetIdx = idToIdx(edge.targetId)
      if (targetIdx != -1) {
        consistentEdges.add(edgeIdx)
        edgesIdx.updatea(edgeIdx, sourceIdx)
        edgesIdx.updateb(edgeIdx, targetIdx)
        outDegree(sourceIdx) += 1
        edge match {
          case _: Edge.Author =>
            authorshipDegree(targetIdx) += 1
          case _: Edge.Member =>
            membershipsForNodeDegree(targetIdx) += 1
          case e: Edge.Parent =>
            val childIsMessage = nodes(sourceIdx).role == NodeRole.Message
            val childIsTask = nodes(sourceIdx).role == NodeRole.Task
            val childIsProject = nodes(sourceIdx).role == NodeRole.Project
            val childIsTag = nodes(sourceIdx).role == NodeRole.Tag
            val parentIsTag = nodes(targetIdx).role == NodeRole.Tag
            parentsDegree(sourceIdx) += 1
            childrenDegree(targetIdx) += 1
            e.data.deletedAt match {
              case None =>
                if (childIsMessage) messageChildrenDegree(targetIdx) += 1
                if (childIsTask) taskChildrenDegree(targetIdx) += 1
                if (childIsProject) projectChildrenDegree(targetIdx) += 1
                if (childIsTag) tagChildrenDegree(targetIdx) += 1
                if (parentIsTag) tagParentsDegree(sourceIdx) += 1
                notDeletedParentsDegree(sourceIdx) += 1
                notDeletedChildrenDegree(targetIdx) += 1
              case Some(deletedAt) =>
                if (deletedAt isAfter now) { // in the future
                  if (childIsMessage) messageChildrenDegree(targetIdx) += 1
                  if (childIsTask) taskChildrenDegree(targetIdx) += 1
                  if (childIsProject) projectChildrenDegree(targetIdx) += 1
                  if (childIsTag) tagChildrenDegree(targetIdx) += 1
                  if (parentIsTag) tagParentsDegree(sourceIdx) += 1
                  notDeletedParentsDegree(sourceIdx) += 1
                  notDeletedChildrenDegree(targetIdx) += 1
                  futureDeletedParentsDegree(sourceIdx) += 1
                } else if (deletedAt isAfter remorseTimeForDeletedParents) { // less than 24h in the past
                  inDeletedGracePeriodDegree(sourceIdx) += 1
                }
              // TODO everything deleted further in the past should already be filtered in backend
              // BUT received on request
            }
          case _: Edge.Assigned =>
            assignedNodesDegree(sourceIdx) += 1
            assignedUsersDegree(targetIdx) += 1
          case _: Edge.Expanded            =>
            expandedNodesDegree(sourceIdx) += 1
          case _: Edge.Notify              =>
            notifyByUserDegree(targetIdx) += 1
          case _: Edge.Pinned              =>
            pinnedNodeDegree(sourceIdx) += 1
          case _: Edge.Invite              =>
            inviteNodeDegree(sourceIdx) += 1
          case _: Edge.LabeledProperty     =>
            propertiesDegree(sourceIdx) += 1
          case _: Edge.Automated           =>
            automatedDegree(sourceIdx) += 1
            automatedReverseDegree(targetIdx) += 1
          case _: Edge.DerivedFromTemplate =>
            derivedFromTemplateDegree(sourceIdx) += 1
            derivedFromTemplateReverseDegree(targetIdx) += 1
          case _                           =>
        }
      }
    }
  }

  private val outgoingEdgeIdxBuilder = NestedArrayInt.builder(outDegree)
  private val parentsIdxBuilder = NestedArrayInt.builder(parentsDegree)
  private val parentEdgeIdxBuilder = NestedArrayInt.builder(parentsDegree)
  private val childrenIdxBuilder = NestedArrayInt.builder(childrenDegree)
  private val messageChildrenIdxBuilder = NestedArrayInt.builder(messageChildrenDegree)
  private val taskChildrenIdxBuilder = NestedArrayInt.builder(taskChildrenDegree)
  private val projectChildrenIdxBuilder = NestedArrayInt.builder(projectChildrenDegree)
  private val tagChildrenIdxBuilder = NestedArrayInt.builder(tagChildrenDegree)
  private val tagParentsIdxBuilder = NestedArrayInt.builder(tagParentsDegree)
  private val notDeletedParentsIdxBuilder = NestedArrayInt.builder(notDeletedParentsDegree)
  private val notDeletedChildrenIdxBuilder = NestedArrayInt.builder(notDeletedChildrenDegree)
  private val inDeletedGracePeriodParentsIdxBuilder = NestedArrayInt.builder(inDeletedGracePeriodDegree)
  private val futureDeletedParentsIdxBuilder = NestedArrayInt.builder(futureDeletedParentsDegree)
  private val authorshipEdgeIdxBuilder = NestedArrayInt.builder(authorshipDegree)
  private val authorIdxBuilder = NestedArrayInt.builder(authorshipDegree)
  private val membershipEdgeForNodeIdxBuilder = NestedArrayInt.builder(membershipsForNodeDegree)
  private val notifyByUserIdxBuilder = NestedArrayInt.builder(notifyByUserDegree)
  private val pinnedNodeIdxBuilder = NestedArrayInt.builder(pinnedNodeDegree)
  private val inviteNodeIdxBuilder = NestedArrayInt.builder(inviteNodeDegree)
  private val expandedNodesIdxBuilder = NestedArrayInt.builder(expandedNodesDegree)
  private val assignedNodesIdxBuilder = NestedArrayInt.builder(assignedNodesDegree)
  private val assignedUsersIdxBuilder = NestedArrayInt.builder(assignedUsersDegree)
  private val propertiesEdgeIdxBuilder = NestedArrayInt.builder(propertiesDegree)
  private val automatedEdgeIdxBuilder = NestedArrayInt.builder(automatedDegree)
  private val automatedEdgeReverseIdxBuilder = NestedArrayInt.builder(automatedReverseDegree)
  private val derivedFromTemplateEdgeIdxBuilder = NestedArrayInt.builder(derivedFromTemplateDegree)
  private val derivedFromTemplateRerverseEdgeIdxBuilder = NestedArrayInt.builder(derivedFromTemplateReverseDegree)

  consistentEdges.foreach { edgeIdx =>
    val sourceIdx = edgesIdx.a(edgeIdx)
    val targetIdx = edgesIdx.b(edgeIdx)
    val edge = edges(edgeIdx)
    outgoingEdgeIdxBuilder.add(sourceIdx, edgeIdx)
    edge match {
      case _: Edge.Author =>
        authorshipEdgeIdxBuilder.add(targetIdx, edgeIdx)
        authorIdxBuilder.add(targetIdx, sourceIdx)
      case _: Edge.Member =>
        membershipEdgeForNodeIdxBuilder.add(targetIdx, edgeIdx)
      case e: Edge.Parent =>
        val childIsMessage = nodes(sourceIdx).role == NodeRole.Message
        val childIsTask = nodes(sourceIdx).role == NodeRole.Task
        val childIsTag = nodes(sourceIdx).role == NodeRole.Tag
        val childIsProject = nodes(sourceIdx).role == NodeRole.Project
        val parentIsTag = nodes(targetIdx).role == NodeRole.Tag
        parentsIdxBuilder.add(sourceIdx, targetIdx)
        parentEdgeIdxBuilder.add(sourceIdx, edgeIdx)
        childrenIdxBuilder.add(targetIdx, sourceIdx)
        e.data.deletedAt match {
          case None =>
            if (childIsMessage) messageChildrenIdxBuilder.add(targetIdx, sourceIdx)
            if (childIsTask) taskChildrenIdxBuilder.add(targetIdx, sourceIdx)
            if (childIsTag) tagChildrenIdxBuilder.add(targetIdx, sourceIdx)
            if (childIsProject) projectChildrenIdxBuilder.add(targetIdx, sourceIdx)
            if (parentIsTag) tagParentsIdxBuilder.add(sourceIdx, targetIdx)
            notDeletedParentsIdxBuilder.add(sourceIdx, targetIdx)
            notDeletedChildrenIdxBuilder.add(targetIdx, sourceIdx)
          case Some(deletedAt) =>
            if (deletedAt isAfter now) { // in the future
              if (childIsMessage) messageChildrenIdxBuilder.add(targetIdx, sourceIdx)
              if (childIsTask) taskChildrenIdxBuilder.add(targetIdx, sourceIdx)
              if (childIsTag) tagChildrenIdxBuilder.add(targetIdx, sourceIdx)
              if (childIsProject) projectChildrenIdxBuilder.add(targetIdx, sourceIdx)
              if (parentIsTag) tagParentsIdxBuilder.add(sourceIdx, targetIdx)
              notDeletedParentsIdxBuilder.add(sourceIdx, targetIdx)
              notDeletedChildrenIdxBuilder.add(targetIdx, sourceIdx)
              futureDeletedParentsIdxBuilder.add(sourceIdx, targetIdx)
            } else if (deletedAt isAfter remorseTimeForDeletedParents) { // less than 24h in the past
              inDeletedGracePeriodParentsIdxBuilder.add(sourceIdx, targetIdx)
            }
          // TODO everything deleted further in the past should already be filtered in backend
          // BUT received on request
        }
      case _: Edge.Expanded =>
        expandedNodesIdxBuilder.add(sourceIdx, targetIdx)
      case _: Edge.Assigned =>
        assignedNodesIdxBuilder.add(sourceIdx, targetIdx)
        assignedUsersIdxBuilder.add(targetIdx, sourceIdx)
      case _: Edge.Notify              =>
        notifyByUserIdxBuilder.add(targetIdx, sourceIdx)
      case _: Edge.Pinned              =>
        pinnedNodeIdxBuilder.add(sourceIdx, targetIdx)
      case _: Edge.Invite              =>
        inviteNodeIdxBuilder.add(sourceIdx, targetIdx)
      case _: Edge.LabeledProperty     =>
        propertiesEdgeIdxBuilder.add(sourceIdx, edgeIdx)
      case _: Edge.Automated           =>
        automatedEdgeIdxBuilder.add(sourceIdx, edgeIdx)
        automatedEdgeReverseIdxBuilder.add(targetIdx, edgeIdx)
      case _: Edge.DerivedFromTemplate =>
        derivedFromTemplateEdgeIdxBuilder.add(sourceIdx, edgeIdx)
        derivedFromTemplateRerverseEdgeIdxBuilder.add(targetIdx, edgeIdx)
      case _                           =>
    }
  }

  val outgoingEdgeIdx: NestedArrayInt = outgoingEdgeIdxBuilder.result()
  val parentsIdx: NestedArrayInt = parentsIdxBuilder.result()
  val parentEdgeIdx: NestedArrayInt = parentEdgeIdxBuilder.result()
  val childrenIdx: NestedArrayInt = childrenIdxBuilder.result()
  val messageChildrenIdx: NestedArrayInt = messageChildrenIdxBuilder.result()
  val taskChildrenIdx: NestedArrayInt = taskChildrenIdxBuilder.result()
  val tagChildrenIdx: NestedArrayInt = tagChildrenIdxBuilder.result()
  val projectChildrenIdx: NestedArrayInt = projectChildrenIdxBuilder.result()
  val tagParentsIdx: NestedArrayInt = tagParentsIdxBuilder.result()
  val notDeletedParentsIdx: NestedArrayInt = notDeletedParentsIdxBuilder.result()
  val notDeletedChildrenIdx: NestedArrayInt = notDeletedChildrenIdxBuilder.result()
  val inDeletedGracePeriodParentIdx: NestedArrayInt = inDeletedGracePeriodParentsIdxBuilder.result()
  val futureDeletedParentsIdx: NestedArrayInt = futureDeletedParentsIdxBuilder.result()
  val authorshipEdgeIdx: NestedArrayInt = authorshipEdgeIdxBuilder.result()
  val membershipEdgeForNodeIdx: NestedArrayInt = membershipEdgeForNodeIdxBuilder.result()
  val notifyByUserIdx: NestedArrayInt = notifyByUserIdxBuilder.result()
  val authorsIdx: NestedArrayInt = authorIdxBuilder.result()
  val pinnedNodeIdx: NestedArrayInt = pinnedNodeIdxBuilder.result()
  val inviteNodeIdx: NestedArrayInt = inviteNodeIdxBuilder.result()
  val expandedNodesIdx: NestedArrayInt = expandedNodesIdxBuilder.result()
  val assignedNodesIdx: NestedArrayInt = assignedNodesIdxBuilder.result() // user -> node
  val assignedUsersIdx: NestedArrayInt = assignedUsersIdxBuilder.result() // node -> user
  val propertiesEdgeIdx: NestedArrayInt = propertiesEdgeIdxBuilder.result() // node -> property edge
  val automatedEdgeIdx: NestedArrayInt = automatedEdgeIdxBuilder.result()
  val automatedEdgeReverseIdx: NestedArrayInt = automatedEdgeReverseIdxBuilder.result()
  val derivedFromTemplateEdgeIdx: NestedArrayInt = derivedFromTemplateEdgeIdxBuilder.result()
  val derivedFromTemplateReverseEdgeIdx: NestedArrayInt = derivedFromTemplateRerverseEdgeIdxBuilder.result()

  def propertyPairIdx(subjectIdx: Int): IndexedSeq[(Edge.LabeledProperty, Node)] = propertiesEdgeIdx(subjectIdx).map(graph.edges(_).asInstanceOf[Edge.LabeledProperty]).map(e => (e, graph.nodesById(e.targetId)))
  val expandedNodesByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if (idx != -1) expandedNodesIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  @inline def expandedNodes(userId: UserId): collection.Set[NodeId] = expandedNodesByIndex(idToIdx(userId))
  val parentsByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if (idx != -1) parentsIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  val notDeletedParentsByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if (idx != -1) notDeletedParentsIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  @inline def isExpanded(userId: UserId, nodeId: NodeId): Boolean = expandedNodes(userId).contains(nodeId)
  @inline def parents(nodeId: NodeId): collection.Set[NodeId] = parentsByIndex(idToIdx(nodeId))
  @inline def notDeletedParents(nodeId: NodeId): collection.Set[NodeId] = notDeletedParentsByIndex(idToIdx(nodeId))
  val childrenByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if (idx != -1) childrenIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  @inline def children(nodeId: NodeId): collection.Set[NodeId] = childrenByIndex(idToIdx(nodeId))

  @inline def isPinned(idx: Int, userIdx: Int): Boolean = pinnedNodeIdx.contains(userIdx)(idx)

  def templateNodes(idx: Int): Seq[Node] = {
    val automatedIdxs = graph.automatedEdgeIdx(idx)
    automatedIdxs.map { automatedIdx =>
      val targetIdx = graph.edgesIdx.b(automatedIdx)
      graph.nodes(targetIdx)
    }
  }
  def automatedNodes(idx: Int): Seq[Node] = {
    val automatedIdxs = graph.automatedEdgeReverseIdx(idx)
    automatedIdxs.map { automatedIdx =>
      val sourceIdx = graph.edgesIdx.a(automatedIdx)
      graph.nodes(sourceIdx)
    }
  }

  val sortedAuthorshipEdgeIdx: NestedArrayInt = NestedArrayInt.apply(authorshipEdgeIdx.map(slice => slice.sortBy(author => edges(author).asInstanceOf[Edge.Author].data.timestamp).toArray).toArray)

  // not lazy because it often used for sorting. and we do not want to compute a lazy val in a for loop.
  val (nodeCreated: Array[EpochMilli], nodeCreatorIdx: Array[Int], nodeModified: Array[EpochMilli]) = {
    val nodeCreated = Array.fill(n)(EpochMilli.min)
    val nodeCreator = new Array[Int](n)
    val nodeModified = Array.fill(n)(EpochMilli.min)
    var nodeIdx = 0
    while (nodeIdx < n) {
      val authorEdgeIndices: ArraySliceInt = sortedAuthorshipEdgeIdx(nodeIdx)
      if (authorEdgeIndices.nonEmpty) {
        val (createdEdgeIdx, lastModifierEdgeIdx) = (authorEdgeIndices.head, authorEdgeIndices.last)
        nodeCreated(nodeIdx) = edges(createdEdgeIdx).asInstanceOf[Edge.Author].data.timestamp
        nodeCreator(nodeIdx) = edgesIdx.a(createdEdgeIdx)
        nodeModified(nodeIdx) = edges(lastModifierEdgeIdx).asInstanceOf[Edge.Author].data.timestamp
      } else {
        nodeCreator(nodeIdx) = -1
      }
      nodeIdx += 1
    }
    (nodeCreated, nodeCreator, nodeModified)
  }

  def nodeCreator(idx: Int): Option[Node.User] = {
    nodeCreatorIdx(idx) match {
      case -1        => None
      case authorIdx => Option(nodes(authorIdx).asInstanceOf[Node.User])
    }
  }

  def nodeModifier(idx: Int): IndexedSeq[(Node.User, EpochMilli)] = {
    val numAuthors = sortedAuthorshipEdgeIdx(idx).length
    if (numAuthors > 1) {
      sortedAuthorshipEdgeIdx(idx).tail.map{ eIdx =>
        val user = nodes(edgesIdx.a(eIdx)).asInstanceOf[Node.User]
        val time = edges(eIdx).asInstanceOf[Edge.Author].data.timestamp
        (user, time)
      }
    } else IndexedSeq.empty[(Node.User, EpochMilli)]
  }

  def topLevelRoleStats(parentIds: Iterable[NodeId]): RoleStats = {
    var messageCount = 0
    var taskCount = 0
    parentIds.foreach { nodeId =>
      val nodeIdx = idToIdx(nodeId)
      if (nodeIdx != -1) {
        notDeletedChildrenIdx.foreachElement(nodeIdx) { childIdx =>
          nodes(childIdx).role match {
            case NodeRole.Message => messageCount += 1
            case NodeRole.Task    => taskCount += 1
            case _                =>
          }
        }
      }
    }
    RoleStats(List(NodeRole.Message -> messageCount, NodeRole.Task -> taskCount))
  }

  def filterIdx(p: Int => Boolean): Graph = {
    // we only want to call p once for each node
    // and not trigger the pre-caching machinery of nodeIds
    val (filteredNodesIndices, retained) = nodes.filterIdxToArraySet(p)

    @inline def nothingFiltered = retained == nodes.length

    if (nothingFiltered) graph
    else {
      val filteredNodeIds: Set[NodeId] = filteredNodesIndices.map(nodeIds)(breakOut)
      val filteredNodes: Set[Node] = filteredNodesIndices.map(nodes)(breakOut)
      Graph(
        nodes = filteredNodes,
        edges = edges.filter(e => filteredNodeIds(e.sourceId) && filteredNodeIds(e.targetId))
      )
    }
  }

  val authorsByIndex: Int => Seq[Node.User] = Memo.arrayMemo[Seq[Node.User]](n).apply { idx =>
    if (idx < 0) Nil
    else authorsIdx(idx).map(idx => nodes(idx).asInstanceOf[Node.User])
  }
  @inline def authors(nodeId: NodeId): Seq[Node.User] = authorsByIndex(idToIdx(nodeId))

  val authorsInByIndex: Int => Seq[Node.User] = Memo.arrayMemo[Seq[Node.User]](n).apply { idx =>
    if (idx < 0) Nil
    else {
      val rootAuthors = authorsByIndex(idx)
      val builder = new mutable.ArrayBuilder.ofRef[Node.User]
      builder.sizeHint(rootAuthors.size)
      builder ++= rootAuthors
      descendantsIdx(idx).foreach { idx =>
        builder ++= authorsByIndex(idx)
      }
      builder.result().distinct
    }
  }
  @inline def authorsIn(nodeId: NodeId): Seq[Node.User] = authorsInByIndex(idToIdx(nodeId))

  val membersByIndex: Int => Seq[Node.User] = Memo.arrayMemo[Seq[Node.User]](n).apply { idx =>
    membershipEdgeForNodeIdx(idx).map(edgeIdx => nodesById(edges(edgeIdx).asInstanceOf[Edge.Member].userId).asInstanceOf[Node.User])
  }
  @inline def members(nodeId: NodeId): Seq[Node.User] = membersByIndex(idToIdx(nodeId))

  def usersInNode(id: NodeId): collection.Set[Node.User] = {
    val builder = new mutable.LinkedHashSet[Node.User]
    val nodeIdx = idToIdx(id)
    val members = membersByIndex(nodeIdx)
    builder ++= members
    depthFirstSearchWithManualAppend(nodeIdx, childrenIdx, append = { idx =>
      builder ++= authorsByIndex(idx)
    })

    builder.result()
  }

  def latestDeletedAt(subjectIdx: Int): Option[EpochMilli] = {
    parentEdgeIdx(subjectIdx).foldLeft(Option.empty[EpochMilli]) { (result, currentEdgeIdx) =>
      val currentDeletedAt = edges(currentEdgeIdx).asInstanceOf[Edge.Parent].data.deletedAt
      (result, currentDeletedAt) match {
        case (None, currentDeletedAt)               => currentDeletedAt
        case (result, None)                         => result
        case (Some(result), Some(currentDeletedAt)) => Some(result newest currentDeletedAt)
      }
    }
  }

  def getRoleParents(nodeId: NodeId, nodeRole: NodeRole): IndexedSeq[NodeId] =
    notDeletedParentsIdx(idToIdx(nodeId)).collect{ case idx if nodes(idx).role == nodeRole => nodeIds(idx) }

  def getRoleParentsIdx(nodeIdx: Int, nodeRole: NodeRole): IndexedSeq[Int] =
    notDeletedParentsIdx(nodeIdx).collect{ case idx if nodes(idx).role == nodeRole => idx }

  def partiallyDeletedParents(nodeId: NodeId): IndexedSeq[Edge.Parent] = graph.parentEdgeIdx(idToIdx(nodeId)).map(edges).flatMap { e =>
    val parentEdge = e.asInstanceOf[Edge.Parent]
    val deleted = parentEdge.data.deletedAt.fold(false)(_ isBefore now)
    if (deleted) Some(parentEdge) else None
  }
  def isPartiallyDeleted(nodeId: NodeId): Boolean = graph.parentEdgeIdx(idToIdx(nodeId)).map(edges).exists{ e => e.asInstanceOf[Edge.Parent].data.deletedAt.fold(false)(_ isBefore now) }

  def isInDeletedGracePeriod(nodeId: NodeId, parent: NodeId): Boolean = isInDeletedGracePeriod(nodeId, Iterable(parent))
  def isInDeletedGracePeriod(nodeId: NodeId, parents: Iterable[NodeId]): Boolean = {
    val nodeIdx = idToIdx(nodeId)
    if (nodeIdx == -1) return false

    @inline def nodeIsDeletedInAtLeastOneParent = inDeletedGracePeriodParentIdx.sliceNonEmpty(nodeIdx)

    @inline def deletedParentSet = {
      val set = ArraySet.create(n)
      inDeletedGracePeriodParentIdx.foreachElement(nodeIdx)(set.add)
      set
    }

    @inline def deletedInAllSpecifiedParentIndices = parents.forall(parent => deletedParentSet.contains(idToIdx(parent)))

    @inline def hasNoParents = parentsIdx.sliceIsEmpty(nodeIdx)

    if (nodeIsDeletedInAtLeastOneParent) {
      if (deletedInAllSpecifiedParentIndices) true
      else hasNoParents
    } else false // node is nowhere deleted
  }

  def isDeletedInFuture(nodeId: NodeId, parents: Iterable[NodeId]): Boolean = {
    val nodeIdx = idToIdx(nodeId)
    if (nodeIdx == -1) return false

    @inline def nodeIsDeletedInAtLeastOneParent = futureDeletedParentsIdx.sliceNonEmpty(nodeIdx)

    @inline def deletedParentSet = {
      val set = ArraySet.create(n)
      futureDeletedParentsIdx.foreachElement(nodeIdx)(set.add)
      set
    }

    @inline def deletedInAllSpecifiedParentIndices = parents.forall(parent => deletedParentSet.contains(idToIdx(parent)))

    @inline def hasNoParents = parentsIdx.sliceIsEmpty(nodeIdx)

    if (nodeIsDeletedInAtLeastOneParent) {
      if (deletedInAllSpecifiedParentIndices) true
      else hasNoParents
    } else false // node is nowhere deleted
  }

  def isInDeletedGracePeriodIdx(nodeIdx: Int, parentIndices: Iterable[Int]): Boolean = {
    @inline def nodeIsDeletedInAtLeastOneParent = inDeletedGracePeriodParentIdx.sliceNonEmpty(nodeIdx)

    @inline def deletedParentSet = {
      val set = ArraySet.create(n)
      inDeletedGracePeriodParentIdx.foreachElement(nodeIdx)(set.add)
      set
    }

    @inline def deletedInAllSpecifiedParentIndices = parentIndices.forall(deletedParentSet.contains)

    @inline def hasNoParents = parentsIdx.sliceIsEmpty(nodeIdx)

    if (nodeIsDeletedInAtLeastOneParent) {
      if (deletedInAllSpecifiedParentIndices) true
      else hasNoParents
    } else false // node is nowhere deleted
  }

  def isInDeletedGracePeriodIdx(nodeIdx: Int, parentIdx: Int): Boolean = {
    @inline def nodeIsDeletedInAtLeastOneParent = inDeletedGracePeriodParentIdx.sliceNonEmpty(nodeIdx)

    @inline def deletedParentSet = {
      val set = ArraySet.create(n)
      inDeletedGracePeriodParentIdx.foreachElement(nodeIdx)(set.add)
      set
    }

    @inline def deletedInAllSpecifiedParentIndices = deletedParentSet.contains(parentIdx)

    @inline def hasNoParents = parentsIdx.sliceIsEmpty(nodeIdx)

    if (nodeIsDeletedInAtLeastOneParent) {
      if (deletedInAllSpecifiedParentIndices) true
      else hasNoParents
    } else false // node is nowhere deleted
  }

  def isDeletedNowIdx(nodeIdx: Int, parentIdx: Int): Boolean = {
    //      !inDeletedGracePeriodParentIdx.contains(nodeIdx)(parentIdx) && !notDeletedParentsIdx.contains(nodeIdx)(parentIdx)
    !notDeletedParentsIdx.contains(nodeIdx)(parentIdx)
  }
  def isDeletedNowIdx(nodeIdx: Int, parentIndices: Iterable[Int]): Boolean = parentIndices.forall(parentIdx => isDeletedNowIdx(nodeIdx, parentIdx))
  def isDeletedNow(nodeId: NodeId, parentId: NodeId): Boolean = isDeletedNowIdx(idToIdx(nodeId), idToIdx(parentId))
  def isDeletedNow(nodeId: NodeId, parentIds: Iterable[NodeId]): Boolean = parentIds.forall(parentId => isDeletedNow(nodeId, parentId))

  def directNodeTags(nodeIdx: Int, parentIndices: collection.Set[Int]): Array[Node] = {
    //      (parents(nodeId).toSet -- (parentIds - nodeId)).map(nodesById) // "- nodeId" reveals self-loops with page-parent

    val tagSet = new mutable.ArrayBuilder.ofRef[Node]

    parentsIdx.foreachElement(nodeIdx) { nodeParentIdx =>
      if (!isInDeletedGracePeriodIdx(nodeIdx, nodeParentIdx)
        && (!parentIndices.contains(nodeParentIdx) || nodeParentIdx == nodeIdx)
        && !isDoneStage(nodes(nodeParentIdx)))
        tagSet += nodes(nodeParentIdx)
    }

    tagSet.result()
  }

  def transitiveNodeTags(nodeIdx: Int, parentIndices: immutable.BitSet): Array[Node] = {
    //      val transitivePageParents = parentIds.flatMap(ancestors)
    //      (ancestors(nodeId).toSet -- parentIds -- transitivePageParents -- parents(nodeId))
    //        .map(nodesById)
    val tagSet = ArraySet.create(n)

    ancestorsIdx(nodeIdx).foreachElement(tagSet.add)
    parentIndices.foreach { parentIdx =>
      tagSet.remove(parentIdx)
      ancestorsIdx(parentIdx).foreachElement(tagSet.remove)
    }
    parentsIdx.foreachElement(nodeIdx)(tagSet.remove)

    tagSet.mapToArray(nodes)
  }

  lazy val chronologicalNodesAscendingIdx: Array[Int] = {
    nodes.indices.toArray.sortBy(nodeCreated)
  }

  lazy val chronologicalNodesAscending: IndexedSeq[Node] = {
    chronologicalNodesAscendingIdx.map(nodes)
  }

  def topologicalSortByIdx[T](seq: Seq[T], extractIdx: T => Int, liftIdx: Int => Option[T]): Seq[T] = {
    if (seq.isEmpty || nodes.isEmpty) return seq

    @inline def idSeq: Seq[Int] = seq.map(extractIdx)
    @inline def idArray: Array[Int] = idSeq.toArray

    val chronological: Array[Int] = idArray.sortBy(nodeCreated)
    //TODO: Sort by ordering idx
    val res: Seq[T] = chronological.map(liftIdx).toSeq.flatten
    res
  }

  lazy val allParentIdsTopologicallySortedByChildren: Array[Int] = {
    val parentSet = ArraySet.create(n)
    edgesIdx.foreachIndexAndTwoElements { (i, _, targetIdx) =>
      if (edges(i).isInstanceOf[Edge.Parent])
        parentSet += targetIdx
    }
    topologicalSort(parentSet.collectAllElements, childrenIdx)
  }

  private lazy val nodeDefaultNeighbourhood: collection.Map[NodeId, Set[NodeId]] =
    defaultNeighbourhood(nodeIds, emptyNodeIdSet)
  @deprecated("Old and slow Graph algorithm. Don't use this.", "")
  lazy val successorsWithoutParent: collection.Map[NodeId, collection.Set[NodeId]] = nodeDefaultNeighbourhood ++ directedAdjacencyList[NodeId, Edge, NodeId](???, _.sourceId, _.targetId)

  def inChildParentRelation(child: NodeId, possibleParent: NodeId): Boolean =
    parents(child).contains(possibleParent)
  def inDescendantAncestorRelation(descendent: NodeId, possibleAncestor: NodeId): Boolean =
    ancestors(descendent).contains(possibleAncestor)

  @inline def hasChildrenIdx(nodeIdx: Int): Boolean = childrenIdx.sliceNonEmpty(nodeIdx)
  @inline def hasParentsIdx(nodeIdx: Int): Boolean = parentsIdx.sliceNonEmpty(nodeIdx)

  @inline def hasNotDeletedChildrenIdx(nodeIdx: Int): Boolean = notDeletedChildrenIdx.sliceNonEmpty(nodeIdx)
  @inline def hasNotDeletedParentsIdx(nodeIdx: Int): Boolean = notDeletedParentsIdx.sliceNonEmpty(nodeIdx)

  @inline private def hasSomethingById(nodeId: NodeId, lookup: Int => Boolean) = {
    val idx = idToIdx(nodeId)
    if (idx == -1)
      false
    else
      lookup(idx)
  }
  def hasChildren(nodeId: NodeId): Boolean = hasSomethingById(nodeId, hasChildrenIdx)
  def hasParents(nodeId: NodeId): Boolean = hasSomethingById(nodeId, hasParentsIdx)

  def hasNotDeletedChildren(nodeId: NodeId): Boolean = hasSomethingById(nodeId, hasNotDeletedChildrenIdx)
  def hasNotDeletedParents(nodeId: NodeId): Boolean = hasSomethingById(nodeId, hasNotDeletedParentsIdx)

  def isChildOfAny(childId: NodeId, parentIds: Iterable[NodeId]): Boolean = {
    val p = parents(childId)
    parentIds.exists(p.contains)
  }

  lazy val incidentParentContainments: collection.Map[NodeId, collection.Set[Edge]] = ???
  lazy val incidentChildContainments: collection.Map[NodeId, collection.Set[Edge]] = ???
  lazy val incidentContainments: collection.Map[NodeId, collection.Set[Edge]] = ???

  val pageFiles: NodeId => Seq[(NodeId, NodeData.File)] = { pageParentId: NodeId =>
    graph.descendantsIdx(graph.idToIdx(pageParentId)).flatMap { nodeIdx =>
      graph.nodes(nodeIdx) match {
        case Node.Content(id, file: NodeData.File, _, _, _) => Some(id -> file)
        case _ => None
      }
    }
  }

  def involvedInContainmentCycleIdx(idx: Int): Boolean = {
    depthFirstSearchExistsAfterStart(idx, childrenIdx, idx)
  }
  def involvedInNotDeletedContainmentCycleIdx(idx: Int): Boolean = {
    depthFirstSearchExistsAfterStart(idx, notDeletedChildrenIdx, idx)
  }
  def involvedInContainmentCycle(id: NodeId): Boolean = {
    val idx = idToIdx(id)
    if (idx == -1) false
    else involvedInContainmentCycleIdx(idx)
  }

  def descendantsIdx(nodeIdx: Int) = _descendantsIdx(nodeIdx)
  private val _descendantsIdx: Int => Array[Int] = Memo.arrayMemo[Array[Int]](n).apply { nodeIdx: Int =>
    depthFirstSearchAfterStart(nodeIdx, childrenIdx)
  }
  def descendants(nodeId: NodeId) = _descendants(idToIdx(nodeId))
  private val _descendants: Int => Seq[NodeId] = Memo.arrayMemo[Seq[NodeId]](n).apply { nodeIdx =>
    if (nodeIdx == -1) Nil
    else descendantsIdx(nodeIdx).map(nodeIds) // instead of dfs, we use already memoized results
  }

  def notDeletedDescendantsIdx(nodeIdx: Int) = _notDeletedDescendantsIdx(nodeIdx)
  private val _notDeletedDescendantsIdx: Int => Array[Int] = Memo.arrayMemo[Array[Int]](n).apply { nodeIdx: Int =>
    depthFirstSearchAfterStart(nodeIdx, notDeletedChildrenIdx)
  }
  def notDeletedDescendants(nodeId: NodeId) = _notDeletedDescendants(idToIdx(nodeId))
  private val _notDeletedDescendants: Int => Seq[NodeId] = Memo.arrayMemo[Seq[NodeId]](n).apply { nodeIdx =>
    if (nodeIdx == -1) Nil
    else notDeletedDescendantsIdx(nodeIdx).map(nodeIds) // instead of dfs, we use already memoized results
  }

  def ancestorsIdx(nodeIdx: Int) = _ancestorsIdx(nodeIdx)
  private val _ancestorsIdx: Int => Array[Int] = Memo.arrayMemo[Array[Int]](n).apply { nodeIdx =>
    depthFirstSearchAfterStart(nodeIdx, parentsIdx)
  }
  def ancestors(nodeId: NodeId) = _ancestors(idToIdx(nodeId))
  private val _ancestors: Int => Seq[NodeId] = Memo.arrayMemo[Seq[NodeId]](n).apply { nodeIdx =>
    if (nodeIdx == -1) Nil
    else ancestorsIdx(nodeIdx).map(nodeIds) // instead of dfs, we use already memoized results
  }

  def anyAncestorIsPinned(nodeIds: Iterable[NodeId], userId: NodeId): Boolean = {
    val userIdx = idToIdx(userId)
    if (userIdx == -1) return false

    val starts = new mutable.ArrayBuilder.ofInt
    nodeIds.foreach { nodeId =>
      val idx = idToIdx(nodeId)
      if (idx != -1) starts += idx
    }

    val isPinnedSet = {
      val set = ArraySet.create(n)
      pinnedNodeIdx.foreachElement(userIdx)(set.add)
      set
    }

    depthFirstSearchExists(starts.result(), notDeletedParentsIdx, isPinnedSet)
  }

  // IMPORTANT:
  // exactly the same as in the stored procedure
  // when changing things, make sure to change them for the stored procedure as well.
  def can_access_node(userId: UserId, nodeId: NodeId): Boolean = {
    def can_access_node_recursive(
      userId: NodeId,
      nodeId: NodeId,
      visited: Set[NodeId] = emptyNodeIdSet
    ): Boolean = {
      if (visited(nodeId)) return false // prevent inheritance cycles

      // is there a membership?
      val levelFromMembership = membershipEdgeForNodeIdx(idToIdx(nodeId)).map(edges).collectFirst {
        case Edge.Member(`userId`, EdgeData.Member(level), _) => level
      }
      levelFromMembership match {
        case None => // if no member edge exists
          // read access level directly from node
          nodesById(nodeId).meta.accessLevel match {
            case NodeAccess.Level(level) => level == AccessLevel.ReadWrite
            case NodeAccess.Inherited =>
              // recursively inherit permissions from parents. minimum one parent needs to allow access.
              parents(nodeId).exists(
                parentId => can_access_node_recursive(userId, parentId, visited + nodeId)
              )
          }
        case Some(level) =>
          level == AccessLevel.ReadWrite
      }
    }

    // everybody has full access to non-existent nodes
    if (!(nodeIds contains nodeId)) return true
    can_access_node_recursive(userId, nodeId)
  }

  def accessLevelOfNode(nodeId: NodeId): Option[AccessLevel] = {
    def inner(
      nodeIdx: Int,
      visited: immutable.BitSet
    ): Option[AccessLevel] = {
      if (visited(nodeIdx)) return None // prevent inheritance cycles and just disallow

      nodes(nodeIdx).meta.accessLevel match {
        case NodeAccess.Level(level) => Some(level)
        case NodeAccess.Inherited =>
          // recursively inherit permissions from parents. minimum one parent needs to allow access.
          var hasPrivateLevel = false
          parentsIdx.foreachElement(nodeIdx) { parentIdx =>
            inner(parentIdx, visited + nodeIdx) match {
              case Some(AccessLevel.ReadWrite)  => return Some(AccessLevel.ReadWrite) // return outer method, there is at least one public parent
              case Some(AccessLevel.Restricted) => hasPrivateLevel = true
              case None                         => ()
            }
          }
          if (hasPrivateLevel) Some(AccessLevel.Restricted) else None
      }
    }

    // everybody has full access to non-existent nodes
    val nodeIdx = idToIdx(nodeId)
    if (nodeIdx < 0) return Some(AccessLevel.ReadWrite)
    inner(nodeIdx, immutable.BitSet.empty)
  }

  def doneNodeForWorkspace(workspaceIdx: Int): Option[Int] = graph.notDeletedChildrenIdx(workspaceIdx).find { nodeIdx =>
    val node = nodes(nodeIdx)
    isDoneStage(node)
  }

  def isDoneStage(node: Node): Boolean = node.role == NodeRole.Stage && node.str.trim.toLowerCase == Graph.doneTextLower


  def workspacesForNode(nodeIdx: Int): Array[Int] = {
    (notDeletedParentsIdx(nodeIdx).flatMap(workspacesForParent)(breakOut): Array[Int]).distinct
  }

  def workspacesForParent(parentIdx: Int): Array[Int] = {
    val parentNode = nodes(parentIdx)
    parentNode.role match {
      case NodeRole.Stage =>
        val workspacesBuilder = new mutable.ArrayBuilder.ofInt
        // search for first transitive parents which are not stages
        depthFirstSearchAfterStartsWithContinue(Array(parentIdx), notDeletedParentsIdx, { idx =>
          nodes(idx).role match {
            case NodeRole.Stage => true
            case _ =>
              workspacesBuilder += idx
              false
          }
        })
        workspacesBuilder.result()
      case NodeRole.Tag => Array.empty[Int]
      case _ =>
        Array(parentIdx)
    }
  }

  def isDoneInAllWorkspaces(nodeIdx: Int, workspaces: Array[Int]): Boolean = {
    @inline def isDoneIn(doneIdx: Int, nodeIdx: Int) = notDeletedChildrenIdx.contains(doneIdx)(nodeIdx)
    workspaces.forall{ workspaceIdx =>
      doneNodeForWorkspace(workspaceIdx).exists(doneIdx => isDoneIn(doneIdx, nodeIdx))
    }
  }

  def isDone(nodeIdx: Int): Boolean = isDoneInAllWorkspaces(nodeIdx, workspacesForNode(nodeIdx))

  //  lazy val containmentNeighbours
  //  : collection.Map[NodeId, collection.Set[NodeId]] = nodeDefaultNeighbourhood ++ adjacencyList[
  //    NodeId,
  //    Edge
  //    ](containments, _.targetId, _.sourceId)
  // Get connected components by only considering containment edges
  //  lazy val connectedContainmentComponents: List[Set[NodeId]] = {
  //    connectedComponents(nodeIds, containmentNeighbours)
  //  }

  lazy val childDepth: collection.Map[NodeId, Int] = depth(children)
  lazy val parentDepth: collection.Map[NodeId, Int] = depth(parents)

  def depth(next: NodeId => Iterable[NodeId]): collection.Map[NodeId, Int] = {
    val tmpDepths = mutable.HashMap[NodeId, Int]()
    val visited = mutable.HashSet[NodeId]() // to handle cycles
    def getDepth(id: NodeId): Int = {
      tmpDepths.getOrElse(id, {
        if (!visited(id)) {
          visited += id

          val c = next(id)
          val d = if (c.isEmpty) 0 else c.map(getDepth).max + 1
          tmpDepths(id) = d
          d
        } else 0 // cycle
      })
    }

    for (id <- nodeIds if !tmpDepths.isDefinedAt(id)) {
      getDepth(id)
    }
    tmpDepths
  }

  lazy val rootNodes: Array[Int] = {
    // val coveredChildrenx: Set[NodeId] = nodes.filter(n => !hasParents(n.id)).flatMap(n => descendants(n.id))(breakOut)
    val coveredChildren = ArraySet.create(n)
    nodes.foreachIndex { i =>
      if (!hasNotDeletedParentsIdx(i)) {
        coveredChildren ++= notDeletedDescendantsIdx(i)
      }
    }

    // val rootNodes = nodes.filter(n => coveredChildren(idToIdx(n.id)) == 0 && (!hasParents(n.id) || involvedInContainmentCycle(n.id))).toSet
    val rootNodesIdx = new mutable.ArrayBuilder.ofInt
    rootNodesIdx.sizeHint(n)
    nodes.foreachIndex { i =>
      //assert(coveredChildren(i) == coveredChildren(idToIdx(nodes(i).id)))
      if (coveredChildren.containsNot(i) && (!hasNotDeletedParentsIdx(i) || involvedInNotDeletedContainmentCycleIdx(i)))
        rootNodesIdx += i
    }
    rootNodesIdx.result()
  }

  def redundantTree(root: Int, excludeCycleLeafs: Boolean, visited: ArraySet = ArraySet.create(n)): Tree = {
    if (visited.containsNot(root) && hasNotDeletedChildrenIdx(root)) {
      visited.add(root)
      if (excludeCycleLeafs) {
        val nonCycleChildren = notDeletedChildrenIdx(root).filterNot(visited.contains)
        if (nonCycleChildren.nonEmpty) {
          Tree.Parent(nodes(root), (nonCycleChildren.map(n => redundantTree(n, excludeCycleLeafs, visited))(breakOut): List[Tree]).sortBy(_.node.id))
        } else
          Tree.Leaf(nodes(root))
      } else {
        Tree.Parent(nodes(root), (notDeletedChildrenIdx(root).map(idx => redundantTree(idx, excludeCycleLeafs, visited))(breakOut): List[Tree]).sortBy(_.node.id))
      }
    } else
      Tree.Leaf(nodes(root))
  }

  def roleTree(root: Int, role: NodeRole, visited: ArraySet = ArraySet.create(n)): Tree = {
    if (visited.containsNot(root) && nodes(root).role == role) {
      visited.add(root)
      Tree.Parent(nodes(root), (
        notDeletedChildrenIdx(root)
          .collect{
            case idx if nodes(idx).role == role => roleTree(idx, role, visited)
          }(breakOut): List[Tree]
      ).sortBy(_.node.id))
    } else
      Tree.Leaf(nodes(root))
  }

  def roleTreeWithUserFilter(userNodeIdx: ArraySliceInt, root: Int, role: NodeRole, pageParentIdx: Int, visited: ArraySet = ArraySet.create(n)): Tree = {
    if (visited.containsNot(root) && nodes(root).role == role) {
      visited.add(root)
      Tree.Parent(nodes(root), (
        notDeletedChildrenIdx(root)
          .collect{
            case idx if nodes(idx).role == role => roleTreeWithUserFilter(userNodeIdx, idx, role, pageParentIdx, visited)
            case idx if notDeletedParentsIdx.contains(idx)(pageParentIdx) && userNodeIdx.contains(idx) => roleTreeWithUserFilter(userNodeIdx, idx, role, pageParentIdx, visited)
          }(breakOut): List[Tree]
      ).sortBy(_.node.id))
    } else
      Tree.Leaf(nodes(root))
  }

  lazy val redundantForestExcludingCycleLeafs: List[Tree] = {
    (rootNodes.map(idx => redundantTree(idx, excludeCycleLeafs = true))(breakOut): List[Tree]).sortBy(_.node.id)
  }
  lazy val redundantForestIncludingCycleLeafs: List[Tree] = {
    (rootNodes.map(idx => redundantTree(idx, excludeCycleLeafs = false))(breakOut): List[Tree]).sortBy(_.node.id)
  }

  def channelTree(user: UserId): Seq[Tree] = {
    val userIdx = idToIdx(user)
    val channelIndices = pinnedNodeIdx(userIdx)
    val isChannel = ArraySet.create(n)
    pinnedNodeIdx.foreachElement(userIdx)(isChannel.add)

    //TODO: more efficient algorithm? https://en.wikipedia.org/wiki/Reachability#Algorithms
    def reachable(childChannelIdx: Int, parentChannelIdx: Int): Boolean = {
      // child --> ...no other channel... --> parent
      // if child channel is trasitive child of parent channel,
      // without traversing over other channels
      val excludedChannels = ArraySet.create(n)
      pinnedNodeIdx.foreachElement(userIdx) { channelIdx =>
        excludedChannels += channelIdx
      }
      excludedChannels -= parentChannelIdx
      depthFirstSearchExcludeExists(childChannelIdx, notDeletedParentsIdx, exclude = excludedChannels, search = parentChannelIdx)
    }

    val topologicalParents = for {
      child <- channelIndices
      parent <- ancestorsIdx(child)
      if child != parent
      if isChannel.contains(parent)
      if reachable(child, parent)
    } yield Edge.Parent(nodes(child).id, nodes(parent).id)

    val topologicalMinor = Graph(channelIndices.map(nodes), topologicalParents)
    topologicalMinor.lookup.redundantForestExcludingCycleLeafs
  }

  def parentDepths(node: NodeId): Map[Int, Map[Int, Seq[NodeId]]] = {
    import wust.util.algorithm.dijkstra
    type ResultMap = Map[Distance, Map[GroupIdx, Seq[NodeId]]]

    def ResultMap() = Map[Distance, Map[GroupIdx, Seq[NodeId]]]()

    // NodeId -> distance
    val (distanceMap: Map[NodeId, Int], _) = dijkstra[NodeId](notDeletedParents, node)
    val nodesInCycles = distanceMap.keys.filter(involvedInContainmentCycle)
    val groupedByCycle = nodesInCycles.groupBy { node => depthFirstSearchWithStartInCycleDetection[NodeId](node, notDeletedParents).toSet }
    type GroupIdx = Int
    type Distance = Int
    val distanceMapForCycles: Map[NodeId, (GroupIdx, Distance)] =
      groupedByCycle.zipWithIndex.map {
        case ((group, cycledNodes), groupIdx) =>
          val smallestDistToGroup: Int = group.map(distanceMap).min
          cycledNodes.zip(Stream.continually { (groupIdx, smallestDistToGroup) })
      }.flatten.toMap

    // we want: distance -> (nocycle : Seq[NodeId], cycle1 : Seq[NodeId],...)
    (distanceMap.keys.toSet ++ distanceMapForCycles.keys.toSet).foldLeft(
      ResultMap()
    ) { (result, nodeid) =>
        // in case that the nodeid is inside distanceMapForCycles, it is contained
        // inside a cycle, so we use the smallest distance of the cycle
        val (gId, dist) = if (distanceMapForCycles.contains(nodeid))
          distanceMapForCycles(nodeid)
        else
          (-1, distanceMap(nodeid))

        import monocle.function.At._
        (monocle.Iso.id[ResultMap] composeLens at(dist)).modify { optInnerMap =>
          val innerMap = optInnerMap.getOrElse(Map.empty)
          Some(((monocle.Iso.id[Map[GroupIdx, Seq[NodeId]]] composeLens at(gId)) modify { optInnerSeq =>
            val innerSeq = optInnerSeq.getOrElse(Nil)
            Some(innerSeq ++ Seq(nodeid))
          }) (innerMap))
        }(result)
      }
  }
}

sealed trait Tree {
  def node: Node
  def flatten: List[Node]
  def flattenWithDepth(depth: Int = 0): List[(Node, Int)]
}
object Tree {
  case class Parent(node: Node, children: List[Tree]) extends Tree {
    override def flatten: List[Node] = node :: (children.flatMap(_.flatten)(breakOut): List[Node])
    override def flattenWithDepth(depth: Int = 0): List[(Node, Int)] = (node, depth) :: (children.flatMap(_.flattenWithDepth(depth + 1))(breakOut): List[(Node, Int)])
  }
  case class Leaf(node: Node) extends Tree {
    override def flatten: List[Node] = node :: Nil
    override def flattenWithDepth(depth: Int = 0): List[(Node, Int)] = (node, depth) :: Nil
  }
}
