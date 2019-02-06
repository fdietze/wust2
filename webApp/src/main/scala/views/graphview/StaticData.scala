package views.graphview

import java.lang.Math._

import flatland._
import d3v4._
import org.scalajs.dom.html
import vectory.Vec2
import views.graphview.ForceSimulationConstants._
import views.graphview.VisualizationType.{Containment, Edge, Tag}
import wust.graph.{Node, _}
import wust.ids._
import wust.sdk.NodeColor._
import wust.util.time.time

import scala.Double.NaN
import scala.collection.mutable.ArrayBuffer
import scala.collection.{breakOut, mutable}
import scala.scalajs.js

/*
 * Data, which is only recalculated once per graph update and stays the same during the simulation
 */
class StaticData(
    //TODO: many lookups are already provided by GraphLookup. Use them.
    val nodeCount: Int,
    val edgeCount: Int,
    val containmentCount: Int,
    var posts: Array[Node],
    val indices: Array[Int],
    val width: Array[Double],
    val height: Array[Double],
    val centerOffsetX: Array[Double],
    val centerOffsetY: Array[Double],
    val radius: Array[Double],
    var maxRadius: Double,
    val collisionRadius: Array[Double],
    val containmentRadius: Array[Double], // TODO: still needed?
    val nodeParentCount: Array[Int],
    val bgColor: Array[String],
    val nodeCssClass: Array[String],
    val nodeReservedArea: Array[Double],
    var totalReservedArea: Double,

    // Edges
    val source: Array[Int], //TODO: Rename to edgeSource
    val target: Array[Int], //TODO: Rename to edgeTarget

    // Euler
    val containmentChild: Array[Int],
    val containmentParent: Array[Int],
    val containmentTest: AdjacencyMatrix,
    var eulerSetCount: Int,
    var eulerSetParent: Array[Int],
    var eulerSetChildren: Array[Array[Int]],
    var eulerSetDisjunctSetPairs: Array[(Int,Int)],
    var eulerSetAllNodes: Array[Array[Int]],
    var eulerSetArea: Array[Double],
    var eulerSetColor: Array[String],
    var eulerSetDepth: Array[Int],

    var eulerZoneCount: Int,
    var eulerZones:Array[Set[Int]],
    var eulerZoneNodes:Array[Array[Int]],
    var eulerZoneArea: Array[Double],
    var eulerZoneAdjacencyMatrix:AdjacencyMatrix,
    var eulerZoneNeighbourhoods:Array[(Int,Int)],
) {
  def this(nodeCount: Int, edgeCount: Int, containmentCount: Int) = this(
    nodeCount = nodeCount,
    edgeCount = edgeCount,
    containmentCount = containmentCount,
    posts = null,
    indices = Array.tabulate(nodeCount)(identity),
    width = new Array(nodeCount),
    height = new Array(nodeCount),
    centerOffsetX = new Array(nodeCount),
    centerOffsetY = new Array(nodeCount),
    radius = new Array(nodeCount),
    maxRadius = NaN,
    collisionRadius = new Array(nodeCount),
    containmentRadius = new Array(nodeCount),
    nodeParentCount = new Array(nodeCount),
    bgColor = new Array(nodeCount),
    nodeCssClass = new Array(nodeCount),
    nodeReservedArea = new Array(nodeCount),
    totalReservedArea = NaN,
    source = new Array(edgeCount),
    target = new Array(edgeCount),
    containmentChild = new Array(containmentCount),
    containmentParent = new Array(containmentCount),
    containmentTest = new AdjacencyMatrix(nodeCount),
    eulerSetCount = -1,
    eulerSetParent = null,
    eulerSetChildren = null,
    eulerSetDisjunctSetPairs = null,
    eulerSetAllNodes = null,
    eulerSetArea = null,
    eulerSetColor = null,
    eulerSetDepth = null,

    eulerZoneCount = -1,
    eulerZones = null,
    eulerZoneNodes = null,
    eulerZoneArea = null,
    eulerZoneAdjacencyMatrix = null,
    eulerZoneNeighbourhoods = null,
  )
}

class AdjacencyMatrix(nodeCount: Int) {
  private val data = new mutable.BitSet(nodeCount * nodeCount) //TODO: Avoid Quadratic Space complexity when over threshold!
  @inline private def index(source: Int, target: Int): Int = source * nodeCount + target
  @inline def set(source: Int, target: Int): Unit = data.add(index(source, target))
  @inline def apply(source: Int, target: Int): Boolean = data(index(source, target))
}

class EulerSet(val parent: NodeId, val children: Array[NodeId], val depth: Int) {
  val allNodes: Array[NodeId] = children :+ parent
}

object StaticData {
  import ForceSimulation.log

  def apply(
      graph: Graph,
      selection: Selection[Node],
      transform: Transform,
      labelVisualization: PartialFunction[EdgeData.Type, VisualizationType]
  ): StaticData = {
    time(log(s"calculateStaticData[${selection.size()}]")) {

      val PartitionedConnections(edges, containments, tags) =
        partitionConnections(graph.edges, labelVisualization)
//      println("edges: " + edges.mkString(", "))
//      println("containments: " + containments.mkString(", "))

      val nodeCount = graph.nodes.length
      val edgeCount = edges.length
      val containmentCount = containments.length
      val staticData = new StaticData(
        nodeCount = nodeCount,
        edgeCount = edgeCount,
        containmentCount = containmentCount
      )
      staticData.posts = graph.nodes
      val scale = transform.k

      @inline def sq(x: Double) = x * x

      var maxRadius = 0.0
      var reservedArea = 0.0
      selection.each[html.Element] { (elem: html.Element, node: Node, i: Int) =>
        if(graph.hasChildren(node.id)) {
          staticData.bgColor(i) = nodeColorWithContext(graph, node.id).toCSS
          staticData.nodeCssClass(i) = "graphnode-tag"
        } else {
          staticData.nodeCssClass(i) = "nodecard"
        }

        // we set the style here, because the border can affect the size of the element
        // and we want to capture that in the post size
        val elemSelection = d3.select(elem)
          .asInstanceOf[js.Dynamic]
          .classed("tag nodecard", false)
          .classed(staticData.nodeCssClass(i), true)
        if(staticData.bgColor(i) != null)
          elemSelection.style("background-color", staticData.bgColor(i))

        val rect = elem.getBoundingClientRect
        val width = rect.width / scale
        val height = rect.height / scale
        staticData.width(i) = width
        staticData.height(i) = height
        staticData.centerOffsetX(i) = width / -2.0
        staticData.centerOffsetY(i) = height / -2.0
        staticData.radius(i) = Vec2.length(width, height) / 2.0 + nodePadding
        maxRadius = maxRadius max staticData.radius(i)
        staticData.collisionRadius(i) = staticData.radius(i) + nodeSpacing * 0.5
        staticData.containmentRadius(i) = staticData.collisionRadius(i)

        staticData.nodeParentCount(i) = graph.parents(node.id).size //TODO: faster?

        val area = sq(staticData.collisionRadius(i) * 2) // bounding square of bounding circle
        staticData.nodeReservedArea(i) = area
        reservedArea += area
      }
      staticData.maxRadius = maxRadius
      staticData.totalReservedArea = reservedArea

      val nodeIdToIndex = graph.idToIdx

      var i = 0
      while (i < edgeCount) {
        staticData.source(i) = nodeIdToIndex(edges(i).sourceId)
        staticData.target(i) = nodeIdToIndex(edges(i).targetId)
        i += 1
      }

      i = 0
      while (i < containmentCount) {
        val child = nodeIdToIndex(containments(i).sourceId)
        val parent = nodeIdToIndex(containments(i).targetId)
        staticData.containmentChild(i) = child
        staticData.containmentParent(i) = parent
        staticData.containmentTest.set(child, parent)
        i += 1
      }

      val eulerSets: Array[EulerSet] = {
        graph.allParentIdsTopologicallySortedByChildren.map { nodeIdx =>
          val depth = graph.childDepth(graph.nodeIds(nodeIdx))
          new EulerSet(
            parent = graph.nodeIds(nodeIdx),
            children = graph.descendantsIdx(nodeIdx).map(graph.nodeIds),
            depth = depth
          )
        }(breakOut)
      }

      // constructing the dual graph of the euler diagram
      // each zone represents a node,
      // every two zones which are separated by a line of the euler diagram become an edge
      {
        // for each node, the set of parent nodes identifies its zone
        println(graph.toDetailedString)
        val zoneGrouping:Seq[(Set[Int],Seq[Int])] = graph.nodes.indices.groupBy(nodeIdx => graph.parentsIdx(nodeIdx).toSet).flatMap{
          case (zoneParentSet, nodeIndices) if zoneParentSet.isEmpty => // All isolated nodes
            Array(zoneParentSet -> nodeIndices.filterNot(graph.hasChildrenIdx)) // remove parents from isolated nodes
          case (zoneParentSet, nodeIndices) if zoneParentSet.size == 1 =>
            Array(zoneParentSet -> (nodeIndices :+ zoneParentSet.head))
          case other => Array(other)
        }.toSeq ++ graph.nodes.indices.filter(i => graph.hasChildrenIdx(i) && graph.childrenIdx.forall(i)(c => graph.parentsIdx.sliceLength(c) != 1)).map(i => (Set(i), Seq(i)))
        println(s"grouping:\n  "+zoneGrouping.map{case (parents, nodes) => (parents.map(i => graph.nodes(i).str), nodes.map(i => graph.nodes(i).str))}.mkString("\n  "))
        val eulerZones:Array[Set[Int]] = zoneGrouping.map(_._1)(breakOut)
        val eulerZoneNodes:Array[Array[Int]] = zoneGrouping.map(_._2.toArray)(breakOut)
        println(s"nodes:\n"+eulerZoneNodes.zipWithIndex.map{ case (nodes,i) => s"  $i: ${nodes.mkString(",")}"}.mkString("\n"))


        

        def setDifference[T](a:Set[T], b:Set[T]) = (a union b) diff (a intersect b)
        val zoneAdjacencyMatrix = new AdjacencyMatrix(eulerZones.size)
        val neighbourhoodBuilder = mutable.ArrayBuilder.make[(Int,Int)]

        eulerZones.foreachIndex2Combination { (zoneAIdx, zoneBIdx) =>
            // adjacent zones should attract each other, because in the euler diagram they are separated by a line.
            // Two zones in an euler diagram are separated by a line iff their parentSet definitions differ by exactly one element
           if( setDifference(eulerZones(zoneAIdx),eulerZones(zoneBIdx)).size == 1 ) {
             zoneAdjacencyMatrix.set(zoneAIdx, zoneBIdx)
             // if(eulerZones(zoneAIdx).nonEmpty && eulerZones(zoneBIdx).nonEmpty)
               neighbourhoodBuilder += ((zoneAIdx, zoneBIdx))
           }
        }

        staticData.eulerZoneCount = eulerZones.length
        staticData.eulerZones = eulerZones
        staticData.eulerZoneNodes = eulerZoneNodes
        staticData.eulerZoneAdjacencyMatrix = zoneAdjacencyMatrix
        staticData.eulerZoneNeighbourhoods = neighbourhoodBuilder.result()

        staticData.eulerZoneArea = new Array[Double](eulerZones.length)
        eulerZoneNodes.foreachIndexAndElement { (i,zoneNodes) => 
          val arbitraryFactor = 1.3
          staticData.eulerZoneArea(i) = zoneNodes.map { nodeIdx =>
            staticData.nodeReservedArea(nodeIdx)
          }.sum * arbitraryFactor
        }

      }

      //TODO: collapsed euler sets
      // val rxCollapsedContainmentCluster = Rx {
      //   val graph = rxDisplayGraph().graph
      //   val nodeIdToSimPost = rxNodeIdToSimPost()

      //   val children: Map[NodeId, Seq[NodeId]] = rxDisplayGraph().collapsedContainments.groupBy(_.targetId).mapValues(_.map(_.sourceId)(breakOut))
      //   val parents: Iterable[NodeId] = children.keys

      //   parents.map { p =>
      //     new ContainmentCluster(
      //       parent = nodeIdToSimPost(p),
      //       children = children(p).map(p => nodeIdToSimPost(p))(breakOut),
      //       depth = graph.childDepth(p)
      //     )
      //   }.toJSArray
      // }

      i = 0
      val eulerSetCount = eulerSets.length
      staticData.eulerSetCount = eulerSetCount
      staticData.eulerSetAllNodes = new Array[Array[Int]](eulerSetCount)
      staticData.eulerSetChildren = new Array[Array[Int]](eulerSetCount)
      staticData.eulerSetParent = new Array[Int](eulerSetCount)
      staticData.eulerSetArea = new Array[Double](eulerSetCount)
      staticData.eulerSetColor = new Array[String](eulerSetCount)
      staticData.eulerSetDepth = new Array[Int](eulerSetCount)
      while (i < eulerSetCount) {
        staticData.eulerSetChildren(i) = eulerSets(i).children.map(nodeIdToIndex)
        staticData.eulerSetAllNodes(i) = eulerSets(i).allNodes.map(nodeIdToIndex)
        staticData.eulerSetParent(i) = nodeIdToIndex(eulerSets(i).parent)
        staticData.eulerSetDepth(i) = eulerSets(i).depth

        val arbitraryFactor = 1.3
        staticData.eulerSetArea(i) = eulerSets(i).allNodes.map { pid =>
          val pi = nodeIdToIndex(pid)
          staticData.nodeReservedArea(pi)
        }.sum * arbitraryFactor

        val color = d3.lab(eulerBgColor(eulerSets(i).parent).toHex) //TODO: use d3.rgb or make colorado handle opacity
        color.opacity = 0.7
        staticData.eulerSetColor(i) = color.toString

        i += 1
      }

      staticData.eulerSetDisjunctSetPairs = (for {
        i <- 0 until eulerSetCount
        j <- 0 until eulerSetCount
        if i > j && staticData.eulerSetChildren(i).intersect(staticData.eulerSetChildren(j)).isEmpty
      } yield (i,j)).toArray


      staticData
    }
  }

  private case class PartitionedConnections(
      edges: Array[Edge],
      containments: Array[Edge],
      tags: Array[Edge]
  )

  private def partitionConnections(
      connections: Iterable[Edge],
      labelVisualization: PartialFunction[EdgeData.Type, VisualizationType]
  ): PartitionedConnections = {
    val edgeBuilder = ArrayBuffer.empty[Edge]
    val containmentBuilder = ArrayBuffer.empty[Edge]
    val tagBuilder = ArrayBuffer.empty[Edge]

    def separator = labelVisualization.lift

    connections.foreach { connection =>
      separator(connection.data.tpe).foreach {
        case Edge        => edgeBuilder += connection
        case Containment => containmentBuilder += connection
        case Tag         => tagBuilder += connection
      }
    }

    PartitionedConnections(
      containments = containmentBuilder.toArray,
      edges = edgeBuilder.toArray,
      tags = tagBuilder.toArray
    )
  }
}
