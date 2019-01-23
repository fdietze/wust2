package wust.core

import wust.api.AuthUser
import wust.db.Db
import wust.graph.{Edge, GraphChanges, Node}
import wust.ids.NodeId
import wust.util.collection._

import scala.concurrent.{ExecutionContext, Future}
import scala.util.control.NonFatal
import scala.collection.breakOut

sealed trait ChangeGraphAuthorization
object ChangeGraphAuthorization {
  case object Allow extends ChangeGraphAuthorization
  case class Deny(reason: String) extends ChangeGraphAuthorization

  @inline def cond(allow: Boolean, denyReason: => String): ChangeGraphAuthorization = if (allow) Allow else Deny(denyReason)

  def combine(a: ChangeGraphAuthorization, b: ChangeGraphAuthorization): ChangeGraphAuthorization = a match {
    case ChangeGraphAuthorization.Allow => b
    case other => other
  }
}

trait ChangeGraphAuthorizer[F[_]] {
  def authorize(user: AuthUser, changes: GraphChanges): F[ChangeGraphAuthorization]
}

//TODO: additionally implement on the basis of graph instead of db for frontend.
//it does not need future but only id. then share some code for rules.
class DbChangeGraphAuthorizer(db: Db)(implicit ec: ExecutionContext) extends ChangeGraphAuthorizer[Future] {
  type Rule = (AuthUser, GraphChanges) => Future[ChangeGraphAuthorization]

  val canOnlyAddNodesWithAuthors: Rule = { (_, changes) =>
    val addNodeIds: collection.Set[NodeId] = changes.addNodes.map(_.id)
    val authoredNodeIds: collection.Set[NodeId] = changes.addEdges.collect { case e: Edge.Author => e.nodeId }

    val allNodesHaveAuthor = addNodeIds.forall(authoredNodeIds.contains)
    val allAuthorsHaveNode = authoredNodeIds.forall(addNodeIds)

    Future.successful(ChangeGraphAuthorization.cond(allNodesHaveAuthor && allAuthorsHaveNode, "There are invalid or missing author edges, each author edge needs a corresponding addNode and vice versa."))
  }

  val canOnlyAddContentOrOwnUser: Rule = { (user, changes) =>
    val onlyContentNodes = changes.addNodes.forall {
      case _: Node.Content => true
      case u: Node.User => u.id == user.id
      case _ => false
    }

    Future.successful(ChangeGraphAuthorization.cond(onlyContentNodes, "There are non-content nodes added, you are only allowed to add/edit Node.Content or your own user"))
  }

  val canAccessNodesAndEdges: Rule = { (user, changes) =>

    // Only allow adding edges that you have access to.
    //TODO: You can add nealy every edge to a user (like pinned, expanded, ...) if you have access to the content node.
    //We allow this because it is convenient for automation. We should have a more dedicated check?
    //Allow for node where the template applies and the user did this explicitly to the template.
    //Do not allow to do this for every possible node. attackers can spam an account.
    //For each edge, we decide which nodeids need to be checked for access
    //permission in order to add this edge:
    //  Left(reason: String) => Not allowed to add this edge, because $reason
    //  Right(nodeIds: Seq[NodeId]) => Allowed to add this edge, if you have acces to all $nodeIds
    val addEdgesCheck: Either[List[String], List[Seq[NodeId]]] = eitherSeq(changes.addEdges.map {
      case e: Edge.Author              => Either.cond(user.id == e.userId, Seq(e.nodeId), "Can only add author edge for own user and an added node")
      case e: Edge.Member              => Right(Seq(e.nodeId))
      case e: Edge.Parent              => Right(Seq(e.childId, e.parentId))
      case e: Edge.LabeledProperty     => Right(Seq(e.propertyId, e.nodeId))
      case e: Edge.Notify              => Right(Seq(e.nodeId))
      case e: Edge.Expanded            => Right(Seq(e.nodeId))
      case e: Edge.Assigned            => Right(Seq(e.nodeId))
      case e: Edge.Pinned              => Right(Seq(e.nodeId))
      case e: Edge.Invite              => Right(Seq(e.nodeId))
      case e: Edge.DerivedFromTemplate => Right(Seq(e.nodeId, e.referenceNodeId))
      case e: Edge.Automated           => Right(Seq(e.nodeId, e.templateNodeId))
    }(breakOut))

    // Only allow deleting edges that you have access to.
    //For each edge, we decide which nodeids need to be checked for access
    //permission in order to delete this edge:
    //  Left(reason: String) => Not allowed to delete this edge, because $reason
    //  Right(nodeIds: Seq[NodeId]) => Allowed to delete this edge, if you have acces to all $nodeIds
    val delEdgesCheck: Either[List[String], List[Seq[NodeId]]] = eitherSeq(changes.delEdges.map {
      case _: Edge.Author              => Left("Cannot delete author edges")
      case e: Edge.Member              => Right(Seq(e.nodeId))
      case e: Edge.Parent              => Right(Seq(e.childId, e.parentId))
      case e: Edge.LabeledProperty     => Right(Seq(e.propertyId, e.nodeId))
      case e: Edge.Notify              => Either.cond(user.id == e.userId, Seq(e.nodeId), "Can only delete notify edge of own user")
      case e: Edge.Expanded            => Either.cond(user.id == e.userId, Seq(e.nodeId), "Can only delete expanded edge of own user")
      case e: Edge.Assigned            => Right(Seq(e.nodeId))
      case e: Edge.Pinned              => Either.cond(user.id == e.userId, Seq(e.nodeId), "Can only delete notify edge of own user")
      case e: Edge.Invite              => Either.cond(user.id == e.userId, Seq(e.nodeId), "Can only delete invite edge of own user")
      case e: Edge.DerivedFromTemplate => Right(Seq(e.nodeId, e.referenceNodeId))
      case e: Edge.Automated           => Right(Seq(e.nodeId, e.templateNodeId))
    }(breakOut))

    val checkNodeIds: Either[List[String], List[NodeId]] = for {
      checkNodeIdsAddEdges <- addEdgesCheck
      checkNodeIdsDelEdges <- delEdgesCheck
      checkNodeIdsAddNodes = changes.addNodes.map(_.id)
    } yield (checkNodeIdsAddEdges.flatten ++ checkNodeIdsDelEdges.flatten ++ checkNodeIdsAddNodes).distinct

    checkNodeIds match {
      case Right(checkNodeIds) => db.user.inaccessibleNodes(user.id, checkNodeIds).map { inaccessibleNodes =>
        ChangeGraphAuthorization.cond(inaccessibleNodes.isEmpty, s"There are inaccessible node ids in the changes: ${inaccessibleNodes.map(_.toBase58).mkString(",")}")
      }
      case Left(reasons) => Future.successful(ChangeGraphAuthorization.Deny(s"There are invalid edges in the changes: ${reasons.mkString(",")}"))
    }
  }

  val rules: List[Rule] =
    canOnlyAddNodesWithAuthors ::
    canOnlyAddContentOrOwnUser ::
    canAccessNodesAndEdges ::
    Nil

  def authorize(user: AuthUser, changes: GraphChanges): Future[ChangeGraphAuthorization] =
    Future.sequence(rules.map(_(user, changes)))
      .map { ruleResults =>
        ruleResults.foldLeft[ChangeGraphAuthorization](ChangeGraphAuthorization.Allow)(ChangeGraphAuthorization.combine)
      }
      .recover { case NonFatal(t) =>
        scribe.warn("An error occurred while checking rules on GraphChanges", t)
        ChangeGraphAuthorization.Deny(s"Unexpected exception in rule: ${t.getMessage}")
      }
}