package wust.frontend

import wust.graph._
import wust.ids._
import wust.util.collection._
import rx._
import rxext._

import autowire._
import boopickle.Default._
import scala.concurrent.ExecutionContext
import scala.util.Success
import wust.util.EventTracker.sendEvent
import scala.collection.mutable
import scala.scalajs.js.timers.setTimeout

sealed trait SyncStatus
object SyncStatus {
  case object Sending extends SyncStatus
  case object Pending extends SyncStatus
  case object Done extends SyncStatus
  case object Error extends SyncStatus
}

sealed trait SyncMode
object SyncMode {
  case object Live extends SyncMode
  case object Offline extends SyncMode

  val fromString: PartialFunction[String, SyncMode] = {
    case "Live"    => Live
    case "Offline" => Offline
  }

  val default = Live
  val all = Seq(Live, Offline)
}

class GraphPersistence(state: GlobalState)(implicit ctx: Ctx.Owner) {
  import ClientCache.storage

  private val hasError = Var(false)
  private val localChanges = Var(storage.graphChanges)
  private val changesInTransit = Var(List.empty[GraphChanges])

  private var undoHistory: List[GraphChanges] = Nil
  private var redoHistory: List[GraphChanges] = Nil
  private val deletedPostsById = new mutable.HashMap[PostId, Post]

  def currentChanges = (changesInTransit.now ++ localChanges.now).foldLeft(GraphChanges.empty)(_ merge _)

  //TODO better
  val canUndo = Var(undoHistory.nonEmpty)
  val canRedo = Var(redoHistory.nonEmpty)

  val status: Rx[SyncStatus] = Rx {
    if (changesInTransit().nonEmpty) SyncStatus.Sending
    else if (hasError()) SyncStatus.Error
    else if (!localChanges().isEmpty) SyncStatus.Pending
    else SyncStatus.Done
  }

  //TODO this writes to localstorage then needed, once for localchanges changes and once for changesintransit changes
  Rx {
    storage.graphChanges = changesInTransit() ++ localChanges()
  }

  private def enrichChanges(changes: GraphChanges): GraphChanges = {
    import changes.consistent._

    val toDelete = delPosts.flatMap { postId =>
      Collapse.getHiddenPosts(state.displayGraphWithoutParents.now.graph removePosts state.graphSelection.now.parentIds, Set(postId))
    }

    val toOwn = state.selectedGroupId.now.toSet.flatMap { (groupId: GroupId) =>
      addPosts.map(p => Ownership(p.id, groupId))
    }

    val containedPosts = addContainments.map(_.childId)
    val toContain = addPosts
      .filterNot(p => containedPosts(p.id))
      .flatMap(p => GraphSelection.toContainments(state.graphSelection.now, p.id))

    changes.consistent merge GraphChanges(delPosts = toDelete, addOwnerships = toOwn, addContainments = toContain)
  }

  def flush()(implicit ec: ExecutionContext): Unit = if (changesInTransit.now.isEmpty) {
    val newChanges = localChanges.now
    state.syncMode.now match {
      case _ if newChanges.isEmpty => ()
      case SyncMode.Live =>
        Var.set(
          VarTuple(localChanges, Nil),
          VarTuple(changesInTransit, newChanges),
          VarTuple(hasError, false)
        )
        println(s"persisting localChanges: $newChanges")
        Client.api.changeGraph(newChanges).call().onComplete {
          case Success(true) =>
            changesInTransit() = Nil

            val compactChanges = newChanges.foldLeft(GraphChanges.empty)(_ merge _)
            if (compactChanges.addPosts.nonEmpty) sendEvent("graphchanges", "addPosts", "success", compactChanges.addPosts.size)
            if (compactChanges.addConnections.nonEmpty) sendEvent("graphchanges", "addConnections", "success", compactChanges.addConnections.size)
            if (compactChanges.addContainments.nonEmpty) sendEvent("graphchanges", "addContainments", "success", compactChanges.addContainments.size)
            if (compactChanges.updatePosts.nonEmpty) sendEvent("graphchanges", "updatePosts", "success", compactChanges.updatePosts.size)
            if (compactChanges.delPosts.nonEmpty) sendEvent("graphchanges", "delPosts", "success", compactChanges.delPosts.size)
            if (compactChanges.delConnections.nonEmpty) sendEvent("graphchanges", "delConnections", "success", compactChanges.delConnections.size)
            if (compactChanges.delContainments.nonEmpty) sendEvent("graphchanges", "delContainments", "success", compactChanges.delContainments.size)

            // flush changes that could not be sent during this transmission
            setTimeout(0)(flush())
          case _ =>
            Var.set(
              VarTuple(localChanges, newChanges ++ localChanges.now),
              VarTuple(changesInTransit, Nil),
              VarTuple(hasError, true)
            )

            println(s"failed to persist localChanges: $newChanges")
            sendEvent("graphchanges", "flush", "failure", newChanges.size)
        }
      case _ => println(s"caching localChanges: $newChanges")
    }
  }

  def addChanges(
    addPosts:        Iterable[Post]        = Set.empty,
    addConnections:  Iterable[Connection]  = Set.empty,
    addContainments: Iterable[Containment] = Set.empty,
    addOwnerships:   Iterable[Ownership]   = Set.empty,
    updatePosts:     Iterable[Post]        = Set.empty,
    delPosts:        Iterable[PostId]      = Set.empty,
    delConnections:  Iterable[Connection]  = Set.empty,
    delContainments: Iterable[Containment] = Set.empty,
    delOwnerships:   Iterable[Ownership]   = Set.empty
  )(implicit ec: ExecutionContext): Unit = {
    val newChanges = GraphChanges.from(addPosts, addConnections, addContainments, addOwnerships, updatePosts, delPosts, delConnections, delContainments, delOwnerships)

    addChanges(newChanges)
  }

  def addChangesEnriched(
    addPosts:        Iterable[Post]        = Set.empty,
    addConnections:  Iterable[Connection]  = Set.empty,
    addContainments: Iterable[Containment] = Set.empty,
    addOwnerships:   Iterable[Ownership]   = Set.empty,
    updatePosts:     Iterable[Post]        = Set.empty,
    delPosts:        Iterable[PostId]      = Set.empty,
    delConnections:  Iterable[Connection]  = Set.empty,
    delContainments: Iterable[Containment] = Set.empty,
    delOwnerships:   Iterable[Ownership]   = Set.empty
  )(implicit ec: ExecutionContext): Unit = {
    val newChanges = enrichChanges(
      GraphChanges.from(addPosts, addConnections, addContainments, addOwnerships, updatePosts, delPosts, delConnections, delContainments, delOwnerships)
    )

    addChanges(newChanges)
  }

  def addChanges(newChanges: GraphChanges)(implicit ec: ExecutionContext): Unit = if (newChanges.nonEmpty) {
    //TODO fake info about own posts when applying
    state.ownPosts ++= newChanges.addPosts.map(_.id)

    // we need store all deleted posts to be able to reconstruct them when
    // undoing post deletion (need to add them again)
    deletedPostsById ++= newChanges.delPosts.map(id => state.rawGraph.now.postsById(id)).by(_.id)

    redoHistory = Nil
    undoHistory +:= newChanges

    saveAndApplyChanges(newChanges.consistent)
  }

  def undoChanges()(implicit ec: ExecutionContext): Unit = if (undoHistory.nonEmpty) {
    val changesToRevert = undoHistory.head
    undoHistory = undoHistory.tail
    redoHistory +:= changesToRevert
    saveAndApplyChanges(changesToRevert.revert(deletedPostsById))
  }

  def redoChanges()(implicit ec: ExecutionContext): Unit = if (redoHistory.nonEmpty) {
    val changesToRedo = redoHistory.head
    redoHistory = redoHistory.tail
    undoHistory +:= changesToRedo
    saveAndApplyChanges(changesToRedo)
  }

  //TODO how to allow setting additional changes in var.set with this one?///
  private def saveAndApplyChanges(changes: GraphChanges)(implicit ec: ExecutionContext) {
    Var.set(
      VarTuple(canUndo, undoHistory.nonEmpty),
      VarTuple(canRedo, redoHistory.nonEmpty),
      VarTuple(localChanges, localChanges.now :+ changes),
      //TODO: change only the display graph in global state by adding the currentChanges to the rawgraph
      VarTuple(state.rawGraph, state.rawGraph.now applyChanges (currentChanges merge changes))
    )

    flush()
  }
}
