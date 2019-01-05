package wust.webApp.views

import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.dom.helpers.EmitterBuilder
import rx._
import wust.css.{Styles, ZIndex}
import wust.graph.{Graph,Page}
import wust.ids._
import wust.webApp.outwatchHelpers._
import wust.webApp.state.GlobalState
import wust.webApp.views.Components._
import wust.util._

// Combines task list and kanban-view into one view with a kanban-switch
object TasksView {
  def apply(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    val topLevelStageExists = Rx {
      // TODO: we should not react on graph, but on the result of a getGraph Request.
      val page = state.page()
      val graph = state.graph()
      page.parentId.fold(false) { parentId =>
        val parentIdx = graph.idToIdx(parentId)
        val workspacesIdx = graph.workspacesForParent(parentIdx)
        val doneNodes:Array[Int] = workspacesIdx.flatMap(workspaceIdx => graph.doneNodeForWorkspace(workspaceIdx))
        graph.notDeletedChildrenIdx.exists(parentIdx) { childIdx =>
          val node = graph.nodes(childIdx)
          val res = node.role == NodeRole.Stage && !doneNodes.contains(childIdx)
          res
        }
      }
    }
    
    val kanbanSwitch = Var(false)

    var lastPage = Page.empty
    state.graph.foreach{ _ =>
      if(lastPage != state.page.now) {
        kanbanSwitch() = topLevelStageExists.now
        lastPage = state.page.now
      }
    }

    div(
      Styles.flex,
      flexDirection.column,
      keyed,

      div(
        alignSelf.flexEnd,
        padding := "5px 15px 5px 5px",

        backgroundColor := "rgba(255,255,255,0.72)",
        borderBottomLeftRadius := "5px",

        Styles.flex,
        justifyContent.flexEnd,
        kanbanSwitchBar(kanbanSwitch),
      ),

      Rx{
        if(kanbanSwitch()) KanbanView(state).apply(
          Styles.growFull,
          flexGrow := 1
        )
        else ListView(state).apply(
          Styles.growFull,
          flexGrow := 1
        )
      }
    )
  }

  private def kanbanSwitchBar(kanbanSwitch:Var[Boolean]) = {
    div(
      UI.tooltip("bottom right") := "Show tasks in a kanban or as list.",
      UI.toggle("Columns", kanbanSwitch),

      zIndex := ZIndex.overlaySwitch, // like selectednodes, but still below
    )
  }
}
