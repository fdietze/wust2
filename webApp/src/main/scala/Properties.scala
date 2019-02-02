package wust.webApp

import monix.reactive.Observable
import monix.reactive.subjects.{BehaviorSubject, PublishSubject}
import org.scalajs.dom
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.Styles
import wust.graph._
import wust.ids._
import wust.sdk.BaseColors
import wust.sdk.NodeColor.hue
import wust.util.StringOps._
import wust.webApp.outwatchHelpers._
import wust.webApp.state._
import wust.webApp.views.{Components, Elements, UI}
import wust.webApp.views.UI.ModalConfig
import wust.webUtil.StringOps._

import scala.scalajs.js

/*
 * Here, the managing of node properties is done.
 * Currently, this is done with providing a modal which enables the user to add properties to a node.
 */
object ItemProperties {

  val naming = "Manage custom fields"

  def iconByNodeData(data: NodeData): VDomModifier = data match {
    //    case _: NodeData.Integer => Icons.propertyInt
    //    case _: NodeData.Float => Icons.propertyDec
    case _: NodeData.Integer | _: NodeData.Decimal => Icons.propertyNumber
    case _: NodeData.Date                          => Icons.propertyDate
    case _: NodeData.File                          => Icons.files
    case _                                         => Icons.propertyText
  }

  def manageProperties(state: GlobalState, nodeId: NodeId)
                      (implicit ctx: Ctx.Owner) : VNode = {
    manageProperties(state, nodeId,
                     div(
                       cursor.pointer,
                       div(cls := "fa-fw", UI.popup("bottom right") := naming, Icons.property)
                     ))}


  def manageProperties(state: GlobalState, nodeId: NodeId,
                       contents : VNode)(implicit ctx: Ctx.Owner): VNode = {

    // todo: check if node is instance of node.content ?
    val graph = state.graph.now
    val node = graph.nodesById(nodeId)

    val clear = Handler.unsafe[Unit].mapObservable(_ => "")

    val modalCloseTrigger = PublishSubject[Unit]
    val propertyTypeSelection = BehaviorSubject[NodeData.Type](NodeData.Empty.tpe).transformObservable(o => Observable(o, clear.map(_ => NodeData.Empty.tpe)).merge)
    val propertyKeyInputProcess = BehaviorSubject[String]("").transformObservable(o => Observable(o, clear.map(_ => "")).merge)
    val propertyValueInputProcess = BehaviorSubject[String]("").transformObservable(o => Observable(o, clear.map(_ => "")).merge)

    def description(implicit ctx: Ctx.Owner) = {
      var element: dom.html.Element = null
      val inputSizeMods = VDomModifier(width := "250px", marginTop := "4px")

      val inputFieldMod: NodeData.Type => VDomModifier = {
        case NodeData.Integer.tpe   => Elements.integerInputMod
        case NodeData.Decimal.tpe   => Elements.decimalInputMod
        case NodeData.Date.tpe      => Elements.dateInputMod
        case NodeData.PlainText.tpe => Elements.textInputMod
        case _                      =>  VDomModifier(disabled, placeholder := "Select a property type")
      }

      VDomModifier(
        form(
          onDomMount.asHtml.foreach { element = _ },
          select(
            inputSizeMods,
            option(
              value := "none", "Select a property type",
              selected,
              selected <-- clear.map(_ => true),
              disabled,
            ),
            option( value := NodeData.Integer.tpe, "Integer Number" ),
            option( value := NodeData.Decimal.tpe, "Decimal Number" ),
            option( value := NodeData.Date.tpe, "Date" ),
            option( value := NodeData.PlainText.tpe, "Text" ),
            onInput.value.map(_.asInstanceOf[NodeData.Type]) --> propertyTypeSelection,
          ),
          input(
            cls := "ui fluid action input",
            inputSizeMods,
            tpe := "text",
            placeholder := "Property Name",
            value <-- clear,
            cls <-- propertyTypeSelection.map(t => if(t == NodeData.Empty.tpe) "disabled" else ""),
            onInput.value --> propertyKeyInputProcess,
          ),
          VDomModifier(
            input(
              cls := "ui fluid action input",
              inputSizeMods,
              value <-- clear,
              onInput.value --> propertyValueInputProcess,
              cls <-- propertyKeyInputProcess.map(k => if(k.isEmpty) "disabled" else ""),
              propertyTypeSelection.map(inputFieldMod),
              Elements.valueWithEnter(propertyValueInputProcess.withLatestFrom2(propertyKeyInputProcess, propertyTypeSelection)((pValue, pKey, pType) => (pKey, pValue, pType))) foreach { propertyData =>
                if(element.asInstanceOf[js.Dynamic].reportValidity().asInstanceOf[Boolean]) {
                  handleAddProperty(propertyData._1, propertyData._2, propertyData._3)
                }
              },
            ),
            div(
              cls := "ui primary button approve",
              cls <-- propertyValueInputProcess.map(v => if(v.isEmpty) "disabled" else ""),
              inputSizeMods,
              "Add property",
              onClick(propertyValueInputProcess.withLatestFrom2(propertyKeyInputProcess, propertyTypeSelection)((pValue, pKey, pType) => (pKey, pValue, pType))) foreach { propertyData =>
                if(element.asInstanceOf[js.Dynamic].reportValidity().asInstanceOf[Boolean]) {
                  handleAddProperty(propertyData._1, propertyData._2, propertyData._3)
                }
              },
            ),
          )
        ),
        div(
          marginTop := "10px",
          maxHeight := s"${0.5*dom.window.innerHeight}px",
          overflow.auto,
          Rx{
            val graph = state.graph()
            val propertyEdgesIdx = graph.propertiesEdgeIdx(graph.idToIdx(nodeId))
            val propertyEdges = propertyEdgesIdx.map(eIdx => graph.edges(eIdx).asInstanceOf[Edge.LabeledProperty])
            val propertyData = propertyEdges.map(e => (e, graph.nodesById(e.propertyId)))

            propertyData.map(data => propertyRow(data._1, data._2))
          },
        ),
        div(
          marginTop := "10px",
          cursor.pointer,
          a(
            onClick.stopPropagation.mapTo(state.urlConfig.now.focus(Page(nodeId), View.Detail)) foreach { vc =>
              modalCloseTrigger.onNext(()).onComplete { _ =>
                state.urlConfig() = vc
              }
            },
            "Show detailed view",
          )
        ),
      )
    }

    def handleAddProperty(propertyKey: String, propertyValue: String, propertyType: String)(implicit ctx: Ctx.Owner): Unit = {

      // TODO: Users and reuse
      val propertyNodeOpt: Option[Node] = propertyType match {
        case NodeData.Integer.tpe   => safeToInt(propertyValue).map(number => Node.Content(NodeData.Integer(number), NodeRole.Neutral))
        case NodeData.Decimal.tpe   => safeToDouble(propertyValue).map(number => Node.Content(NodeData.Decimal(number), NodeRole.Neutral))
        case NodeData.Date.tpe      => safeToEpoch(propertyValue).map(datum => Node.Content(NodeData.Date(datum), NodeRole.Neutral))
        case NodeData.PlainText.tpe => Some(Node.Content(NodeData.PlainText(propertyValue), NodeRole.Neutral))
        case _                      => None
      }

      propertyNodeOpt.foreach { propertyNode =>
        val propertyEdge = Edge.LabeledProperty (nodeId, EdgeData.LabeledProperty (propertyKey), propertyNode.id)
        val gc = GraphChanges (addNodes = Set (propertyNode), addEdges = Set (propertyEdge) )

        state.eventProcessor.changes.onNext (gc) foreach { _ => clear.onNext (()) }
      }
    }

    def handleRemoveProperty(propertyData: EdgeData.LabeledProperty, propertyId: NodeId)(implicit ctx: Ctx.Owner): Unit = {
      state.eventProcessor.changes.onNext(
        GraphChanges.disconnect(Edge.LabeledProperty)(nodeId, propertyData, propertyId)
      )
    }

    def propertyRow(propertyKey: Edge.LabeledProperty, propertyValue: Node)(implicit ctx: Ctx.Owner): VNode = div(
      Styles.flex,
      alignItems.center,
      Components.removablePropertyTag(state, propertyKey, propertyValue),
    )

    contents(
      onClick(Ownable(implicit ctx => UI.ModalConfig(
                        header = ModalConfig.defaultHeader(state, node, naming, Icons.property),
                        description = description,
                        close = modalCloseTrigger,
                        modalModifier = VDomModifier(
                          cls := "mini form",
                          ),
                        contentModifier = VDomModifier(
                          backgroundColor := BaseColors.pageBgLight.copy(h = hue(nodeId)).toHex
                        ),
                        ))) --> state.modalConfig
    )

    

  }

}

