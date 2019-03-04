package wust.webApp.views

import fontAwesome.freeSolid
import googleAnalytics.Analytics
import monix.reactive.Observable
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.{CommonStyles, Styles}
import wust.graph._
import wust.ids._
import wust.sdk.{BaseColors, NodeColor}
import wust.util.RichBoolean
import wust.webApp.dragdrop.{DragItem, _}
import wust.webApp.outwatchHelpers._
import wust.webApp.state._
import wust.webApp.views.Components._
import wust.webApp.views.SharedViewElements._
import wust.webApp.BrowserDetect
import wust.webApp.{Icons, ItemProperties, Ownable}

import scala.collection.breakOut

object RightSidebar {

  def apply(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = apply(state, state.rightSidebarNode, state.rightSidebarNode() = _)
  def apply(state: GlobalState, focusedNodeId: Rx[Option[NodeId]], parentIdAction: Option[NodeId] => Unit, openModifier: VDomModifier = VDomModifier.empty)(implicit ctx: Ctx.Owner): VNode = {
    val toggleVar = Var(focusedNodeId.now.isDefined)
    focusedNodeId.triggerLater(opt => toggleVar() = opt.isDefined)
    toggleVar.triggerLater(show => if (!show) parentIdAction(None))

    GenericSidebar.right(
      toggleVar,
      config = Ownable { implicit ctx => GenericSidebar.Config(
        openModifier = VDomModifier(content(state, focusedNodeId, parentIdAction), openModifier)
      )}
    )
  }

  // TODO rewrite to rely on static focusid
  def content(state: GlobalState, focusedNodeId: Rx[Option[NodeId]], parentIdAction: Option[NodeId] => Unit)(implicit ctx: Ctx.Owner) = {
    val nodeStyle = focusedNodeId.map(PageStyle.ofNode)

    def accordionEntry(name: String, body: VDomModifier): (VDomModifier, VDomModifier) = {
      VDomModifier(
        marginTop := "5px",
        b(name),
        Styles.flexStatic,
      ) -> VDomModifier(
        padding := "4px",
        body
      )
    }

    div(
      Styles.growFull,
      Styles.flex, // we need flex here because otherwise the height of this element is wrong - it overflows.
      flexDirection.column,
      borderRadius := "3px",
      margin := "5px",
      color.black,
      backgroundColor <-- nodeStyle.map(_.bgLightColor),

      div(
        Styles.flex,
        alignItems.center,
        div(
          fontSize.xLarge,
          cls := "fa-fw", freeSolid.faAngleDoubleRight,
          cursor.pointer,
          onClick(None).foreach(parentIdAction),
        ),
        div(
          marginLeft := "5px",
          nodeBreadcrumbs(state, focusedNodeId, parentIdAction),
        ),
      ),
      UI.accordion(
        Seq(
          accordionEntry("Content", VDomModifier(
            nodeContent(state, focusedNodeId, parentIdAction),
            overflowY.auto,
            maxHeight := "40%"
          )),
          accordionEntry("Properties", VDomModifier(
            nodeDetailsMenu(state, focusedNodeId, parentIdAction),
            overflowY.auto,
            flex := "1 1 20%"
          )),
          accordionEntry("Views", VDomModifier(
            viewContent(state, focusedNodeId, parentIdAction),
            flex := "1 1 40%"
          )),
        ),
        styles = "styled fluid",
        exclusive = false, //BrowserDetect.isMobile,
        initialActive = Seq(0, 1, 2), //if (BrowserDetect.isMobile) Seq(0) else Seq(0, 1, 2),
      ).apply(
        height := "100%",
        marginBottom := "5px",
        Styles.flex,
        flexDirection.column,
        justifyContent.flexStart,
        backgroundColor <-- nodeStyle.map(_.bgLightColor), //explicitly overwrite bg color from accordion.
        boxShadow := "none", //explicitly overwrite boxshadow from accordion.
      )
    )
  }
  private def viewContent(state: GlobalState, focusedNodeId: Rx[Option[NodeId]], parentIdAction: Option[NodeId] => Unit)(implicit ctx: Ctx.Owner) = {
    VDomModifier(
      Styles.flex,
      flexDirection.column,
      focusedNodeId.map(_.map { nodeId =>
        val graph = state.rawGraph.now
        val initialView = graph.nodesByIdGet(nodeId).fold[View.Visible](View.Empty)(ViewHeuristic.bestView(graph, _))
        val viewVar = Var[View.Visible](initialView)
        def viewAction(view: View): Unit = viewVar() = ViewHeuristic.visibleView(graph, nodeId, view)

        VDomModifier(
          div(
            Styles.flexStatic,
            Styles.flex,
            alignItems.center,
            ViewSwitcher(state, nodeId, viewVar, viewAction),
            borderBottom := "2px solid black",
            button(
              cls := "ui mini button compact",
              marginLeft := "10px",
              Icons.zoom,
              cursor.pointer,
              onClick.foreach { state.urlConfig.update(_.focus(Page(nodeId), viewVar.now)) }
            ),
          ),
          Rx {
            val view = viewVar()
              ViewRender(state, FocusState(view, nodeId, nodeId, isNested = true, viewAction, nodeId => parentIdAction(Some(nodeId))), view).apply(
                Styles.growFull,
                flexGrow := 1,
              ).prepend(
                overflow.visible,
              )
          }
        )
      })
    )
  }

  private def nodeBreadcrumbs(state: GlobalState, focusedNodeId: Rx[Option[NodeId]], parentIdAction: Option[NodeId] => Unit)(implicit ctx: Ctx.Owner) = {
    VDomModifier(
      Rx {
        focusedNodeId().flatMap { nodeId =>
          state.rawGraph().nodesByIdGet(nodeId).map { node =>
            // val hasParents = state.rawGraph().notDeletedParents(nodeId).nonEmpty
            // VDomModifier.ifTrue(hasParents)(
              BreadCrumbs(state, state.page().parentId, focusedNodeId, nodeId => parentIdAction(Some(nodeId))).apply(paddingBottom := "3px")
            // )
          }
        }
      }
    )
  }

  private def nodeContent(state: GlobalState, focusedNodeId: Rx[Option[NodeId]], parentIdAction: Option[NodeId] => Unit)(implicit ctx: Ctx.Owner) = {
    val editMode = Var(false)

    VDomModifier(
      Rx {
        focusedNodeId().flatMap { nodeId =>
          state.rawGraph().nodesByIdGet(nodeId).map { node =>
            div(
              Styles.flex,
              alignItems.flexStart,
              Components.roleSpecificRender[VNode](node,
                nodeCard = Components.nodeCardEditable(state, node, editMode),
                nodePlain = Components.editableNode(state, node, editMode),
              ).apply(styles.extra.wordBreak.breakWord, width := "100%", margin := "3px 0px 3px 3px", cls := "enable-text-selection"),
              div(
                Icons.edit,
                padding := "4px",
                cursor.pointer,
                onClick.stopPropagation(true) --> editMode
              )
            )
          }
        }
      }
    )
  }

  private def nodeDetailsMenu(state: GlobalState, focusedNodeId: Rx[Option[NodeId]], parentIdAction: Option[NodeId] => Unit)(implicit ctx: Ctx.Owner) = {
    VDomModifier(
      Rx {
        focusedNodeId().flatMap { nodeId =>
          state.rawGraph().nodesByIdGet(nodeId).map { node =>
            nodeProperties(state, state.rawGraph(), node)
          }
        }
      }
    )
  }

  private def nodeProperties(state: GlobalState, graph: Graph, node: Node)(implicit ctx: Ctx.Owner) = {
    val nodeIdx = graph.idToIdxOrThrow(node.id)

    val propertySingle = PropertyData.Single(graph, nodeIdx)

    def renderSplit(left: VDomModifier, right: VDomModifier) = div(
      Styles.flex,
      justifyContent.spaceBetween,
      div(
        width := "100%",
        left
      ),
      div(
        Styles.flex,
        justifyContent.flexEnd,
        flexShrink := 0,
        right
      )
    )

    def searchInput(placeholder: String, filter: Node => Boolean) =
      Components.searchInGraph(state.rawGraph, placeholder = placeholder, filter = filter, showParents = false, completeOnInit = false, inputModifiers = VDomModifier(
        width := "120px",
        padding := "2px 10px 2px 10px",
      ), elementModifier = VDomModifier(
        padding := "3px 0px 3px 0px",
      ))

    VDomModifier(
      div(
        Styles.flex,
        justifyContent.flexEnd,
        div(
          button(
            cls := "ui compact button mini",
            "+ Custom field"
          ),
          ItemProperties.managePropertiesDropdown(state, node.id, dropdownModifier = cls := "top right"),
        )
      ),
      renderSplit(
        left = VDomModifier(
          Styles.flex,
          alignItems.center,
          flexWrap.wrap,
          propertySingle.info.tags.map { tag =>
            Components.removableNodeTag(state, tag, taggedNodeId = node.id)
          },
        ),
        right = VDomModifier(
          searchInput("Add Tag", filter = _.role == NodeRole.Tag).foreach { tagId =>
            state.eventProcessor.changes.onNext(GraphChanges.connect(Edge.Child)(ParentId(tagId), ChildId(node.id)))
          }
        ),
      ).apply(marginTop := "15px"),
      renderSplit(
        left = VDomModifier(
          Styles.flex,
          alignItems.center,
          flexWrap.wrap,
          propertySingle.info.assignedUsers.map { user =>
            Components.removableAssignedUser(state, user, node.id)
          },
        ),
        right = VDomModifier(
          searchInput("Assign User", filter = _.data.isInstanceOf[NodeData.User]).foreach { userId =>
            state.eventProcessor.changes.onNext(GraphChanges.connect(Edge.Assigned)(node.id, UserId(userId)))
          }
        )
      ).apply(marginTop := "15px"),

      div(
        marginTop := "15px",
        propertySingle.properties.map { property =>
          Components.removablePropertySection(state, property.key, property.values).apply(
            marginBottom := "15px",
          )
        },
      ),
    )
  }
}
