package wust.sdk

import monix.execution.Scheduler
import monix.reactive.{Observable, OverflowStrategy}
import monix.reactive.subjects.{PublishSubject, PublishToOneSubject}
import wust.api.ApiEvent._
import wust.api._
import wust.ids.NodeId
import wust.graph._

import scala.concurrent.Future
import scala.util.{Failure, Success}
import concurrent.Promise
import monix.reactive.{Observable, Observer}
import monix.reactive.subjects.PublishSubject
import monix.reactive.OverflowStrategy.Unbounded
import monix.execution.Cancelable
import monix.execution.Ack
import monix.reactive.observers.BufferedSubscriber
import wust.ids.EdgeData

import scala.util.control.NonFatal
import scala.concurrent.duration._
import scala.collection.{breakOut, mutable}

sealed trait SyncStatus
object SyncStatus {
  case object Sending extends SyncStatus
  case object Pending extends SyncStatus
  case object Done extends SyncStatus
  case object Error extends SyncStatus
}

object EventProcessor {

  //TODO factory and constructor shared responsibility
  def apply(
      eventStream: Observable[Seq[ApiEvent]],
      enrichChanges: (GraphChanges, Graph) => GraphChanges,
      sendChange: List[GraphChanges] => Future[Boolean],
      initialAuth: Authentication
  )(implicit scheduler: Scheduler): EventProcessor = {
    val s = eventStream.share
    val graphEvents = s
      .map(_.collect { case e: ApiEvent.GraphContent => e })
      .collect { case l if l.nonEmpty => l }

    val authEvents = s
      .map(_.collect { case e: ApiEvent.AuthContent => e })
      .collect { case l if l.nonEmpty => l }

    new EventProcessor(
      graphEvents,
      authEvents,
      enrichChanges,
      sendChange,
      initialAuth
    )
  }
}

class EventProcessor private (
    eventStream: Observable[Seq[ApiEvent.GraphContent]],
    authEventStream: Observable[Seq[ApiEvent.AuthContent]],
    enrichChanges: (GraphChanges, Graph) => GraphChanges,
    sendChange: List[GraphChanges] => Future[Boolean],
    val initialAuth: Authentication
)(implicit scheduler: Scheduler) {
  // import Client.storage
  // storage.graphChanges <-- localChanges //TODO

  private val currentAuthUpdate = PublishSubject[Authentication]
  val currentAuth: Observable[Authentication] = Observable(currentAuthUpdate, authEventStream.collect {
    case events if events.nonEmpty => EventUpdate.createAuthFromEvent(events.last)
  }).merge.share
  val currentUser: Observable[AuthUser] = currentAuth.map(_.user)

  //TODO: publish only Observer? publishtoone subject? because used as hot observable?
  val changes = PublishSubject[GraphChanges]
  val localEvents = PublishSubject[ApiEvent.GraphContent]

  // public reader
  val (localChanges, graph): (Observable[GraphChanges], Observable[Graph]) = {
    // events  withLatestFrom
    // --------O----------------> localchanges
    //         ^          |
    //         |          v
    //         -----------O---->--
    //          graph,viewconfig

    val rawGraph = PublishToOneSubject[Graph]()
    val sharedRawGraph = rawGraph.share
    val rawGraphWithInit = sharedRawGraph.startWith(Seq(Graph.empty))

    val enrichedChanges = changes.withLatestFrom(rawGraphWithInit) { (changes, graph) =>
      val newChanges = enrichChanges(changes, graph)
      scribe.info(s"Local Graphchanges: ${newChanges.toPrettyString(graph)}")
      newChanges
    }

    val localChanges = enrichedChanges.withLatestFrom(currentUser.startWith(Seq(initialAuth.user)))((g, u) => (g, u)).collect {
      case (changes, user) if changes.nonEmpty => changes.consistent.withAuthor(user.id)
    }.share

    val localChangesAsEvents = localChanges.withLatestFrom(currentUser)((g, u) => (g, u)).map(gc => Seq(NewGraphChanges.forPrivate(gc._2.toNode, gc._1)))
    val graphEvents = Observable(eventStream, localEvents.map(Seq(_)), localChangesAsEvents).merge

    val graphWithChanges: Observable[Graph] = {
      var lastGraph = Graph.empty
      graphEvents.map { events =>
        var lastChanges: GraphChanges = null
        val replacements = new mutable.ArrayBuffer[ApiEvent.ReplaceNode]
        events.foreach {
          case ApiEvent.NewGraphChanges(user, changes) =>
            val completeChanges = if (changes.addNodes.exists(_.id == user.id)) changes else changes.copy(addNodes = changes.addNodes ++ Set(user)) // do not add author of change if the node was updated, the author might be outdated.
            if (lastChanges == null) lastChanges = completeChanges.consistent
            else lastChanges = lastChanges.merge(completeChanges).consistent
          case ApiEvent.ReplaceGraph(graph) =>
            lastChanges = null
            lastGraph = graph
          case r: ApiEvent.ReplaceNode =>
            replacements += r
        }
        if (lastChanges != null) lastGraph = lastGraph.applyChanges(lastChanges)
        lastGraph = replacements.foldLeft[Graph](lastGraph) { case (g, ReplaceNode(oldNodeId, newNode)) => g.replaceNode(oldNodeId, newNode) }
        lastGraph
      }
    }

    graphWithChanges subscribe rawGraph

    (localChanges, sharedRawGraph)
  }

  def applyChanges(changes: GraphChanges)(implicit scheduler: Scheduler): Future[Graph] = {
    //TODO: this function is not perfectly correct. A change could be written into rawGraph, before the current change is applied
    //TODO should by sync
    val obs = graph.headL
    this.changes.onNext(changes)
    obs.runToFuture
  }

  private val localChangesIndexed: Observable[(GraphChanges, Long)] = localChanges.zipWithIndex.asyncBoundary(Unbounded)

  private val sendingChanges: Observable[Long] = {
    val localChangesIndexedBusy = new BusyBufferObservable(localChangesIndexed, maxCount = 10)
      .map(list => (list.map(_._1), list.last._2))

    Observable.tailRecM(localChangesIndexedBusy.delayOnNext(200 millis)) { changes =>
      changes.flatMap {
        case (c, idx) =>
          Observable.fromFuture(sendChanges(c)).map {
            case true =>
              Right(idx)
            case false =>
              // TODO delay with exponential backoff
              Left(Observable((c, idx)).sample(5 seconds))
          }
      }
    }.share
  }

  sendingChanges.subscribe(
    _ => Ack.Continue,
    err => scribe.error("[Events] Error sending out changes, cannot continue", err) //TODO this is a critical error and should never happen? need something to notify the application of an unresolvable problem.
  )

  graph.combineLatest(currentAuth).subscribe(
    {
      case (graph, auth@Authentication.Verified(user: AuthUser.Real, _, _)) =>
        graph.nodesByIdGet(user.id).asInstanceOf[Option[Node.User]].fold[Future[Ack]](Ack.Continue) { userNode =>
          val newName = userNode.data.name
          if (newName != user.name) currentAuthUpdate.onNext(auth.copy(user = user.copy(name = newName)))
          else Ack.Continue
        }
      case _ => Ack.Continue
    }: ((Graph, Authentication)) => Future[Ack]
  )

  val changesInTransit: Observable[List[GraphChanges]] = localChangesIndexed
    .combineLatest[Long](sendingChanges.startWith(Seq(-1)))
    .scan(List.empty[(GraphChanges, Long)]) {
      case (prevList, (nextLocal, sentIdx)) =>
        (prevList :+ nextLocal) collect { case t @ (_, idx) if idx > sentIdx => t }
    }
    .map(_.map(_._1))
    .share

  private def sendChanges(changes: Seq[GraphChanges]): Future[Boolean] = {
    //TODO: why is import wust.util._ not enough to resolve RichFuture?
    // We only need it for the 2.12 polyfill
    new wust.util.RichFuture(sendChange(changes.toList)).transform {
      case Success(success) =>
        if (!success) {
          scribe.warn(s"ChangeGraph request returned false: $changes")
        }

        Success(success)
      case Failure(t) =>
        scribe.warn(s"ChangeGraph request failed '${t}': $changes")

        Success(false)
    }
  }
}
