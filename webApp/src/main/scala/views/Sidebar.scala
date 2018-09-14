package wust.webApp.views

import fontAwesome.freeSolid
import googleAnalytics.Analytics
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.{Styles, ZIndex}
import wust.graph.Tree.Leaf
import wust.graph._
import wust.ids._
import wust.sdk.{BaseColors, NodeColor}
import wust.util.RichBoolean
import wust.webApp.dragdrop.DragItem
import wust.webApp.outwatchHelpers._
import wust.webApp.state.{GlobalState, PageStyle, ScreenSize, View}

import collection.breakOut

object Sidebar {
  import MainViewParts._, Rendered._, Components._

  def apply(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {

    div(
      cls := "sidebar",
      backgroundColor <-- state.pageStyle.map(_.sidebarBgColor),
      //      flexBasis <-- sidebarOpen.map { case true => "175px"; case false => "30px" },
      state.sidebarOpen.map {
        case true  => VDomModifier( // sidebar open
          channels(state)(ctx),
          newChannelButton(state)(ctx)(
            cls := "newChannelButton-large " + buttonStyles,
            onClick --> sideEffect { Analytics.sendEvent("sidebar_open", "newchannel") }
          ),
          Rx {
            if(state.screenSize() == ScreenSize.Small) VDomModifier(
              div(Topbar.authentication(state))(
                alignSelf.center,
                marginTop := "30px",
                marginBottom := "10px",
              ),
              width := "100%",
              height := "100%",
              zIndex := ZIndex.overlay,
              onClick(false) --> state.sidebarOpen
            ) else VDomModifier(
              maxWidth := "200px",
            )
          },
        )
        case false =>
          val iconSize = 40
          VDomModifier( // sidebar closed
          minWidth := s"${iconSize}px",
          channelIcons(state, iconSize)(ctx),
          newChannelButton(state, "+")(ctx)(
            cls := "newChannelButton-small " + buttonStyles,
            onClick --> sideEffect { Analytics.sendEvent("sidebar_closed", "newchannel") }
          )
        )
      },
      registerDraggableContainer(state)
    )
  }

  val buttonStyles = Seq("tiny", "compact", "inverted", "grey").mkString(" ")

  def channels(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {

    def channelLine(node: Node, pageParentIds: Set[NodeId], pageStyle: PageStyle): VNode = {
      val selected = pageParentIds contains node.id
      div(
        cls := "channel-line",
        selected.ifTrueSeq(
          Seq(
            color := pageStyle.sidebarBgColor,
            backgroundColor := pageStyle.sidebarBgHighlightColor
          )
        ),

        channelIcon(state, node, selected, 30)(ctx)(
          marginRight := "5px",
          borderRadius := "2px",
        ),
        renderNodeData(node.data)(
          cls := "channel-name",
          paddingLeft := "3px",
          paddingRight := "3px",
        ),
        onChannelClick(ChannelAction.Node(node.id))(state),
        onClick --> sideEffect { Analytics.sendEvent("sidebar_open", "clickchannel") },
        cls := "node drag-feedback",
        draggableAs(state, DragItem.Channel(node.id)),
        dragTarget(DragItem.Channel(node.id)),
      ),
    }

    def channelList(channels: Tree, pageParentIds: Set[NodeId], pageStyle: PageStyle, depth: Int = 0): VNode = {
      div(
        channelLine(channels.node, pageParentIds, pageStyle),
        channels match {
          case Tree.Parent(_, children) => div(
            paddingLeft := "10px",
            fontSize := s"${ math.max(8, 14 - depth) }px",
            children.map { child => channelList(child, pageParentIds, pageStyle, depth = depth + 1) }(breakOut): Seq[VDomModifier]
          )
          case Tree.Leaf(_)             => VDomModifier.empty
        }
      )
    }

    def invitations(selected:Boolean, pageStyle: PageStyle) = div(
      cls := "channel-line",

      invitationsIcon(selected)(ctx)(
        marginRight := "5px",
        borderRadius := "2px",
      ),
      "Invitations",
      selected.ifTrueSeq(
        Seq(
          color := pageStyle.sidebarBgColor,
          backgroundColor := pageStyle.sidebarBgHighlightColor
        )
      ),
      onChannelClick(ChannelAction.Page(PageMode.Orphans))(state)
    )

    div(
      cls := "channels",
      Rx {
        val channelTree: Tree = state.channelTree()
        val pageParentIds = state.page().parentIdSet
        val pageStyle = state.pageStyle()
        VDomModifier(
          channelLine(state.user().toNode, pageParentIds, pageStyle),
          channelTree match {
            case Tree.Parent(_,children) =>
            children.map { child => channelList(child, pageParentIds, pageStyle, depth = 0) }(breakOut): Seq[VDomModifier]
            case Tree.Leaf(_) => VDomModifier.empty
          }
          // invitations(state.page().mode == PageMode.Orphans, pageStyle),
        )
      }
    )
  }

  def invitationsIcon(selected: Boolean)(implicit ctx: Ctx.Owner) = div(
    cls := "customChannelIcon",
    margin := "0px",
    freeSolid.faUsers,
    selected.ifTrue[VDomModifier](backgroundColor := "white")
  )

  def channelIcons(state: GlobalState, size: Int)(implicit ctx: Ctx.Owner): VNode = {
    div(
      cls := "channelIcons",
      Rx {
        val allChannels = state.channels().drop(1)
        val page = state.page()
        VDomModifier(
          allChannels.map { node =>
            channelIcon(state, node, page.parentIds.contains(node.id), size, BaseColors.sidebarBg.copy(h = NodeColor.hue(node.id)).toHex)(ctx)(
              onChannelClick(ChannelAction.Node(node.id))(state),
              onClick --> sideEffect { Analytics.sendEvent("sidebar_closed", "clickchannel") },
              draggableAs(state, DragItem.Channel(node.id)),
              dragTarget(DragItem.Channel(node.id)),
              cls := "node drag-feedback"
            )
          },
        )
      }
    )
  }

  def channelIcon(state: GlobalState, node: Node, selected: Boolean, size: Int, selectedBorderColor: String = "transparent")(
    implicit ctx: Ctx.Owner
  ): VNode = {
    div(
      cls := "channelicon",
      width := s"${ size }px",
      height := s"${ size }px",
      style("userSelect") := "none",
      backgroundColor := (node match {
        case node:Node.Content => BaseColors.pageBg.copy(h = NodeColor.hue(node.id)).toHex
        case _:Node.User => "rgb(255, 255, 255)"
      }),
      opacity := (node match {
        case node:Node.Content => if(selected) 1.0 else 0.75
        case _:Node.User => if(selected) 1.0 else 0.9
      }),
      selected.ifTrueOption(borderColor := selectedBorderColor),
      Avatar(node),
      title := node.data.str,
    )
  }

  sealed trait ChannelAction extends Any
  object ChannelAction {
    case class Node(id: NodeId) extends AnyVal with ChannelAction
    case class Page(mode: PageMode) extends AnyVal with ChannelAction
  }
  private def onChannelClick(action: ChannelAction)(state: GlobalState)(implicit ctx: Ctx.Owner) =
    onClick.map { e =>
      val page = state.page.now
      //TODO if (e.shiftKey) {
      action match {
        case ChannelAction.Node(id)   =>
          if(e.ctrlKey) {
            val filtered = page.parentIds.filterNot(_ == id)
            val parentIds =
              if(filtered.size == page.parentIds.size) page.parentIds :+ id
              else if(filtered.nonEmpty) filtered
              else Seq(id)
            page.copy(parentIds = parentIds)
          } else Page(Seq(id))
        case ChannelAction.Page(mode) =>
          val newMode = if(page.mode != mode) mode else PageMode.Default
          if(e.ctrlKey) page.copy(mode = newMode) else Page(Seq.empty, mode = newMode)
      }
    } --> sideEffect { page =>
      val contentView = if(state.view.now.isContent) state.view.now else View.default
      state.viewConfig() = state.viewConfig.now.copy(page = page, view = contentView)
    }
}
