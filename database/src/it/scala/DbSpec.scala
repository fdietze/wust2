package wust.db

import org.scalatest._

import scala.concurrent.duration._
import scala.concurrent.{ Await, Future, ExecutionContext }

import wust.db.Data._
import wust.ids._

import io.getquill._

// TODO: Query-Probing: https://github.com/getquill/quill#query-probing
// "Query probing validates queries against the database at compile time, failing the compilation if it is not valid. The query validation does not alter the database state."
object DbSpec {
  def createOwned(db: Db, post: Post, groupId: GroupId)(implicit ec: ExecutionContext): Future[Boolean] = {
    import db.ctx, ctx._
    ctx.transaction { implicit ec =>
      for {
        true <- db.post.createPublic(post)
        true <- db.ownership(Ownership(post.id, groupId))
      } yield true
    }
  }
}

class DbSpec extends DbIntegrationTestSpec with MustMatchers {
  import DbSpec._
  implicit def passwordToDigest(pw: String): Array[Byte] = pw.map(_.toByte).toArray
  implicit class EqualityByteArray(val arr: Array[Byte]) {
    def mustEqualDigest(pw: String) = arr mustEqual passwordToDigest(pw)
  }

  def Connection(sourceId: PostId, targetId: PostId) = new Connection(sourceId, "my-fucking-test-label", targetId)
  def Containment(sourceId: PostId, targetId: PostId) = new Connection(sourceId, Label.parent, targetId)

  //TODO: still throw exceptions on for example database connection errors

  "post" - {
    "create public post" in { db =>
      import db._, db.ctx, ctx._
      val post = Post("ei-D", "dono")
      for {
        success <- db.post.createPublic(Set(post))

        queriedPosts <- ctx.run(query[Post])
        queriedOwnerships <- ctx.run(query[Ownership])
      } yield {
        success mustBe true
        queriedPosts must contain theSameElementsAs List(post)
        queriedOwnerships mustBe empty
      }
    }

    "create two public posts" in { db =>
      import db._, db.ctx, ctx._
      val post = Post("ei-D", "dono")
      val post2 = Post("yogo", "girko")
      for {
        success <- db.post.createPublic(Set(post, post2))

        queriedPosts <- ctx.run(query[Post])
        queriedOwnerships <- ctx.run(query[Ownership])
      } yield {
        success mustBe true
        queriedPosts must contain theSameElementsAs List(post, post2)
        queriedOwnerships mustBe empty
      }
    }

    "create public post (existing, same title)" in { db =>
      import db._, db.ctx, ctx._
      val post = Post("ei-D", "dino")
      for {
        _ <- db.post.createPublic(Set(post))
        success <- db.post.createPublic(Set(Post("ei-D", "dino")))

        queriedPosts <- ctx.run(query[Post])
        queriedOwnerships <- ctx.run(query[Ownership])
      } yield {
        success mustBe true
        queriedPosts must contain theSameElementsAs List(post)
        queriedOwnerships mustBe empty
      }
    }

    "create two public posts (one existing, same title)" in { db =>
      import db._, db.ctx, ctx._
      val post = Post("ei-D", "dino")
      val post2 = Post("dero", "funa")
      for {
        _ <- db.post.createPublic(Set(post))
        success <- db.post.createPublic(Set(Post("ei-D", "dino"), post2))

        queriedPosts <- ctx.run(query[Post])
        queriedOwnerships <- ctx.run(query[Ownership])
      } yield {
        success mustBe true
        queriedPosts must contain theSameElementsAs List(post, post2)
        queriedOwnerships mustBe empty
      }
    }

    //TODO
    // "create public post (existing but different title)" in { db =>
    //   import db._, db.ctx, ctx._
    //   val post = Post("ei-D", "dono")
    //   for {
    //     _ <- db.post.createPublic(Set(post))
    //     success <- db.post.createPublic(Set(Post("ei-D", "dino")))

    //     queriedPosts <- ctx.run(query[Post])
    //     queriedOwnerships <- ctx.run(query[Ownership])
    //   } yield {
    //     success mustBe false
    //     queriedPosts must contain theSameElementsAs List(post)
    //     queriedOwnerships mustBe empty
    //   }
    // }

    // "create two public posts (one existing but different title)" in { db =>
    //   import db._, db.ctx, ctx._
    //   val post = Post("ei-D", "dono")
    //   val post2 = Post("heide", "haha")
    //   for {
    //     _ <- db.post.createPublic(Set(post))
    //     success <- db.post.createPublic(Set(Post("ei-D", "dino"), post2))

    //     queriedPosts <- ctx.run(query[Post])
    //     queriedOwnerships <- ctx.run(query[Ownership])
    //   } yield {
    //     success mustBe false
    //     queriedPosts must contain theSameElementsAs List(post)
    //     queriedOwnerships mustBe empty
    //   }
    // }

    "get existing post" in { db =>
      import db._, db.ctx, ctx._
      val post = Post("hege", "walt")
      for {
        true <- db.post.createPublic(post)
        getPost <- db.post.get(post.id)
      } yield {
        getPost mustEqual Option(post)
      }
    }

    "get non-existing post" in { db =>
      import db._, db.ctx, ctx._
      for {
        getPost <- db.post.get("17134")
      } yield {
        getPost mustEqual None
      }
    }

    "update existing post" in { db =>
      import db._, db.ctx, ctx._
      val post = Post("harnig", "delauf")
      for {
        true <- db.post.createPublic(post)
        success <- db.post.update(post.copy(title = "harals"))
        queriedPosts <- ctx.run(query[Post])
      } yield {
        success mustBe true
        queriedPosts must contain theSameElementsAs List(post.copy(title = "harals"))
      }
    }

    "update non-existing post" in { db =>
      import db._, db.ctx, ctx._
      for {
        success <- db.post.update(Post("1135", "harals"))
        queriedPosts <- ctx.run(query[Post])
      } yield {
        success mustBe false
        queriedPosts mustBe empty
      }
    }

    "delete existing post" in { db =>
      import db._, db.ctx, ctx._
      val post = Post("harnig", "delauf")
      for {
        true <- db.post.createPublic(post)
        success <- db.post.delete(post.id)
        queriedPosts <- ctx.run(query[Post])
      } yield {
        success mustBe true
        queriedPosts mustBe empty
      }
    }

    "delete non-existing post" in { db =>
      import db._, db.ctx, ctx._
      for {
        success <- db.post.delete("135481")
        queriedPosts <- ctx.run(query[Post])
      } yield {
        success mustBe false
        queriedPosts mustBe empty
      }
    }
  }

  "connection" - {
    "create between two existing posts" in { db =>
      import db._, db.ctx, ctx._
      val sourcePost = Post("t", "yo")
      val targetPost = Post("r", "yo")
      val connection = Connection("t", "r")
      for {
        true <- db.post.createPublic(sourcePost)
        true <- db.post.createPublic(targetPost)
        success <- db.connection(connection)
        connections <- ctx.run(query[Connection])
      } yield {
        success mustBe true
        connections must contain theSameElementsAs List(connection)
      }
    }

    "create between two existing posts with already existing connection" in { db =>
      import db._, db.ctx, ctx._
      val sourcePost = Post("t", "yo")
      val targetPost = Post("r", "yo")
      val connection = Connection("t", "r")
      for {
        true <- db.post.createPublic(sourcePost)
        true <- db.post.createPublic(targetPost)
        true <- db.connection(connection)
        success <- db.connection(connection)
        connections <- ctx.run(query[Connection])
      } yield {
        success mustBe true
        connections must contain theSameElementsAs List(connection)
      }
    }

    "create between two existing posts with already existing containments" in { db =>
      import db._, db.ctx, ctx._
      val sourcePost = Post("t", "yo")
      val targetPost = Post("r", "yo")
      val connection = Connection("t", "r")
      for {
        true <- db.post.createPublic(sourcePost)
        true <- db.post.createPublic(targetPost)
        true <- db.connection(Containment(sourcePost.id, targetPost.id))
        true <- db.connection(Containment(targetPost.id, sourcePost.id))
        success <- db.connection(connection)
        connections <- ctx.run(query[Connection])
      } yield {
        success mustBe true
        connections must contain theSameElementsAs List(connection)
      }
    }

    "create between two posts, source not existing" in { db =>
      import db._, db.ctx, ctx._
      val targetPost = Post("r", "yo")
      val connection = Connection("t", "r")
      for {
        true <- db.post.createPublic(targetPost)
        success <- db.connection(connection)
        connections <- ctx.run(query[Connection])
      } yield {
        success mustBe false
        connections mustBe empty
      }
    }

    "create between two posts, target not existing" in { db =>
      import db._, db.ctx, ctx._
      val sourcePost = Post("r", "yo")
      val connection = Connection("r", "t")
      for {
        true <- db.post.createPublic(sourcePost)
        success <- db.connection(connection)
        connections <- ctx.run(query[Connection])
      } yield {
        success mustBe false
        connections mustBe empty
      }
    }

    "create between two posts, both not existing" in { db =>
      import db._, db.ctx, ctx._
      val connection = Connection("r", "t")
      for {
        success <- db.connection(connection)
        connections <- ctx.run(query[Connection])
      } yield {
        success mustBe false
        connections mustBe empty
      }
    }

    "delete existing connection" in { db =>
      import db._, db.ctx, ctx._
      val sourcePost = Post("t", "yo")
      val targetPost = Post("r", "yo")
      val connection = Connection("t", "r")
      for {
        true <- db.post.createPublic(sourcePost)
        true <- db.post.createPublic(targetPost)
        true <- db.connection(connection)

        deleted <- db.connection.delete(connection)
        queriedConnections <- ctx.run(query[Connection])
      } yield {
        deleted mustEqual true
        queriedConnections mustBe empty
      }
    }

    "delete non-existing connection" in { db =>
      import db._, db.ctx, ctx._
      val connection = Connection("t", "r")
      for {
        deleted <- db.connection.delete(connection)
        queriedConnections <- ctx.run(query[Connection])
      } yield {
        deleted mustEqual true
        queriedConnections mustBe empty
      }
    }
  }

  "containment" - {
    "create between two existing posts" in { db =>
      import db._, db.ctx, ctx._
      val parent = Post("t", "yo")
      val child = Post("r", "yo")
      val containment = Containment("t", "r")
      for {
        true <- db.post.createPublic(parent)
        true <- db.post.createPublic(child)
        success <- db.connection(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustBe true
        containments must contain theSameElementsAs List(containment)
      }
    }

    "create between two existing posts with already existing containment" in { db =>
      import db._, db.ctx, ctx._
      val parent = Post("t", "yo")
      val child = Post("r", "yo")
      val containment = Containment("t", "r")
      for {
        true <- db.post.createPublic(parent)
        true <- db.post.createPublic(child)
        true <- db.connection(containment)
        success <- db.connection(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustBe true
        containments must contain theSameElementsAs List(containment)
      }
    }

    "create between two existing posts with already existing connection" in { db =>
      import db._, db.ctx, ctx._
      val parent = Post("t", "yo")
      val child = Post("r", "yo")
      val containment = Containment("t", "r")
      for {
        true <- db.post.createPublic(parent)
        true <- db.post.createPublic(child)
        true <- db.connection(Connection(parent.id, child.id))
        true <- db.connection(Connection(child.id, parent.id))
        success <- db.connection(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustBe true
        containments must contain theSameElementsAs List(containment)
      }
    }

    "create between two posts, parent not existing" in { db =>
      import db._, db.ctx, ctx._
      val child = Post("r", "yo")
      val containment = Containment("t", "r")
      for {
        true <- db.post.createPublic(child)
        success <- db.connection(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustBe false
        containments mustBe empty
      }
    }

    "create between two posts, child not existing" in { db =>
      import db._, db.ctx, ctx._
      val parent = Post("t", "yo")
      val containment = Containment("t", "r")
      for {
        true <- db.post.createPublic(parent)
        success <- db.connection(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustBe false
        containments mustBe empty
      }
    }

    "create between two posts, both not existing" in { db =>
      import db._, db.ctx, ctx._
      val containment = Containment("t", "r")
      for {
        success <- db.connection(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustBe false
        containments mustBe empty
      }
    }

    "delete existing containment" in { db =>
      import db._, db.ctx, ctx._
      val parent = Post("t", "yo")
      val child = Post("r", "yo")
      val containment = Containment("t", "r")
      for {
        true <- db.post.createPublic(parent)
        true <- db.post.createPublic(child)
        true <- db.connection(containment)

        success <- db.connection.delete(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustEqual true
        containments mustBe empty
      }
    }

    "delete non-existing containment" in { db =>
      import db._, db.ctx, ctx._
      val containment = Containment("t", "r")
      for {
        success <- db.connection.delete(containment)
        containments <- ctx.run(query[Connection])
      } yield {
        success mustEqual true
        containments mustBe empty
      }
    }
  }

  "user" - {
    "create non-existing" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(user) <- db.user("heigo", "parwin")
        Some((queriedUser, queriedDigest)) <- db.user.getUserAndDigest("heigo")
        queriedGroups <- ctx.run(query[UserGroup])
      } yield {
        user.name mustEqual "heigo"
        user.isImplicit mustEqual false
        user.revision mustEqual 0
        queriedUser mustEqual user
        queriedDigest mustEqualDigest "parwin"
        queriedGroups mustBe empty
      }
    }

    "try to create existing with same password" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        None <- db.user("heigo", "parwin")
        Some((queriedUser, queriedDigest)) <- db.user.getUserAndDigest("heigo")
      } yield {
        queriedUser mustEqual existingUser
        queriedDigest mustEqualDigest "parwin"
      }
    }

    "try to create existing with different password" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        None <- db.user("heigo", "reidon")
        Some((queriedUser, queriedDigest)) <- db.user.getUserAndDigest("heigo")
      } yield {
        queriedUser mustEqual existingUser
        queriedDigest mustEqualDigest "parwin"
      }
    }

    "create implicit user" in { db =>
      import db._, db.ctx, ctx._
      for {
        user <- db.user.createImplicitUser()
        queriedUsers <- ctx.run(query[User])
        queriedPasswords <- ctx.run(query[Password])
      } yield {
        user.name must startWith("anon-")
        user.isImplicit mustEqual true
        user.revision mustEqual 0
        queriedUsers must contain theSameElementsAs List(user)
        queriedPasswords mustBe empty
      }
    }

    "create two implicit users" in { db =>
      import db._, db.ctx, ctx._
      for {
        user1 <- db.user.createImplicitUser()
        user2 <- db.user.createImplicitUser()
        queriedUsers <- ctx.run(query[User])
        queriedPasswords <- ctx.run(query[Password])
      } yield {
        user1.name must not equal (user2.name)
        queriedUsers.toSet mustEqual Set(user1, user2)
        queriedPasswords mustBe empty
      }
    }

    "activate implicit user to non-existing" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        Some(user) <- db.user.activateImplicitUser(implUser.id, "ganiz", "faura")
        Some((queriedUser, queriedDigest)) <- db.user.getUserAndDigest("ganiz")
      } yield {
        user.name mustEqual "ganiz"
        user.isImplicit mustEqual false
        user.revision mustEqual 1
        queriedUser mustEqual user
        queriedDigest mustEqualDigest "faura"
      }
    }

    "activate non-implicit user to non-existing" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(normalUser) <- db.user("perisol", "heulum")
        None <- db.user.activateImplicitUser(normalUser.id, "ganiz", "faura")
        queriedUserDigest <- db.user.getUserAndDigest("ganiz")
        Some(queriedNormalUser) <- db.user.get(normalUser.id)
      } yield {
        queriedNormalUser mustEqual normalUser
        queriedUserDigest mustEqual None
      }
    }

    "try to activate implicit user to existing with same password" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        Some(existingUser) <- db.user("ganiz", "heuriso")
        None <- db.user.activateImplicitUser(implUser.id, "ganiz", "heuriso")
        Some((queriedUser, queriedDigest)) <- db.user.getUserAndDigest("ganiz")
        queriedUsers <- ctx.run(query[User])
        queriedPasswords <- ctx.run(query[Password])
      } yield {
        queriedUsers must contain theSameElementsAs List(existingUser, implUser)
        queriedPasswords.size mustEqual 1
        queriedUser mustEqual existingUser
        queriedDigest mustEqualDigest "heuriso"
      }
    }

    "try to activate implicit user to existing with different password" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        Some(existingUser) <- db.user("ganiz", "heuriso")
        None <- db.user.activateImplicitUser(implUser.id, "ganiz", "faura")
        Some((queriedUser, queriedDigest)) <- db.user.getUserAndDigest("ganiz")
        queriedUsers <- ctx.run(query[User])
        queriedPasswords <- ctx.run(query[Password])
      } yield {
        queriedUsers must contain theSameElementsAs List(existingUser, implUser)
        queriedPasswords.size mustEqual 1
        queriedUser mustEqual existingUser
        queriedDigest mustEqualDigest "heuriso"
      }
    }

    "merge implicit user" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        Some((`implUser`, membership, group)) <- db.group.createForUser(implUser.id)
        Some(normalUser) <- db.user("harals", Array.empty[Byte])
        success <- db.user.mergeImplicitUser(implUser.id, normalUser.id)
        queriedImplUser <- db.user.get(implUser.id)
        queriedNormalUser <- db.user.get(normalUser.id)
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        success mustEqual true
        queriedImplUser mustEqual None
        queriedNormalUser mustEqual Some(normalUser)
        queryMemberships must contain theSameElementsAs List(Membership(normalUser.id, group.id))
      }
    }

    "merge two implicit users" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        implUser2 <- db.user.createImplicitUser()
        success <- db.user.mergeImplicitUser(implUser.id, implUser2.id)
        queriedImplUser <- db.user.get(implUser.id)
        queriedImplUser2 <- db.user.get(implUser2.id)
      } yield {
        success mustEqual true
        queriedImplUser mustEqual Some(implUser)
        queriedImplUser2 mustEqual Some(implUser2)
      }
    }

    "merge real user with real user" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        Some(normalUser) <- db.user("harals", Array.empty[Byte])
        Some(normalUser2) <- db.user("rodora", Array.empty[Byte])
        success <- db.user.mergeImplicitUser(normalUser.id, normalUser2.id)
        queriedNormalUser <- db.user.get(normalUser.id)
        queriedNormalUser2 <- db.user.get(normalUser2.id)
      } yield {
        success mustEqual true //TODO
        queriedNormalUser mustEqual Some(normalUser)
        queriedNormalUser2 mustEqual Some(normalUser2)
      }
    }

    "merge implicit user with non-existing user" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        success <- db.user.mergeImplicitUser(implUser.id, UserId(-1))
        queriedImplUser <- db.user.get(implUser.id)
      } yield {
        success mustEqual true //TODO
        queriedImplUser mustEqual Some(implUser)
      }
    }

    "merge non-existing implicit user" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(normalUser) <- db.user("harals", Array.empty[Byte])
        success <- db.user.mergeImplicitUser(UserId(-1), normalUser.id)
        queriedNormalUser <- db.user.get(normalUser.id)
      } yield {
        success mustEqual true //TODO
        queriedNormalUser mustEqual Some(normalUser)
      }
    }

    "merge implicit user with both non-existing" in { db =>
      import db._, db.ctx, ctx._
      for {
        success <- db.user.mergeImplicitUser(UserId(-1), UserId(-2))
      } yield {
        success mustEqual true //TODO
      }
    }

    "merge same implicit user" in { db =>
      import db._, db.ctx, ctx._
      for {
        implUser <- db.user.createImplicitUser()
        success <- db.user.mergeImplicitUser(implUser.id, implUser.id)
        queriedImplUser <- db.user.get(implUser.id)
      } yield {
        success mustEqual false
        queriedImplUser mustEqual Some(implUser)
      }
    }

    "get existing by id" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        Some(user) <- db.user.get(existingUser.id)
      } yield {
        user mustEqual existingUser
      }
    }

    "get non-existing by id" in { db =>
      import db._, db.ctx, ctx._
      for {
        userOpt <- db.user.get(11351)
      } yield {
        userOpt mustEqual None
      }
    }

    "get existing by name,password" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        Some((user, digest)) <- db.user.getUserAndDigest("heigo")
      } yield {
        digest mustEqualDigest "parwin"
        user mustEqual existingUser
      }
    }

    "get non-existing by name,password" in { db =>
      import db._, db.ctx, ctx._
      for {
        userOpt <- db.user.getUserAndDigest("a")
      } yield {
        userOpt mustEqual None
      }
    }

    "get existing with wrong username" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        userOpt <- db.user.getUserAndDigest("ürgens")
      } yield {
        userOpt mustEqual None
      }
    }

    "check if existing user exists" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        exists <- db.user.checkIfEqualUserExists(existingUser)
      } yield {
        exists mustBe true
      }
    }

    "check if existing user exists (wrong id)" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        exists <- db.user.checkIfEqualUserExists(existingUser.copy(id = 187))
      } yield {
        exists mustBe false
      }
    }

    "check if existing user exists (wrong name)" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        exists <- db.user.checkIfEqualUserExists(existingUser.copy(name = "heikola"))
      } yield {
        exists mustBe false
      }
    }

    "check if existing user exists (wrong isImplicit)" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        exists <- db.user.checkIfEqualUserExists(existingUser.copy(isImplicit = true))
      } yield {
        exists mustBe false
      }
    }

    "check if existing user exists (wrong revision)" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(existingUser) <- db.user("heigo", "parwin")
        exists <- db.user.checkIfEqualUserExists(existingUser.copy(revision = 3))
      } yield {
        exists mustBe false
      }
    }
  }

  "group" - {
    "create group for existing user" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(user) <- db.user("garna", "utria")
        Some((`user`, membership, group)) <- db.group.createForUser(user.id)
        queryGroups <- ctx.run(query[UserGroup])
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        queryGroups must contain theSameElementsAs List(group)
        queryMemberships must contain theSameElementsAs List(membership)

        membership mustEqual Membership(user.id, group.id)
      }
    }

    "create group for non-existing user" in { db =>
      import db._, db.ctx, ctx._
      for {
        resultOpt <- db.group.createForUser(13153)
        queryGroups <- ctx.run(query[UserGroup])
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        resultOpt mustEqual None
        queryGroups mustBe empty
        queryMemberships mustBe empty
      }
    }

    "add existing user to existing group" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(initialUser) <- db.user("garna", "utria")
        Some((_, _, group)) <- db.group.createForUser(initialUser.id)
        Some(user) <- db.user("furo", "garnaki")

        Some((_, membership, _)) <- db.group.addMember(group.id, user.id)
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        membership mustEqual Membership(user.id, group.id)
        queryMemberships must contain theSameElementsAs List(Membership(initialUser.id, group.id), membership)
      }
    }

    "add existing user to existing group (is already member)" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(initialUser) <- db.user("garna", "utria")
        Some((_, _, group)) <- db.group.createForUser(initialUser.id)
        Some(user) <- db.user("furo", "garnaki")

        Some((_, _, _)) <- db.group.addMember(group.id, user.id)
        Some((_, membership, _)) <- db.group.addMember(group.id, user.id)
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        membership mustEqual Membership(user.id, group.id)
        queryMemberships must contain theSameElementsAs List(Membership(initialUser.id, group.id), membership)
      }
    }

    "add non-existing user to existing group" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(initialUser) <- db.user("garna", "utria")
        Some((_, _, group)) <- db.group.createForUser(initialUser.id)
        Some(user) <- db.user("furo", "garnaki")

        membershipOpt <- db.group.addMember(group.id, 131551)
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        membershipOpt mustEqual None
        queryMemberships must contain theSameElementsAs List(Membership(initialUser.id, group.id))
      }
    }

    "add existing user to non-existing group" in { db =>
      import db._, db.ctx, ctx._
      for {
        Some(user) <- db.user("garna", "utria")

        membershipOpt <- db.group.addMember(13515, user.id)
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        membershipOpt mustEqual None
        queryMemberships mustBe empty
      }
    }

    "add non-existing user to non-existing group" in { db =>
      import db._, db.ctx, ctx._
      for {
        membershipOpt <- db.group.addMember(13515, 68415)
        queryMemberships <- ctx.run(query[Membership])
      } yield {
        membershipOpt mustEqual None
        queryMemberships mustBe empty
      }
    }

    "hasAccessToPost" - {
      "post in pubic group" in { db =>
        val post = Post("id", "p")
        for {
          Some(user) <- db.user("u", "123456")
          true <- db.post.createPublic(post)
          hasAccess <- db.group.hasAccessToPost(user.id, post.id)
        } yield hasAccess mustBe true
      }

      "post in private group (user not member)" in { db =>
        val post = Post("id", "p")
        for {
          Some(user) <- db.user("u2", "123456")
          Some(user2) <- db.user("other", "123456")
          Some((_, _, group)) <- db.group.createForUser(user2.id)
          true <- createOwned(db, post, group.id)
          hasAccess <- db.group.hasAccessToPost(user.id, post.id)
        } yield hasAccess mustBe false
      }

      "post in private group (user is member)" in { db =>
        val post = Post("id", "p")
        for {
          Some(user) <- db.user("u3", "123456")
          Some((_, _, group)) <- db.group.createForUser(user.id)
          true <- createOwned(db, post, group.id)
          hasAccess <- db.group.hasAccessToPost(user.id, post.id)
        } yield hasAccess mustBe true
      }
    }
  }

  "graph" - {
    "without user" - {
      "public posts, connections and containments" in { db =>
        import db._, db.ctx, ctx._
        val postA = Post("a", "A")
        val postB = Post("b", "B")
        val postC = Post("c", "C")
        for {
          true <- db.post.createPublic(postA)
          true <- db.post.createPublic(postB)
          true <- db.post.createPublic(postC)
          conn = Connection(postA.id, postB.id)
          cont = Containment(postB.id, postC.id)
          true <- db.connection(conn)
          true <- db.connection(cont)

          (posts, connections, userGroups, ownerships, users, memberships) <- db.graph.getAllVisiblePosts(None)
        } yield {
          posts must contain theSameElementsAs List(postA, postB, postC)
          connections must contain theSameElementsAs List(conn, cont)
          userGroups mustBe empty
          ownerships mustBe empty
          users mustBe empty
          memberships mustBe empty
        }
      }

      "public posts, connections and containments, private posts" in { db =>
        import db._, db.ctx, ctx._
        for {
          Some(user) <- db.user("heigo", "parwin")
          Some((_, membership, group)) <- db.group.createForUser(user.id)

          postA = Post("A", "sehe")
          true <- db.post.createPublic(postA)
          postB = Post("B", "cehen")
          ownershipB = Ownership(postB.id, group.id)
          true <- createOwned(db, postB, group.id)
          postC = Post("C", "geh")
          true <- db.post.createPublic(postC)
          conn = Connection(postA.id, postB.id)
          cont = Containment(postB.id, postC.id)
          true <- db.connection(conn)
          true <- db.connection(cont)

          (posts, connections, userGroups, ownerships, users, memberships) <- db.graph.getAllVisiblePosts(None)
        } yield {
          posts must contain theSameElementsAs List(postA, postC)
          connections must contain theSameElementsAs List()
          userGroups mustBe empty
          ownerships mustBe empty
          users mustBe empty
          memberships mustBe empty
        }
      }
    }

    "with user" - {
      "public posts, connections and containments" in { db =>
        import db._, db.ctx, ctx._
        for {
          Some(user) <- db.user("heigo", "parwin")
          postA = Post("A", "sei")
          true <- db.post.createPublic(postA)
          postB = Post("B", "rete")
          true <- db.post.createPublic(postB)
          postC = Post("C", "dete")
          true <- db.post.createPublic(postC)
          conn = Connection(postA.id, postB.id)
          cont = Containment(postB.id, postC.id)
          true <- db.connection(conn)
          true <- db.connection(cont)

          (posts, connections, userGroups, ownerships, users, memberships) <- db.graph.getAllVisiblePosts(Option(user.id))
        } yield {
          posts must contain theSameElementsAs List(postA, postB, postC)
          connections must contain theSameElementsAs List(conn, cont)
          userGroups mustBe empty
          ownerships mustBe empty
          users must contain theSameElementsAs List(user)
          memberships mustBe empty
        }
      }

      "group without posts" in { db =>
        import db._, db.ctx, ctx._
        for {
          Some(user) <- db.user("heigo", "parwin")
          Some((_, membership, group)) <- db.group.createForUser(user.id)

          (posts, connections, userGroups, ownerships, users, memberships) <- db.graph.getAllVisiblePosts(Option(user.id))
        } yield {
          posts must contain theSameElementsAs List()
          connections must contain theSameElementsAs List()
          userGroups mustEqual List(group)
          ownerships mustBe empty
          users must contain theSameElementsAs List(user)
          memberships must contain theSameElementsAs memberships
        }
      }

      "public posts, own private posts" in { db =>
        import db._, db.ctx, ctx._
        for {
          Some(user) <- db.user("heigo", "parwin")
          Some((_, membership, group)) <- db.group.createForUser(user.id)

          postA = Post("A", "heit")
          true <- db.post.createPublic(postA)

          postB = Post("b", "asd")
          ownershipB = Ownership(postB.id, group.id)

          true <- createOwned(db, postB, group.id)
          postC = Post("C", "derlei")
          true <- db.post.createPublic(postC)

          conn = Connection(postA.id, postB.id)
          true <- db.connection(conn)
          cont = Containment(postB.id, postC.id)
          true <- db.connection(cont)

          (posts, connections, userGroups, ownerships, users, memberships) <- db.graph.getAllVisiblePosts(Option(user.id))
        } yield {
          posts must contain theSameElementsAs List(postA, postB, postC)
          connections must contain theSameElementsAs List(conn, cont)
          userGroups must contain theSameElementsAs List(group)
          ownerships must contain theSameElementsAs List(ownershipB)
          users must contain theSameElementsAs List(user)
          memberships must contain theSameElementsAs List(membership)
        }
      }

      "public posts, own private posts, invisible posts" in { db =>
        import db._, db.ctx, ctx._
        for {
          Some(user) <- db.user("heigo", "parwin")
          Some((_, membership, group)) <- db.group.createForUser(user.id)

          Some(otherUser) <- db.user("gurkulo", "meisin")
          Some((_, otherMembership, otherGroup)) <- db.group.createForUser(otherUser.id)

          postA = Post("hei", "selor")
          true <- db.post.createPublic(postA)

          postB = Post("id", "hasnar")
          ownershipB = Ownership(postB.id, group.id)
          postC = Post("2d", "shon")
          ownershipC = Ownership(postC.id, otherGroup.id)
          true <- createOwned(db, postB, group.id)
          true <- createOwned(db, postC, otherGroup.id)

          conn = Connection(postA.id, postB.id)
          true <- db.connection(conn)
          cont = Containment(postB.id, postC.id)
          true <- db.connection(cont)

          (posts, connections, userGroups, ownerships, users, memberships) <- db.graph.getAllVisiblePosts(Option(user.id))
        } yield {
          posts must contain theSameElementsAs List(postA, postB)
          connections must contain theSameElementsAs List(conn)
          userGroups must contain theSameElementsAs List(group)
          ownerships must contain theSameElementsAs List(ownershipB)
          users must contain theSameElementsAs List(user)
          memberships must contain theSameElementsAs List(membership)
        }
      }
    }
  }
}
