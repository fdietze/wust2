package wust.frontend

import io.circe.Decoder.state
import vectory._
import wust.api._
import wust.frontend.views.{PageStyle, View, ViewConfig}
import wust.graph._
import wust.ids._
import org.scalajs.dom.{Event, console, window}
import org.scalajs.dom.experimental.Notification
import outwatch.dom._
import rxscalajs.subjects._
import rxscalajs.facade._
import wust.util.Analytics
import vectory._
import wust.util.outwatchHelpers._
import rxscalajs.Observable
import rx._

import scalaz.Tag

//outwatch beispiel:
  // def component(handler:Handler[ViewPage]) = {
  //   div(
  //     span(child <-- handler.map(_.toString)),
  //     button(ViewPage("hallo")) --> handler
  //   )
  // }

case class PostCreatorMenu(pos: Vec2) {
  var ySimPostOffset: Double = 50
}

class GlobalState(rawEventStream: Observable[Seq[ApiEvent]])(implicit ctx: Ctx.Owner) {

  import StateHelpers._
  import ClientCache.storage


  val eventStream: Observable[ApiEvent] = {
    val partitionedEvents = rawEventStream.map(_.partition {
      case NewGraphChanges(_) => true
      case _ => false
    })

    val graphEvents = partitionedEvents.map(_._1)
    val otherEvents = partitionedEvents.map(_._2)
    //TODO bufferunless here will crash merge for rawGraph with infinite loop.
    // somehow `x.bufferUnless(..) merge y.bufferUnless(..)` does not work.
    val bufferedGraphEvents = graphEvents //.bufferUnless(syncEnabled).map(_.flatten)

    val events = bufferedGraphEvents merge otherEvents
    events.concatMap(Observable.from(_)) //TODO flattening here is nice, but also pushes more updates?
  }

  val currentUser: Observable[Option[User]] = eventStream.collect {
    case LoggedIn(auth) => Some(auth.user)
    case LoggedOut => None
  }.startWith(None)

  private val inner = new {
    val syncMode = Var[SyncMode](SyncMode.default) //TODO storage.syncMode
    val syncEnabled = syncMode.map(_ == SyncMode.Live)

    val persistence = new GraphPersistence(syncEnabled.toObservable)

    val rawGraph: Rx[Graph] = {
      val localEvents = persistence.localChanges.map(NewGraphChanges)
      val events = eventStream merge localEvents
      events.scan(Graph.empty)(GraphUpdate.applyEvent).toRx(seed = Graph.empty)
    }

    val viewConfig: Var[ViewConfig] = UrlRouter.variable.imap(ViewConfig.fromHash)(x => Option(ViewConfig.toHash(x)))

    val inviteToken = viewConfig.map(_.invite)

    val view: Var[View] = viewConfig.zoom(_.view)((config, view) => config.copy(view = view))


    val page: Var[Page] = {
      val rawPage = viewConfig.zoom(_.page)((config, page) => config.copy(page = page))
      new Var.Composed(rawPage, Rx {
        rawPage() match {
          case Page.Union(ids) =>
            Page.Union(ids.filter(rawGraph().postsById.isDefinedAt))
          case s => s
        }
      })
    }

    val pageParentPosts: Rx[Set[Post]] = Rx {
      page().parentIds.map(rawGraph().postsById)
    }

    val pageStyle = Rx {
      println(s"calculating page style: (${page()}, ${pageParentPosts()})")
      PageStyle(page(), pageParentPosts())
    }

    val selectedGroupId: Var[Option[GroupId]] = {
      val rawSelectedGroupId = viewConfig.zoom(_.groupIdOpt)((config, groupIdOpt) => config.copy(groupIdOpt = groupIdOpt))
      new Var.Composed(rawSelectedGroupId, Rx {
        rawSelectedGroupId().filter(rawGraph().groupsById.isDefinedAt)
      })
    }


    // be aware that this is a potential memory leak.
    // it contains all ids that have ever been collapsed in this session.
    // this is a wanted feature, because manually collapsing posts is preserved with navigation
    val collapsedPostIds: Var[Set[PostId]] = Var(Set.empty)

    val currentView: Var[Perspective] = {
      val rawPerspective = Var(Perspective())
      new Var.Composed(rawPerspective, Rx {
        rawPerspective().union(Perspective(collapsed = Selector.IdSet(collapsedPostIds())))
      })
    }

    //TODO: when updating, both displayGraphs are recalculated
    // if possible only recalculate when needed for visualization
    val displayGraphWithoutParents: Rx[DisplayGraph] = Rx {
      val graph = groupLockFilter(viewConfig(), selectedGroupId(), rawGraph().consistent)
      page() match {
        case Page.Root =>
          currentView().applyOnGraph(graph)

        case Page.Union(parentIds) =>
          val descendants = parentIds.flatMap(graph.descendants) -- parentIds
          val selectedGraph = graph.filter(descendants)
          currentView().applyOnGraph(selectedGraph)
      }
    }


    val displayGraphWithParents: Rx[DisplayGraph] = Rx {
      val graph = groupLockFilter(viewConfig(), selectedGroupId(), rawGraph().consistent)
      page() match {
        case Page.Root =>
          currentView().applyOnGraph(graph)

        case Page.Union(parentIds) =>
          val descendants = parentIds.flatMap(graph.descendants) ++ parentIds
          val selectedGraph = graph.filter(descendants)
          currentView().applyOnGraph(selectedGraph)
      }
    }

    val chronologicalPostsAscending = displayGraphWithoutParents.map { dg =>
      val graph = dg.graph
      graph.posts.toList.sortBy(p => Tag.unwrap(p.id))
    }

    val focusedPostId: Var[Option[PostId]] = {
      val rawFocusedPostId = Var(Option.empty[PostId])
      new Var.Composed(rawFocusedPostId, Rx {
        rawFocusedPostId().filter(displayGraphWithoutParents().graph.postsById.isDefinedAt)
      })
    }
  }

  val persistence = inner.persistence

  val rawGraph = inner.rawGraph.toObservable
  val viewConfig = inner.viewConfig.toHandler
  val currentView = inner.currentView.toHandler
  val page = inner.page.toHandler
  val pageStyle = inner.pageStyle.toObservable
  val view = inner.view.toHandler
  val pageParentPosts = inner.pageParentPosts.toObservable
  val syncMode = inner.syncMode.toHandler
  val syncEnabled = inner.syncEnabled.toObservable
  val displayGraphWithParents = inner.displayGraphWithParents.toObservable
  val displayGraphWithoutParents = inner.displayGraphWithoutParents.toObservable
  val chronologicalPostsAscending = inner.chronologicalPostsAscending.toObservable



  val postCreatorMenus: Handler[List[PostCreatorMenu]] = createHandler(List.empty[PostCreatorMenu]).unsafeRunSync()

  val jsErrors: Handler[Seq[String]] = createHandler(Seq.empty[String]).unsafeRunSync()
  DevOnly {
    val errorMessage = Observable.create[String] { observer =>
      window.onerror = { (msg: Event, source: String, line: Int, col: Int) =>
        //TODO: send and log production js errors in backend
        observer.next(msg.toString)
      }
    }
    jsErrors <-- errorMessage.scan(Vector.empty[String])((acc,msg) => acc :+ msg)
  }

  //TODO: hack for having authorship of post. this needs to be encoded in the graph / versioning scheme
  val ownPosts = new collection.mutable.HashSet[PostId]


  //events!!
  //TODO persistence?
      // rawGraph() = newGraph applyChanges persistence.currentChanges
  //TODO: on user login:
      //     ClientCache.currentAuth = Option(auth)
      //     if (auth.user.isImplicit) {
      //       Analytics.sendEvent("auth", "loginimplicit", "success")
      //     }
      //     ClientCache.currentAuth = None

  // rawEventStream { events =>
    // DevOnly {
    //   views.DevView.apiEvents.updatef(events.toList ++ _)
    //   events foreach {
    //     case ReplaceGraph(newGraph) =>
    //       assert(newGraph.consistent == newGraph, s"got inconsistent graph from server:\n$newGraph\nshould be:\n${newGraph.consistent}")
    //     //TODO needed?
    //     // assert(currentUser.now.forall(user => newGraph.usersById.isDefinedAt(user.id)), s"current user is not in Graph:\n$newGraph\nuser: ${currentUser.now}")
    //     // assert(currentUser.now.forall(user => newGraph.groupsByUserId(user.id).toSet == newGraph.groups.map(_.id).toSet), s"User is not member of all groups:\ngroups: ${newGraph.groups}\nmemberships: ${newGraph.memberships}\nuser: ${currentUser.now}\nmissing memberships for groups:${currentUser.now.map(user => newGraph.groups.map(_.id).toSet -- newGraph.groupsByUserId(user.id).toSet)}")
    //     case _ =>
    //   }
    // }
  // }

  DevOnly {
    rawGraph.debug((g:Graph) => s"rawGraph: ${g.toSummaryString}")
    //      collapsedPostIds.debug("collapsedPostIds")
    currentView.debug("currentView")
    //      displayGraphWithoutParents.debug { dg => s"displayGraph: ${dg.graph.toSummaryString}" }
    //      focusedPostId.debug("focusedPostId")
    //      selectedGroupId.debug("selectedGroupId")
    // rawPage.debug("rawPage")
    page.debug("page")
    viewConfig.debug("viewConfig")
    //      currentUser.debug("\ncurrentUser")

  }

}

object StateHelpers {
  def groupLockFilter(viewConfig: ViewConfig, selectedGroupId: Option[GroupId], graph: Graph): Graph =
    if (viewConfig.lockToGroup) {
      val groupPosts = selectedGroupId.map(graph.postsByGroupId).getOrElse(Set.empty)
      graph.filter(groupPosts)
    } else graph
}
