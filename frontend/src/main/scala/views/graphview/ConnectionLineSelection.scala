package wust.frontend.views.graphview

import org.scalajs.d3v4._
import wust.frontend._

import scala.concurrent.ExecutionContext.Implicits.global

// object ConnectionLineSelection extends DataSelection[SimConnection] {
//   override val tag = "line"
//   override def enterAppend(line: Selection[SimConnection]) {
//     line
//       .attr("marker-end", "url(#graph_arrow)")
//       .style("stroke", "#666")
//       .style("stroke-width", "3px")
//   }

//   override def draw(line: Selection[SimConnection]) {
//     line
//       .attr("x1", (e: SimConnection) => e.source.x)
//       .attr("y1", (e: SimConnection) => e.source.y)
//       .attr("x2", (e: SimConnection) => e.target.x)
//       .attr("y2", (e: SimConnection) => e.target.y)
//   }
// }

// class ConnectionElementSelection(graphState: GraphState) extends DataSelection[SimConnection] {
//   import graphState.state.persistence

//   override val tag = "div"
//   override def enterAppend(element: Selection[SimConnection]) {
//     element
//       .style("position", "absolute")
//       .style("font-size", "20px")
//       .style("margin-left", "-0.5ex")
//       .style("margin-top", "-0.5em")
//       .text("\u00d7")
//       .style("pointer-events", "auto") // parent has pointer-events disabled, enable explicitly for the x button.
//       .style("cursor", "pointer")
//       .on("click", { (e: SimConnection) =>


//         DevPrintln(s"\nDelete Connection: ${e.sourceId} -> ${e.targetId}")
//         persistence.addChanges(delConnections = Set(e.connection))
//       })
//   }

//   override def draw(element: Selection[SimConnection]) {
//     element
//       // .style("left", (e: SimConnection) => s"${e.x.get}px")
//       // .style("top", (e: SimConnection) => s"${e.y.get}px")
//       .style("transform", { (e: SimConnection) =>
//         val center = (e.source.pos.get + e.target.pos.get) / 2
//         s"translate(${center.x}px,${center.y}px)"
//       })
//   }
// }
