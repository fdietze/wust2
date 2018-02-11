 package wust.webApp.views.graphview

 import d3v4._
 import wust.graph.Post
 import wust.ids.PostId
 import wust.util.Pipe

 import scala.math._

 class SimPost(val post: Post) extends ExtendedD3Node with SimulationNodeImpl {
   @inline def id: PostId = post.id

   @inline def content: String = post.content


  var color = "red"
  var fontSize = "100%"
  var border = "none"
  var opacity = 1.0

  var dragClosest: Option[SimPost] = None
  var isClosest = false
  var dropAngle = 0.0
  def dropIndex(n: Int) = {
    val positiveAngle = (dropAngle + 2.5 * Pi) % (2 * Pi) // 0 degree is at 12 o'clock, proceeding clockwise (conform to d3.pie())
    val stepSize = 2 * Pi / n
    val index = (positiveAngle / stepSize).toInt
    index
  }

  def newDraggingPost = {
    new SimPost(post) sideEffect { g =>
      g.x = x
      g.y = y
      g.size = size
      g.centerOffset = centerOffset

      g.color = color
      g.fontSize = fontSize
      g.border = border
    }
  }
  var draggingPost: Option[SimPost] = None

  //TODO: derive @derive((post, x, y) => toString)
  override def toString = s"SimPost($post, $content, $x, $y)"
}
