package wust.graph

import wust.ids._
import wust.util.{Memo, NestedArrayInt, SliceInt, algorithm}
import wust.util.algorithm._
import wust.util.collection._
import wust.util.time.time

import collection.{breakOut, mutable}
import scala.collection.immutable

object Graph {
  val empty = new Graph(Array.empty, Array.empty)

  def apply(nodes: Iterable[Node] = Nil, edges: Iterable[Edge] = Nil): Graph = {
    new Graph(nodes.toArray, edges.toArray)
  }
}

//TODO: this is only a case class because julius is too  lazy to write a custom encoder/decoder for boopickle and circe
final case class Graph(nodes: Array[Node], edges: Array[Edge]) {
  // because it is a case class, we overwrite equals and hashcode, because we do not want comparisons here.
  override def hashCode(): Int = super.hashCode()
  override def equals(that: Any): Boolean = super.equals(that)

  lazy val lookup = GraphLookup(this, nodes, edges)

  def isEmpty: Boolean = nodes.isEmpty
  def nonEmpty: Boolean = !isEmpty
  def size: Int = nodes.size
  def length: Int = size

  override def toString: String = {
    def nodeStr(node: Node) =
      s"${node.data.tpe}(${node.data.str}:${node.id.toBase58.takeRight(3)})"
    s"Graph(${nodes.map(nodeStr).mkString(" ")}, " +
      s"${edges
        .map(c => s"${c.sourceId.toBase58.takeRight(3)}-${c.data}->${c.targetId.toBase58.takeRight(3)}")
        .mkString(", ")})"
  }

  def toSummaryString = {
    s"Graph(nodes: ${ nodes.size }, ${ edges.size })"
  }

  def pageContent(page: Page): Graph = {
    val pageChildren = page.parentIds.flatMap(lookup.descendants _)
    this.filterIds(pageChildren.toSet)
  }

  def filterIds(p: NodeId => Boolean): Graph = filter(node => p(node.id))
  def filter(p: Node => Boolean): Graph = {
    // we only want to call p once for each node
    // and not trigger the pre-caching machinery of nodeIds
    val filteredNodes = nodes.filter(p)

    @inline def nothingFiltered = filteredNodes.size == nodes.size

    if(nothingFiltered) this
    else {
      val filteredNodeIds: Set[NodeId] = filteredNodes.map(_.id)(breakOut)
      new Graph(
        nodes = filteredNodes,
        edges = edges.filter(e => filteredNodeIds(e.sourceId) && filteredNodeIds(e.targetId))
      )
    }
  }

  def filterNotIds(p: NodeId => Boolean): Graph = filterIds(id => !p(id))
  def filterNot(p: Node => Boolean): Graph = filter(node => !p(node))

  def applyChangesWithUser(user: Node.User, c: GraphChanges): Graph = changeGraphInternal(addNodes = c.addNodes ++ Set(user), addEdges = c.addEdges, deleteEdges = c.delEdges)
  def applyChanges(c: GraphChanges): Graph = changeGraphInternal(addNodes = c.addNodes, addEdges = c.addEdges, deleteEdges = c.delEdges)

  private def changeGraphInternal(addNodes: collection.Set[Node], addEdges: collection.Set[Edge], deleteEdges: collection.Set[Edge] = Set.empty): Graph = {
    val nodesBuilder = mutable.ArrayBuilder.make[Node]()
    val edgesBuilder = mutable.ArrayBuilder.make[Edge]()
    // nodesBuilder.sizeHint(nodes.length + addNodes.size)
    // edgesBuilder.sizeHint(edges.length + addEdges.size)

    val addNodeIds: Set[NodeId] = addNodes.map(_.id)(breakOut)
    val updatedEdgeIds: Set[(NodeId, String, NodeId)] = (addEdges ++ deleteEdges).map(e => (e.sourceId, e.data.tpe, e.targetId))(breakOut)

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

  @deprecated("", "")
  def removeNodes(nids: Iterable[NodeId]): Graph = filterNotIds(nids.toSet)

  @deprecated("", "")
  def removeEdges(es: Iterable[Edge]): Graph = new Graph(nodes = nodes, edges = edges.filterNot(es.toSet))

  @deprecated("","")
  def addNodes(newNodes: Iterable[Node]): Graph = new Graph(nodes = nodes ++ newNodes, edges = edges)

  @deprecated("", "") @inline def nodeIds = lookup.nodeIds
  @deprecated("", "") @inline def nodesById(nodeId: NodeId) = lookup.nodesById(nodeId)
  @deprecated("", "") @inline def nodeModified(nodeId: NodeId) = lookup.nodeModified(lookup.idToIdx(nodeId))
  @deprecated("", "") @inline def nodeCreated(nodeId: NodeId) = lookup.nodeCreated(lookup.idToIdx(nodeId))
  @deprecated("", "") @inline def expandedNodes(userId: UserId) = lookup.expandedNodes(userId)
  @deprecated("", "")
  @inline def isChildOfAny(n: NodeId, parentIds: Iterable[NodeId]) = lookup.isChildOfAny(n, parentIds)
  @deprecated("", "") @inline def successorsWithoutParent = lookup.successorsWithoutParent
  @deprecated("", "") @inline def predecessorsWithoutParent = lookup.predecessorsWithoutParent
  @deprecated("", "") @inline def neighboursWithoutParent = lookup.neighboursWithoutParent
  @deprecated("", "") @inline def children(n: NodeId) = lookup.children(n)
  @deprecated("", "") @inline def members(n: NodeId) = lookup.members(n)
  @deprecated("", "") @inline def authors(n: NodeId) = lookup.authors(n)
  @deprecated("", "") @inline def authorsIn(n: NodeId) = lookup.authorsIn(n)
  @deprecated("", "") @inline def hasChildren(n: NodeId) = lookup.hasChildren(n)
  @deprecated("", "") @inline def parents(n: NodeId) = lookup.parents(n)
  @deprecated("", "") @inline def parentDepths(n: NodeId) = lookup.parentDepths(n)
  @deprecated("", "") @inline def childDepth(n: NodeId) = lookup.childDepth(n)
  @deprecated("", "") @inline def hasParents(n: NodeId) = lookup.hasParents(n)
  @deprecated("", "") @inline def rootNodes = lookup.rootNodes
  @deprecated("", "")
  @inline def redundantTree(root: Node, excludeCycleLeafs: Boolean) = lookup.redundantTree(lookup.idToIdx(root.id), excludeCycleLeafs)
  @deprecated("", "") @inline def channelTree(user: UserId): Seq[Tree] = lookup.channelTree(user)
  @deprecated("", "")
  @inline def can_access_node(userId: NodeId, nodeId: NodeId): Boolean = lookup.can_access_node(userId, nodeId)
  @deprecated("", "") @inline def descendants(nodeId: NodeId) = lookup.descendants(nodeId)
  @deprecated("", "") @inline def ancestors(nodeId: NodeId) = lookup.ancestors(nodeId)
  @deprecated("", "") @inline def involvedInContainmentCycle(id: NodeId) = lookup.involvedInContainmentCycle(id)
  @deprecated("", "")
  @inline def inChildParentRelation(child: NodeId, possibleParent: NodeId): Boolean = lookup.inChildParentRelation(child, possibleParent)
  @deprecated("", "")
  @inline def isStaticParentIn(id: NodeId, parentIds: Iterable[NodeId]): Boolean = lookup.isStaticParentIn(id, parentIds)

}

final case class GraphLookup(graph: Graph, nodes: Array[Node], edges: Array[Edge]) {

  @inline private def n = nodes.length
  @inline private def m = edges.length

  def createArraySet(ids: Set[NodeId]): ArraySet = {
    val marker = ArraySet.create(n)
    ids.foreach { id =>
      val idx = idToIdx(id)
      if(idx != -1)
        marker.add(idx)
    }
    marker
  }

  def createBitSet(ids: Iterable[NodeId]): immutable.BitSet = {
    val builder = immutable.BitSet.newBuilder
    ids.foreach { id =>
      val idx = idToIdx(id)
      if(idx != -1)
        builder += idx
    }
    builder.result()
  }

  @deprecated("", "")
  private val _idToIdx = mutable.HashMap.empty[NodeId, Int]
  _idToIdx.sizeHint(n)
  val nodeIds = new Array[NodeId](n)

  nodes.foreachIndexAndElement { (i, node) =>
    val nodeId = node.id
    _idToIdx(nodeId) = i
    nodeIds(i) = nodeId
  }


  @deprecated("", "")
  @inline def idToIdx: collection.Map[NodeId, Int] = _idToIdx.withDefaultValue(-1)
  @deprecated("", "")
  @inline def nodesById(nodeId: NodeId): Node = nodes(idToIdx(nodeId))
  @inline def nodesByIdGet(nodeId: NodeId): Option[Node] = {
    val idx = idToIdx(nodeId)
    if(idx == -1) None
    else Some(nodes(idx))
  }

  @deprecated("", "")
  @inline def contains(nodeId: NodeId) = idToIdx.isDefinedAt(nodeId)

  assert(idToIdx.size == nodes.length, "nodes are not distinct by id")

  private val emptyNodeIdSet = Set.empty[NodeId]
  private val edgesIdx = InterleavedArray.create(edges.length)


  // TODO: have one big triple nested array for all edge lookups?

  // To avoid array builders for each node, we collect the node degrees in a
  // loop and then add the indices in a second loop. This is twice as fast
  // than using one loop with arraybuilders. (A lot less allocations)
  private val parentsDegree = new Array[Int](n)
  private val childrenDegree = new Array[Int](n)
  private val deletedParentsDegree = new Array[Int](n)
  private val authorshipDegree = new Array[Int](n)
  private val membershipDegree = new Array[Int](n)
  private val notifyByUserDegree = new Array[Int](n)
  private val pinnedNodeDegree = new Array[Int](n)
  private val staticParentInDegree = new Array[Int](n)
  private val expandedNodesDegree = new Array[Int](n)

  private val remorseTimeForDeletedParents: EpochMilli = EpochMilli(EpochMilli.now - (24 * 3600 * 1000))

  edges.foreachIndexAndElement { (edgeIdx, edge) =>
    val sourceIdx = idToIdx(edge.sourceId)
    if(sourceIdx != -1) {
      val targetIdx = idToIdx(edge.targetId)
      if(targetIdx != -1) {

        edgesIdx.updatea(edgeIdx, sourceIdx)
        edgesIdx.updateb(edgeIdx, targetIdx)

        edge match {
          case _: Edge.Author         =>
            authorshipDegree(targetIdx) += 1
          case _: Edge.Member         =>
            membershipDegree(targetIdx) += 1
          case e: Edge.Parent         =>
            e.data.deletedAt match {
              case None            =>
                parentsDegree(sourceIdx) += 1
                childrenDegree(targetIdx) += 1
              case Some(deletedAt) =>
                //TODO should already be filtered in backend
                if(deletedAt > remorseTimeForDeletedParents) {
                  parentsDegree(sourceIdx) += 1
                  childrenDegree(targetIdx) += 1
                  deletedParentsDegree(sourceIdx) += 1

                }
            }
          case _: Edge.StaticParentIn =>
            staticParentInDegree(sourceIdx) += 1
          case _: Edge.Expanded       =>
            expandedNodesDegree(sourceIdx) += 1
          case _: Edge.Notify         =>
            notifyByUserDegree(targetIdx) += 1
          case _: Edge.Pinned         =>
            pinnedNodeDegree(sourceIdx) += 1
          case _                      =>
        }
      }
    }
  }

  private val parentsIdxBuilder = NestedArrayInt.builder(parentsDegree)
  private val childrenIdxBuilder = NestedArrayInt.builder(childrenDegree)
  private val deletedParentsIdxBuilder = NestedArrayInt.builder(deletedParentsDegree)
  private val authorshipEdgeIdxBuilder = NestedArrayInt.builder(authorshipDegree)
  private val authorIdxBuilder = NestedArrayInt.builder(authorshipDegree)
  private val membershipEdgeIdxBuilder = NestedArrayInt.builder(membershipDegree)
  private val notifyByUserIdxBuilder = NestedArrayInt.builder(notifyByUserDegree)
  private val pinnedNodeIdxBuilder = NestedArrayInt.builder(pinnedNodeDegree)
  private val staticParentInIdxBuilder = NestedArrayInt.builder(staticParentInDegree)
  private val expandedNodesIdxBuilder = NestedArrayInt.builder(expandedNodesDegree)

  edgesIdx.foreachIndexAndTwoElements { (edgeIdx, sourceIdx, targetIdx) =>
    val edge = edges(edgeIdx)
    edge match {
      case e: Edge.Author         =>
        authorshipEdgeIdxBuilder.add(targetIdx, edgeIdx)
        authorIdxBuilder.add(targetIdx, sourceIdx)
      case e: Edge.Member         =>
        membershipEdgeIdxBuilder.add(targetIdx, edgeIdx)
      case e: Edge.Parent         =>
        e.data.deletedAt match {
          case None            =>
            parentsIdxBuilder.add(sourceIdx, targetIdx)
            childrenIdxBuilder.add(targetIdx, sourceIdx)
          case Some(deletedAt) =>
            //TODO should already be filtered in backend
            if(deletedAt > remorseTimeForDeletedParents) {
              parentsIdxBuilder.add(sourceIdx, targetIdx)
              childrenIdxBuilder.add(targetIdx, sourceIdx)
              deletedParentsIdxBuilder.add(sourceIdx, targetIdx)
            }
        }
      case _: Edge.StaticParentIn =>
        staticParentInIdxBuilder.add(sourceIdx, targetIdx)
      case _: Edge.Expanded       =>
        expandedNodesIdxBuilder.add(sourceIdx, targetIdx)
      case _: Edge.Notify         =>
        notifyByUserIdxBuilder.add(targetIdx, sourceIdx)
      case _: Edge.Pinned         =>
        pinnedNodeIdxBuilder.add(sourceIdx, targetIdx)
      case _                      =>
    }
  }

  val parentsIdx: NestedArrayInt = parentsIdxBuilder.result()
  val childrenIdx: NestedArrayInt = childrenIdxBuilder.result()
  val deletedParentsIdx: NestedArrayInt = deletedParentsIdxBuilder.result()
  val authorshipEdgeIdx: NestedArrayInt = authorshipEdgeIdxBuilder.result()
  val membershipEdgeIdx: NestedArrayInt = membershipEdgeIdxBuilder.result()
  val notifyByUserIdx: NestedArrayInt = notifyByUserIdxBuilder.result()
  val authorsIdx: NestedArrayInt = authorIdxBuilder.result()
  val pinnedNodeIdx: NestedArrayInt = pinnedNodeIdxBuilder.result()
  val staticParentInIdx: NestedArrayInt = staticParentInIdxBuilder.result()
  val expandedNodesIdx: NestedArrayInt = expandedNodesIdxBuilder.result()

  val expandedNodesByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if(idx != -1) expandedNodesIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  @inline def expandedNodes(userId: UserId): collection.Set[NodeId] = expandedNodesByIndex(idToIdx(userId))
  val staticParentInByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if(idx != -1) staticParentInIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  @inline def staticParentIn(nodeId: NodeId): collection.Set[NodeId] = staticParentInByIndex(idToIdx(nodeId))
  val parentsByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if(idx != -1) parentsIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  @inline def parents(nodeId: NodeId): collection.Set[NodeId] = parentsByIndex(idToIdx(nodeId))
  val childrenByIndex: Int => collection.Set[NodeId] = Memo.arrayMemo[collection.Set[NodeId]](n).apply { idx =>
    if(idx != -1) childrenIdx(idx).map(i => nodes(i).id)(breakOut) else emptyNodeIdSet
  }
  @inline def children(nodeId: NodeId): collection.Set[NodeId] = childrenByIndex(idToIdx(nodeId))

  // not lazy because it often used for sorting. and we do not want to compute a lazy val in a for loop.
  val (nodeCreated: Array[EpochMilli], nodeModified: Array[EpochMilli]) = {
    val nodeCreated = Array.fill(n)(EpochMilli.min)
    val nodeModified = Array.fill(n)(EpochMilli.min)
    var nodeIdx = 0
    while(nodeIdx < n) {
      val authorEdgeIndices: SliceInt = authorshipEdgeIdx(nodeIdx)
      if(authorEdgeIndices.nonEmpty) {
        val (createdEdge, lastModifierEdge) = authorEdgeIndices.minMax(smallerThan = (a, b) => edges(a).asInstanceOf[Edge.Author].data.timestamp < edges(b).asInstanceOf[Edge.Author].data.timestamp)
        nodeCreated(nodeIdx) = edges(createdEdge).asInstanceOf[Edge.Author].data.timestamp
        nodeModified(nodeIdx) = edges(lastModifierEdge).asInstanceOf[Edge.Author].data.timestamp
      }
      nodeIdx += 1
    }
    (nodeCreated, nodeModified)
  }

  def filterIdx(p: Int => Boolean): Graph = {
    // we only want to call p once for each node
    // and not trigger the pre-caching machinery of nodeIds
    val filteredNodesIndices = nodes.indices.filter(p)

    @inline def nothingFiltered = filteredNodesIndices.length == nodes.length

    if(nothingFiltered) graph
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
    if(idx < 0) Nil
    else authorsIdx(idx).map(idx => nodes(idx).asInstanceOf[Node.User])
  }
  @inline def authors(nodeId: NodeId): Seq[Node.User] = authorsByIndex(idToIdx(nodeId))

  val authorsInByIndex: Int => Seq[Node.User] = Memo.arrayMemo[Seq[Node.User]](n).apply { idx =>
    if(idx < 0) Nil
    else {
      val rootAuthors = authorsByIndex(idx)
      val builder = new mutable.ArrayBuilder.ofRef[Node.User]
      builder.sizeHint(rootAuthors.size)
      builder ++= rootAuthors
      descendantsIdx(idx).foreach { idx =>
        builder ++= authorsByIndex(idx)
      }
      builder.result()
    }
  }
  @inline def authorsIn(nodeId: NodeId): Seq[Node.User] = authorsInByIndex(idToIdx(nodeId))

  val membersByIndex: Int => Seq[Node.User] = Memo.arrayMemo[Seq[Node.User]](n).apply { idx =>
    membershipEdgeIdx(idx).map(edgeIdx => nodesById(edges(edgeIdx).asInstanceOf[Edge.Member].userId).asInstanceOf[Node.User])
  }
  @inline def members(nodeId: NodeId): Seq[Node.User] = membersByIndex(idToIdx(nodeId))

  def usersInNode(id: NodeId, max: Int): collection.Set[Node.User] = {
    val builder = new mutable.LinkedHashSet[Node.User]
    val nodeIdx = idToIdx(id)
    val members = membersByIndex(nodeIdx)
    builder ++= members
    var counter = members.size
    depthFirstSearchWithManualAppend(nodeIdx, childrenIdx, append = { idx =>
      val authors = authorsByIndex(idx)
      builder ++= authors
      counter += authors.size
      counter <= max
    })

    builder.result().take(max)
  }

  def isDeletedNow(nodeId: NodeId, parents: Iterable[NodeId]): Boolean = {
    val nodeIdx = idToIdx(nodeId)
    if (nodeIdx == -1) return false

    @inline def nodeIsDeletedInAtLeastOneParent = deletedParentsIdx.sliceNonEmpty(nodeIdx)
    @inline def deletedParentSet = {
      val set = ArraySet.create(n)
      deletedParentsIdx.foreachElement(nodeIdx)(set.add)
      set
    }
    @inline def deletedInAllSpecifiedParentIndices = parents.forall(parent => deletedParentSet.contains(idToIdx(parent)))
    @inline def hasNoParents = parentsIdx.sliceIsEmpty(nodeIdx)

    if(nodeIsDeletedInAtLeastOneParent) {
      if(deletedInAllSpecifiedParentIndices) true
      else hasNoParents
    } else false // node is nowhere deleted
  }

  def isDeletedNowIdx(nodeIdx: Int, parentIndices: immutable.BitSet): Boolean = {
    @inline def nodeIsDeletedInAtLeastOneParent = deletedParentsIdx.sliceNonEmpty(nodeIdx)
    @inline def deletedParentSet = {
      val set = ArraySet.create(n)
      deletedParentsIdx.foreachElement(nodeIdx)(set.add)
      set
    }
    @inline def deletedInAllSpecifiedParentIndices = parentIndices.forall(deletedParentSet.contains)
    @inline def hasNoParents = parentsIdx.sliceIsEmpty(nodeIdx)

    if(nodeIsDeletedInAtLeastOneParent) {
      if(deletedInAllSpecifiedParentIndices) true
      else hasNoParents
    } else false // node is nowhere deleted
  }

  def directNodeTags(nodeIdx: Int, parentIndices: immutable.BitSet): Array[Node] = {
    //      (parents(nodeId).toSet -- (parentIds - nodeId)).map(nodesById) // "- nodeId" reveals self-loops with page-parent

    val tagSet = new mutable.ArrayBuilder.ofRef[Node]

    parentsIdx.foreachElement(nodeIdx) { i =>
      if(!parentIndices.contains(i) || i == nodeIdx)
        tagSet += nodes(i)
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

    tagSet.map(nodes)
  }

  lazy val chronologicalNodesAscendingIdx: Array[Int] = {
     nodes.indices.toArray.sortBy(nodeCreated)
  }

  lazy val chronologicalNodesAscending: IndexedSeq[Node] = {
     chronologicalNodesAscendingIdx.map(nodes)
  }

  lazy val allParentIds: IndexedSeq[NodeId] = ???
  lazy val allParentIdsTopologicallySortedByChildren: Iterable[NodeId] =
    allParentIds.topologicalSortBy(children)

  private lazy val nodeDefaultNeighbourhood: collection.Map[NodeId, Set[NodeId]] =
    defaultNeighbourhood(nodeIds, emptyNodeIdSet)
  lazy val successorsWithoutParent
  : collection.Map[NodeId, collection.Set[NodeId]] = nodeDefaultNeighbourhood ++ directedAdjacencyList[
    NodeId,
    Edge,
    NodeId
    ](???, _.sourceId, _.targetId)
  lazy val predecessorsWithoutParent
  : collection.Map[NodeId, collection.Set[NodeId]] = nodeDefaultNeighbourhood ++ directedAdjacencyList[
    NodeId,
    Edge,
    NodeId
    ](???, _.targetId, _.sourceId)
  lazy val neighboursWithoutParent
  : collection.Map[NodeId, collection.Set[NodeId]] = nodeDefaultNeighbourhood ++ adjacencyList[
    NodeId,
    Edge
    ](???, _.targetId, _.sourceId)

  def inChildParentRelation(child: NodeId, possibleParent: NodeId): Boolean =
    parents(child).contains(possibleParent)
  def inDescendantAncestorRelation(descendent: NodeId, possibleAncestor: NodeId): Boolean =
    ancestors(descendent).contains(possibleAncestor)

  def isStaticParentIn(id: NodeId, parentId: NodeId): Boolean = staticParentIn(id).contains(parentId)
  def isStaticParentIn(id: NodeId, parentIds: Iterable[NodeId]): Boolean = {
    val staticInParents = staticParentIn(id)
    parentIds.exists(parentId => staticInParents.contains(parentId))
  }


  @inline def hasChildrenIdx(nodeIdx: Int): Boolean = childrenIdx.sliceNonEmpty(nodeIdx)
  @inline def hasParentsIdx(nodeIdx: Int): Boolean = parentsIdx.sliceNonEmpty(nodeIdx)

  @inline private def hasSomethingById(nodeId: NodeId, lookup: Int => Boolean) = {
    val idx = idToIdx(nodeId)
    if(idx == -1)
      false
    else
      lookup(idx)
  }
  def hasChildren(nodeId: NodeId): Boolean = hasSomethingById(nodeId, hasChildrenIdx)
  def hasParents(nodeId: NodeId): Boolean = hasSomethingById(nodeId, hasParentsIdx)

  def isChildOfAny(childId: NodeId, parentIds: Iterable[NodeId]): Boolean = {
    val p = parents(childId)
    parentIds.exists(p.contains)
  }

  private lazy val connectionDefaultNeighbourhood: collection.Map[NodeId, collection.Set[Edge]] =
    defaultNeighbourhood(nodeIds, Set.empty[Edge])
  lazy val outgoingEdges
  : collection.Map[NodeId, collection.Set[Edge]] = connectionDefaultNeighbourhood ++
    directedIncidenceList[NodeId, Edge](edges, _.sourceId)

  lazy val incidentParentContainments: collection.Map[NodeId, collection.Set[Edge]] = ???
  lazy val incidentChildContainments: collection.Map[NodeId, collection.Set[Edge]] = ???
  lazy val incidentContainments: collection.Map[NodeId, collection.Set[Edge]] = ???

  def involvedInContainmentCycleIdx(idx: Int): Boolean = {
    depthFirstSearchExistsWithoutStart(idx, childrenIdx, idx)
  }
  def involvedInContainmentCycle(id: NodeId): Boolean = {
    val idx = idToIdx(id)
    if(idx == -1) false
    else involvedInContainmentCycleIdx(idx)
  }

  def descendantsIdx(nodeIdx: Int) = _descendantsIdx(nodeIdx)
  private val _descendantsIdx: Int => Array[Int] = Memo.arrayMemo[Array[Int]](n).apply { nodeIdx: Int =>
    depthFirstSearchWithoutStart(nodeIdx, childrenIdx)
  }
  def descendants(nodeId: NodeId) = _descendants(idToIdx(nodeId))
  private val _descendants: Int => Seq[NodeId] = Memo.arrayMemo[Seq[NodeId]](n).apply { nodeIdx =>
    if(nodeIdx == -1) Nil
    else descendantsIdx(nodeIdx).map(nodeIds) // instead of dfs, we use already memoized results
  }

  def ancestorsIdx(nodeIdx: Int) = _ancestorsIdx(nodeIdx)
  private val _ancestorsIdx: Int => Array[Int] = Memo.arrayMemo[Array[Int]](n).apply { nodeIdx =>
    depthFirstSearchWithoutStart(nodeIdx, parentsIdx)
  }
  def ancestors(nodeId: NodeId) = _ancestors(idToIdx(nodeId))
  private val _ancestors: Int => Seq[NodeId] = Memo.arrayMemo[Seq[NodeId]](n).apply { nodeIdx =>
    if(nodeIdx == -1) Nil
    else ancestorsIdx(nodeIdx).map(nodeIds) // instead of dfs, we use already memoized results
  }

  // IMPORTANT:
  // exactly the same as in the stored procedure
  // when changing things, make sure to change them for the stored procedure as well.
  def can_access_node(userId: NodeId, nodeId: NodeId): Boolean = {
    def can_access_node_recursive(
      userId: NodeId,
      nodeId: NodeId,
      visited: Set[NodeId] = emptyNodeIdSet
    ): Boolean = {
      if(visited(nodeId)) return false // prevent inheritance cycles

      // is there a membership?
      val levelFromMembership = membershipEdgeIdx(idToIdx(nodeId)).map(edges).collectFirst {
        case Edge.Member(`userId`, EdgeData.Member(level), _) => level
      }
      levelFromMembership match {
        case None        => // if no member edge exists
          // read access level directly from node
          nodesById(nodeId).meta.accessLevel match {
            case NodeAccess.Level(level) => level == AccessLevel.ReadWrite
            case NodeAccess.Inherited    =>
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
    if(!(nodeIds contains nodeId)) return true
    can_access_node_recursive(userId, nodeId)
  }

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
        if(!visited(id)) {
          visited += id

          val c = next(id)
          val d = if(c.isEmpty) 0 else c.map(getDepth).max + 1
          tmpDepths(id) = d
          d
        } else 0 // cycle
      })
    }

    for(id <- nodeIds if !tmpDepths.isDefinedAt(id)) {
      getDepth(id)
    }
    tmpDepths
  }

  lazy val rootNodes: Array[Int] = {
    // val coveredChildrenx: Set[NodeId] = nodes.filter(n => !hasParents(n.id)).flatMap(n => descendants(n.id))(breakOut)
    val coveredChildren = {
      val a = new Array[Int](n)
      var i = 0
      var j = 0
      while(i < n) {
        if(!hasParentsIdx(i)) {
          val descendants = descendantsIdx(i)
          j = 0
          while(j < descendants.size) {
            a(descendants(j)) = 1
            j += 1
          }
        }
        i += 1
      }
      a
    }

    // val rootNodes = nodes.filter(n => coveredChildren(idToIdx(n.id)) == 0 && (!hasParents(n.id) || involvedInContainmentCycle(n.id))).toSet
    val rootNodesIdx = new mutable.ArrayBuilder.ofInt
    rootNodesIdx.sizeHint(n)
    var i = 0
    while(i < n) {
      //assert(coveredChildren(i) == coveredChildren(idToIdx(nodes(i).id)))
      if(coveredChildren(i) == 0 && (!hasParentsIdx(i) || involvedInContainmentCycleIdx(i)))
        rootNodesIdx += i
      i += 1
    }
    rootNodesIdx.result()
  }

  def redundantTree(root: Int, excludeCycleLeafs: Boolean, visited: ArraySet = ArraySet.create(n)): Tree = {
    if(visited.containsNot(root) && hasChildrenIdx(root)) {
      visited.add(root)
      if(excludeCycleLeafs) {
        val nonCycleChildren = childrenIdx(root).filterNot(visited.contains)
        if(nonCycleChildren.nonEmpty) {
          Tree.Parent(nodes(root), nonCycleChildren.map(n => redundantTree(n, excludeCycleLeafs, visited))(breakOut))
        }
        else
          Tree.Leaf(nodes(root))
      } else {
        Tree.Parent(nodes(root), childrenIdx(root).map(idx => redundantTree(idx, excludeCycleLeafs, visited))(breakOut))
      }
    }
    else
      Tree.Leaf(nodes(root))
  }

  lazy val redundantForestExcludingCycleLeafs: List[Tree] = {
    rootNodes.map(idx => redundantTree(idx, excludeCycleLeafs = true))(breakOut)
  }
  lazy val redundantForestIncludingCycleLeafs: List[Tree] = {
    rootNodes.map(idx => redundantTree(idx, excludeCycleLeafs = false))(breakOut)
  }

  def channelTree(user: UserId): Seq[Tree] = {
    val channelIndices = pinnedNodeIdx(idToIdx(user))
    val isChannel = new Array[Int](n)
    channelIndices.foreach { isChannel(_) = 1 }

    //TODO: more efficient algorithm? https://en.wikipedia.org/wiki/Reachability#Algorithms
    def reachable(childChannelIdx: Int, parentChannelIdx: Int): Boolean = {
      // child --> ...no other channel... --> parent
      // if child channel is trasitive child of parent channel,
      // without traversing over other channels
      val excludedChannels = new Array[Int](n)
      channelIndices.foreach { channelIdx =>
        excludedChannels(channelIdx) = 1
      }
      excludedChannels(parentChannelIdx) = 0
      depthFirstSearchExcludeExists(childChannelIdx, parentsIdx, exclude = excludedChannels, search = parentChannelIdx)
    }

    val topologicalParents = for {
      child <- channelIndices
      parent <- ancestorsIdx(child)
      if child != parent
      if isChannel(parent) == 1
      if reachable(child, parent)
    } yield Edge.Parent(nodes(child).id, nodes(parent).id)

    val topologicalMinor = Graph(channelIndices.map(nodes), topologicalParents)
    topologicalMinor.lookup.redundantForestExcludingCycleLeafs
  }

  def parentDepths(node: NodeId): Map[Int, Map[Int, Seq[NodeId]]]
  = {
    trait Grouping
    case class Cycle(node: NodeId) extends Grouping
    case class NoCycle(node: NodeId) extends Grouping

    import wust.util.algorithm.{dijkstra}
    type ResultMap = Map[Distance, Map[GroupIdx, Seq[NodeId]]]

    def ResultMap() = Map[Distance, Map[GroupIdx, Seq[NodeId]]]()

    // NodeId -> distance
    val (distanceMap: Map[NodeId, Int], predecessorMap) = dijkstra[NodeId](parents, node)
    val nodesInCycles = distanceMap.keys.filter(involvedInContainmentCycle(_))
    val groupedByCycle = nodesInCycles.groupBy { node => depthFirstSearchWithStartInCycleDetection[NodeId](node, parents).toSet }
    type GroupIdx = Int
    type Distance = Int
    val distanceMapForCycles: Map[NodeId, (GroupIdx, Distance)] =
      groupedByCycle.zipWithIndex.map { case ((group, cycledNodes), groupIdx) =>
        val smallestDistToGroup: Int = group.map(distanceMap).min
        cycledNodes.zip(Stream.continually { (groupIdx, smallestDistToGroup) })
      }.flatten.toMap

    // we want: distance -> (nocycle : Seq[NodeId], cycle1 : Seq[NodeId],...)
    (distanceMap.keys.toSet ++ distanceMapForCycles.keys.toSet).foldLeft(
      ResultMap()) { (result, nodeid) =>
      // in case that the nodeid is inside distanceMapForCycles, it is contained
      // inside a cycle, so we use the smallest distance of the cycle
      val (gId, dist) = if(distanceMapForCycles.contains(nodeid))
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
}
object Tree {
  case class Parent(node: Node, children: List[Tree]) extends Tree {
    override def flatten = node :: (children.flatMap(_.flatten)(breakOut): List[Node])
  }
  case class Leaf(node: Node) extends Tree {
    override def flatten = node :: Nil
  }
}
