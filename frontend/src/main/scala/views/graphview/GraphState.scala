package wust.frontend.views.graphview

import rx._
import rxext._
import wust.frontend.Color._
import wust.frontend.{DevOnly, GlobalState}
import wust.graph._
import wust.util.Pipe
import wust.util.collection._

import scala.collection.breakOut
import scala.scalajs.js
import scala.scalajs.js.JSConverters._

class GraphState(val state: GlobalState)(implicit ctx: Ctx.Owner) {
  val rxDisplayGraph = state.displayGraph
  val rxEditedPostId = state.editedPostId
  val rxCollapsedPostIds = state.collapsedPostIds

  val rxSimPosts: Rx[js.Array[SimPost]] = Rx {
    val rawGraph = state.rawGraph()
    val graph = rxDisplayGraph().graph
    val collapsedPostIds = rxCollapsedPostIds()

    graph.posts.map { p =>
      val sp = new SimPost(p)

      def parents = rawGraph.parents(p.id)
      def hasParents = parents.nonEmpty
      def mixedDirectParentColors = mixColors(parents.map(baseColor))
      def hasChildren = graph.children(p.id).nonEmpty

      //TODO: move border and color to views.post()
      sp.border =
        if (hasChildren) "none"
        else "2px solid rgba(0,0,0,0.2)" // no children

      sp.color = (
        //TODO collapsedPostIds is not sufficient for being a parent (butt currently no knowledge about collapsed children in graph)
        if (hasChildren) {
          if (collapsedPostIds(p.id))
            postDefaultColor
          else
            "transparent" // convex hull shows the color instead
        } else { // no children
          if (hasParents)
            mixColors(mixedDirectParentColors, postDefaultColor)
          else
            postDefaultColor
        }
      ).toString

      sp.opacity = if (graph.groupsByPostId(p.id) contains state.selectedGroupId()) 1.0 else 0.3

      sp

    }.toJSArray
  }

  val rxPostIdToSimPost: Rx[Map[PostId, SimPost]] = rxSimPosts.fold(Map.empty[PostId, SimPost]) {
    (previousMap, simPosts) =>
      (simPosts: js.ArrayOps[SimPost]).by(_.id) ||> (_.foreach {
        case (id, simPost) =>
          previousMap.get(id).foreach { previousSimPost =>
            // preserve position, velocity and fixed position
            simPost.x = previousSimPost.x
            simPost.y = previousSimPost.y
            simPost.vx = previousSimPost.vx
            simPost.vy = previousSimPost.vy
            simPost.fx = previousSimPost.fx
            simPost.fy = previousSimPost.fy
          }
      })
  }

  //TODO: multiple menus for multi-user multi-touch interface?
  val rxFocusedSimPost = RxVar(state.focusedPostId, Rx { state.focusedPostId().flatMap(rxPostIdToSimPost().get) })
  // val rxFocusedSimPost = state.focusedPostId.combine { fp => fp.flatMap(postIdToSimPost().get) } // TODO: Possible? See RxExt

  val rxSimConnects = Rx {
    val graph = rxDisplayGraph().graph
    val postIdToSimPost = rxPostIdToSimPost()

    val newData = graph.connections.map { c =>
      new SimConnects(c, postIdToSimPost(c.sourceId))
    }.toJSArray

    val connIdToSimConnects: Map[ConnectsId, SimConnects] = (newData: js.ArrayOps[SimConnects]).by(_.id)

    // set hyperedge targets, goes away with custom linkforce
    newData.foreach { e =>
      e.target = e.targetId match {
        case id: PostId => postIdToSimPost(id)
        case id: ConnectsId => connIdToSimConnects(id)
        case _: ConnectableId => throw new Exception("Unresolved ConnectableId found. Should not happen in consistent graph.")
      }
    }

    newData
  }

  val rxSimRedirectedConnects = Rx {
    val displayGraph = rxDisplayGraph()
    val postIdToSimPost = rxPostIdToSimPost()

    displayGraph.redirectedConnections.map { c =>
      new SimRedirectedConnects(c, postIdToSimPost(c.sourceId), postIdToSimPost(c.targetId))
    }.toJSArray
  }

  val rxSimContains = Rx {
    val graph = rxDisplayGraph().graph
    val postIdToSimPost = rxPostIdToSimPost()

    graph.containments.map { c =>
      new SimContains(c, postIdToSimPost(c.parentId), postIdToSimPost(c.childId))
    }.toJSArray
  }

  val rxSimCollapsedContains = Rx {
    val postIdToSimPost = rxPostIdToSimPost()

    rxDisplayGraph().collapsedContainments.map { c =>
      new SimCollapsedContains(c, postIdToSimPost(c.parentId), postIdToSimPost(c.childId))
    }.toJSArray
  }

  val rxContainmentCluster = Rx {
    val graph = rxDisplayGraph().graph
    val postIdToSimPost = rxPostIdToSimPost()

    val parents: Seq[PostId] = graph.containments.map(_.parentId)(breakOut).distinct

    // due to transitive containment visualisation,
    // inner posts should be drawn above outer ones.
    val ordered = parents.topologicalSortBy(graph.children)

    ordered.map { p =>
      new ContainmentCluster(
        parent = postIdToSimPost(p),
        children = graph.transitiveChildren(p).map(p => postIdToSimPost(p))(breakOut),
        depth = graph.depth(p)
      )
    }.toJSArray
  }

  val rxCollapsedContainmentCluster = Rx {
    val graph = rxDisplayGraph().graph
    val postIdToSimPost = rxPostIdToSimPost()

    val children: Map[PostId, Seq[PostId]] = rxDisplayGraph().collapsedContainments.groupBy(_.parentId).mapValues(_.map(_.childId)(breakOut))
    val parents: Iterable[PostId] = children.keys

    parents.map { p =>
      new ContainmentCluster(
        parent = postIdToSimPost(p),
        children = children(p).map(p => postIdToSimPost(p))(breakOut),
        depth = graph.depth(p)
      )
    }.toJSArray
  }

  DevOnly {
    rxSimPosts.debug(v => s"  simPosts: ${v.size}")
    rxPostIdToSimPost.debug(v => s"  postIdToSimPost: ${v.size}")
    rxSimConnects.debug(v => s"  simConnects: ${v.size}")
    rxSimContains.debug(v => s"  simContains: ${v.size}")
    rxContainmentCluster.debug(v => s"  containmentCluster: ${v.size}")
    rxFocusedSimPost.rx.debug(v => s"  focusedSimPost: ${v.size}")
  }
}
