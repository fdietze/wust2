package wust.webApp.views

import cats.effect.IO
import org.scalajs.dom
import org.scalajs.dom.raw.HTMLElement
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.Styles
import wust.graph._
import wust.ids._
import wust.sdk.NodeColor._
import wust.sdk.{BaseColors, NodeColor}
import wust.util._
import wust.util.collection._
import wust.webApp.outwatchHelpers._
import wust.webApp.state.{GlobalState, ScreenSize}
import wust.webApp.views.Components._
import wust.webApp.views.Elements._

import scala.collection.breakOut
import scala.scalajs.js


object ChatView {
  import SharedViewElements._

  private final case class SelectedNode(nodeId:NodeId)(val editMode:Var[Boolean], val directParentIds: Iterable[NodeId]) extends SelectedNodeBase

  def apply(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    val selectedNodes = Var(Set.empty[SelectedNode]) //TODO move up

    val scrollHandler = ScrollHandler(Var(None: Option[HTMLElement]), Var(true))

    val currentReply = Var(Set.empty[NodeId])

    div(
      keyed,
      Styles.flex,
      flexDirection.column,
      position.relative, // for absolute positioning of selectednodes
      //TODO: maybe we want to reply to multiple nodes as well in chat?
      SelectedNodes[SelectedNode](state, _.nodeId, selectedNodeActions(state, selectedNodes), selectedSingleNodeActions(state, selectedNodes, currentReply), selectedNodes).apply(
        position.absolute,
        width := "100%"
      ),
      div(
        cls := "chat-history",
        overflow.auto,
        backgroundColor <-- state.pageStyle.map(_.bgLightColor),
        chatHistory(state, currentReply, selectedNodes),
        registerDraggableContainer(state),

        // clicking on background deselects
        onClick handleWith { e => if(e.currentTarget == e.target) selectedNodes() = Set.empty[SelectedNode] },
        scrollHandler.scrollOptions(state)
      ),
      managed(IO { state.page.foreach { _ => currentReply() = Set.empty[NodeId] } }),
      onGlobalEscape(Set.empty[NodeId]) --> currentReply,
      Rx {
        val graph = state.graph()
        div(
          Styles.flexStatic,

          backgroundColor <-- state.pageStyle.map(_.bgLightColor),
          Styles.flex,
          alignItems.flexStart,
          currentReply().map { replyNodeId =>
            val isDeleted = graph.lookup.isDeletedNow(replyNodeId, state.page.now.parentIds)
            val node = graph.nodesById(replyNodeId)
            div(
              padding := "5px",
              backgroundColor := BaseColors.pageBg.copy(h = NodeColor.pageHue(replyNodeId :: Nil).get).toHex,
              div(
                Styles.flex,
                alignItems.flexStart,
                parentMessage(state, node, isDeleted, currentReply).apply(alignSelf.center),
                closeButton(
                  marginLeft.auto,
                  onTap handleWith { currentReply.update(_ - replyNodeId) }
                ),
              )
            )
          }(breakOut):Seq[VDomModifier]
        )
      },
      {
        def replyNodes: Set[NodeId] = {
          if(currentReply.now.nonEmpty) currentReply.now
          else state.page.now.parentIdSet
        }
        inputField(state, replyNodes, scrollHandler)(ctx)(Styles.flexStatic)
      }
    )
  }

  private def renderMessageRow(state: GlobalState, nodeId: NodeId, directParentIds:Iterable[NodeId], selectedNodes: Var[Set[SelectedNode]], isDeleted: Rx[Boolean], editMode: Var[Boolean], currentReply: Var[Set[NodeId]])(implicit ctx: Ctx.Owner): VNode = {

    val isSelected = Rx {
      selectedNodes().exists(_.nodeId == nodeId)
    }

    val parentNodes: Rx[Seq[Node]] = Rx {
      val graph = state.graph()
      (graph.parents(nodeId) -- state.page.now.parentIds)
        .map(id => graph.nodes(graph.lookup.idToIdx(id)))(breakOut)
    }

    val renderedMessage = renderMessage(state, nodeId, isDeleted = isDeleted, editMode = editMode)
    val controls = msgControls(state, nodeId, directParentIds, selectedNodes, isDeleted = isDeleted, editMode = editMode, replyAction = currentReply.update(_ ++ Set(nodeId))) //TODO reply action
    val checkbox = msgCheckbox(state, nodeId, selectedNodes, newSelectedNode = SelectedNode(_)(editMode, directParentIds), isSelected = isSelected)
    val selectByClickingOnRow = {
      onClickOrLongPress handleWith { longPressed =>
        if(longPressed) selectedNodes.update(_ + SelectedNode(nodeId)(editMode, directParentIds))
        else {
          // stopPropagation prevents deselecting by clicking on background
          val selectionModeActive = selectedNodes.now.nonEmpty
          if(selectionModeActive) selectedNodes.update(_.toggle(SelectedNode(nodeId)(editMode, directParentIds)))
        }
      }
    }

    div(
      cls := "chat-row",
      Styles.flex,

      isSelected.map(_.ifTrue[VDomModifier](backgroundColor := "rgba(65,184,255, 0.5)")),
      selectByClickingOnRow,
      checkbox,
      Rx {
        val messageCard = renderedMessage()
        val parents = parentNodes()
        if(parents.nonEmpty) {
          val bgColor = BaseColors.pageBgLight.copy(h = NodeColor.pageHue(parents.map(_.id)).get).toHex
          div(
            cls := "nodecard",
            backgroundColor := (if(isDeleted()) bgColor + "88" else bgColor), //TODO: rgbia hex notation is not supported yet in Edge: https://caniuse.com/#feat=css-rrggbbaa
            div(
              Styles.flex,
              alignItems.flexStart,
              parents.map { parent =>
                parentMessage(state, parent, isDeleted(), currentReply)
              }
            ),
            messageCard.map(_(boxShadow := "none", backgroundColor := bgColor))
          )
        } else messageCard: VDomModifier
      },
      controls,
      messageRowDragOptions(nodeId, selectedNodes, editMode)
    )
  }

    def parentMessage(state: GlobalState, parent: Node, isDeleted: Boolean, currentReply: Var[Set[NodeId]])(implicit ctx: Ctx.Owner) = {
      val authorAndCreated = Rx {
        val graph = state.graph()
        val idx = graph.lookup.idToIdx(parent.id)
        val authors = graph.authors(parent.id)
        val creationEpochMillis = if (idx == -1) None else Some(graph.lookup.nodeCreated(idx))
        (authors.headOption, creationEpochMillis)
      }

      div(
        padding := "1px",
        borderTopLeftRadius := "2px",
        borderTopRightRadius := "2px",
        Rx {
          val tuple = authorAndCreated()
          val (author, creationEpochMillis) = tuple
          chatMessageHeader(author, creationEpochMillis.getOrElse(EpochMilli.min), author.map(smallAuthorAvatar))(
            padding := "2px",
          )
        },
        nodeCard(parent)(
          fontSize.xSmall,
          backgroundColor := BaseColors.pageBgLight.copy(h = NodeColor.hue(parent.id)).toHex,
          boxShadow := s"0px 1px 0px 1px ${ tagColor(parent.id).toHex }",
          cursor.pointer,
          onTap handleWith {currentReply.update(_ ++ Set(parent.id))},
        ),
        margin := "3px",
        isDeleted.ifTrue[VDomModifier](opacity := 0.5)
      )
    }

  private def thunkGroup(state: GlobalState, groupGraph: Graph, group: Array[Int], currentReply: Var[Set[NodeId]], selectedNodes: Var[Set[SelectedNode]])(implicit ctx: Ctx.Owner): VDomModifier = {
    val author:Option[Node.User] = groupGraph.lookup.authorsIdx.get(group(0), 0).map(authorIdx => groupGraph.lookup.nodes(authorIdx).asInstanceOf[Node.User])
    val creationEpochMillis = groupGraph.lookup.nodeCreated(group(0))
    val isLargeScreen = state.screenSize.now == ScreenSize.Large

    VDomModifier(
      cls := "chat-group-outer-frame",
      isLargeScreen.ifTrue[VDomModifier](author.map(bigAuthorAvatar)),
      div(
        cls := "chat-group-inner-frame",
        chatMessageHeader(author, creationEpochMillis, isLargeScreen.ifFalse[VDomModifier](author.map(smallAuthorAvatar))),

        group.map { groupIdx =>
          val nodeId = groupGraph.lookup.nodeIds(groupIdx)
          div.thunkRx(keyValue(nodeId))(state.screenSize.now) { implicit ctx =>

            val isDeleted = Rx {
              val graph = state.graph()
              graph.lookup.isDeletedNow(nodeId, state.page.now.parentIds)
            }

            val editMode = Var(false)

            renderMessageRow(state, nodeId, state.page.now.parentIds, selectedNodes, editMode = editMode, isDeleted = isDeleted, currentReply = currentReply)
          }
        }
      )
    )
  }

  private def chatHistory(state: GlobalState, currentReply: Var[Set[NodeId]], selectedNodes: Var[Set[SelectedNode]])(implicit ctx: Ctx.Owner): Rx[VDomModifier] = {
    Rx {
      state.screenSize() // on screensize change, rerender whole chat history
      val page = state.page()
      state.graphWithLoading { graph =>
        val groups = calculateMessageGrouping(chatMessages(page.parentIds, graph), graph)

        groups.map { group =>
          // because of equals check in thunk, we implicitly generate a wrapped array
          val nodeIds: Seq[NodeId] = group.map(graph.lookup.nodeIds)
          val key = nodeIds.head.toString

          div.thunk(key)(nodeIds, state.screenSize.now)(thunkGroup(state, graph, group, currentReply, selectedNodes))
        }
      }
    }
  }

  private def chatMessages(parentIds: Iterable[NodeId], graph: Graph): js.Array[Int] = {
    val nodeSet = ArraySet.create(graph.nodes.length)
    parentIds.foreach { parentId =>
      val parentIdx = graph.lookup.idToIdx(parentId)
      if(parentIdx != -1) {
        graph.lookup.descendantsIdx(parentIdx).foreachElement { childIdx =>
          val childNode = graph.lookup.nodes(childIdx)
          if(childNode.isInstanceOf[Node.Content])
            nodeSet.add(childIdx)
        }
      }
    }
    val nodes = js.Array[Int]()
    nodeSet.foreachAdded(nodes += _)
    sortByCreated(nodes, graph)
    nodes
  }

  //TODO share code with threadview?
  private def selectedSingleNodeActions(state: GlobalState, selectedNodes: Var[Set[SelectedNode]], currentReply: Var[Set[NodeId]]): SelectedNode => List[VNode] = selectedNode => if(state.graph.now.lookup.contains(selectedNode.nodeId)) {
    List(
      editButton(
        onClick handleWith {
          selectedNodes.now.head.editMode() = true
          selectedNodes() = Set.empty[SelectedNode]
        }
      ),
      replyButton(
        onClick handleWith {
          currentReply() = selectedNodes.now.map(_.nodeId)
          selectedNodes() = Set.empty[SelectedNode]
        }
      ) //TODO: scroll to focused field?
    )
  } else Nil
}
