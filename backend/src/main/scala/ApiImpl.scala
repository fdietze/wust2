package wust.backend

import wust.api._
import wust.backend.DbConversions._
import wust.db.Db
import wust.graph._
import wust.ids._
import wust.util.RandomUtil

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal

import cats.implicits._

class ApiImpl(dsl: GuardDsl, db: Db)(implicit ec: ExecutionContext) extends Api[ApiFunction] {
  import ApiEvent._
  import dsl._

  // TODO: Abstract over user id
  // private def enrichPostWithUser(posts: Set[Post]) = assureDbUser { (_, user, wasCreated) =>
  //     posts.map { post =>
  //       if(!wasCreated) assert(post.author == user.id, s"(Post author id) ${post.author} != ${user.id} (user id)")
  //       post.copy(author = user.id)
  //     }
  // }
  // TODO: createPost function for api

  //TODO assure timestamps of posts are correct
  override def changeGraph(changes: List[GraphChanges]): ApiFunction[Boolean] = Effect.assureDbUser { (_, user) =>
    //TODO permissions

    val result: Future[Boolean] = db.ctx.transaction { implicit ec =>
      changes.foldLeft(Future.successful(true)){ (previousSuccess, changes) =>
        import changes.consistent._

        // val postsWithUser: Set[Post] = enrichPostWithUser(addPosts);
        // TODO: error if author not user
        val postsWithUser: Set[Post] = addPosts.map(_.copy(author = user.id))

        previousSuccess.flatMap { success =>
          if (success) {
            for {
              true <- db.post.createPublic(postsWithUser)
              true <- db.connection(addConnections)
              true <- db.ownership(addOwnerships)
              true <- db.post.update(updatePosts)
              true <- db.post.delete(delPosts)
              true <- db.connection.delete(delConnections)
              true <- db.ownership.delete(delOwnerships)
            } yield true
          } else Future.successful(false)
        }
      }
    }

    result.map { success =>
      if (success) {
        val compactChanges = changes.foldLeft(GraphChanges.empty)(_ merge _)
        Returns(true, Seq(NewGraphChanges(compactChanges)))
      } else Returns(false)
    }
  }

  def getPost(id: PostId): ApiFunction[Option[Post]] = Action(db.post.get(id).map(_.map(forClient))) //TODO: check if public or user has access
  def getUser(id: UserId): ApiFunction[Option[User]] = Action(db.user.get(id).map(_.map(forClient)))

  //TODO: error handling
  def addGroup(): ApiFunction[GroupId] = Effect.assureDbUser { (_, user) =>
    for {
      //TODO: simplify db.createForUser return values
      Some((_, dbMembership, dbGroup)) <- db.group.createForUser(user.id)
    } yield {
      val group = forClient(dbGroup)
      Returns(group.id, Seq(NewMembership(dbMembership)))
    }
  }

  //TODO: error handling
  def addMember(groupId: GroupId, userId: UserId): ApiFunction[Boolean] = Effect.assureDbUser { (_, user) =>
    db.ctx.transaction { implicit ec =>
      isGroupMember(groupId, user.id) {
        for {
          Some(user) <- db.user.get(userId)
          Some((_, dbMembership, group)) <- db.group.addMember(groupId, userId)
        } yield Returns(true, Seq(NewMembership(dbMembership), NewUser(user)))
      }
    }
  }

  def addMemberByName(groupId: GroupId, userName: String): ApiFunction[Boolean] = Effect.assureDbUser { (_, user) =>
    db.ctx.transaction { implicit ec =>
      isGroupMember(groupId, user.id) {
        for {
          Some(user) <- db.user.byName(userName)
          Some((_, dbMembership, group)) <- db.group.addMember(groupId, user.id)
        } yield Returns(true, Seq(NewMembership(dbMembership), NewUser(user)))
      }
    }
  }

  def recreateGroupInviteToken(groupId: GroupId): ApiFunction[Option[String]] = Action.assureDbUser { (_, user) =>
    db.ctx.transaction { implicit ec =>
      isGroupMember(groupId, user.id) {
        setRandomGroupInviteToken(groupId).map(Returns(_))
      }
    }
  }

  def getGroupInviteToken(groupId: GroupId): ApiFunction[Option[String]] = Action.assureDbUser { (_, user) =>
    db.ctx.transaction { implicit ec =>
      isGroupMember(groupId, user.id) {
        db.group.getInviteToken(groupId).flatMap {
          case someToken @ Some(_) => Future.successful(someToken)
          case None                => setRandomGroupInviteToken(groupId)
        }
      }
    }
  }

  def acceptGroupInvite(token: String): ApiFunction[Option[GroupId]] = Effect.assureDbUser { (_, user) =>
    //TODO optimize into one request?
    db.ctx.transaction { implicit ec =>
      db.group.fromInvite(token).flatMap {
        case Some(group) =>
          db.group.addMember(group.id, user.id).map {
            case Some((_, dbMembership, dbGroup)) =>
              val group = forClient(dbGroup)
              Returns(Option(group.id), Seq(NewMembership(dbMembership)))
            case None => Returns(Option.empty[GroupId])
          }
        case None => Future.successful(Returns(Option.empty[GroupId]))
      }
    }
  }

  def getGraph(selection: Page): ApiFunction[Graph] = Action { state =>
    val userIdOpt = state.auth.dbUserOpt.map(_.id)
    val graph = selection match {
      case Page.Root =>
        db.graph.getAllVisiblePosts(userIdOpt).map(forClient(_).consistent) // TODO: consistent should not be necessary here
      case Page.Union(parentIds) =>
        getUnion(userIdOpt, parentIds).map(_.consistent) // TODO: consistent should not be necessary here
    }

    graph
  }

  // def getComponent(id: Id): Graph = {
  //   graph.inducedSubGraphData(graph.depthFirstSearch(id, graph.neighbours).toSet)
  // }

  private def setRandomGroupInviteToken(groupId: GroupId)(implicit ec: ExecutionContext): Future[Option[String]] = {
    val randomToken = RandomUtil.alphanumeric()
    db.group.setInviteToken(groupId, randomToken)
      .collect { case true => Option(randomToken) }
      .recover { case NonFatal(_) => None }
  }

  private def getUnion(userIdOpt: Option[UserId], rawParentIds: Set[PostId])(implicit ec: ExecutionContext): Future[Graph] = {
    //TODO: in stored procedure
    // we also include the direct parents of the parentIds to be able no navigate upwards
    db.graph.getAllVisiblePosts(userIdOpt).map { dbGraph =>
      val graph = forClient(dbGraph)
      val parentIds = rawParentIds filter graph.postsById.isDefinedAt
      val descendants = parentIds.flatMap(graph.descendants) ++ parentIds
      val descendantsWithDirectParents = descendants ++ parentIds.flatMap(graph.parents)
      graph removePosts graph.postIds.filterNot(descendantsWithDirectParents)
    }
  }

  //TODO: refactor import method to a proper service
  def importGithubUrl(url: String): ApiFunction[Boolean] = Action.assureDbUser { (_, user) =>

    // TODO: Reuse graph changes instead
    val (owner, repo, issueNumber) = GitHubImporter.urlExtractor(url)
    val postsOfUrl = GitHubImporter.getIssues(owner, repo, issueNumber, user)
    val result: Future[Boolean] = postsOfUrl.flatMap { case (posts, connections) =>
      db.ctx.transaction { implicit ec =>
        for {
          true <- db.post.createPublic(posts)
          true <- db.connection(connections)
        } yield true
      }
    }

    result.recover {
      case NonFatal(t) =>
        scribe.error(s"unexpected error in import")
        scribe.error(t)
        false
    }//.map(Returns(_, NewGraphChanges(GraphChanges(addPosts = postsOfUrl)))) //<-- not working for import
  }

  def importGitterUrl(url: String): ApiFunction[Boolean] = Action.assureDbUser { (_, user) =>

    // TODO: Reuse graph changes instead
    val postsOfUrl = Set(Post(PostId(scala.util.Random.nextInt.toString), url, user.id))
    val messages = GitterImporter.getMessages()
    val result: Future[Boolean] = db.ctx.transaction { implicit ec =>
      for {
        true <- db.post.createPublic(postsOfUrl)
      } yield true

    }
    result.recover {
      case NonFatal(t) =>
        scribe.error(s"unexpected error in import")
        scribe.error(t)
        false
    } //.map(Returns(_,  NewGraphChanges(GraphChanges(addPosts = postsOfUrl))))
  }
}
