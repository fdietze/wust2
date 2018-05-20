package wust.webApp.views

import org.scalajs.dom.raw.Element
import outwatch.ObserverSink
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.graph._
import wust.ids.{JoinDate, Label}
import wust.sdk.PostColor._
import wust.webApp._
import wust.webApp.fontAwesome.{freeBrands, freeRegular, freeSolid}
import wust.webApp.outwatchHelpers._
import wust.webApp.views.Elements._
import wust.webApp.views.Rendered._

object ChatView extends View {
  override val key = "chat"
  override val displayName = "Chat"

  override def apply(state: GlobalState)(implicit ctx: Ctx.Owner) = {

    state.displayGraphWithoutParents.foreach(dg => scribe.info(s"ChatView Graph: ${dg.graph}"))

    div(
      display.flex,
      flexDirection.column,
      justifyContent.flexStart,
      alignItems.stretch,
      alignContent.stretch,

      chatHeader(state)(ctx)(flexGrow := 0, flexShrink := 0),
      chatHistory(state)(ctx)(height := "100%", overflow.auto),
      inputField(state)(ctx)(flexGrow := 0, flexShrink := 0)
    )
  }

  def chatHeader(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    import state._
    div(
      padding := "0px 10px",
      pageParentPosts.map(_.map { parent =>
        div(
          display.flex,
          alignItems.center,
          Avatar.post(parent.id)(
            width := "40px",
            height := "40px",
            marginRight := "10px"
          ),
          showPostContent(parent.content)(fontSize := "20px"),
          channelControl(state, parent)(ctx)(marginLeft := "5px"),
          joinControl(state, parent)(ctx)(marginLeft := "5px"),
          deleteButton(state, parent)(marginLeft := "5px")
        )
      })
    )
  }

  def channelControl(state: GlobalState, post: Post)(implicit ctx: Ctx.Owner): VNode = div(
    Rx {
      (state.rawGraph().children(state.user().channelPostId).contains(post.id) match {
        case true => freeSolid.faBookmark
        case false => freeRegular.faBookmark
      }):VNode
    },
    cursor.pointer,
    onClick --> sideEffect {_ =>
      val changes = state.rawGraph.now.children(state.user.now.channelPostId).contains(post.id) match {
        case true => GraphChanges.disconnect(post.id, Label.parent, state.user.now.channelPostId)
        case false => GraphChanges.connect(post.id, Label.parent, state.user.now.channelPostId)
      }
      state.eventProcessor.changes.onNext(changes)
    }
  )

  def joinControl(state:GlobalState, post:Post)(implicit  ctx: Ctx.Owner):VNode = {
    val text = post.joinDate match {
      case JoinDate.Always => "Users can join via URL"
      case JoinDate.Never => "Private Group"
      case JoinDate.Until(time) => s"Users can join via URL until $time" //TODO: epochmilli format
    }
    div(
      "(", text, ")",
      title := "Click to toggle",
      cursor.pointer,
      onClick --> sideEffect{ _ =>
        val newJoinDate = post.joinDate match {
          case JoinDate.Always => JoinDate.Never
          case JoinDate.Never => JoinDate.Always
          case _ => JoinDate.Never
        }

        Client.api.setJoinDate(post.id, newJoinDate)
      }
    )
  }

  def chatHistory(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    import state._
    val graph = displayGraphWithoutParents.map(_.graph)

    div(
      padding := "20px",

      Rx{
        val posts = graph().chronologicalPostsAscending
        if (posts.isEmpty) Seq(emptyMessage)
        else posts.map(chatMessage(state, _, graph(), user()))
      },
      onPostPatch --> sideEffect[(Element, Element)] { case (_, elem) => scrollToBottom(elem) }
    )
  }

  def emptyMessage: VNode = h3(textAlign.center, "Nothing here yet.", paddingTop := "40%", color := "rgba(0,0,0,0.5)")

  def postTag(state:GlobalState, post:Post):VNode = {
    span(
      post.content.str, //TODO trim! fit for tag usage...
      onClick --> sideEffect{e => state.page() = Page(Seq(post.id)); e.stopPropagation()},
      backgroundColor := computeTagColor(post.id),
      fontSize.small,
      color := "#fefefe",
      borderRadius := "2px",
      padding := "0px 3px",
      marginRight := "3px"
    )
  }

  def deleteButton(state: GlobalState, post: Post) = div(
    freeRegular.faTrashAlt,
    padding := "5px",
    onClick.map{e => e.stopPropagation(); GraphChanges.delete(post)} --> ObserverSink(state.eventProcessor.changes)
  )

  def chatMessage(state:GlobalState, post: Post, graph:Graph, currentUser:User)(implicit ctx: Ctx.Owner): VNode = {
    val postTags: Seq[Post] = graph.ancestors(post.id).map(graph.postsById(_)).toSeq

    val isMine = currentUser.id == post.author

    val content = div(
      showPostContent(post.content),
      cls := "chatpost",
      padding := "0px 3px",
      margin := "2px 0px"
    )

    val tags = div( // post tags
      postTags.map{ tag => postTag(state, tag) },
      margin := "0px",
      padding := "0px"
    )

    div( // wrapper for floats
      div( // post wrapper
        div(
          display.flex,
          alignItems.center,
          content,
          deleteButton(state, post)
        ),
        tags,

        onClick(Page(Seq(post.id))) --> state.page,
        borderRadius := (if (isMine) "7px 0px 7px 7px" else "0px 7px 7px"),
        float := (if (isMine) "right" else "left"),
        borderWidth := (if (isMine) "1px 7px 1px 1px" else "1px 1px 1px 7px"),
        borderColor := computeColor(graph, post.id),
        backgroundColor := postDefaultColor,
        display.block,
        maxWidth := "80%",
        padding := "5px 10px",
        margin := "5px 0px",
        borderStyle := "solid",
        cursor.pointer // TODO: What about cursor when selecting text?
      ),
      width := "100%",
      clear.both
    )
  }

  def inputField(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    import state._

    val graphIsEmpty = displayGraphWithParents.map(_.graph.isEmpty)

    textArea(
      valueWithEnter.map(PostContent.Markdown) --> state.newPostSink,
      disabled <-- graphIsEmpty,
      height := "3em",
      style("resize") := "vertical", //TODO: outwatch resize?
      Placeholders.newPost
    )
  }
}
