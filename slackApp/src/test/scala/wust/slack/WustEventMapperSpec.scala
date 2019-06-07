package wust.slack

import akka.actor.ActorSystem
import org.scalatest._
import wust.graph._
import wust.ids._
import wust.slack.Data._

import scala.concurrent.Future

case object MockAdapter extends PersistenceAdapter {

  implicit val system: ActorSystem = ActorSystem("slack-mock-adapter")

  // Store
  def storeOrUpdateUserAuthData(userMapping: User_Mapping): Future[Boolean] = ???
  def storeOrUpdateMessageMapping(messageMapping: Message_Mapping): Future[Boolean] = ???
  def storeOrUpdateChannelMapping(channelMapping: Channel_Mapping): Future[Boolean] = ???
  def storeOrUpdateTeamMapping(teamMapping: Team_Mapping): Future[Boolean] = ???


  // Update
  def updateMessageMapping(messageMapping: Message_Mapping): Future[Boolean] = ???
  def updateChannelMapping(channelMapping: Channel_Mapping): Future[Boolean] = ???


  // Delete
  def deleteChannelBySlackId(channelId: SlackChannelId): Future[Boolean] = ???
  def unDeleteChannelBySlackId(channelId: SlackChannelId): Future[Boolean] = ???
  def deleteMessageBySlackIdData(channelId: SlackChannelId, timestamp: SlackTimestamp): Future[Boolean] = ???


  // Queries
  // Query Wust NodeId by Slack Id
  def getTeamNodeBySlackId(teamId: SlackTeamId): Future[Option[NodeId]] = ???
  def getChannelNodeBySlackId(channelId: SlackChannelId): Future[Option[NodeId]] = ???
  def getMessageNodeBySlackIdData(channel: SlackChannelId, timestamp: SlackTimestamp): Future[Option[NodeId]] = ???


  // Query Slack Id by Wust NodeId
  def getSlackChannelByWustId(nodeId: NodeId): Future[Option[SlackChannelId]] = {
    val m = Map( TestConstants.channelNodeId -> "SlackChannelId")
    Future.successful(m.get(nodeId))
  }


  // Query Data by Slack Id


  // Query Data by Wust Id
  def getWustUserBySlackUserId(slackUser: SlackUserId): Future[Option[WustUserData]] = ???
  def getSlackUserByWustId(userId: UserId): Future[Option[SlackUserData]] = ???
  def getChannelMappingByWustId(nodeId: NodeId): Future[Option[Channel_Mapping]] = ???
  def getSlackMessageByWustId(nodeId: NodeId): Future[Option[Message_Mapping]] = ???


  // Guards
  def teamExistsByWustId(nodeId: NodeId): Future[Boolean] = Future.successful(nodeId == TestConstants.workspaceId)
  def channelExistsByNameAndTeam(teamId: SlackTeamId, channelName: String): Future[Boolean] = ???
  def channelExistsByWustId(nodeId: NodeId): Future[Boolean] = ???
  def isChannelDeletedBySlackId(channelId: String): Future[Boolean] = ???
  def isChannelUpToDateBySlackDataElseGetNodes(channelId: String, name: String): Future[Option[(NodeId, NodeId)]] = ???
  def isMessageDeletedBySlackIdData(channelId: SlackChannelId, timestamp: SlackTimestamp): Future[Boolean] = ???
  def isMessageUpToDateBySlackData(channel: String, timestamp: String, text: String): Future[Boolean] = ???
}

object TestConstants {
  val workspaceId: NodeId = NodeId.fromBase58String("5R73FK2PwrEU6Kt1dEUzkw").right.get
  val messageNodeId: NodeId = NodeId.fromBase58String("5R73FK2PwrEU6Kt1dEUzkJ").right.get
  val channelNodeId: NodeId = NodeId.fromBase58String("5R73E84pCdVisswaNFr47x").right.get
  val userId: UserId = UserId.fromBase58String("5R5TZsZJeL3enTMmq8Jwmg").right.get
}

class WustEventMapperSpec extends FreeSpec with EitherValues with Matchers {

  implicit val system: ActorSystem = ActorSystem("slack-test-wust-event")
  import monix.execution.Scheduler.Implicits.global


  def getMapper(path: String = "wust.slack.test") = {
    WustEventMapper("", MockAdapter)
  }

//  "load config" in {
//    val config = Config.load("wust.slack.test")
//    config should be ('right)
//  }

//  "use correct user for events" in {
//    val mapper: WustEventMapper = getMapper()
//
//    // TODO
//  }

  "detect: create message event" in {
    val mapper: WustEventMapper = getMapper()

//     List(GraphChanges(Set(Content(5R73FK2PwrEU6Kt1dEUzkJ,Markdown(createMessageEvent),NodeMeta(Inherited))),Set(Parent(5R73FK2PwrEU6Kt1dEUzkJ,Parent,5R73E84pCdVisswaNFr47x), Author(5R5TZsZJeL3enTMmq8Jwmg,Author(2018-08-12 14:03:14),5R73FK2PwrEU6Kt1dEUzkJ)),Set()))
    val createMessage = GraphChanges(
      addNodes = Set(
        Node.Content(
          TestConstants.messageNodeId,
          NodeData.Markdown("createMessageEvent"),
          NodeRole.Message,
          NodeMeta(NodeAccess.Inherited),
        )
      ),
      addEdges = Set(
        Edge.Child(
          ParentId(TestConstants.channelNodeId),
          EdgeData.Child(None, None),
          ChildId(TestConstants.messageNodeId),
        ),
        Edge.Author(
          TestConstants.messageNodeId,
          EdgeData.Author(EpochMilli.now),
          TestConstants.userId,
        ),
      ),
      delEdges = Set.empty[Edge]
    )

    val filterCreateMessage = mapper.filterCreateMessageEvents(createMessage)
    val filterCreateChannel = mapper.filterCreateChannelEvents(createMessage)
    val filterDelete = mapper.filterDeleteEvents(createMessage)
    val filterUpdate = mapper.filterUpdateEvents(createMessage)
    val filterUnDelete = mapper.filterUndeleteEvents(createMessage)

    filterCreateMessage.map(_.nonEmpty shouldBe true)
    filterCreateChannel.map(_.nonEmpty shouldBe false)
    filterDelete.nonEmpty shouldBe false
    filterUpdate.nonEmpty shouldBe false
    filterUnDelete.nonEmpty shouldBe false

  }

  "detect: create channel event" in {
    val mapper: WustEventMapper = getMapper()

    // List(GraphChanges(Set(Content(5R73E84pCdVisswaNFr47x,Markdown(createChannelEvent),NodeMeta(Inherited))),Set(Parent(5R73E84pCdVisswaNFr47x,Parent,5R28qFeQj1Ny6tM9b7BXis), Author(5R5TZsZJeL3enTMmq8Jwmg,Author(2018-08-12 14:02:38),5R73E84pCdVisswaNFr47x)),Set()))
    val createChannel = GraphChanges(
      addNodes = Set(
        Node.Content(
          TestConstants.channelNodeId,
          NodeData.Markdown("createChannelEvent"),
          NodeRole.Message,
          NodeMeta(NodeAccess.Inherited),
        )
      ),
      addEdges = Set(
        Edge.Child(
          ParentId(TestConstants.workspaceId),
          EdgeData.Child(None, None),
          ChildId(TestConstants.channelNodeId),
        ),
        Edge.Author(
          TestConstants.channelNodeId,
          EdgeData.Author(EpochMilli.now),
          TestConstants.userId,
        )
      ),
      delEdges = Set.empty[Edge]
    )

    val filterCreateMessage = mapper.filterCreateMessageEvents(createChannel)
    val filterCreateChannel = mapper.filterCreateChannelEvents(createChannel)
    val filterDelete = mapper.filterDeleteEvents(createChannel)
    val filterUpdate = mapper.filterUpdateEvents(createChannel)
    val filterUnDelete = mapper.filterUndeleteEvents(createChannel)

    filterCreateMessage.map(_.nonEmpty shouldBe false)
    filterCreateChannel.map(_.nonEmpty shouldBe true)
    filterDelete.nonEmpty shouldBe false
    filterUpdate.nonEmpty shouldBe false
    filterUnDelete.nonEmpty shouldBe false

  }

  "detect: delete message event" in {
    val mapper: WustEventMapper = getMapper()

    // List(GraphChanges(Set(),Set(Parent(5R73FK2PwrEU6Kt1dEUzkJ,Parent(deletedAt = 2018-08-12 14:08:32),5R73E84pCdVisswaNFr47x)),Set()))
    val deleteMessage = GraphChanges(
      addNodes = Set.empty[Node],
      addEdges = Set(
        Edge.Child(
          ParentId(TestConstants.channelNodeId),
          EdgeData.Child(Some(EpochMilli.now), None),
          ChildId(TestConstants.messageNodeId),
        ),
      ),
      delEdges = Set.empty[Edge]
    )

    val filterCreateMessage = mapper.filterCreateMessageEvents(deleteMessage)
    val filterCreateChannel = mapper.filterCreateChannelEvents(deleteMessage)
    val filterDelete = mapper.filterDeleteEvents(deleteMessage)
    val filterUpdate = mapper.filterUpdateEvents(deleteMessage)
    val filterUnDelete = mapper.filterUndeleteEvents(deleteMessage)

    filterCreateMessage.map(_.nonEmpty shouldBe false)
    filterCreateChannel.map(_.nonEmpty shouldBe false)
    filterDelete.nonEmpty shouldBe true
    filterUpdate.nonEmpty shouldBe false
    filterUnDelete.nonEmpty shouldBe false

  }

  "detect: delete channel event" in {
    val mapper: WustEventMapper = getMapper()

    // List(GraphChanges(Set(),Set(Parent(5R73E84pCdVisswaNFr47x,Parent(deletedAt = 2018-08-12 14:09:27),5R28qFeQj1Ny6tM9b7BXis)),Set()))
    val deleteChannel = GraphChanges(
      addNodes = Set.empty[Node],
      addEdges = Set(
        Edge.Child(
          ParentId(TestConstants.workspaceId),
          EdgeData.Child(Some(EpochMilli.now), None),
          ChildId(TestConstants.channelNodeId),
        ),
      ),
      delEdges = Set.empty[Edge]
    )

    val filterCreateMessage = mapper.filterCreateMessageEvents(deleteChannel)
    val filterCreateChannel = mapper.filterCreateChannelEvents(deleteChannel)
    val filterDelete = mapper.filterDeleteEvents(deleteChannel)
    val filterUpdate = mapper.filterUpdateEvents(deleteChannel)
    val filterUnDelete = mapper.filterUndeleteEvents(deleteChannel)

    filterCreateMessage.map(_.nonEmpty shouldBe false)
    filterCreateChannel.map(_.nonEmpty shouldBe false)
    filterDelete.nonEmpty shouldBe true
    filterUpdate.nonEmpty shouldBe false
    filterUnDelete.nonEmpty shouldBe false

  }

  "detect: update message event" in {
    val mapper: WustEventMapper = getMapper()

    // List(GraphChanges(Set(Content(5R73FK2PwrEU6Kt1dEUzkJ,Markdown(updateMessageEvent),NodeMeta(Inherited))),Set(Author(5R5TZsZJeL3enTMmq8Jwmg,Author(2018-08-12 14:04:59),5R73FK2PwrEU6Kt1dEUzkJ)),Set()))
    val updateMessage = GraphChanges(
      addNodes = Set(
        Node.Content(
          TestConstants.messageNodeId,
          NodeData.Markdown("updateMessageEvent"),
          NodeRole.Message,
          NodeMeta(NodeAccess.Inherited),
        )
      ),
      addEdges = Set(
        Edge.Author(
          TestConstants.messageNodeId,
          EdgeData.Author(EpochMilli.now),
          TestConstants.userId,
        ),
      ),
      delEdges = Set.empty[Edge]
    )

    val filterCreateMessage = mapper.filterCreateMessageEvents(updateMessage)
    val filterCreateChannel = mapper.filterCreateChannelEvents(updateMessage)
    val filterDelete = mapper.filterDeleteEvents(updateMessage)
    val filterUpdate = mapper.filterUpdateEvents(updateMessage)
    val filterUnDelete = mapper.filterUndeleteEvents(updateMessage)

    filterCreateMessage.map(_.nonEmpty shouldBe false)
    filterCreateChannel.map(_.nonEmpty shouldBe false)
    filterDelete.nonEmpty shouldBe false
    filterUpdate.nonEmpty shouldBe true
    filterUnDelete.nonEmpty shouldBe false

  }

  "detect: rename channel event" in {
    val mapper: WustEventMapper = getMapper()

    // List(GraphChanges(Set(Content(5R73E84pCdVisswaNFr47x,Markdown(updateChannelEvent),NodeMeta(Inherited))),Set(Author(5R5TZsZJeL3enTMmq8Jwmg,Author(2018-08-12 14:07:30),5R73E84pCdVisswaNFr47x)),Set())))
    val renameChannel = GraphChanges(
      addNodes = Set(
        Node.Content(
          TestConstants.channelNodeId,
          NodeData.Markdown("updateChannelEvent"),
          NodeRole.Message,
          NodeMeta(NodeAccess.Inherited),
        )
      ),
      addEdges = Set(
        Edge.Author(
          TestConstants.channelNodeId,
          EdgeData.Author(EpochMilli.now),
          TestConstants.userId,
        ),
      ),
      delEdges = Set.empty[Edge]
    )

    val filterCreateMessage = mapper.filterCreateMessageEvents(renameChannel)
    val filterCreateChannel = mapper.filterCreateChannelEvents(renameChannel)
    val filterDelete = mapper.filterDeleteEvents(renameChannel)
    val filterUpdate = mapper.filterUpdateEvents(renameChannel)
    val filterUnDelete = mapper.filterUndeleteEvents(renameChannel)

    filterCreateMessage.map(_.nonEmpty shouldBe false)
    filterCreateChannel.map(_.nonEmpty shouldBe false)
    filterDelete.nonEmpty shouldBe false
    filterUpdate.nonEmpty shouldBe true
    filterUnDelete.nonEmpty shouldBe false

  }

  "detect: undelete channel event" in {
    val mapper: WustEventMapper = getMapper()

//    List(ForPublic(5RBLjXEbmu16SNUjyee7Bf,GraphChanges(Set(User(5RBLjXEbmu16SNUjyee7Bf,User(j,false,0,5RBLjXF3CLH6vX1GJ1AS8g),NodeMeta(Level(Restricted)))),Set(Parent(5RGHCTvXSd1cKniWc3xc25,Parent(deletedAt = 2018-08-23 15:08:54),5RGHCHpdWsK4X6NpzbCVMK)),Set())))

    val undeleteChannel = GraphChanges(
      addNodes = Set(
        Node.User(
          UserId.fromBase58String("5RBLjXEbmu16SNUjyee7Bf").right.get,
          NodeData.User("j",false,0),
          NodeMeta(NodeAccess.Restricted)
        )
      ),
      addEdges = Set(
        Edge.Child(
          ParentId(NodeId.fromBase58String("5RGHCHpdWsK4X6NpzbCVMK").right.get),
          EdgeData.Child(None, None),
          ChildId(NodeId.fromBase58String("5RGHCTvXSd1cKniWc3xc25").right.get),
        ),
      )
    )

    val filterCreateMessage = mapper.filterCreateMessageEvents(undeleteChannel)
    val filterCreateChannel = mapper.filterCreateChannelEvents(undeleteChannel)
    val filterDelete = mapper.filterDeleteEvents(undeleteChannel)
    val filterUpdate = mapper.filterUpdateEvents(undeleteChannel)
    val filterUnDelete = mapper.filterUndeleteEvents(undeleteChannel)

    filterCreateMessage.map(_.nonEmpty shouldBe false)
    filterCreateChannel.map(_.nonEmpty shouldBe false)
    filterDelete.nonEmpty shouldBe false
    filterUpdate.nonEmpty shouldBe false
    filterUnDelete.nonEmpty shouldBe true

  }

}
