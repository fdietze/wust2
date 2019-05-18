package wust.backend

import wust.api._
import wust.graph._
import wust.db.{Data, Db}
import wust.ids._

import scala.collection.JavaConverters._
import covenant.ws.api.EventDistributor
import mycelium.server.NotifiableClient
import wust.api.ApiEvent.NewGraphChanges

import scala.collection.{breakOut, mutable}
import scala.concurrent.{ExecutionContext, Future}
import scala.collection.parallel.ExecutionContextTaskSupport
import wust.backend.config.PushNotificationConfig
import wust.db.Data.RawPushData

import scala.collection.parallel.immutable.ParSeq
import scala.util.{Failure, Success}

//TODO adhere to TOO MANY REQUESTS Retry-after header: https://developers.google.com/web/fundamentals/push-notifications/common-issues-and-reporting-bugs
class HashSetEventDistributorWithPush(db: Db, pushConfig: Option[PushNotificationConfig])(
    implicit ec: ExecutionContext
) extends EventDistributor[ApiEvent, State] {

  private val subscribers = mutable.HashSet.empty[NotifiableClient[ApiEvent, State]]
  private val pushService = pushConfig.map(PushService.apply)

  def subscribe(client: NotifiableClient[ApiEvent, State]): Unit = {
    subscribers += client
  }

  def unsubscribe(client: NotifiableClient[ApiEvent, State]): Unit = {
    subscribers -= client
  }

  // origin is optional, since http clients can also trigger events
  override def publish(
    events: List[ApiEvent],
    origin: Option[NotifiableClient[ApiEvent, State]]
  ): Unit = if (events.nonEmpty) Future {
    scribe.info(s"Event distributor (${ subscribers.size } clients): $events from $origin.")

    val groupedEvents = events.collect { case a: ApiEvent.NewGraphChanges => a }.groupBy(_.user)
    val replacements = events.collect { case a: ApiEvent.ReplaceNode => a }
    groupedEvents.foreach { case (user, changes) =>
      val mergedChanges = changes.foldLeft(GraphChanges.empty)(_ merge _.changes)
      // filter out read edges - we do not want to notify clients about read edges because there would be too many updates in these clients, which makes the ui too slow.
      // TODO: removing read-edges is a workaround
      val filteredChanges = mergedChanges.copy(
        addEdges = mergedChanges.addEdges.filterNot(_.isInstanceOf[Edge.Read]),
        delEdges = mergedChanges.delEdges.filterNot(_.isInstanceOf[Edge.Read])
      )
      publishPerAuthor(user, filteredChanges, origin)
    }

    publishReplacements(replacements, origin)
  }

  //TODO do not send replacements to everybody, this leaks information. We use replacenodes primarily for merging users.
  // checking whether a user is of interest for a client would need to check whether two users are somehow in one workspace together.
  private def publishReplacements(
    replacements: List[ApiEvent.ReplaceNode],
    origin: Option[NotifiableClient[ApiEvent, State]]
  ): Unit = {
    // send out notifications to websocket subscribers
    if (replacements.nonEmpty) subscribers.foreach { client =>
      if (origin.fold(true)(_ != client)) client.notify(_ => Future.successful(replacements))
    }
  }

  private def publishPerAuthor(
    author: Node.User,
    graphChanges: GraphChanges,
    origin: Option[NotifiableClient[ApiEvent, State]]
  ): Unit = {
    // send out notifications to websocket subscribers
    if (graphChanges.nonEmpty) subscribers.foreach { client =>
      if (origin.fold(true)(_ != client)) client.notify(state =>
        state.flatMap(getWebsocketNotifications(author, graphChanges))
      )
    }

    // send out push notifications
    if (graphChanges.nonEmpty) pushService.foreach { pushService =>
      val addNodesByNodeId: Map[NodeId, Node] = graphChanges.addNodes.map(node => node.id -> node)(breakOut)
      for {
        parentNodeByChildId <- parentNodeByChildId(graphChanges)
        rawPushMeta <- determinePushPerUser(addNodesByNodeId.keys)
      } sendPushNotifications(pushService, author, addNodesByNodeId, parentNodeByChildId, rawPushMeta)
    }
  }

  private def determinePushPerUser(nodesOfInterest: Iterable[NodeId]): Future[List[RawPushData]] = {
    // since the push data is coming from the database, the contained nodes are already checked for permissions for each user
    db.notifications.notifyDataBySubscribedNodes(nodesOfInterest.toList)
  }

  private def parentNodeByChildId(graphChanges: GraphChanges): Future[Map[NodeId, Data.Node]] = {
    val childIdByParentId: Map[NodeId, NodeId] = graphChanges.addEdges.collect { case e: Edge.Child => e.parentId -> e.childId }(breakOut)

    if(childIdByParentId.nonEmpty) {
      db.node.get(childIdByParentId.keySet).map(_.map(parentNode => childIdByParentId(parentNode.id) -> parentNode).toMap)
    } else Future.successful(Map.empty[NodeId, Data.Node])
  }

  private def getWebsocketNotifications(author: Node.User, graphChanges: GraphChanges)(state: State): Future[List[ApiEvent]] = {
    state.auth.fold(Future.successful(List.empty[ApiEvent])) { auth =>
      db.notifications.updateNodesForConnectedUser(auth.user.id, graphChanges.involvedNodeIds)
        .map { permittedNodeIds =>
          val filteredChanges = graphChanges.filterCheck(permittedNodeIds.toSet, {
            case e: Edge.User    => List(e.sourceId)
            case e: Edge.Content => List(e.sourceId, e.targetId)
          })

          // we send replacements without a check, because they are normally only about users
          // TODO: actually check whether we have access to the replaced nodes
          if(filteredChanges.isEmpty) Nil
          else NewGraphChanges.forPrivate(author, filteredChanges) :: Nil
        }
    }
  }

  private def deleteSubscriptions(subscriptions: Seq[Data.WebPushSubscription]): Unit = if(subscriptions.nonEmpty) {
    db.ctx.transaction { implicit ec =>
      db.notifications.delete(subscriptions)
    }.onComplete {
      case Success(res) =>
        scribe.info(s"Deleted subscriptions ($subscriptions): $res.")
      case Failure(res) =>
        scribe.error(s"Failed to delete subscriptions ($subscriptions), due to exception: $res.")
    }
  }

  private def sendPushNotifications(
    pushService: PushService,
    author: Node.User,
    addNodesByNodeId: Map[NodeId, Node],
    parentNodeByChildId: Map[NodeId, Data.Node],
    rawPushMeta: List[RawPushData]): Unit = {

    // see https://developers.google.com/web/fundamentals/push-notifications/common-issues-and-reporting-bugs
    val expiryStatusCodes = Set(
      404, 410, // expired
    )
    val invalidHeaderCodes = Set(
      400, 401, // invalid auth headers
    )
    val mismatchSenderIdCode = 403
    val tooManyRequestsCode = 429
    val payloadTooLargeCode = 413
    val successStatusCode = 201

    //TODO really .par?
    val parallelRawPushMeta = rawPushMeta.par
    parallelRawPushMeta.tasksupport = new ExecutionContextTaskSupport(ec)

    val expiredSubscriptions: ParSeq[List[Future[Option[Data.WebPushSubscription]]]] = parallelRawPushMeta.map {
      case RawPushData(subscription, notifiedNodes, subscribedNodeId, subscribedNodeContent) if subscription.userId != author.id =>
        notifiedNodes.map { nodeId =>
          val node = addNodesByNodeId(nodeId)

          val pushData = PushData(author.name,
            node.data.str.trim,
            node.id.toBase58,
            subscribedNodeId.toBase58,
            subscribedNodeContent,
            parentNodeByChildId.get(nodeId).map(_.id.toBase58),
            parentNodeByChildId.get(nodeId).map(_.data.str),
            EpochMilli.now.toString
          )

          pushService.send(subscription, pushData).transform {
            case Success(response) =>
              response.getStatusLine.getStatusCode match {
                case `successStatusCode`                                   =>
                  Success(None)
                case statusCode if expiryStatusCodes.contains(statusCode)  =>
                  scribe.info(s"Subscription expired. Deleting subscription.")
                  Success(Some(subscription))
                case statusCode if invalidHeaderCodes.contains(statusCode) =>
                  scribe.error(s"Invalid headers. Deleting subscription.")
                  Success(Some(subscription))
                case `mismatchSenderIdCode`                                 =>
                  scribe.error(s"Mismatch sender id error. Deleting subscription.")
                  Success(Some(subscription))
                case `tooManyRequestsCode`                                 =>
                  scribe.error(s"Too many requests.")
                  Success(None)
                case `payloadTooLargeCode`                                 =>
                  scribe.error(s"Payload too large.")
                  Success(None)
                case _                                                     =>
                  val body = new java.util.Scanner(response.getEntity.getContent).asScala.mkString
                  scribe.error(s"Unexpected success code: $response body: $body.")
                  Success(None)
              }
            case Failure(t)        =>
              scribe.error(s"Cannot send push notification, due to unexpected exception: $t.")
              Success(None)
          }
        }
      case _ => List.empty[Future[Option[Data.WebPushSubscription]]]
    }

    Future.sequence(expiredSubscriptions.seq.flatten)
      .map(_.flatten)
      .foreach(deleteSubscriptions)
  }

}
