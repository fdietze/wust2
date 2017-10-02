package wust.frontend.views

import org.scalajs.d3v4._
import org.scalajs.dom.raw.HTMLTextAreaElement
import org.scalajs.dom.{Event, window}
import rx._
import rxext._
import scalaz.Tag
import wust.frontend.Color._
import wust.frontend._
import wust.frontend.views.Elements.textareaWithEnter
import wust.graph._
import scala.concurrent.ExecutionContext.Implicits.global

import org.scalajs.dom.{ window, document, console }
import org.scalajs.dom.raw.{ Text, Element, HTMLElement }
import org.scalajs.dom.{ Event }
import org.scalajs.dom.raw.{ HTMLTextAreaElement }
import Elements.{ inlineTextarea, textareaWithEnter }
import scala.scalajs.js
import scala.scalajs.js.timers.setTimeout
import org.scalajs.dom.ext.KeyCode
import org.scalajs.dom.{ Event, KeyboardEvent }
import scala.util.control.NonFatal

import outwatch.dom._
import outwatch.Sink
import wust.util.outwatchHelpers._

object ChatView {

  def apply(state: GlobalState)(implicit ctx: Ctx.Owner) = {

    val focusedParentIds = state.graphSelection.map(_.parentIds)

    val headLineText = Rx {
      val parents = focusedParentIds().map(state.rawGraph().postsById)
      val parentTitles = parents.map(_.title).mkString(", ")
      parentTitles
    }.toObservable

    val bgColor = Rx {
      val mixedDirectParentColors = mixColors(focusedParentIds().map(baseColor))
      mixColors(List(mixedDirectParentColors, d3.lab("#FFFFFF"), d3.lab("#FFFFFF"))).toString
    }.toObservable

    val chatHistory:Rx[Seq[Post]] = state.displayGraphWithoutParents.rx.map{ dg =>
      val graph = dg.graph
      graph.posts.toSeq.sortBy(p => Tag.unwrap(p.id))
    }

    val latestPost = Rx { chatHistory().lastOption }

    def scrollToBottom(elem:Element) {
      //TODO: scrollHeight is not yet available in jsdom tests: https://github.com/tmpvar/jsdom/issues/1013
      try {
        elem.scrollTop = elem.scrollHeight
      } catch { case _: Throwable => } // with NonFatal(_) it fails in the tests
    }

    val chatHistoryDiv = div(
      update --> {(e:Element) => println("update hook"); setTimeout(100) { scrollToBottom(e) }},
      Style("height", "100%"),
      Style("overflow", "auto"),
      Style("padding", "20px"),
      Style("backgroundColor", "white"),

      children <-- chatHistory.toObservable.map { _.map{post =>
        val isMine = state.ownPosts(post.id)
        div(
          p(
            Style("maxWidth", "60%"),
            post.title,
            Style("backgroundColor", (if (isMine) "rgb(192, 232, 255)" else "#EEE")),
            Style("float", (if (isMine) "right" else "left")),
            Style("clear", "both"),
            Style("padding", "5px 10px"),
            Style("borderRadius", "7px"),
            Style("margin", "5px 0px"),
            // TODO: What about curson when selecting text?
            Style("cursor", "pointer"),
            click(GraphSelection.Union(Set(post.id))) --> state.graphSelection
          )
      )}
      }
      )

    def submitInsert(field: HTMLTextAreaElement) = {
      val newPost = Post.newId(field.value)
      state.persistence.addChangesEnriched(
        addPosts = Set(newPost),
        addConnections = latestPost.now.map(latest => Connection(latest.id, newPost.id)).toSet
      )
      field.value = ""
      false
    }
    val insertFieldValue = createStringHandler()
    val insertField = textarea(placeholder := "Create new post. Press Enter to submit.",
      Style("width", "100%")
      // changeValue --> insertFieldValue
      )
    val createPostHandler = createStringHandler()
    // insertFieldValueLl
    val insertForm = form(
      insertField,
      input(tpe := "submit", "insert"),
      // submit(true) --> createPostHandler
      // onsubmit := { (e: Event) =>
      //   submitInsert(insertField)
      //   e.preventDefault()
      // }
    )

    div(
      Style("height", "100%"),
      // bgColor.map(Style("backgroundColor", _)),

      div(
        Style("margin", "0 auto"),
        Style("maxWidth", "48rem"),
        Style("width", "48rem"),
        Style("height", "100%"),

        Style("display", "flex"),
        Style("flexDirection", "column"),
        Style("justifyContent", "flexStart"),
        Style("alignItems", "stretch"),
        Style("alignContent", "stretch"),

        h1(child <-- headLineText),

        chatHistoryDiv,
        insertForm
      )
    ).render
  }
}
