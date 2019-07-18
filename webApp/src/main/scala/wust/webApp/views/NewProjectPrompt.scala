package wust.webApp.views

import flatland._
import wust.webApp.parsers.{ UrlConfigWriter }
import fontAwesome._
import monix.execution.Ack
import monix.reactive.Observer
import monix.reactive.subjects.PublishSubject
import org.scalajs.dom
import outwatch.dom._
import outwatch.dom.dsl.{label, _}
import outwatch.dom.helpers.EmitterBuilder
import rx._
import wust.css.Styles
import wust.graph._
import wust.ids._
import wust.sdk.NodeColor
import wust.util._
import wust.webApp._
import wust.webApp.dragdrop.DragItem.DisableDrag
import wust.webApp.dragdrop.{DragItem, DragPayload, DragTarget}
import wust.webApp.state._
import wust.webApp.views.Components._
import wust.webApp.views.DragComponents.{drag, dragWithHandle}
import wust.webUtil.Elements._
import wust.webUtil.outwatchHelpers._
import wust.webUtil.{BrowserDetect, Elements, ModalConfig, Ownable, UI}
import wust.webApp.views.DragComponents.{drag, registerDragContainer}
import GlobalState.SelectedNode

import scala.collection.breakOut
import scala.scalajs.js
import monix.reactive.Observable
import SharedViewElements._
import scala.reflect.ClassTag

object NewProjectPrompt {

  def newProjectButton(label: String = "New Project"): VNode = {
    val selectedViews = Var[Seq[View.Visible]](Seq.empty)
    val triggerSubmit = PublishSubject[Unit]
    def body(implicit ctx: Ctx.Owner) = div(
      color := "#333",
      div("Emojis at the beginning of the name become the Project's icon. For Example ", b(":tomato: Shopping List")),
      viewCheckboxes --> selectedViews,
      div("After creating, you can invite participants by clicking ", Icons.menu, " at the top right and then clicking ",  b("Members"), "."),
      div(
        marginTop := "20px",
        Styles.flex,
        justifyContent.flexEnd,
        button(
          "Create",
          cls := "ui violet button",
          onClick.stopPropagation(()) --> triggerSubmit
        )
      )
    )

    def newProject(sub: InputRow.Submission) = {
      val newName = if (sub.text.trim.isEmpty) GraphChanges.newProjectName else sub.text
      val nodeId = NodeId.fresh
      val views = if (selectedViews.now.isEmpty) None else Some(selectedViews.now.toList)
      GlobalState.submitChanges(GraphChanges.newProject(nodeId, GlobalState.user.now.id, newName, views) merge sub.changes(nodeId))
      GlobalState.urlConfig.update(_.focus(Page(nodeId), needsGet = false))
      selectedViews() = Seq.empty

      Ack.Continue
    }

    button(
      cls := "ui button",
      label,
      onClickNewNamePrompt(
        header = "Create Project",
        body = Ownable { implicit ctx => body },
        placeholder = Placeholder("Name of the Project"),
        showSubmitIcon = false,
        triggerSubmit = triggerSubmit,
        enableEmojiPicker = true
      ).foreach(newProject(_)),
      onClick.stopPropagation foreach { ev => ev.target.asInstanceOf[dom.html.Element].blur() },
    )
  }

  def viewCheckboxes = multiCheckbox[View.Visible](
    View.selectableList,
    view => span(
      marginLeft := "4px",
      ViewSwitcher.viewToTabInfo(view, 0, 0, 0).icon, //TODO: Map[View,Icon] ?
      span(marginLeft := "4px", view.toString)
    ),
  ).mapResult { modifier =>
      VDomModifier(
        width := "100%",
        padding := "20px 10px",
        h3("Select views:", marginBottom := "0px"),
        div("(can be changed later)", fontSize.smaller, color.gray, marginBottom := "15px"),
        modifier,
      )
    }

  def multiCheckbox[T: ClassTag](checkboxes: Array[T], description: T => VDomModifier): EmitterBuilder[Seq[T], VDomModifier] = EmitterBuilder.ofModifier { sink =>
    var checkedState = Array.fill(checkboxes.length)(false)
    val changed = PublishSubject[Unit]

    div(
      Styles.flex,
      flexDirection.column,
      fontSize.larger,
      checkboxes.mapWithIndex { (idx, value) =>
        div(
          marginLeft := "10px",
          marginBottom := "10px",
          label(
            Styles.flex,
            alignItems.center,
            input(
              tpe := "checkbox",
              onChange.checked.foreach { checked =>
                checkedState(idx) = checked
                changed.onNext(())
              },
              onClick.stopPropagation --> Observer.empty, // fix safari emitting extra click event onChange
              dsl.checked <-- changed.map(_ => checkedState(idx))
            ),
            description(value),
            cursor.pointer,
          )
        )
      },
      emitter(changed).map(_ => checkedState.flatMapWithIndex { (idx, checked) => if (checked) Array(checkboxes(idx)) else Array.empty }: Seq[T]) --> sink,
    )
  }

}