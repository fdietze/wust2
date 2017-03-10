package wust.frontend.views

import scalajs.concurrent.JSExecutionContext.Implicits.queue

import org.scalajs.d3v4
import org.scalajs.dom
import autowire._
import boopickle.Default._
import rx._
import scalatags.generic.Modifier
import scalatags.rx.all._
import scalatags.JsDom.all._

import wust.frontend.Client
import wust.frontend.Color._
import wust.graph._

object Views {
  def post(post: Post) = div(
    post.title,
    maxWidth := "10em",
    wordWrap := "break-word",
    margin := 3,
    padding := "3px 5px",
    border := "1px solid #444",
    borderRadius := "3px"
  )

  def parents(postId: PostId, graph: Graph) = {
    val containsIds = graph.incidentParentContains(postId).toSeq
    div(
      display.flex,
      containsIds.map { containsId =>
        val contains = graph.containments(containsId)
        val parent = graph.posts(contains.parentId)
        post(parent)(
          backgroundColor := baseColor(parent.id).toString,
          span(
            "×",
            padding := "0 0 0 3px",
            cursor.pointer,
            onclick := { () => Client.api.deleteContainment(contains.id).call(); () }
          )
        )
      }
    )
  }
}
