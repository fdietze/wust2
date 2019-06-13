package wust.webApp.state

import java.util.concurrent.TimeUnit

import wust.facades.googleanalytics.Analytics
import wust.facades.hotjar
import monix.eval.Task
import monix.reactive.Observable
import org.scalajs.dom
import org.scalajs.dom.window
import outwatch.dom.helpers.OutwatchTracing
import rx._
import wust.webUtil.outwatchHelpers._
import wust.webUtil.{BrowserDetect, UI}
import wust.api.ApiEvent.ReplaceGraph
import wust.graph._
import wust.ids._
import wust.sdk._
import wust.util.StringOps
import wust.webApp.jsdom.{Navigator, ServiceWorker}
import wust.webApp.parsers.{UrlConfigParser, UrlConfigWriter}
import wust.webApp.views.EditableContent
import wust.webApp.{Client, DevOnly}

import scala.concurrent.duration._
import scala.util.{Failure, Success}

object GlobalStateFactory {
  def create(swUpdateIsAvailable: Observable[Unit])(implicit ctx: Ctx.Owner): GlobalState = {
    val sidebarOpen = Client.storage.sidebarOpen.imap(_ getOrElse !BrowserDetect.isMobile)(Some(_)) // expanded sidebar per default for desktop
    val urlConfig = UrlRouter.variable.imap(UrlConfigParser.fromUrlRoute)(UrlConfigWriter.toUrlRoute)

    val eventProcessor = EventProcessor(
      Client.observable.event,
      (changes, userId, graph) => GraphChangesAutomation.enrich(userId, graph, urlConfig, EmojiReplacer.replaceChangesToColons(changes)).consistent,
      Client.api.changeGraph,
      Client.currentAuth
    )

    Observable(EditableContent.currentlyEditing, UI.currentlyEditing).merge.subscribe(eventProcessor.stopEventProcessing)

    val isOnline = Observable(
      Client.observable.connected.map(_ => true),
      Client.observable.closed.map(_ => false)
    ).merge.unsafeToRx(true)

    val isLoading = Var(false)

    val hasError = Var(false)

    val fileDownloadBaseUrl = Var[Option[String]](None)

    val screenSize: Rx[ScreenSize] = outwatch.dom.dsl.events.window.onResize
      .debounce(0.2 second)
      .map(_ => ScreenSize.calculate())
      .unsafeToRx(ScreenSize.calculate())

    val showTagsList = Client.storage.taglistOpen.imap(_ getOrElse false)(Some(_))
    val showFilterList = Client.storage.filterlistOpen.imap(_ getOrElse false)(Some(_))

    val state = new GlobalState(swUpdateIsAvailable, eventProcessor, sidebarOpen, showTagsList, showFilterList, urlConfig, isOnline, isLoading, hasError, fileDownloadBaseUrl, screenSize)
    import state._

    // on mobile left and right sidebars overlay the screen.
    // close the right sidebar when the left sidebar is opened on mobile.
    // you can never open the right sidebar when the left sidebar is open,
    if (BrowserDetect.isMobile) {
      leftSidebarOpen.triggerLater { open =>
        if (open) {
          state.rightSidebarNode() = None
        }
      }
    }

    def closeAllOverlays(): Unit = {
      state.uiSidebarClose.onNext(())
      state.uiModalClose.onNext(())
      state.rightSidebarNode() = None
    }

    page.triggerLater {
      closeAllOverlays()
      state.graphTransformations() = state.defaultTransformations
    }
    view.map(_.isContent).triggerLater { isContent =>
      if (!isContent) {
        closeAllOverlays()
      }
    }

    // would be better to statically have this base url from the index.html or something.
    def renewFileDownloadBaseUrl(): Unit = {
      def scheduleRenewal(seconds: Int): Unit = {
        Task(renewFileDownloadBaseUrl()).delayExecution(FiniteDuration(seconds, TimeUnit.SECONDS)).runToFuture
      }

      Client.api.fileDownloadBaseUrl.onComplete {
        case Success(Some(fileUrl)) =>
          fileDownloadBaseUrl() = Some(fileUrl.url)
        case Success(None) =>
          () // nothing to do, not file url available at backend
        case Failure(err) =>
          scribe.warn("Error getting file download base url, will retry in 30 seconds...", err)
          scheduleRenewal(seconds = 30)
      }
    }
    renewFileDownloadBaseUrl()

    // automatically notify visited nodes and add self as member
    def enrichVisitedGraphWithSideEffects(page: Page, graph: Graph): Graph = {
      //TODO: userdescendant
      val user = state.user.now

      page.parentId.fold(graph) { parentId =>
        val userIdx = graph.idToIdx(user.id)
        graph.idToIdxFold(parentId)(graph) { pageIdx =>
          def anyPageParentIsPinned = graph.anyAncestorOrSelfIsPinned(Array(pageIdx), user.id)
          def pageIsInvited = userIdx.fold(false)(userIdx => graph.inviteNodeIdx.contains(userIdx)(pageIdx))
          def userIsMemberOfPage: Boolean = userIdx.fold(false)(userIdx => graph.membershipEdgeForNodeIdx.exists(pageIdx)(edgeIdx => graph.edgesIdx.b(edgeIdx) == userIdx))

          val memberChanges = if (!userIsMemberOfPage) {
            GraphChanges.connect(Edge.Member)(parentId, EdgeData.Member(AccessLevel.ReadWrite), user.id)
          } else GraphChanges.empty

          val edgeChanges = if (!anyPageParentIsPinned && !pageIsInvited) {
            GraphChanges.connect(Edge.Notify)(parentId, user.id)
              .merge(GraphChanges.connect(Edge.Pinned)(parentId, user.id))
              .merge(GraphChanges.disconnect(Edge.Invite)(parentId, user.id))
          } else GraphChanges.empty

          val allChanges = memberChanges merge edgeChanges
          if (allChanges.nonEmpty) {
            eventProcessor.changes.onNext(allChanges)
            graph.applyChanges(allChanges)
          } else graph
        }
      }
    }

    def getNewGraph(page: Page) = {
      isLoading() = true
      val graph = for {
        graph <- Client.api.getGraph(page)
      } yield enrichVisitedGraphWithSideEffects(page, graph)

      graph.transform { result =>
        isLoading() = false
        result
      }
    }

    // if we have a invitation token, we merge this invited user into our account and get the graph again.
    urlConfig.foreach { viewConfig =>
      viewConfig.invitation match {
        case Some(inviteToken) => Client.auth.acceptInvitation(inviteToken).foreach {
          case () =>
            //clear the invitation from the viewconfig and url
            state.urlConfig.update(_.copy(invitation = None))

            // get a new graph with new right after the accepted invitation
            getNewGraph(viewConfig.pageChange.page).foreach { graph =>
              eventProcessor.localEvents.onNext(ReplaceGraph(graph))
            }
        }
        //TODO: signal status of invitation to user in UI
        case None => ()
      }
    }

    // clear selected nodes on view and page change
    {
      val clearTrigger = Rx {
        view()
        page()
        ()
      }
      clearTrigger.foreach { _ => selectedNodes() = Nil }
    }

    state.auth.foreach { auth =>
      Analytics.setUserId(auth.user.id.toUuid.toString)
    }

    //TODO: better in rx/obs operations
    // store auth in localstore and send to serviceworker
    val authWithPrev = auth.fold((auth.now, auth.now)) { (prev, auth) => (prev._2, auth) }
    authWithPrev.foreach {
      case (prev, auth) =>
        if (prev != auth) {
          Client.storage.auth() = Some(auth)
        }

        ServiceWorker.sendAuth(auth)
    }

    //TODO: better build up state from server events?
    // only adding a new channel would normally get a new graph. but we can
    // avoid this here and do nothing. Optimistic UI. We just integrate this
    // change into our local state without asking the backend. when the page
    // changes, we get a new graph. except when it is just a Page.NewChannel.
    // There we want tnuro issue the new-channel change.
    {
      val userAndPage = Rx {
        (urlConfig(), user().toNode)
      }

      var lastTransitChanges: List[GraphChanges] = Nil
      eventProcessor.changesInTransit.foreach { lastTransitChanges = _ }

      var isFirstGraphRequest = true
      var prevPage: PageChange = null
      var prevUser: Node.User = null
      userAndPage.toObservable
        .switchMap {
          case (viewConfig, user) =>
            val currentTransitChanges = lastTransitChanges.fold(GraphChanges.empty)(_ merge _)
            val observable: Observable[Graph] =
              if (prevUser == null || prevUser.id != user.id || prevUser.data.isImplicit != user.data.isImplicit) {
                Observable.fromFuture(getNewGraph(viewConfig.pageChange.page))
              } else if (prevPage == null || prevPage != viewConfig.pageChange) {
                if (viewConfig.pageChange.needsGet && (!viewConfig.pageChange.page.isEmpty || isFirstGraphRequest)) {
                  Observable.fromFuture(getNewGraph(viewConfig.pageChange.page))
                } else Observable.empty
              } else {
                Observable.empty
              }

            prevPage = viewConfig.pageChange
            prevUser = user
            isFirstGraphRequest = false

            observable
              .onErrorHandle(_ => Graph.empty)
              .map(g => ReplaceGraph(g.applyChanges(currentTransitChanges)))
        }
        .subscribe(eventProcessor.localEvents)
    }

    val titleSuffix = if (DevOnly.isTrue) "dev" else "Woost"
    // switch to View name in title if view switches to non-content
    Rx {
      if (view().isContent) {
        val channelName = page().parentId.flatMap(id => graph().nodesById(id).map(n => StringOps.trimToMaxLength(n.str, 30))).map(EmojiTitleConverter.emojiTitleConvertor.replace_colons)
        window.document.title = channelName.fold(titleSuffix)(name => s"${if (name.contains("unregistered-user")) "Unregistered User" else name} - $titleSuffix")
      } else {
        window.document.title = s"${view().toString} - $titleSuffix"
      }
    }

    // trigger for updating the app and reloading. we drop 1 because we do not want to trigger for the initial state
    // if update is available, a reload will be triggered at every page or view change
    val appUpdateTrigger = Observable(page.toTailObservable, view.toTailObservable).merge

    // try to update serviceworker. We do this automatically every 60 minutes. If we do a navigation change like changing the page,
    // we will check for an update immediately, but at max every 30 minutes.
    val autoCheckUpdateInterval = 60.minutes
    val maxCheckUpdateInterval = 30.minutes
    appUpdateTrigger
      .echoRepeated(autoCheckUpdateInterval)
      .throttleFirst(maxCheckUpdateInterval)
      .foreach { _ =>
        Navigator.serviceWorker.foreach(_.getRegistration().toFuture.foreach(_.foreach { reg =>
          scribe.info("Requesting updating from SW.")
          reg.update().toFuture.onComplete { res =>
            scribe.info(s"Result of update request: ${if (res.isSuccess) "Success" else "Failure"}.")
          }
        }))
      }

    // if there is a page change and we got an sw update, we want to reload the page
    appUpdateTrigger.withLatestFrom(appUpdateIsAvailable)((_, _) => Unit).foreach { _ =>
      scribe.info("Going to reload page, due to SW update.")
      // if flag is true, page will be reloaded without cache. False means it may use the browser cache.
      window.location.reload(flag = false)
    }


    Client.apiErrorSubject.foreach { _ =>
      scribe.error("API request did fail, because the API is incompatible")
      hasError() = true
    }
    OutwatchTracing.error.foreach{ t =>
      scribe.error("Error in outwatch component", t)
      hasError() = true
    }
    hasError.foreach { error =>
      if (error) hotjar.pageView("/js-error")
    }

    // we send client errors from javascript to the backend
    dom.window.addEventListener("onerror", { (e: dom.ErrorEvent) =>
      Client.api.log(s"Javascript Error: ${e.message}.")
    })

    DevOnly {
      rawGraph.debugWithDetail((g: Graph) => s"rawGraph: ${g.toString}", (g: Graph) => g.toDetailedString)
      graph.debugWithDetail((g: Graph) => s"graph: ${g.toString}", (g: Graph) => g.toDetailedString)

      page.debug("page")
      view.debug("view")
      user.debug("auth")
    }

    state
  }
}