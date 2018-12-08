package wust.webApp.views

import fontAwesome.{freeRegular, freeSolid}
import monix.reactive.subjects.PublishSubject
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.Styles
import wust.graph._
import wust.ids.{NodeData, NodeId, NodeRole}
import wust.sdk.BaseColors
import wust.sdk.NodeColor._
import wust.util._
import flatland._
import wust.webApp.{BrowserDetect, Icons}
import wust.webApp.dragdrop.{DragContainer, DragItem}
import wust.webApp.outwatchHelpers._
import wust.webApp.state.GlobalState
import wust.webApp.views.Components._
import wust.webApp.views.Elements._
import wust.util.collection._

object ListView {
  import SharedViewElements._

  def apply(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    div(
      overflow.auto,
      padding := "10px",

      addListItemInputField(state),

      Rx {
        println("reloading listview")
        val graph = state.graph()
        state.page().parentId.map { pageParentId =>
          val pageParentIdx = graph.idToIdx(pageParentId)
          val workspaces = graph.workspacesForParent(pageParentIdx)

          val allTasks: ArraySet = {
            val taskSet = ArraySet.create(graph.size)
            // we go down from workspaces, since tasks which only live in stages have an invalid encoding and should be ignored
//            println("listview workspaces: " + workspaces.map(i => graph.nodes(i).str).mkString(", "))
            algorithm.depthFirstSearchAfterStartsWithContinue(workspaces,graph.notDeletedChildrenIdx, {nodeIdx =>
              val node = graph.nodes(nodeIdx)
//              println("  listview " + node.str)
              node.role match {
                case NodeRole.Task =>
                  @inline def isCorrectlyEncodedTask = graph.notDeletedParentsIdx.exists(nodeIdx)(parentIdx => workspaces.contains(parentIdx)) //  || taskSet.contains(parentIdx) taskSet.contains activates subtasks
//                  println("    listview is Task, correctly encoded: " + isCorrectlyEncodedTask)
                  if(isCorrectlyEncodedTask) {
                    taskSet += nodeIdx
                    false // true goes deeper and also shows subtasks
                  } else false
                case NodeRole.Stage =>
//                  println("    listview is Stage")
                  true
                case _ => false
              }
            })

            taskSet
          }

//          println("listview allTasks: " + allTasks.collectAllElements.map(i => graph.nodes(i).str).mkString(", "))


          val (doneTasks, todoTasks) = {
//            println("listview separating done/todo")
            allTasks.partition { nodeIdx =>
              val node = graph.nodes(nodeIdx)
//              println("  listview " + node.str)
              val workspaces = graph.workspacesForNode(nodeIdx)
//              println("  listview workspaces: " + workspaces.map(i => graph.nodes(i).str).mkString(", "))
              graph.isDoneInAllWorkspaces(nodeIdx, workspaces)
            }
          }

          VDomModifier(
            div(todoTasks.map { nodeIdx =>
              val node = graph.nodes(nodeIdx)
              nodeCardWithCheckbox(state, node, pageParentId :: Nil).apply(margin := "4px")
            }),

            doneTasks.calculateNonEmpty.ifTrue[VDomModifier](hr(border := "1px solid black", opacity := 0.4, margin := "15px")),

            div(
              opacity := 0.5,
              doneTasks.map { nodeIdx =>
                val node = graph.nodes(nodeIdx)
                nodeCardWithCheckbox(state, node, pageParentId :: Nil).apply(margin := "4px")
              })
          )
        }

      },
    )
  }


  private def addListItemInputField(state: GlobalState)(implicit ctx: Ctx.Owner) = {
    def submitAction(str: String) = {
      val change = GraphChanges.addMarkdownTask(str)
      state.eventProcessor.enriched.changes.onNext(change)
    }

    val placeHolder = if(BrowserDetect.isMobile) "" else "Press Enter to add."

    val inputFieldFocusTrigger = PublishSubject[Unit]

    if(!BrowserDetect.isMobile) {
      state.page.triggerLater {
        inputFieldFocusTrigger.onNext(Unit) // re-gain focus on page-change
        ()
      }
    }

    inputRow(state, submitAction,
      preFillByShareApi = true,
      autoFocus = !BrowserDetect.isMobile,
      triggerFocus = inputFieldFocusTrigger,
      placeHolderMessage = Some(placeHolder),
      submitIcon = freeSolid.faPlus
    )(ctx)(Styles.flexStatic)
  }

}
