package wust.webApp

import fontAwesome.freeSolid
import monix.eval.Task
import monix.reactive.{Observable, Observer}
import monix.reactive.subjects.{BehaviorSubject, PublishSubject}
import org.scalajs.dom
import outwatch.dom._
import outwatch.dom.helpers.EmitterBuilder
import outwatch.dom.dsl._
import rx._
import wust.css.Styles
import wust.graph._
import wust.ids._
import wust.sdk.BaseColors
import wust.sdk.NodeColor.hue
import wust.util.StringOps._
import wust.util.macros.InlineList
import wust.webApp.outwatchHelpers._
import wust.webApp.state._
import wust.webApp.views.{Components, EditContext, EditElementParser, EditImplicits, EditInteraction, EditStringParser, EditableContent, Elements, UI, ValueStringifier}
import wust.webApp.StringJsOps._

import scala.scalajs.js
import scala.collection.breakOut
import scala.util.Try

/*
 * Here, the managing of node properties is done.
 * This is done via a simple form.
 * Either just get the form: managePropertiesContent
 * Or get a dropdown of this content: managePropertiesDropdown
 */
object ItemProperties {

  sealed trait TypeSelection
  object TypeSelection {
    case class Data(data: NodeData.Type) extends TypeSelection
    case object Ref extends TypeSelection

    import wust.ids.serialize.Circe._
    import io.circe._, io.circe.generic.auto._

    implicit val parser: EditStringParser[TypeSelection] = EditImplicits.circe.StringParser[TypeSelection]
    implicit val stringifier: ValueStringifier[TypeSelection] = EditImplicits.circe.Stringifier[TypeSelection]
  }

  sealed trait ValueSelection
  object ValueSelection {
    case class Data(nodeData: NodeData.Content) extends ValueSelection
    case class Ref(nodeId: NodeId) extends ValueSelection
  }

  case class Config(prefilledType: Option[TypeSelection] = Some(TypeSelection.Data(NodeData.Markdown.tpe)), prefilledKey: String = "")
  object Config {
    def default = Config()
  }

  sealed trait Target
  object Target {
    case class Node(id: NodeId) extends Target
    case class Custom(submitAction: (EdgeData.LabeledProperty, NodeId => GraphChanges) => GraphChanges, isAutomation: Rx[Boolean]) extends Target
  }

  def managePropertiesContent(state: GlobalState, target: Target, config: Config = Config.default)(implicit ctx: Ctx.Owner) = EmitterBuilder.ofModifier[Unit] { sink =>

    val clear = Handler.unsafe[Unit].mapObservable(_ => "")

    val propertyTypeSelection = Var[Option[TypeSelection]](config.prefilledType)
    val propertyKeyInput = Var[Option[NonEmptyString]](NonEmptyString(config.prefilledKey))
    val propertyValueInput = Var[Option[ValueSelection]](None)
    propertyTypeSelection.foreach { selection =>
      propertyValueInput() = None// clear value on each type change...
      if (propertyKeyInput.now.isEmpty) selection.foreach {
        case TypeSelection.Data(NodeData.File.tpe) => propertyKeyInput() = NonEmptyString(EdgeData.LabeledProperty.attachment.key)
        case TypeSelection.Ref => propertyKeyInput() = NonEmptyString(EdgeData.LabeledProperty.reference.key)
        case _ => ()
      }
    }

    val editModifier = VDomModifier(width := "100%")
    val editableConfig = EditableContent.Config(
      submitMode = EditableContent.SubmitMode.OnInput,
      selectTextOnFocus = false
    )
    implicit val context = EditContext(state)

    def description(implicit ctx: Ctx.Owner) = {
      var element: dom.html.Element = null

      val isChildOfAutomationTemplate = target match {
        case Target.Node(nodeId) => Rx {
          val graph = state.graph()
          val nodeIdx = graph.idToIdxOrThrow(nodeId)
          graph.automatedEdgeReverseIdx.sliceNonEmpty(nodeIdx) || graph.ancestorsIdxExists(nodeIdx)(parentIdx => graph.automatedEdgeReverseIdx.sliceNonEmpty(parentIdx))
        }
        case Target.Custom(_, isAutomation) => isAutomation
      }

      def createProperty() = {
        handleAddProperty(propertyKeyInput.now, propertyValueInput.now)
      }

      VDomModifier(
        form(
          width := "200px",
          onDomMount.asHtml.foreach { element = _ },
          Styles.flex,
          flexDirection.column,
          alignItems.center,
          VDomModifier.ifTrue(propertyKeyInput.now.isEmpty)( //do not select key if already specifided
            EditableContent.editorRx[NonEmptyString](propertyKeyInput, editableConfig.copy(
              modifier = VDomModifier(
                width := "100%",
                Elements.onEnter.stopPropagation foreach(createProperty()),
                placeholder := "Field Name"
              ),
            )).apply(editModifier)
          ),
          div(
            marginTop := "4px",
            width := "100%",
            Styles.flex,
            alignItems.center,
            justifyContent.spaceBetween,
            b("Field Type:", color.gray, margin := "0px 5px 0px 5px"),
            isChildOfAutomationTemplate.map { isTemplate =>
              EditableContent.select[TypeSelection](
                "Select a field type",
                propertyTypeSelection,
                ("Text", TypeSelection.Data(NodeData.Markdown.tpe)) ::
                ("Number", TypeSelection.Data(NodeData.Decimal.tpe)) ::
                ("File", TypeSelection.Data(NodeData.File.tpe)) ::
                ("Date", TypeSelection.Data(NodeData.Date.tpe)) ::
                ("DateTime", TypeSelection.Data(NodeData.DateTime.tpe)) ::
                ("Duration", TypeSelection.Data(NodeData.Duration.tpe)) ::
                (if (isTemplate) ("Relative Date", TypeSelection.Data(NodeData.RelativeDate.tpe)) :: Nil else Nil) :::
                ("Refer to...", TypeSelection.Ref) ::
                Nil
              ).apply(tabIndex := -1)
            }
          ),
          propertyTypeSelection.map(_.flatMap {
            case TypeSelection.Data(propertyType) =>
              EditElementParser.forNodeDataType(propertyType) map { implicit parser =>
                EditableContent.editorRx[NodeData.Content](propertyValueInput.imap[Option[NodeData.Content]](_.collect { case ValueSelection.Data(data) => data })(_.map(ValueSelection.Data(_))), editableConfig.copy(
                  modifier = VDomModifier(
                    width := "100%",
                    marginTop := "4px",
                    Elements.onEnter.stopPropagation foreach(createProperty())
                  )
                )).apply(editModifier)
              }
            case TypeSelection.Ref => Some(
              Components.searchAndSelectNodeApplied(state, propertyValueInput.imap[Option[NodeId]](_.collect { case ValueSelection.Ref(data) => data })(_.map(ValueSelection.Ref(_)))).apply(
                width := "100%",
                marginTop := "4px",
              )
            )
          }),
          div(
            marginTop := "5px",
            cls := "ui primary button approve",
            Rx {
              VDomModifier.ifTrue(propertyKeyInput().isEmpty || propertyValueInput().isEmpty)(cls := "disabled")
            },
            "Add Custom Field",
            onClick.stopPropagation foreach(createProperty())
          ),
        ),
      )
    }

    def handleAddProperty(propertyKey: Option[NonEmptyString], propertyValue: Option[ValueSelection])(implicit ctx: Ctx.Owner): Unit = for {
      propertyKey <- propertyKey
      propertyValue <- propertyValue
    } {

      val propertyEdgeData = EdgeData.LabeledProperty(propertyKey.string)

      def sendChanges(addProperty: NodeId => GraphChanges, extendable: Either[NodeId, Node.Content]) = {
        val changes = target match {
          case Target.Custom(submitAction, _) => submitAction(propertyEdgeData, addProperty)
          case Target.Node(nodeId) => addProperty(nodeId)
        }

        state.eventProcessor.changes.onNext(changes) foreach { _ => clear.onNext (()) }
      }

      propertyValue match {

        case ValueSelection.Data(data: NodeData.Content) =>
          val propertyNode = Node.Content(NodeId.fresh, data, NodeRole.Neutral)
          def addProperty(targetNodeId: NodeId): GraphChanges = {
            val newPropertyNode = propertyNode.copy(id = NodeId.fresh)
            val propertyEdge = Edge.LabeledProperty(targetNodeId, propertyEdgeData, PropertyId(newPropertyNode.id))
            GraphChanges(addNodes = Array(newPropertyNode), addEdges = Array(propertyEdge))
          }

          sendChanges(addProperty, Right(propertyNode))

        case ValueSelection.Ref(nodeId)                  =>
          def addProperty(targetNodeId: NodeId): GraphChanges = {
            val propertyEdge = Edge.LabeledProperty(targetNodeId, propertyEdgeData, PropertyId(nodeId))
            GraphChanges(addEdges = Array(propertyEdge))
          }

          sendChanges(addProperty, Left(nodeId))

        case _                             => ()
      }

      sink.onNext(())
    }

    def propertyRow(propertyKey: Edge.LabeledProperty, propertyValue: Node)(implicit ctx: Ctx.Owner): VNode = div(
      Styles.flex,
      alignItems.center,
      Components.removablePropertyTag(state, propertyKey, propertyValue),
    )

    description
  }

  def managePropertiesDropdown(state: GlobalState, target: Target, config: Config = Config.default, descriptionModifier: VDomModifier = VDomModifier.empty, dropdownModifier: VDomModifier = cls := "top left")(implicit ctx: Ctx.Owner): VDomModifier = {
    val closeDropdown = PublishSubject[Unit]
    UI.dropdownMenu(VDomModifier(
      padding := "5px",
      div(cls := "item", display.none), // dropdown menu needs an item
      div(
        cls := "ui mini form",
        managePropertiesContent(state, target, config) --> closeDropdown,
        descriptionModifier
      )
    ), closeDropdown, dropdownModifier = dropdownModifier)
  }

  def managePropertiesInline(state: GlobalState, target: Target, config: Config = Config.default, descriptionModifier: VDomModifier = VDomModifier.empty)(implicit ctx: Ctx.Owner): EmitterBuilder[Unit, VDomModifier] = EmitterBuilder.ofModifier { editMode =>
    div(
      padding := "10px",

      div(
        Styles.flex,
        justifyContent.center,
        marginBottom := "10px",
        color.gray,
        b("New Custom Field"),
        div(marginLeft := "15px", freeSolid.faTimes, cursor.pointer, onClick.stopPropagation(()) --> editMode)
      ),
      div(
        Styles.flex,
        flexDirection.column,
        alignItems.center,
        cls := "ui mini form",
        managePropertiesContent(state, target, config) --> editMode,
        descriptionModifier
      )
    )
  }
}

