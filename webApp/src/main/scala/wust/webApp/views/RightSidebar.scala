package wust.webApp.views

import acyclic.file
import fontAwesome.freeSolid
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.Styles
import wust.graph._
import wust.ids._
import wust.sdk.Colors
import wust.webApp.state._
import wust.webApp.views.Components._
import wust.webApp.views.SharedViewElements._
import wust.webApp.{Icons, ItemProperties}
import wust.webUtil.Elements._
import wust.webUtil.outwatchHelpers._
import wust.webUtil.{Ownable, UI}

object RightSidebar {

  @inline def apply(viewRender: ViewRenderLike)(implicit ctx: Ctx.Owner): VNode = apply( GlobalState.rightSidebarNode, nodeId => GlobalState.rightSidebarNode() = nodeId.map(FocusPreference(_)), viewRender: ViewRenderLike)
  def apply(focusedNodeId: Rx[Option[FocusPreference]], parentIdAction: Option[NodeId] => Unit, viewRender: ViewRenderLike, openModifier: VDomModifier = VDomModifier.empty)(implicit ctx: Ctx.Owner): VNode = {
    val toggleVar = Var(focusedNodeId.now.isDefined)
    focusedNodeId.triggerLater(opt => toggleVar() = opt.isDefined)
    toggleVar.triggerLater(show => if (!show) parentIdAction(None))

    GenericSidebar.right(
      toggleVar,
      config = Ownable { implicit ctx => GenericSidebar.Config(
        openModifier = VDomModifier(focusedNodeId.map(_.map(content( _, parentIdAction, viewRender))), openModifier)
      )}
    )
  }

  def content(focusPref: FocusPreference, parentIdAction: Option[NodeId] => Unit, viewRender: ViewRenderLike)(implicit ctx: Ctx.Owner): VNode = {
    val nodeStyle = PageStyle.ofNode(focusPref.nodeId)

    val sidebarHeader = div(
      opacity := 0.5,

      Styles.flex,
      alignItems.center,
      div(
        freeSolid.faAngleDoubleRight,
        color := "gray",
        cls := "fa-fw", 
        fontSize.xLarge,
        cursor.pointer,
        onClick(None).foreach(parentIdAction)
      ),
      div(
        marginLeft := "5px",
        nodeBreadcrumbs( focusPref, parentIdAction, hideIfSingle = true),
      ),
    )

    def accordionEntry(title: VDomModifier, content: VDomModifier, active:Boolean): UI.AccordionEntry = {
      UI.AccordionEntry(
        title = VDomModifier(
          b(title),
          marginTop := "5px",
          Styles.flexStatic,
          ), 
        content = VDomModifier(
          margin := "5px",
          padding := "0px",
          content
        ),
        active = active
      )
    }


    div(
      height := "100%",
      Styles.flex, // we need flex here because otherwise the height of this element is wrong - it overflows.
      flexDirection.column,
      color.black,

      sidebarHeader.apply(Styles.flexStatic),
      nodeContent( focusPref, parentIdAction).apply(Styles.flexStatic, overflowY.auto, maxHeight := "50%"),

      UI.accordion(
        content = Seq(
          accordionEntry("Properties & Custom Fields", VDomModifier(
            nodeProperties( focusPref, parentIdAction),
            Styles.flexStatic,
          ), active = false),
          accordionEntry("Views", VDomModifier(
            height := "100%",
            viewContent( focusPref, parentIdAction, nodeStyle, viewRender),
          ), active = true),
        ),
        styles = "styled fluid",
        exclusive = false, //BrowserDetect.isMobile,
      ).apply(
        height := "100%",
        Styles.flex,
        flexDirection.column,
        justifyContent.flexStart,
        boxShadow := "none", //explicitly overwrite boxshadow from accordion.
      )
    )
  }
  private def viewContent(focusPref: FocusPreference, parentIdAction: Option[NodeId] => Unit, nodeStyle:PageStyle, viewRender: ViewRenderLike)(implicit ctx: Ctx.Owner) = {
    val graph = GlobalState.rawGraph.now // this is per new focusPref, and ViewSwitcher just needs an initialvalue
    val initialView = graph.nodesById(focusPref.nodeId).flatMap(ViewHeuristic.bestView(graph, _, GlobalState.user.now.id)).getOrElse(View.Empty)
    val viewVar = Var[View.Visible](initialView)
    def viewAction(view: View): Unit = viewVar() = ViewHeuristic.visibleView(graph, focusPref.nodeId, view).getOrElse(View.Empty)

    VDomModifier(
      Styles.flex,
      flexDirection.column,
      margin := "0px", // overwrite accordion entry margin

      div(
        cls := "pageheader",
        backgroundColor := nodeStyle.pageBgColor,
        paddingTop := "10px", // to have some colored space above the tabs
        Styles.flexStatic,
        Styles.flex,
        alignItems.center,

        ViewSwitcher( focusPref.nodeId, viewVar, viewAction, focusPref.view.flatMap(ViewHeuristic.visibleView(graph, focusPref.nodeId, _))),
        UnreadComponents.notificationsButton( focusPref.nodeId, modifiers = marginLeft := "10px") --> viewVar,
      ),

      Rx {
        val view = viewVar()
        viewRender( FocusState(view, focusPref.nodeId, focusPref.nodeId, isNested = true, viewAction, nodeId => parentIdAction(Some(nodeId))), view).apply(
          Styles.growFull,
          flexGrow := 1,
        ).prepend(
          overflow.visible,
          backgroundColor := Colors.contentBg,
        )
      }
    )
  }

  private def nodeBreadcrumbs(focusedNodeId: FocusPreference, parentIdAction: Option[NodeId] => Unit, hideIfSingle:Boolean)(implicit ctx: Ctx.Owner) = {
    VDomModifier(
      Rx {
        BreadCrumbs(
          
          GlobalState.rawGraph(),
          GlobalState.user(),
          GlobalState.page().parentId,
          Some(focusedNodeId.nodeId),
          nodeId => parentIdAction(Some(nodeId)),
          hideIfSingle = hideIfSingle
        ).apply(paddingBottom := "3px")
      }
    )
  }

  private def nodeContent(focusPref: FocusPreference, parentIdAction: Option[NodeId] => Unit)(implicit ctx: Ctx.Owner) = {
    val editMode = Var(false)

    val node = Rx {
      GlobalState.graph().nodesByIdOrThrow(focusPref.nodeId)
    }

    val hasNotDeletedParents = Rx {
      GlobalState.graph().hasNotDeletedParents(focusPref.nodeId)
    }

    val buttonMods = VDomModifier(
      color := "gray",
      fontSize := "18px",
      padding := "12px 8px",
      cursor.pointer,
    )

    val zoomButton = div(
      Icons.zoom,
      buttonMods,
      onClick.foreach {
        GlobalState.urlConfig.update(_.focus(Page(focusPref.nodeId)))
      }
    )

    val deleteButton = Rx {
      VDomModifier.ifTrue(hasNotDeletedParents())(
        div(
          Icons.delete,
          buttonMods,
          onClick.stopPropagation.foreach { _ =>
            GlobalState.submitChanges(GraphChanges.deleteFromGraph(ChildId(focusPref.nodeId), GlobalState.graph.now))
            parentIdAction(None)
          },
        )
      )
    }

    val nodeCard = Rx {
      Components.nodeCardEditable( node(), editMode,
        contentInject = width := "100%" // pushes cancel button to the right
      ).apply(
        cls := "right-sidebar-node",

        Styles.flex,
        justifyContent.spaceBetween,

        fontSize := "20px",
        width := "100%",
        margin := "3px 3px 3px 3px",
        Styles.wordWrap,
        cls := "enable-text-selection",
        onClick.stopPropagation(true) --> editMode,

        UnreadComponents.readObserver( node().id)
      )
    }

    div(
      div(
        Styles.flex,
        alignItems.flexStart,

        nodeCard,
        Rx {
          VDomModifier.ifNot(editMode())(
            zoomButton,
            deleteButton
          )
        }
      ),

      nodeAuthor( focusPref.nodeId),

      div(
        Styles.flex,
        alignItems.center,

        Components.automatedNodesOfNode( focusPref.nodeId),
      ),
    )
  }

  private def nodeAuthor(nodeId: NodeId)(implicit ctx: Ctx.Owner): VDomModifier = {
    val authorship = Rx {
      val graph = GlobalState.graph()
      val idx = graph.idToIdxOrThrow(nodeId)
      val author = graph.nodeCreator(idx)
      val creationEpochMillis = graph.nodeCreated(idx)
      (author, creationEpochMillis)
    }

    div(
      Styles.flex,
      justifyContent.flexEnd,

      authorship.map { case (author, creationEpochMillis) =>
        chatMessageHeader( author, creationEpochMillis, nodeId, author.map(smallAuthorAvatar)).apply(marginRight := "5px")
      },
    )
  }

  private def nodeProperties(focusPref: FocusPreference, parentIdAction: Option[NodeId] => Unit)(implicit ctx: Ctx.Owner) = {

    val propertySingle = Rx {
      val graph = GlobalState.rawGraph()
      val nodeIdx = graph.idToIdxOrThrow(focusPref.nodeId)
      PropertyData.Single(graph, nodeIdx)
    }
    def renderSplit(left: VDomModifier, right: VDomModifier) = div(
      Styles.flex,
      justifyContent.spaceBetween,
      div(
        left
      ),
      div(
        Styles.flex,
        justifyContent.flexEnd,
        right
      )
    )

    def createNewTag(str: String): Boolean = {
      val createdNode = Node.MarkdownTag(str)
      val change = GraphChanges.addNodeWithParent(createdNode, ParentId(GlobalState.page.now.parentId)) merge
        GraphChanges.connect(Edge.Child)(ParentId(createdNode.id), ChildId(focusPref.nodeId))
      GlobalState.submitChanges(change)
      true
    }

    def searchInput(placeholder: String, filter: Node => Boolean, createNew: String => Boolean = _ => false, showNotFound: Boolean = true) =
      Components.searchInGraph(GlobalState.rawGraph, placeholder = placeholder, filter = filter, showNotFound = showNotFound, createNew = createNew, inputModifiers = VDomModifier(
        width := "140px",
        padding := "2px 10px 2px 10px",
      ), elementModifier = VDomModifier(
        padding := "3px 0px 3px 0px",
      ))

    sealed trait AddProperty
    object AddProperty {
      case object None extends AddProperty
      case object CustomField extends AddProperty
      final case class DefinedField(title: String, key: String, tpe: NodeData.Type) extends AddProperty
      final case class EdgeReference(title: String, create: (NodeId, NodeId) => Edge) extends AddProperty
    }

    val addFieldMode = Var[AddProperty](AddProperty.None)

    val selfOrParentIsAutomationTemplate = Rx {
      val graph = GlobalState.rawGraph()
      val nodeIdx = graph.idToIdxOrThrow(focusPref.nodeId)
      graph.selfOrParentIsAutomationTemplate(nodeIdx)
    }

    addFieldMode.map {
      case AddProperty.CustomField =>
        ItemProperties.managePropertiesInline(
          
          ItemProperties.Target.Node(focusPref.nodeId)
        ).map(_ => AddProperty.None) --> addFieldMode
      case AddProperty.EdgeReference(title, create) =>
        ItemProperties.managePropertiesInline(
          
          ItemProperties.Target.Node(focusPref.nodeId),
          ItemProperties.TypeConfig(prefilledType = Some(NodeTypeSelection.Ref), hidePrefilledType = true, filterRefCompletion = { node =>
            val graph = GlobalState.rawGraph.now
            graph.idToIdxFold(node.id)(false)(graph.selfOrParentIsAutomationTemplate(_))
          }),
          ItemProperties.EdgeFactory.Plain(create),
          ItemProperties.Names(addButton = title)
        ).map(_ => AddProperty.None) --> addFieldMode
      case AddProperty.DefinedField(title, key, tpe) =>
        ItemProperties.managePropertiesInline(
          
          ItemProperties.Target.Node(focusPref.nodeId),
          ItemProperties.TypeConfig(prefilledType = Some(NodeTypeSelection.Data(tpe)), hidePrefilledType = true),
          ItemProperties.EdgeFactory.labeledProperty(key),
          names = ItemProperties.Names(addButton = title)
        ).map(_ => AddProperty.None) --> addFieldMode
      case AddProperty.None => VDomModifier(
        div(
          cls := "ui form",
          marginTop := "10px",
          Rx {
            VDomModifier(
              propertySingle().properties.map { property =>
                Components.removablePropertySection( property.key, property.values, parentIdAction)
              },

              VDomModifier.ifTrue(propertySingle().info.reverseProperties.nonEmpty)(div(
                Styles.flex,
                flexWrap.wrap,
                fontSize.small,
                span("Backlinks: ", color.gray),
                propertySingle().info.reverseProperties.map { node =>
                  Components.nodeCard( node, maxLength = Some(50)).apply(
                    margin := "3px",
                    Components.sidebarNodeFocusClickMod(Var(Some(focusPref)), pref => parentIdAction(pref.map(_.nodeId)), node.id)
                  )
                }
              ))
            )
          }
        ),
        div(
          div(
            Styles.flex,
            justifyContent.center,

            button(
              cls := "ui compact basic primary button mini",
              "+ Add Due Date",
              cursor.pointer,
              onClick.stopPropagation(AddProperty.DefinedField("Add Due Date", EdgeData.LabeledProperty.dueDate.key, NodeData.DateTime.tpe)) --> addFieldMode
            ),

            selfOrParentIsAutomationTemplate.map {
              case false => VDomModifier.empty
              case true => button(
                cls := "ui compact basic primary button mini",
                "+ Add Relative Due Date",
                cursor.pointer,
                onClick.stopPropagation(AddProperty.DefinedField("Add Relative Due Date", EdgeData.LabeledProperty.dueDate.key, NodeData.RelativeDate.tpe)) --> addFieldMode
              )
            },

            button(
              cls := "ui compact basic button mini",
              "+ Add Custom Field",
              cursor.pointer,
              onClick.stopPropagation(AddProperty.CustomField) --> addFieldMode
            )
          ),

          selfOrParentIsAutomationTemplate.map {
            case false => VDomModifier.empty
            case true =>
              val referenceNodes = Rx {
                val graph = GlobalState.rawGraph()
                val nodeIdx = graph.idToIdxOrThrow(focusPref.nodeId)
                graph.referencesTemplateEdgeIdx.map(nodeIdx)(edgeIdx => graph.nodes(graph.edgesIdx.b(edgeIdx)))
              }

              def addButton = VDomModifier(
                cursor.pointer,
                onClick.stopPropagation(AddProperty.EdgeReference("Add Reference Template", (sourceId, targetId) => Edge.ReferencesTemplate(sourceId, TemplateId(targetId)))) --> addFieldMode
              )
              def deleteButton(referenceNodeId: NodeId) = VDomModifier(
                cursor.pointer,
                onClick.stopPropagation(GraphChanges(delEdges = Array(Edge.ReferencesTemplate(focusPref.nodeId, TemplateId(referenceNodeId))))) --> GlobalState.eventProcessor.changes
              )

              div(
                padding := "5px",
                alignItems.flexStart,
                Styles.flex,
                justifyContent.spaceBetween,

                b("Template Reference:", UI.popup := "Reference another template, such that the current node becomes the automation template for any existing node derived from the referenced template node."),

                referenceNodes.map { nodes =>
                  div(
                    nodes.map { node =>
                      div(
                        Styles.flex,
                        Components.nodeCard( node, maxLength = Some(200)).apply(
                          Components.sidebarNodeFocusClickMod(Var(Some(focusPref)), pref => parentIdAction(pref.map(_.nodeId)), node.id)
                        ),
                        div(padding := "2px", Icons.delete, deleteButton(node.id))
                      )
                    },
                    button(cls := "ui compact basic button mini", "Add Template Reference", addButton)
                  )
                }
              )
          }
        ),
        renderSplit(
          left = VDomModifier(
            searchInput("Add Tag", filter = _.role == NodeRole.Tag, createNew = createNewTag(_), showNotFound = false).foreach { tagId =>
              GlobalState.submitChanges(GraphChanges.connect(Edge.Child)(ParentId(tagId), ChildId(focusPref.nodeId)))
            }
          ),
          right = VDomModifier(
            Styles.flex,
            alignItems.center,
            flexWrap.wrap,
            Rx {
              propertySingle().info.tags.map { tag =>
                Components.removableNodeTag( tag, taggedNodeId = focusPref.nodeId)
              }
            }
          ),
        ).apply(marginTop := "10px"),
        renderSplit(
          left = VDomModifier(
            searchInput("Assign User", filter = _.data.isInstanceOf[NodeData.User]).foreach { userId =>
              GlobalState.submitChanges(GraphChanges.connect(Edge.Assigned)(focusPref.nodeId, UserId(userId)))
            }
          ),
          right = VDomModifier(
            Styles.flex,
            alignItems.center,
            flexWrap.wrap,
            Rx {
              propertySingle().info.assignedUsers.map { user =>
                Components.removableAssignedUser( user, focusPref.nodeId)
              }
            }
          )
        ).apply(marginTop := "10px"),
      )
    }
  }
}
