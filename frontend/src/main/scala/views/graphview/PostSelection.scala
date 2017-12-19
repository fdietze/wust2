package wust.frontend.views.graphview

import org.scalajs.d3v4._
import org.scalajs.dom.raw.HTMLElement
import rx._
import wust.frontend._
import wust.util.collection._
import wust.util.Analytics

import scala.collection.breakOut
import scalatags.JsDom.all._

class PostSelection(graphState: GraphState, d3State: D3State, postDrag: PostDrag) extends DataSelection[SimPost] {
  import graphState.rxFocusedSimPost
  import postDrag._

  override val tag = "div"
  override def enter(post: Enter[SimPost]) {
    post.append((simPost: SimPost) => GraphView.postView(simPost.post)(
      title := simPost.title,
      position.absolute,
      pointerEvents.auto, // reenable
      cursor.default
    ).render)
      //TODO: http://bl.ocks.org/couchand/6394506 distinguish between click and doubleclick, https://stackoverflow.com/questions/42330521/distinguishing-click-and-double-click-in-d3-version-4
      //TODO: Doubleclick -> Focus
      .on("click", { (p: SimPost) =>
        //TODO: click should not trigger drag
        DevPrintln(s"\nClicked Post: ${p.id} ${p.title}")
        Var.set(
          VarTuple(rxFocusedSimPost, rxFocusedSimPost.now.map(_.id).setOrToggle(p.id)),
          VarTuple(graphState.state.postCreatorMenus, Nil)
        )
      })
      .call(d3.drag[SimPost]()
        .clickDistance(10) // interpret short drags as clicks
        .on("start", { (simPost: SimPost) =>
          Var.set(
            VarTuple(graphState.state.focusedPostId, None),
            VarTuple(graphState.state.postCreatorMenus, Nil)
          )
          postDragStarted(simPost)
        })
        .on("drag", postDragged _)
        .on("end", postDragEnded _))
  }

  override def update(post: Selection[SimPost]) {
    post
      .style("font-size", (post: SimPost) => post.fontSize)
      .style("background-color", (post: SimPost) => post.color)
      .style("border", (p: SimPost) => p.border)
      .style("opacity", (p: SimPost) => p.opacity)
      .text((p: SimPost) => p.title)

    recalculateNodeSizes(post)
  }

  private def recalculateNodeSizes(post: Selection[SimPost]) {
    post.each({ (node: HTMLElement, p: SimPost) =>
      p.recalculateSize(node, d3State.transform.k)
    })

    // for each connected component give all posts the maximum collision radius within that component
    val graph = graphState.state.displayGraphWithoutParents.now.graph
    graph.connectedContainmentComponents.foreach { component =>
      val simPosts: List[SimPost] = component.map(graphState.rxPostIdToSimPost.now)(breakOut)
      val maxRadius = simPosts.maxBy(_.radius).radius
      simPosts.foreach { _.collisionRadius = maxRadius }
    }
    d3State.forces.collision.initialize(post.data)
  }

  private var draw = 0
  override def draw(post: Selection[SimPost]) {

    // DevOnly {
    //   assert(post.data().forall(_.size.width == 0) || post.data().forall(_.size.width != 0))
    // }
    val onePostHasSizeZero = {
      // every drawcall exactly one different post is checked
      val simPosts = post.data()
      if (simPosts.isEmpty) false
      else simPosts(draw % simPosts.size).size.width == 0

    }
    if (onePostHasSizeZero) {
      // if one post has size zero => all posts have size zero
      // --> recalculate all visible sizes
      recalculateNodeSizes(post)
    }

    post
      // .style("left", (p: SimPost) => s"${p.x.get + p.centerOffset.x}px")
      // .style("top", (p: SimPost) => s"${p.y.get + p.centerOffset.y}px")
      .style("transform", (p: SimPost) => s"translate(${p.x.get + p.centerOffset.x}px,${p.y.get + p.centerOffset.y}px)")

    draw += 1
  }
}
