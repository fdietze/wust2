package wust.webApp.views

import fontAwesome.{IconLookup, freeRegular, freeSolid}
import jquery.JQuerySelection
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.graph._
import wust.ids.{NodeAccess, NodeData, NodeId, NodeRole}
import wust.webApp.state.{GlobalState, PageChange, View}
import Components._
import cats.effect.IO
import colorado.{Color, RGB}
import fomanticui.DropdownEntry
import monix.execution.Ack
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import wust.css.Styles
import wust.graph.Edge.Pinned
import wust.sdk.{BaseColors, NodeColor}
import wust.webApp.outwatchHelpers._
import wust.util._
import wust.webApp.views

import scala.concurrent.Future

object CreateNewPrompt {
  private sealed trait Error
  private object Error {
    case object MissingTag extends Error
  }

  def apply(state: GlobalState, show: Observable[Boolean], defaultView: View, defaultAddToChannels: Boolean, defaultNodeRole: NodeRole)(implicit ctx: Ctx.Owner): VDomModifier = IO {
    val parentNodes = Var[List[NodeId]](Nil)
    val childNodes = Var[List[NodeId]](Nil)
    val nodeRole = Var[NodeRole](defaultNodeRole)
    val addToChannels = Var[Boolean](defaultAddToChannels)
    val errorMessages = Var[List[Error]](Nil)
    val nodeAccess = Var[NodeAccess](NodeAccess.Inherited)

    var modalElement: JQuerySelection = null
    var searchElement: JQuerySelection = null

    def newMessage(msg: String): Future[Ack] = {
      println("HI " + nodeAccess.now)
      println("HI " + nodeAccess.now.getClass)
      if (parentNodes.now.isEmpty) {
        errorMessages.update(errors => (Error.MissingTag :: errors).distinct)
        Ack.Continue
      } else if (errorMessages.now.isEmpty) {
        val newNode = Node.Content(NodeData.Markdown(msg), nodeRole.now, NodeMeta(nodeAccess.now))
        val changes =
          GraphChanges.addNodeWithParent(newNode, parentNodes.now) merge
          GraphChanges.addToParent(childNodes.now, newNode.id)

        val ack = if (addToChannels.now) {
          val channelChanges = GraphChanges.connect(Pinned)(state.user.now.id, newNode.id)
          state.viewConfig() = state.focusNodeViewConfig(newNode.id, needsGet = false)
          state.eventProcessor.changes.onNext(changes merge channelChanges)
        } else {
          state.eventProcessor.changes.onNext(changes)
        }

        modalElement.modal("hide")
        Toast(s"Created new ${nodeRole.now}: ${StringOps.trimToMaxLength(newNode.str, 10)}", click = () => state.viewConfig() = state.focusNodeViewConfig(newNode.id), level = ToastLevel.Success)
        ack
      } else {
        Ack.Continue
      }

    }

    val header = div(
      Styles.flex,
      flexDirection.row,
      flexWrap.wrap,
      alignItems.center,

      div("Create new ", color := "rgba(0,0,0,0.62)"),
      div(
        marginLeft := "10px",
        cls := "ui basic buttons",
        Rx {
          def roleButton(title: String, icon: IconLookup, role: NodeRole): VDomModifier = div(
            cls := "ui button",
            icon, " ", title,
            (nodeRole() == role).ifTrue[VDomModifier](cls := "active"),
            onClick(role) --> nodeRole
          )
          VDomModifier(
            roleButton("Task", freeRegular.faCheckSquare, NodeRole.Task),
            roleButton("Message", freeRegular.faComment, NodeRole.Message)
          )
        },
        marginRight := "30px"
      ),
      UI.toggle("Bookmark", initialChecked = addToChannels.now) --> addToChannels
    )

    val description = VDomModifier(
      div(
        padding := "5px",
        Styles.flex,
        flexWrap.wrap,
        justifyContent.spaceBetween,

        div(
          div("Tags:", color := "rgba(0,0,0,0.62)"),
          div(
            Styles.flex,
            flexDirection.row,
            alignItems.center,

            Rx {
              val g = state.graph()
              parentNodes().map(tagId =>
                g.nodesByIdGet(tagId).map { tag =>
                  removableNodeTagCustom(state, tag, () => parentNodes.update(list => list.filter(_ != tag.id)))(padding := "2px")
                }
              )
            },
            div(
              paddingLeft := "5px",
              searchInGraph(
                state.graph,
                placeholder = "Add an existing tag",
                valid = parentNodes.map(_.nonEmpty),
                {
                  case n: Node.Content => !parentNodes.now.contains(n.id)
                  // only allow own user, we do not have public profiles yet
                  case n: Node.User => state.user.now.id == n.id && !parentNodes.now.contains(n.id)
                }
              ).foreach { nodeId =>
                errorMessages.update(_.filterNot(_ == Error.MissingTag))
                parentNodes() = nodeId :: parentNodes.now
              },
            )
          )
        ),
        div(
          div("Permission:", color := "rgba(0,0,0,0.62)"),
          UI.dropdown(
            new DropdownEntry {
              value = NodeAccess.Inherited.str
              name = "Inherited"
              selected = nodeAccess.now == NodeAccess.Inherited
            },
            new DropdownEntry {
              value = NodeAccess.ReadWrite.str
              name = "Public"
              selected = nodeAccess.now == NodeAccess.ReadWrite
            },
            new DropdownEntry {
              value = NodeAccess.Restricted.str
              name = "Private"
              selected = nodeAccess.now == NodeAccess.Restricted
            },
          ).collect(NodeAccess.fromString) --> nodeAccess
        )
      ),

      SharedViewElements.inputField(state, submitAction = newMessage, autoFocus = true).apply(width := "100%", padding := "10px"),

      errorMessages.map {
        case Nil => VDomModifier.empty
        case errors =>
          div(
            cls := "ui negative message",
            div(cls := "header", s"Cannot create new ${nodeRole.now}"),
            errors.map {
              case Error.MissingTag => p("Missing tag")
            }
          )
      },

      div(
        width := "300px",
        marginLeft := "auto",
        Rx {
          val nodes = childNodes().flatMap { id =>
            state.graph().nodesByIdGet(id).map { node =>
              nodeCard(node, contentInject = VDomModifier(Styles.flex, flexDirection.row, justifyContent.spaceBetween, span(freeSolid.faTimes, cursor.pointer, onClick.mapTo(childNodes.now.filterNot(_ == node.id)) --> childNodes)), maxLength = Some(20))
            }
          }

          if (nodes.isEmpty) VDomModifier.empty
          else VDomModifier(
            div(
              Styles.flex,
              flexDirection.column,
              justifyContent.spaceBetween,

              freeRegular.faComments,
              span(marginLeft := "auto", freeSolid.faTimes, cursor.pointer, onClick(Nil) --> childNodes)
            ),
            nodes
          )
        }
      )
    )

    VDomModifier(
      emitter(show).foreach { show =>
        if (show) {
          Var.set(
            parentNodes -> List(state.page.now.parentId.getOrElse(state.user.now.id)),
            childNodes -> state.selectedNodes.now
          )

          modalElement.modal("show")
        }
        else modalElement.modal("hide")
      },

      // TODO: better way to expose element from modal?
      UI.modal(header, description)(
        backgroundColor <-- parentNodes.map[String](_.foldLeft[Color](RGB("#FFFFFF"))((c, id) => NodeColor.mixColors(c, NodeColor.eulerBgColor(id))).toHex),
        onDomMount.asJquery.foreach(modalElement = _))
    )
  }

}
