package frontend.graphview

import frontend._

import scalajs.js
import js.JSConverters._
import scala.scalajs.js.annotation._
import org.scalajs.dom
import org.scalajs.dom.console
import dom.raw.HTMLElement
import scalajs.concurrent.JSExecutionContext.Implicits.queue
import com.outr.scribe._

import japgolly.scalajs.react._
import japgolly.scalajs.react.vdom.prefix_<^._
import fdietze.scalajs.react.component._

import vectory._

import graph._
import collection.breakOut
import math._

import org.scalajs.d3v4._
import util.collectionHelpers._

case class MenuAction(symbol: String, action: (SimPost, Simulation[SimPost]) => Unit)

object GraphView extends Playground[Graph]("GraphView") {
  val environmentFactory = new D3Environment(_)

  class D3Environment(component: HTMLElement) extends Environment {
    var graph: Graph = _
    override def setProps(newGraph: Graph) { graph = newGraph }
    override def propsUpdated(oldGraph: Graph) { update(graph) }

    //TODO: dynamic by screen size, refresh on window resize, put into centering force
    val width = 640
    val height = 480

    val menuOuterRadius = 100
    val menuInnerRadius = 50
    val menuPaddingAngle = 2 * Pi / 100
    val menuCornerRadius = 3

    val dragHitDetectRadius = 200
    val postDefaultColor = d3.lab("#f8f8f8")
    def baseHue(id: AtomId) = (id * 137) % 360
    def baseColor(id: AtomId) = d3.hcl(baseHue(id), 50, 70)

    val menuActions = {
      import autowire._
      import boopickle.Default._
      (
        MenuAction("Split", { (p: SimPost, s: Simulation[SimPost]) => logger.info(s"Split: ${p.id}") }) ::
        MenuAction("Del", { (p: SimPost, s: Simulation[SimPost]) => Client.api.deletePost(p.id).call() }) ::
        MenuAction("Unfix", { (p: SimPost, s: Simulation[SimPost]) => p.fixedPos = js.undefined; s.restart() }) ::
        Nil
      )
    }

    val dropActions = {
      import autowire._
      import boopickle.Default._
      (
        ("Connect", "green", { (dropped: SimPost, target: SimPost) => Client.api.connect(dropped.id, target.id).call() }) ::
        ("Contain", "blue", { (dropped: SimPost, target: SimPost) => Client.api.contain(target.id, dropped.id).call() }) ::
        ("Merge", "red", { (dropped: SimPost, target: SimPost) => /*Client.api.merge(target.id, dropped.id).call()*/ }) ::
        Nil
      ).toArray
    }
    val dropColors = dropActions.map(_._2)

    var postData: js.Array[SimPost] = js.Array()
    var postIdToSimPost: Map[AtomId, SimPost] = Map.empty
    var connectionData: js.Array[SimConnects] = js.Array()
    var containmentData: js.Array[SimContains] = js.Array()
    var containmentClusters: js.Array[ContainmentCluster] = js.Array()

    var _focusedPost: Option[SimPost] = None
    def focusedPost = _focusedPost
    def focusedPost_=(target: Option[SimPost]) {
      _focusedPost = target
      _focusedPost match {
        case Some(post) =>
          ringMenu.style("visibility", "visible")
          AppCircuit.dispatch(SetRespondingTo(Some(post.id)))
        case None =>
          ringMenu.style("visibility", "hidden")
          AppCircuit.dispatch(SetRespondingTo(None))
      }
    }

    object forces {
      val center = d3.forceCenter[SimPost]()
      val gravityX = d3.forceX[SimPost]()
      val gravityY = d3.forceY[SimPost]()
      val repel = d3.forceManyBody[SimPost]()
      val collision = d3.forceCollide[SimPost]() //TODO: rectangle collision detection?
      val connection = d3.forceLink[ExtendedD3Node, SimConnects]()
      val containment = d3.forceLink[SimPost, SimContains]()
    }

    val simulation = d3.forceSimulation[SimPost]()
      .force("center", forces.center)
      .force("gravityx", forces.gravityX)
      .force("gravityy", forces.gravityY)
      .force("repel", forces.repel)
      .force("collision", forces.collision)
      .force("connection", forces.connection.asInstanceOf[Link[SimPost, SimulationLink[SimPost, SimPost]]])
      .force("containment", forces.containment)

    simulation.on("tick", draw _)

    var transform: Transform = d3.zoomIdentity // stores current pan and zoom

    // prepare containers where we will append elements depending on the data
    // order is important
    val container = d3.select(component)
    val svg = container.append("svg")
    val containmentHulls = svg.append("g")
    val connectionLines = svg.append("g")

    val html = container.append("div")
    val connectionElements = html.append("div")
    val postElements = html.append("div")
    val draggingPostElements = html.append("div") //TODO: place above ring menu?

    val menuSvg = container.append("svg")
    val menuLayer = menuSvg.append("g")
    val ringMenu = menuLayer.append("g")

    initContainerDimensionsAndPositions()
    initRingMenu()
    initZoomEvents()
    initForces()

    svg.on("click", () => focusedPost = None)

    def initForces() {

      forces.center.x(width / 2).y(height / 2)
      forces.gravityX.x(width / 2)
      forces.gravityY.y(height / 2)

      forces.repel.strength(-1000)
      forces.collision.radius((p: SimPost) => p.collisionRadius)

      forces.connection.distance(100)
      forces.containment.distance(100)

      forces.gravityX.strength(0.1)
      forces.gravityY.strength(0.1)
    }

    def initZoomEvents() {
      svg.call(d3.zoom().on("zoom", zoomed _))
    }

    def initContainerDimensionsAndPositions() {
      container
        .style("position", "absolute")
        .style("top", "0")
        .style("left", "0")
        .style("z-index", "-1")
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
    }

    def initRingMenu() {
      menuSvg
        .style("position", "absolute")
        .style("width", "100%")
        .style("height", "100%")
        .style("pointer-events", "none")

      ringMenu
        .style("visibility", "hidden")

      val pie = d3.pie()
        .value(1)
        .padAngle(menuPaddingAngle)

      val arc = d3.arc()
        .innerRadius(menuInnerRadius)
        .outerRadius(menuOuterRadius)
        .cornerRadius(menuCornerRadius)

      val pieData = menuActions.toJSArray
      val ringMenuArc = ringMenu.selectAll("path")
        .data(pie(pieData))
      val ringMenuLabels = ringMenu.selectAll("text")
        .data(pie(pieData))

      ringMenuArc.enter()
        .append("path")
        .attr("d", (d: PieArcDatum[MenuAction]) => arc(d))
        .attr("fill", "rgba(0,0,0,0.7)")
        .style("cursor", "pointer")
        .style("pointer-events", "all")
        .on("click", (d: PieArcDatum[MenuAction]) => focusedPost.foreach(d.data.action(_, simulation)))

      ringMenuLabels.enter()
        .append("text")
        .text((d: PieArcDatum[MenuAction]) => d.data.symbol)
        .attr("text-anchor", "middle")
        .attr("fill", "white")
        .attr("x", (d: PieArcDatum[MenuAction]) => arc.centroid(d)(0))
        .attr("y", (d: PieArcDatum[MenuAction]) => arc.centroid(d)(1))
    }

    def update(graph: Graph) {
      postData = graph.posts.values.map { p =>
        val sp = new SimPost(p)
        postIdToSimPost.get(sp.id).foreach { old =>
          // preserve position
          sp.x = old.x
          sp.y = old.y
        }
        //TODO: d3-color Facades!
        val parents = graph.parents(p.id)
        val parentColors: Seq[Hcl] = parents.map((p: Post) => baseColor(p.id))
        val colors: Seq[Color] = (if (graph.children(p.id).nonEmpty) baseColor(p.id) else postDefaultColor) +: parentColors
        val labColors = colors.map(d3.lab(_))
        val colorSum = labColors.reduce((c1, c2) => d3.lab(c1.l + c2.l, c1.a + c2.a, c1.b + c2.b))
        val colorCount = labColors.size
        val colorAvg = d3.lab(colorSum.l / colorCount, colorSum.a / colorCount, colorSum.b / colorCount)
        sp.color = colorAvg.toString()
        sp
      }.toJSArray
      postIdToSimPost = (postData: js.ArrayOps[SimPost]).by(_.id)

      focusedPost = focusedPost.collect { case sp if postIdToSimPost.isDefinedAt(sp.id) => postIdToSimPost(sp.id) }

      val post = postElements.selectAll("div")
        .data(postData, (p: SimPost) => p.id)

      connectionData = graph.connections.values.map { c =>
        new SimConnects(c, postIdToSimPost(c.sourceId))
      }.toJSArray
      val connIdToSimConnects: Map[AtomId, SimConnects] = (connectionData: js.ArrayOps[SimConnects]).by(_.id)
      connectionData.foreach { e =>
        e.target = postIdToSimPost.getOrElse(e.targetId, connIdToSimConnects(e.targetId))
      }
      val connectionLine = connectionLines.selectAll("line")
        .data(connectionData, (r: SimConnects) => r.id)
      val connectionElement = connectionElements.selectAll("div")
        .data(connectionData, (r: SimConnects) => r.id)

      containmentData = graph.containments.values.map { c =>
        new SimContains(c, postIdToSimPost(c.parentId), postIdToSimPost(c.childId))
      }.toJSArray

      containmentClusters = {
        val parents: Seq[Post] = graph.containments.values.map(c => graph.posts(c.parentId)).toSeq.distinct
        parents.map(p => new ContainmentCluster(postIdToSimPost(p.id), graph.children(p.id).map(p => postIdToSimPost(p.id))(breakOut))).toJSArray
      }
      val containmentHull = containmentHulls.selectAll("path")
        .data(containmentClusters, (c: ContainmentCluster) => c.parent.id)

      post.exit().remove()
      post.enter().append("div")
        .text((post: SimPost) => post.title)
        .style("background-color", (post: SimPost) => post.color)
        .style("padding", "3px 5px")
        .style("border-radius", "5px")
        .style("border", "1px solid #AAA")
        .style("max-width", "100px")
        .style("position", "absolute")
        .style("cursor", "default")
        .style("pointer-events", "auto") // reenable
        .on("click", { (p: SimPost) =>
          //TODO: click should not trigger drag
          if (focusedPost.isEmpty || focusedPost.get != p)
            focusedPost = Some(p)
          else
            focusedPost = None
          draw()
        })
        .call(d3.drag[SimPost]()
          .on("start", postDragStarted _)
          .on("drag", postDragged _)
          .on("end", postDragEnded _))

      connectionLine.enter().append("line")
        .style("stroke", "#8F8F8F")
      connectionLine.exit().remove()

      connectionElement.enter().append("div")
        .style("position", "absolute")
        .style("font-size", "20px")
        .style("margin-left", "-0.5ex")
        .style("margin-top", "-0.5em")
        .text("\u00d7")
        .style("pointer-events", "auto") // reenable
        .style("cursor", "pointer")
        .on("click", { (e: SimConnects) =>
          import autowire._
          import boopickle.Default._

          Client.api.deleteConnection(e.id).call()
        })
      connectionElement.exit().remove()

      containmentHull.enter().append("path")
        .style("fill", (cluster: ContainmentCluster) => cluster.parent.color)
        .style("stroke", (cluster: ContainmentCluster) => cluster.parent.color)
        .style("stroke-width", "70px")
        .style("stroke-linejoin", "round")
        .style("opacity", "0.7")
      containmentHull.exit().remove()

      postElements.selectAll("div").each({ (node: HTMLElement, p: SimPost) =>
        //TODO: if this fails, because post is not rendered yet, recalculate it lazyly
        val rect = node.getBoundingClientRect
        p.size = Vec2(rect.width, rect.height)
        p.centerOffset = p.size / -2
        p.radius = p.size.length / 2
        p.collisionRadius = p.radius
      })

      forces.connection.strength { (e: SimConnects) =>
        import graph.fullDegree
        val targetDeg = e.target match {
          case p: SimPost => fullDegree(p.post)
          case _: SimConnects => 2
        }
        1.0 / min(fullDegree(e.source.post), targetDeg)
      }

      forces.containment.strength { (e: SimContains) =>
        import graph.fullDegree
        1.0 / min(fullDegree(e.source.post), fullDegree(e.target.post))
      }

      simulation.nodes(postData)
      forces.connection.links(connectionData)
      forces.containment.links(containmentData)
      simulation.alpha(1).restart()
    }

    def updateDraggingPosts() {
      val posts = graph.posts.values
      val draggingPosts = posts.flatMap(p => postIdToSimPost(p.id).draggingPost).toJSArray
      val post = draggingPostElements.selectAll("div")
        .data(draggingPosts, (p: SimPost) => p.id)

      post.enter().append("div")
        .text((post: SimPost) => post.title)
        .style("opacity", "0.5")
        .style("background-color", (post: SimPost) => post.color)
        .style("padding", "3px 5px")
        .style("border-radius", "5px")
        .style("border", "1px solid #AAA")
        .style("max-width", "100px")
        .style("position", "absolute")
        .style("cursor", "move")

      post.exit().remove()
    }

    def zoomed() {
      transform = d3.event.asInstanceOf[ZoomEvent].transform
      svg.selectAll("g").attr("transform", transform.toString)
      html.style("transform", s"translate(${transform.x}px,${transform.y}px) scale(${transform.k})")
      menuLayer.attr("transform", transform.toString)
    }

    def postDragStarted(p: SimPost) {
      val draggingPost = p.newDraggingPost
      p.draggingPost = Some(draggingPost)
      updateDraggingPosts()

      val eventPos = Vec2(d3.event.asInstanceOf[DragEvent].x, d3.event.asInstanceOf[DragEvent].y)
      p.dragStart = eventPos
      draggingPost.pos = eventPos
      drawDraggingPosts()

      simulation.stop()
    }

    def postDragged(p: SimPost) {
      val draggingPost = p.draggingPost.get
      val eventPos = Vec2(d3.event.asInstanceOf[DragEvent].x, d3.event.asInstanceOf[DragEvent].y)
      val transformedEventPos = p.dragStart + (eventPos - p.dragStart) / transform.k
      val closest = simulation.find(transformedEventPos.x, transformedEventPos.y, dragHitDetectRadius).toOption

      p.dragClosest.foreach(_.isClosest = false)
      closest match {
        case Some(target) if target != p =>
          val dir = draggingPost.pos.get - target.pos.get
          target.isClosest = true
          target.dropAngle = dir.angle
        case _ =>
      }
      p.dragClosest = closest

      draggingPost.pos = transformedEventPos
      drawDraggingPosts()
      drawPosts() // for highlighting closest
    }

    def postDragEnded(p: SimPost) {
      logger.info("postDragEnded")
      val eventPos = Vec2(d3.event.asInstanceOf[DragEvent].x, d3.event.asInstanceOf[DragEvent].y)
      val transformedEventPos = p.dragStart + (eventPos - p.dragStart) / transform.k

      val closest = simulation.find(transformedEventPos.x, transformedEventPos.y, dragHitDetectRadius).toOption
      closest match {
        case Some(target) if target != p =>
          import autowire._
          import boopickle.Default._

          dropActions(target.dropIndex(dropActions.size))._3(p, target)

          target.isClosest = false
          p.fixedPos = js.undefined
        case _ =>
          p.pos = transformedEventPos
          p.fixedPos = transformedEventPos
      }

      p.draggingPost = None
      updateDraggingPosts()
      drawDraggingPosts()

      simulation.alpha(1).restart()
    }

    def draw() {
      drawPosts()
      drawRelations()
      drawPostMenu()
    }

    def drawDraggingPosts() {
      draggingPostElements.selectAll("div")
        .style("left", (p: SimPost) => s"${p.x.get + p.centerOffset.x}px")
        .style("top", (p: SimPost) => s"${p.y.get + p.centerOffset.y}px")
    }

    def drawPosts() {
      postElements.selectAll("div")
        .style("left", (p: SimPost) => s"${p.x.get + p.centerOffset.x}px")
        .style("top", (p: SimPost) => s"${p.y.get + p.centerOffset.y}px")
        .style("border", (p: SimPost) => if (p.isClosest) s"5px solid ${dropColors(p.dropIndex(dropActions.size))}" else "1px solid #AAA")
    }

    def drawRelations() {
      connectionElements.selectAll("div")
        .style("left", (e: SimConnects) => s"${e.x.get}px")
        .style("top", (e: SimConnects) => s"${e.y.get}px")

      connectionLines.selectAll("line")
        .attr("x1", (e: SimConnects) => e.source.x)
        .attr("y1", (e: SimConnects) => e.source.y)
        .attr("x2", (e: SimConnects) => e.target.x)
        .attr("y2", (e: SimConnects) => e.target.y)

      containmentHulls.selectAll("path")
        .attr("d", { (cluster: ContainmentCluster) =>
          // https://codeplea.com/introduction-to-splines
          // https://github.com/d3/d3-shape#curves
          val points = cluster.convexHull
          // val curve = d3.curveCardinalClosed
          val curve = d3.curveCatmullRomClosed.alpha(0.5)
          // val curve = d3.curveNatural

          d3.line().curve(curve)(points)
        })
    }

    def drawPostMenu() {
      focusedPost.foreach { post =>
        ringMenu.attr("transform", s"translate(${post.x}, ${post.y})")
      }
    }

  }

}
