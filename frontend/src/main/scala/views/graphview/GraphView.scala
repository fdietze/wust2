package wust.frontend.views.graphview

import scala.scalajs.js.JSConverters._
import org.scalajs.d3v4._
import org.scalajs.dom
import org.scalajs.dom.raw.HTMLElement
import org.scalajs.dom.{Element, window, console}
import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.dom.dsl.styles.extra._
import rx._
import vectory._
import wust.frontend.Color._
import wust.frontend.views.View
import wust.frontend.{DevOnly, DevPrintln, GlobalState}
import wust.graph._
import wust.util.outwatchHelpers._
import wust.util.time.time
import wust.ids._

import scala.concurrent.ExecutionContext
import scala.scalajs.js
import wust.frontend.views.Elements._
import wust.frontend.views.Rendered._
import wust.frontend.views.Placeholders


//TODO: remove disableSimulation argument, as it is only relevant for tests. Better solution?
class GraphView(disableSimulation: Boolean = false)(implicit ec: ExecutionContext, owner: Ctx.Owner) extends View {
  override val key = "graph"
  override val displayName = "Mindmap"

  override def apply(state: GlobalState) = {
    val container = Handler.create[dom.html.Element].unsafeRunSync()
    val htmlLayer = Handler.create[dom.html.Element].unsafeRunSync()
    val svgLayer = Handler.create[dom.svg.Element].unsafeRunSync()

    val d3State = new D3State(disableSimulation)
    val graphState = new GraphState(state)

    // since all elements get inserted at once, we can zip and only have one function call
    Observable.zip3(container, htmlLayer, svgLayer).foreach { case (container, htmlLayer, svgLayer) =>
      println("Initializing Graph view")
      new GraphViewInstance(
        state,
        d3State,
        graphState,
        container,
        htmlLayer,
        svgLayer,
        disableSimulation
      )
    }

    div(
      height := "100%",
      backgroundColor <-- state.pageStyle.map(_.bgColor),

      tag("svg")(
        onInsert.asSvg --> svgLayer,
        GraphView.svgArrow
      ),
      div(onInsert.asHtml --> htmlLayer),
      onInsert.asHtml --> container,

      children <-- graphState.postCreationMenus.map(_.map { menu =>
        PostCreationMenu(state, graphState, menu, d3State.transform)
      }).toObservable,

      child <-- graphState.selectedPostId.map(_.map { id =>
        SelectedPostMenu(id, state, graphState, d3State.transform)
      }).toObservable
    )
  }
}

object GraphView {
  def postView(post: Post) = div(
    mdHtml(post.content),
    cls := "graphpost"
  )

  val svgArrow =
    tag("svg:defs")(
      tag("svg:marker")(
        attr("id") := "graph_arrow",
        attr("viewBox") := "0 -3 10 6", // x y w h
        attr("refX") := 35, // This is a workaround. The line is longer than displayed...
        attr("markerWidth") := 15,
        attr("markerHeight") := 9,
        attr("orient") := "auto",
        tag("svg:path")(
          attr("d") := "M 0,-3 L 10,-0.5 L 10,0.5 L0,3",
          style("fill") := "#666"
        )
      )
    )
}

case class DragAction(name: String, action: (SimPost, SimPost) => Unit)

object KeyImplicits {
  implicit val SimPostWithKey = new WithKey[SimPost](_.id)
  implicit val SimConnectionWithKey = new WithKey[SimConnection](c => s"${c.sourceId} ${c.targetId}")
  implicit val SimRedirectedConnectionWithKey = new WithKey[SimRedirectedConnection](c => s"${c.sourceId} ${c.targetId}")
  implicit val ContainmentClusterWithKey = new WithKey[ContainmentCluster](_.id)
}

class GraphViewInstance(
                         state: GlobalState,
                         d3State: D3State,
                         graphState: GraphState,
                         element: dom.html.Element,
                         htmlLayer: dom.html.Element,
                         svgLayer: dom.svg.Element,
                         disableSimulation: Boolean = false
                       )(implicit ec: ExecutionContext, ctx: Ctx.Owner) {
  val postDrag = new PostDrag(graphState, d3State, onPostDrag _, onPostDragEnd _)

  import state.inner.{displayGraphWithoutParents => rxDisplayGraph, _}
  import graphState._

  // prepare containers where we will append elements depending on the data
  // order is important
  import KeyImplicits._

  val container = d3.select(element)

  val svg = d3.select(svgLayer)
  val containmentClusterSelection = SelectData.rx(ContainmentClusterSelection, rxContainmentCluster)(svg.append("g"))
  val collapsedContainmentClusterSelection = SelectData.rx(CollapsedContainmentClusterSelection, rxCollapsedContainmentCluster)(svg.append("g"))
  val connectionLineSelection = SelectData.rx(ConnectionLineSelection, rxSimConnection)(svg.append("g"))
  val redirectedConnectionLineSelection = SelectData.rx(RedirectedConnectionLineSelection, rxSimRedirectedConnection)(svg.append("g"))
  // useful for simulation debugging: (also uncomment in draw())
  val postRadiusSelection = SelectData.rx(new PostRadiusSelection(graphState, d3State), rxSimPosts)(svg.append("g"))
  val postCollisionRadiusSelection = SelectData.rx(PostCollisionRadiusSelection, rxSimPosts)(svg.append("g"))
  val postContainmentRadiusSelection = SelectData.rx(PostContainmentRadiusSelection, rxSimPosts)(svg.append("g"))

  val html = d3.select(htmlLayer)
  val connectionElementSelection = SelectData.rx(new ConnectionElementSelection(graphState), rxSimConnection)(html.append("div"))
  val rawPostSelection = new PostSelection(graphState, d3State, postDrag, updatedNodeSizes _)
  val postSelection = SelectData.rx(rawPostSelection, rxSimPosts)(html.append("div"))
  val draggingPostSelection = SelectData.rxDraw(DraggingPostSelection, postDrag.draggingPosts)(html.append("div")) //TODO: place above ring menu?

  val menuSvg = container.append("svg")
  val dragMenuLayer = menuSvg.append("g")
  val dragMenuSelection = SelectData.rxDraw(new DragMenuSelection(postDrag.dragActions, d3State), postDrag.closestPosts)(dragMenuLayer.append("g"))

  //  val controls = {
  //    val iconButton = button(width := "2.5em", padding := "5px 10px")
  //    container.append(() => div(
  //      position.absolute, left := "5px", top := "100px",
  //      iconButton("⟳", title := "automatic layout",
  //        onMouseDown --> { () =>
  //          rxSimPosts.now.foreach { simPost =>
  //            simPost.fixedPos = js.undefined
  //          }
  //          d3State.simulation.alpha(1).alphaTarget(1).restart()
  //        },
  //        onMouseUp --> { () =>
  //          d3State.simulation.alphaTarget(0)
  //        }), br(),
  //      DevOnly {
  //        div(
  //          button("tick", onClick --> {
  //            rxSimPosts.now.foreach { simPost =>
  //              simPost.fixedPos = js.undefined
  //            }
  //            d3State.simulation.tick()
  //            draw()
  //          }), br(),
  //          button("stop", onClick --> {
  //            d3State.simulation.stop()
  //            draw()
  //          }), br(),
  //          button("draw", onClick --> {
  //            rxSimPosts.now.foreach { simPost =>
  //              simPost.fixedPos = js.undefined
  //            }
  //            draw()
  //          }), br(),
  //          iconButton("+", title := "zoom in", onClick --> {
  //            svg.call(d3State.zoom.scaleBy _, 1.2) //TODO: transition for smooth animation, zoomfactor in global constant
  //          }), br(),
  //          iconButton("0", title := "reset zoom", onClick --> {
  //            recalculateBoundsAndZoom()
  //          }), br(),
  //          iconButton("-", title := "zoom out", onClick --> {
  //            svg.call(d3State.zoom.scaleBy _, 1 / 1.2) //TODO: transition for smooth animation, zoomfactor in global constant
  //          })
  //        )
  //      }
  //    ).render)
  //  }

  // Arrows
  initContainerDimensionsAndPositions()
  initEvents()

  state.jsErrors.foreach { _ => d3State.simulation.stop() }

  //TODO: react on size of div
  // https://marcj.github.io/css-element-queries/
  events.window.onResize.foreach(_ => recalculateBoundsAndZoom())

  def recalculateBoundsAndZoom(): Unit = {
    import Math._
    // val padding = 15
    time("recalculateBoundsAndZoom") {
      val rect = container.node.asInstanceOf[HTMLElement].getBoundingClientRect
      val width = rect.width
      val height = rect.height
      if (width > 0 && height > 0 && rxSimPosts.now.nonEmpty && rxSimPosts.now.head.radius > 0) {
        // DevPrintln("    updating bounds and zoom")
        val graph = rxDisplayGraph.now.graph
        // DevPrintln(graph.allParentIds.map(postId => rxPostIdToSimPost.now(postId).containmentArea).toString)
        // DevPrintln(rxSimPosts.now.map(_.collisionArea).toString)
        // val postsArea = graph.toplevelPostIds.map( postId => rxPostIdToSimPost.now(postId).containmentArea ).sum
        val arbitraryFactor = 1.3
        val postsArea = rxSimPosts.now.foldLeft(0.0)((sum, post) => sum + post.collisionBoundingSquareArea) * arbitraryFactor
        val scale = sqrt((width * height) / postsArea) min 1.5 // scale = sqrt(ratio) because areas grow quadratically
        // DevPrintln(s"    parentsArea: $postsArea, window: ${width * height}")
        // DevPrintln(s"    scale: $scale")

        svg.call(d3State.zoom.transform _, d3.zoomIdentity
          .translate(width / 2, height / 2)
          .scale(scale))

        d3State.forces.meta.rectBound.xOffset = -width / 2 / scale
        d3State.forces.meta.rectBound.yOffset = -height / 2 / scale
        d3State.forces.meta.rectBound.width = width / scale
        d3State.forces.meta.rectBound.height = height / scale
        d3State.forces.meta.gravity.width = width / scale
        d3State.forces.meta.gravity.height = height / scale
        InitialPosition.width = width / scale
        InitialPosition.height = height / scale

        // rxSimPosts.now.foreach { simPost =>
        //   simPost.fixedPos = js.undefined
        // }
        d3State.simulation.alpha(1).restart()
      }
    }
  }

  Rx {
    rxDisplayGraph()
    rxSimPosts()
    rxSimConnection()
    rxSimContainment()
    rxContainmentCluster()
  }.foreach { _ =>
    val simPosts = rxSimPosts.now
    val simConnection = rxSimConnection.now
    val simRedirectedConnection = rxSimRedirectedConnection.now
    val simContainment = rxSimContainment.now
    val simCollapsedContainment = rxSimCollapsedContainment.now
    val graph = rxDisplayGraph.now.graph

    DevPrintln("    updating graph simulation")

    d3State.simulation.nodes(simPosts) // also sets initial positions if NaN
    d3State.forces.connection.links(simConnection)
    d3State.forces.redirectedConnection.links(simRedirectedConnection)
    d3State.forces.containment.links(simContainment)
    d3State.forces.collapsedContainment.links(simCollapsedContainment)

    d3State.forces.meta.setConnections(rxSimConnection.now)
    d3State.forces.meta.setContainments(rxSimContainment.now)
    d3State.forces.meta.setContainmentClusters(rxContainmentCluster.now)

    draw() // triggers updating node sizes
    // wait for the drawcall and start simulation
    window.requestAnimationFrame { (_) =>
      recalculateBoundsAndZoom()
      d3State.simulation.alpha(1).restart()
    }
  }

  def updatedNodeSizes(): Unit = {
    DevPrintln("    updating node sizes")
    d3State.forces.connection.initialize(rxSimPosts.now)
    d3State.forces.redirectedConnection.initialize(rxSimPosts.now)
    d3State.forces.containment.initialize(rxSimPosts.now)
    d3State.forces.collapsedContainment.initialize(rxSimPosts.now)
    rxContainmentCluster.now.foreach(_.recalculateConvexHull())

    calculateRecursiveContainmentRadii()
    d3State.forces.meta.updatedNodeSizes()
    recalculateBoundsAndZoom()
  }

  def calculateRecursiveContainmentRadii(): Unit = {
    DevPrintln("       calculateRecursiveContainmentRadii")

    def circleAreaToRadius(a: Double) = Math.sqrt(a / Math.PI) // a = PI*r^2 solved by r
    def circleArea(r: Double) = Math.PI * r * r

    val graph = rxDisplayGraph.now.graph
    DevPrintln("need-----------------")
    for (postId <- graph.postIdsTopologicalSortedByParents) {
      val post = rxPostIdToSimPost.now(postId)
      val children = graph.children(postId)
      if (children.nonEmpty) {
        var childRadiusMax = 0.0
        val childrenArea: Double = children.foldLeft(0.0) { (sum, childId) =>
          val child = rxPostIdToSimPost.now(childId)
          if (child.containmentRadius > childRadiusMax) {
            childRadiusMax = child.containmentRadius
            DevPrintln(s"max: $childRadiusMax by ${child.content}")
          }
          // sum + child.containmentArea
          val arbitraryFactor = 1.5 // 1.5 is arbitrary to have more space
          sum + child.containmentBoundingSquareArea * arbitraryFactor
        }

        DevPrintln(s"need children: $children ${children.map(rxPostIdToSimPost.now).map(_.containmentArea)}")
        DevPrintln(s"need sum: $childrenArea")

        val neededArea = post.containmentArea + childrenArea
        DevPrintln(s"neededArea = ${post.containmentArea} + $childrenArea = $neededArea (${children.size} children)")
        val neededRadius = post.containmentRadius + childRadiusMax * 2 // so that the largest node still fits in the bounding radius of the cluster
        post.containmentRadius = circleAreaToRadius(neededArea) max neededRadius
      }
    }
  }

  private def onPostDrag(): Unit = {
    draggingPostSelection.draw()
  }

  private def onPostDragEnd(): Unit = {
    rxContainmentCluster.now.foreach {
      _.recalculateConvexHull()
    }
    draw()
  }

  private def initEvents(): Unit = {
    d3State.zoom
      .on("zoom", () => zoomed())
      .clickDistance(10) // interpret short drags as clicks
    DevOnly {
      svg.call(d3State.zoom)
    } // activate pan + zoom on svg

    svg.on("click", { () =>
      //TODO: Also show postCreationMenu when no user is present
      if (postCreationMenus.now.isEmpty && selectedPostId.now.isEmpty) {
        val author = state.inner.currentUser.now
        val pos = d3State.transform.now.invert(d3.mouse(svg.node))
        postCreationMenus() = List(PostCreationMenu.Menu(Vec2(pos(0), pos(1)), author.id))
      } else {
        // Var.set(
        //   VarTuple(state.postCreationMenus, Nil),
        //   VarTuple(focusedPostId, None)
        // )
        postCreationMenus() = Nil
        selectedPostId() = None
      }
    })

    val staticForceLayout = false
    var inInitialSimulation = staticForceLayout
    if (inInitialSimulation) container.style("visibility", "hidden")
    d3State.simulation.on("tick", () => if (!inInitialSimulation) draw())
    d3State.simulation.on("end", { () =>
      // rxSimPosts.now.foreach { simPost =>
      //   simPost.fixedPos = simPost.pos
      // }
      if (inInitialSimulation) {
        container.style("visibility", "visible")
        draw()
        inInitialSimulation = false
      }
      DevPrintln("simulation ended.")
    })
  }

  private def zoomed(): Unit = {
    val transform = d3State.transform.now
    val htmlTransformString = s"translate(${transform.x}px,${transform.y}px) scale(${transform.k})"
    svg.selectAll("g").attr("transform", transform.toString)
    html.style("transform", htmlTransformString)
  }

  private def draw(): Unit = {
    d3State.forces.meta.updateClusterConvexHulls()

    postSelection.draw()
    connectionLineSelection.draw()
    redirectedConnectionLineSelection.draw()
    connectionElementSelection.draw()
    containmentClusterSelection.draw()
    collapsedContainmentClusterSelection.draw()

    // debug draw:
    postRadiusSelection.draw()
    postCollisionRadiusSelection.draw()
    postContainmentRadiusSelection.draw()
  }

  private def initContainerDimensionsAndPositions(): Unit = {
    container
      .style("position", "relative")
      .style("width", "100%")
      .style("height", "100%")
      .style("overflow", "hidden")

    svg
      .style("position", "absolute")
      .style("width", "100%")
      .style("height", "100%")

    html
      .style("position", "absolute")
      .style("pointer-events", "none") // pass through to svg (e.g. zoom)
      .style("transform-origin", "top left") // same as svg default
      .style("width", "100%")
      .style("height", "100%")

    menuSvg
      .style("position", "absolute")
      .style("width", "100%")
      .style("height", "100%")
      .style("pointer-events", "none")
  }
}
