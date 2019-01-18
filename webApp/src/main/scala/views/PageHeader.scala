package wust.webApp.views

import clipboard.ClipboardJS
import fontAwesome._
import googleAnalytics.Analytics
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalajs.dom
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.{Styles, CommonStyles, ZIndex}
import wust.graph.Node.User
import wust.graph._
import wust.ids._
import wust.sdk.BaseColors
import wust.sdk.NodeColor.hue
import wust.util._
import wust.webApp._
import wust.webApp.dragdrop.DragItem
import wust.webApp.jsdom.{Navigator, ShareData}
import wust.webApp.outwatchHelpers._
import wust.webApp.search.Search
import wust.webApp.state._
import wust.webApp.views.Components.{renderNodeData, _}
import wust.webApp.views.UI.ModalConfig

import scala.collection.breakOut
import scala.scalajs.js
import scala.util.{Failure, Success}


object PageHeader {
  val lineColor = "#6B808F"
  val lineWidth = "2px"

  def apply(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    div.static(keyValue)(Ownable { implicit ctx =>
      VDomModifier(
        cls := "pageheader",
        borderBottom := s"$lineWidth solid $lineColor",

        Rx {
          val graph = state.graph()
          val page = state.page()
          val pageNode = page.parentId.flatMap(graph.nodesByIdGet)
          pageNode.map { pageNode => pageRow (state, pageNode) }
        },
      )
    })
  }

  private def pageRow(state: GlobalState, pageNode: Node)(implicit ctx: Ctx.Owner): VNode = {
    val maxLength = if(BrowserDetect.isPhone) Some(30) else Some(250)
    val channelTitle = NodePermission.canWrite(state, pageNode.id).map { canWrite =>
      pageNode.role match {
        case NodeRole.Message | NodeRole.Task =>
          val node =
            if(!canWrite) nodeCard(pageNode)
            else {
              val editable = Var(false)
              nodeCardEditable(state, pageNode, editable).apply(onClick.stopPropagation(true) --> editable)
              // editableNodeOnClick(state, pageNode, state.eventProcessor.changes, maxLength)(ctx)(
              //   onClick foreach { Analytics.sendEvent("pageheader", "editchanneltitle") }
              // )
            }
            node(cls := "pageheader-channeltitle")

        case _ => // usually NodeRole.Project
          val node =
            if(!canWrite) renderNodeData(pageNode.data, maxLength)
            else {
              editableNodeOnClick(state, pageNode, maxLength)(ctx)(
                onClick.stopPropagation foreach { Analytics.sendEvent("pageheader", "editchanneltitle") }
              )
            }
            node(cls := "pageheader-channeltitle")

      }
    }


    val channelMembersList = Rx {
      val hasBigScreen = state.screenSize() != ScreenSize.Small
      hasBigScreen.ifTrue[VDomModifier](channelMembers(state, pageNode).apply(marginRight := "10px", lineHeight := "0")) // line-height:0 fixes vertical alignment
    }

    val permissionIndicator = Rx {
      val level = Permission.resolveInherited(state.graph(),pageNode.id)
      div(level.icon, UI.tooltip("bottom center") := level.description, zIndex := ZIndex.tooltip-15)
    }

    div(
      paddingTop := "5px",
      paddingLeft := "5px",
      paddingRight := "10px",
      backgroundColor := BaseColors.pageBg.copy(h = hue(pageNode.id)).toHex,

      Styles.flex,
      alignItems.center,
      flexWrap.wrap,

      div(
        Styles.flex,
        alignItems.center,
        justifyContent.flexStart,
        flexGrow := 1,
        flexShrink := 2,
        padding := "0 5px",
        nodeAvatar(pageNode, size = 30)(marginRight := "5px", flexShrink := 0),
        channelTitle.map(_(marginRight := "5px")),
        channelMembersList,
        permissionIndicator,
        paddingBottom := "5px",
      ),
      menu(state, pageNode).apply(alignSelf.flexEnd, marginLeft.auto),
    )
  }

  private def menu(state: GlobalState, channel: Node)(implicit ctx: Ctx.Owner): VNode = {
    val isSpecialNode = Rx{
      //TODO we should use the permission system here and/or share code with the settings menu function
      channel.id == state.user().id
    }
    val isBookmarked = Rx {
      val g = state.graph()
      val channelIdx = g.idToIdx(channel.id)
      val userIdx = g.idToIdx(state.user().id)
      state.graph().pinnedNodeIdx(userIdx).contains(channelIdx)
    }

    val buttonStyle = VDomModifier(Styles.flexStatic, margin := "5px", fontSize := "20px", cursor.pointer)

    div(
      Styles.flex,
      alignItems.center,
      minWidth.auto,
      Rx {
        val hideBookmarkButton = isSpecialNode() || isBookmarked()
        hideBookmarkButton.ifFalse[VDomModifier](addToChannelsButton(state, channel).apply(
          Styles.flexStatic,
          marginTop := "3px",
          marginBottom := "3px",
        ))
      },
//      notifyControl(state, channel).apply(buttonStyle),
      state.isFilterActive.map(_.ifTrue[VDomModifier]{
        div(
          Elements.icon(Icons.filter),
          color := "green",
          onClick.stopPropagation(Seq(state.defaultTransformation)) --> state.graphTransformations,
          cursor.pointer,
          UI.tooltip("bottom right") := "A filter is active. Click to reset to default.",
        )
      }),
      viewSwitcher(state).apply(alignSelf.flexEnd),
      Rx {
        settingsMenu(state, channel, isBookmarked()).apply(buttonStyle, marginLeft := "15px")
      },
    )
  }

  private def channelMembers(state: GlobalState, channel: Node)(implicit ctx: Ctx.Owner) = {
    div(
      Styles.flex,
      flexWrap.wrap,
      registerDragContainer(state),
      Rx {
        val graph = state.graph()
        val nodeIdx = graph.idToIdx(channel.id)
        val members = graph.membersByIndex(nodeIdx)

        members.map(user => div(
          Avatar.user(user.id)(
            marginLeft := "2px",
            width := "22px",
            height := "22px",
            cls := "avatar",
            marginBottom := "2px",
          ),
          Styles.flexStatic,
          cursor.grab,
          UI.tooltip("bottom center") := Components.displayUserName(user.data)
        )(
          drag(payload = DragItem.User(user.id)),
        ))(breakOut) : js.Array[VNode]
      }
    )
  }

  private def shareButton(state: GlobalState, channel: Node)(implicit ctx: Ctx.Owner): VNode = {

    new ClipboardJS(".shareurlholder")

    val urlHolderCls = "shareurlholder"
    val shareTitle = channel.data.str
    val shareUrl = dom.window.location.href
    val shareDesc = s"Share: $shareTitle"

    div(
      cls := s"$urlHolderCls item",
      Elements.icon(Icons.share)(marginRight := "5px"),
      span(cls := "text", "Share Link", cursor.pointer),
      dataAttr("clipboard-text") := shareUrl,
      onClick.stopPropagation foreach {
        scribe.info(s"sharing node: $channel")

        // make channel public if it is not. we are sharing the link, so we want it to be public.
        channel match {
          case channel: Node.Content =>
            if (channel.meta.accessLevel != NodeAccess.ReadWrite) {
              val changes = GraphChanges.addNode(channel.copy(meta = channel.meta.copy(accessLevel = NodeAccess.ReadWrite)))
              state.eventProcessor.changes.onNext(changes)
              UI.toast(s"${StringOps.trimToMaxLength(channel.str, 10)} is now public")
            }
          case _ => ()
        }

        if(Navigator.share.isDefined) {
          Navigator.share(new ShareData {
            title = shareTitle
            text = shareDesc
            url = shareUrl
          }).toFuture.onComplete {
            case Success(()) => ()
            case Failure(t)  => scribe.warn("Cannot share url via share-api", t)
          }
        } else {

          UI.toast(title = shareDesc, msg = "Link copied to clipboard")
        }
      },
      onClick.stopPropagation foreach { Analytics.sendEvent("pageheader", "share") }
    )
  }

  private def searchButton(state: GlobalState, node: Node)(implicit ctx: Ctx.Owner): VNode = {
    sealed trait SearchInput
    object SearchInput {
      case class Global(query: String) extends SearchInput
      case class Local(query: String) extends SearchInput

    }

    val searchLocal = PublishSubject[String]
    val searchGlobal = PublishSubject[String]
    val searchInputProcess = PublishSubject[String]
    val closeModal = PublishSubject[Unit]

    def renderSearchResult(needle: String, haystack: List[Node], globalSearchScope: Boolean) = {
      val searchRes = Search.byString(needle, haystack, Some(100), 0.75).map( nodeRes =>
        div(
          cls := "ui approve item",
          fontWeight.normal,
          cursor.pointer,
          padding := "3px",
          Components.nodeCard(nodeRes._1),
          onClick.stopPropagation.mapTo(state.viewConfig.now.focus(Page(nodeRes._1.id))) --> state.viewConfig,
          onClick.stopPropagation(()) --> closeModal
        ),
      )

      div(
        s"Found ${searchRes.length} result(s) in ${if(globalSearchScope) "all channels" else "the current workspace"} ",
        padding := "5px 0",
        fontWeight.bold,
        height := s"${dom.window.innerHeight/2}px",
        div(
          height := "100%",
          overflow.auto,
          searchRes,
        ),
        //TODO: Implement backend search
//        button(
//          cls := "ui button",
//          marginTop := "10px",
//          display := (if(globalSearchScope) "none" else "block"),
//          "Search in all channels",
//          onClick(needle) --> searchGlobal
//        )
      )
    }

    val searches = Observable(searchLocal.map(SearchInput.Local), searchGlobal.map(SearchInput.Global))
      .merge
      .distinctUntilChanged(cats.Eq.fromUniversalEquals)

    val searchResult: Observable[VDomModifier] = searches.map {
      case SearchInput.Local(query) if query.nonEmpty =>
        val graph = state.graph.now
        val nodes = graph.nodes.toList
        val descendants = graph.descendants(node.id)

        val channelDescendants = nodes.filter(n => descendants.toSeq.contains(n.id))
        renderSearchResult(query, channelDescendants, false)
      case SearchInput.Global(query) if query.nonEmpty =>
        Observable.fromFuture(Client.api.getGraph(Page.empty)).map { graph => //TODO? get whole graph? does that make sense?
          renderSearchResult(query, graph.nodes.toList, true)
        }
      case _ => VDomModifier.empty
    }

    def header(implicit ctx: Ctx.Owner) = VDomModifier(
      backgroundColor := BaseColors.pageBg.copy(h = hue(node.id)).toHex,
      div(
        Styles.flex,
        alignItems.center,
        nodeAvatar(node, size = 20)(marginRight := "5px"),
        renderNodeData(node.data)(fontWeight.normal),
      ),
      div(
        cls := "ui search",
        div(
          cls := "ui input action",
          input(
            cls := "prompt",
            placeholder := "Enter search text",
            Elements.valueWithEnter --> searchLocal,
            onChange.value --> searchInputProcess
          ),
          div(
            cls := "ui primary icon button approve",
            Elements.icon(Icons.search),
            span(cls := "text", "Search", marginLeft := "5px", cursor.pointer),
            onClick.stopPropagation(searchInputProcess) --> searchLocal
          ),
        ),
      )
    )

    def description(implicit ctx: Ctx.Owner) = VDomModifier(
      cls := "scrolling",
      backgroundColor := BaseColors.pageBgLight.copy(h = hue(node.id)).toHex,
      div(
        cls := "ui fluid search-result",
        searchResult,
      )
    )

    div(
      cls := "item",
      Elements.icon(Icons.search)(marginRight := "5px"),
      span(cls := "text", "Search", cursor.pointer),

      onClick.stopPropagation(Ownable(implicit ctx => UI.ModalConfig(header = header, description = description, close = closeModal,
        modalModifier = cls := "form",
        contentModifier = backgroundColor := BaseColors.pageBgLight.copy(h = hue(node.id)).toHex,
      ))) --> state.modalConfig
    )
  }

  private def manageMembers(state: GlobalState, node: Node.Content)(implicit ctx: Ctx.Owner): VNode = {

    val clear = Handler.unsafe[Unit].mapObservable(_ => "")
    val userNameInputProcess = PublishSubject[String]
    val modalCloseTrigger = PublishSubject[Unit]
    val statusMessageHandler = PublishSubject[Option[(String, String, VDomModifier)]]

    def addUserMember(userId: UserId): Unit = {
      val change:GraphChanges = GraphChanges.from(addEdges = Set(Edge.Invite(userId, node.id)))
      state.eventProcessor.changes.onNext(change)
      Client.api.addMember(node.id, userId, AccessLevel.ReadWrite).onComplete {
        case Success(b) =>
          if(!b) {
            statusMessageHandler.onNext(Some(("negative", "Adding Member failed", "Member does not exist")))
            scribe.info("Could not add member: Member does not exist")
          } else {
            statusMessageHandler.onNext(None)
            clear.onNext(())
          }
        case Failure(ex) =>
          statusMessageHandler.onNext(Some(("negative", "Adding Member failed", "Unexpected error")))
          scribe.warn("Could not add member to channel", ex)
      }
    }
    def handleAddMember(email: String)(implicit ctx: Ctx.Owner): Unit = {
      val graphUser = Client.api.getUserByEMail(email)
      graphUser.onComplete {
        case Success(Some(u)) if state.graph.now.members(node.id).exists(_.id == u.id) => // user exists and is already member
          statusMessageHandler.onNext(None)
          clear.onNext(())
          ()
        case Success(Some(u)) => // user exists with this email
          addUserMember(u.id)
        case Success(None)       => // user does not exist with this email
          Client.auth.getUserDetail(state.user.now.id).onComplete {
            case Success(Some(userDetail)) if userDetail.verified =>
              Client.auth.invitePerMail(address = email, node.id).onComplete {
                case Success(()) =>
                  statusMessageHandler.onNext(Some(("positive", "New member was invited", s"Invitation mail has been sent to '$email'.")))
                  clear.onNext(())
                case Failure(ex) =>
                  statusMessageHandler.onNext(Some(("negative", "Adding Member failed", "Unexpected error")))
                  scribe.warn("Could not add member to channel because invite failed", ex)
              }
            case Success(_) =>
              statusMessageHandler.onNext(Some(("negative", "Adding Member failed", "Please verify your own email address to send out invitation emails.")))
              scribe.warn("Could not add member to channel because user email is not verified")
            case Failure(ex) =>
              statusMessageHandler.onNext(Some(("negative", "Adding Member failed", "Unexpected error")))
              scribe.warn("Could not add member to channel", ex)
          }
        case Failure(ex)       =>
          statusMessageHandler.onNext(Some(("negative", "Adding Member failed", "Unexpected error")))
          scribe.warn("Could not add member to channel because get userdetails failed", ex)
      }
    }

    def handleRemoveMember(membership: Edge.Member)(implicit ctx: Ctx.Owner): Unit = {
      if(membership.userId == state.user.now.id) {
        if(dom.window.confirm("Do you really want to remove yourself from this workspace?")) {
          state.viewConfig() = state.viewConfig.now.copy(pageChange = PageChange(Page.empty))
          modalCloseTrigger.onNext(())
        } else return
      }

      Client.api.removeMember(membership.nodeId,membership.userId,membership.level) //TODO: handle error...
    }

    def userLine(user:Node.User)(implicit ctx: Ctx.Owner):VNode = {
      div(
        marginTop := "10px",
        Styles.flex,
        alignItems.center,
        Avatar.user(user.id)(
          cls := "avatar",
          width := "22px",
          height := "22px",
          Styles.flexStatic,
          marginRight := "5px",
        ),
        div(
          Components.displayUserName(user.data),
          fontSize := "15px",
          Styles.wordWrap,
        ),
      )
    }

    def description(implicit ctx: Ctx.Owner) = {
      var element: dom.html.Element = null
      val showEmailInvite = Var(false)
      val inputSizeMods = VDomModifier(width := "250px", height := "30px")
      VDomModifier(
        form(
          onDomMount.asHtml.foreach { element = _ },

          input(tpe := "text", style := "position: fixed; left: -10000000px", disabled := true), // prevent autofocus of input elements. it might not be pretty, but it works.

          showEmailInvite.map {
            case true => VDomModifier(
              div(
                cls := "ui fluid action input",
                inputSizeMods,
                input(
                  tpe := "email",
                  placeholder := "Invite by email address",
                  value <-- clear,
                  Elements.valueWithEnter(clearValue = false) foreach { str =>
                    if(element.asInstanceOf[js.Dynamic].reportValidity().asInstanceOf[Boolean]) {
                      handleAddMember(str)
                    }
                  },
                  onChange.value --> userNameInputProcess
                ),
                div(
                  cls := "ui primary button approve",
                  "Add",
                  onClick.stopPropagation(userNameInputProcess) foreach { str =>
                    if(element.asInstanceOf[js.Dynamic].reportValidity().asInstanceOf[Boolean]) {
                      handleAddMember(str)
                    }
                  }
                ),
              ),
              a(href := "#", padding := "5px", onClick.stopPropagation.preventDefault(false) --> showEmailInvite, "Invite user by username")
            )
            case false => VDomModifier(
              searchInGraph(state.graph, "Invite by username", filter = u => u.isInstanceOf[Node.User] && !state.graph.now.members(node.id).exists(_.id == u.id), showParents = false, inputModifiers = inputSizeMods).foreach { userId =>
                addUserMember(UserId(userId))
              },
              a(href := "#", padding := "5px", onClick.stopPropagation.preventDefault(true) --> showEmailInvite, "Invite user by email address")
            )
          },
          statusMessageHandler.map {
            case Some((statusCls, title, errorMessage)) => div(
              cls := s"ui $statusCls message",
              div(cls := "header", title),
              p(errorMessage)
            )
            case None => VDomModifier.empty
          },
        ),
        div(
          marginLeft := "10px",
          Rx {
            val graph = state.graph()
            val nodeIdx = graph.idToIdx(node.id)
            if(nodeIdx != -1) {
              graph.membershipEdgeForNodeIdx(nodeIdx).map { membershipIdx =>
                val membership = graph.edges(membershipIdx).asInstanceOf[Edge.Member]
                val user = graph.nodesById(membership.userId).asInstanceOf[User]
                userLine(user).apply(
                  button(
                    cls := "ui tiny compact negative basic button",
                    marginLeft := "10px",
                    "Remove",
                    onClick.stopPropagation(membership).foreach(handleRemoveMember(_))
                  )
                )
              }:VDomModifier
            } else VDomModifier.empty
          },
          if(true) VDomModifier.empty else List(div)
        )
      )
    }

    div(
      cls := "item",
      Elements.icon(Icons.users)(marginRight := "5px"),
      span(cls := "text", "Manage Members", cursor.pointer),

      onClick.stopPropagation(Ownable(implicit ctx => UI.ModalConfig(
        header = ModalConfig.defaultHeader(state, node, "Manage Members", Icons.users),
        description = description,
        close = modalCloseTrigger,
        modalModifier = VDomModifier(
          cls := "mini form",
        ),
        contentModifier = VDomModifier(
          backgroundColor := BaseColors.pageBgLight.copy(h = hue(node.id)).toHex
        )
      ))) --> state.modalConfig
    )
  }

  // private def addToChannelsButton(state: GlobalState, channel: Node)(implicit ctx: Ctx.Owner): VNode =
  //   button(
  //     cls := "ui compact primary button",
  //     if (BrowserDetect.isMobile) "Pin" else "Pin to sidebar",
  //     onClick.mapTo(GraphChanges.connect(Edge.Pinned)(state.user.now.id, channel.id)) --> state.eventProcessor.changes,
  //     onClick foreach { Analytics.sendEvent("pageheader", "join") }
  //   )

  private def addToChannelsButton(state: GlobalState, channel: Node)(implicit ctx: Ctx.Owner): VNode = {
    val isInvited = Rx {
      val graph = state.graph()
      val user = state.user()

      graph.inviteNodeIdx(graph.idToIdx(user.id)).contains(graph.idToIdx(channel.id))
    }

    div(
      div(
        Styles.flex,
        button(
          cls := "ui compact primary button",
          if (BrowserDetect.isMobile) "Pin" else "Pin to sidebar",
          onClick.mapTo(GraphChanges(addEdges = Set(Edge.Pinned(state.user.now.id, channel.id), Edge.Notify(channel.id, state.user.now.id)), delEdges = Set(Edge.Invite(state.user.now.id, channel.id)))) --> state.eventProcessor.changes,
          onClick foreach { Analytics.sendEvent("pageheader", "join") }
        )
      )
    )
  }

  //TODO make this reactive by itself and never rerender, because the modal stuff is quite expensive.
  //TODO move menu to own file, makes up for a lot of code in this file
  //TODO: also we maybe can prevent rerendering menu buttons and modals while the menu is closed and do this lazily?
  private def settingsMenu(state: GlobalState, channel: Node, bookmarked: Boolean)(implicit ctx: Ctx.Owner): VNode = {

    val channelAsContent: Option[Node.Content] = Some(channel).collect { case n: Node.Content => n }
    def channelIsContent = channelAsContent.isDefined
    val canWrite = Permission.canWrite(state, channel)
    val permissionItem = Permission.permissionItem(state, channel)
    val nodeRoleItem:VDomModifier = channelAsContent.collect {
      case channel if canWrite && channel.role != NodeRole.Stage =>
        def nodeRoleSubItem(nodeRole: NodeRole, roleIcon: IconLookup) = div(
          cls := "item",
          Elements.icon(roleIcon)(marginRight := "5px"),
          nodeRole.toString,
          (channel.role == nodeRole).ifTrueOption(i(cls := "check icon", margin := "0 0 0 20px")),
          onClick(GraphChanges.addNode(channel.copy(role = nodeRole))) --> state.eventProcessor.changes,
          onClick foreach {
            Analytics.sendEvent("pageheader", "changerole", nodeRole.toString)
          }
        )

        div(
          cls := "item",
          Elements.icon(Icons.convertItem)(marginRight := "5px"),
          span(cls := "text", "Convert to ...", cursor.pointer),
          div(
            cls := "menu",
            ConvertSelection.all.map { convert => nodeRoleSubItem(convert.role, convert.icon) }
          )
        )
    }

    val mentionInItem:VDomModifier = {
      div(
        cls := "item",
        Elements.icon(Icons.mentionIn)(marginRight := "5px"),
        span(cls := "text", "Mention in", cursor.pointer),
        div(
          cls := "menu",
          padding := "10px",
          searchInGraph(state.graph, placeholder = "Enter Tag", filter = {
            case node: Node.Content => true
            case node: Node.User => node.id == state.user.now.id
          }).foreach { nodeId =>
            state.eventProcessor.changes.onNext(
              GraphChanges.addToParent(channel.id, nodeId)
            )
          }
        )
      )
    }

    val leaveItem:VDomModifier =
      (bookmarked && channelIsContent).ifTrue[VDomModifier](div(
        cls := "item",
        Elements.icon(Icons.signOut)(marginRight := "5px"),
        span(cls := "text", "Unpin from sidebar", cursor.pointer),
        onClick.stopPropagation.mapTo(GraphChanges.disconnect(Edge.Pinned)(state.user.now.id, channel.id)) --> state.eventProcessor.changes
      ))

    val deleteItem =
      (channelIsContent && canWrite).ifTrue[VDomModifier](div(
        cls := "item",
        Elements.icon(Icons.delete)(marginRight := "5px"),
        span(cls := "text", "Archive", cursor.pointer),
        onClick.stopPropagation foreach {
          state.eventProcessor.changes.onNext(
            GraphChanges.delete(channel.id, state.graph.now.parents(channel.id).toSet)
              .merge(GraphChanges.disconnect(Edge.Pinned)(state.user.now.id, channel.id))
          )
          UI.toast(s"Archived '${StringOps.trimToMaxLength(channel.str, 10)}'", click = () => state.viewConfig.update(_.focus(Page(channel.id))), level = UI.ToastLevel.Success)
        }
      ))


    val addMemberItem = channel match {
      case channel: Node.Content if canWrite => manageMembers(state, channel)
      case _ => VDomModifier.empty
    }
    val shareItem = channelIsContent.ifTrue[VDomModifier](shareButton(state, channel))
    val searchItem = searchButton(state, channel)
    val notificationItem = channelIsContent.ifTrue[VDomModifier](Rx { WoostNotification.generateNotificationItem(state, state.permissionState(), state.graph(), state.user().toNode, channel) })
    val filterItem = ViewFilter.renderMenu(state)

    val items:List[VDomModifier] = List(notificationItem, searchItem, addMemberItem, shareItem, filterItem, mentionInItem, permissionItem, nodeRoleItem, leaveItem, deleteItem)

    div(
      // https://semantic-ui.com/modules/dropdown.html#pointing
      cls := "ui icon top left labeled pointing dropdown",
      Icons.menuDropdown,
      div(
        cls := "menu",
        div(cls := "header", "Settings", cursor.default),
        items
      ),
//      UI.tooltip("bottom right") := "Settings",
      zIndex := ZIndex.overlay,                               // leave zIndex here since otherwise it gets overwritten
      Elements.withoutDefaultPassiveEvents,                   // revert default passive events, else dropdown is not working
      onDomMount.asJquery.foreach(_.dropdown("hide")),   // https://semantic-ui.com/modules/dropdown.html#/usage
    )
  }

  private def viewSwitcher(state: GlobalState)(implicit ctx: Ctx.Owner): VNode = {
    def item(currentView: View, pageStyle: PageStyle, targetView: View, icon: IconDefinition, wording: String, numItems: Int) = {
      div(
        cls := "viewswitcher-item",

        border := s"$lineWidth solid $lineColor",
        marginBottom := s"-$lineWidth",

        div(cls := "fa-fw", icon),
        Styles.flex,
        alignItems.center,
        // will ensure that icons shrink to fit a line
        // see: https://stackoverflow.com/questions/45819924/css-scale-items-when-resizing-in-flexbox
        width := "100%",

        Rx {
          val pageStyle = state.pageStyle()
          def isSelected = currentView.viewKey == targetView.viewKey
          if (isSelected) {
            VDomModifier(
              backgroundColor := pageStyle.bgLightColor,
              borderBottomColor := pageStyle.bgLightColor,
            )
          } else {
            VDomModifier(
              backgroundColor := "#97aaba",
              borderBottomColor := lineColor,
              color := "rgba(0,0,0,0.7)",
            )
          }
        },
        VDomModifier.ifTrue(numItems > 0)(span(numItems, paddingLeft := "7px")),

        UI.tooltip("bottom right") := s"${targetView.toString}${(numItems > 0).ifTrue[String](s": $numItems $wording")}",
        onClick.stopPropagation foreach {
          state.viewConfig.update(_.focusView(targetView))
          Analytics.sendEvent("viewswitcher", "switch", currentView.viewKey)
        },
        cursor.pointer,
      )
    }

    div(
      marginLeft := "5px",
      Styles.flex,
      justifyContent.center,
      alignItems.center,
      minWidth.auto,

      Rx {
        val currentView = state.view()
        val pageStyle = state.pageStyle()

        val (numMsg, numTasks, numFiles) = if(!BrowserDetect.isPhone) {
          state.page.now.parentId.fold((0, 0, 0)){ pid =>
            val graph = state.graph.now
            val nodeIdx = graph.idToIdx(pid)
            val messageChildrenCount = graph.messageChildrenIdx.sliceLength(nodeIdx)
            val taskChildrenCount = graph.taskChildrenIdx.sliceLength(nodeIdx)
            val filesCount = graph.pageFiles(pid).length
            (messageChildrenCount, taskChildrenCount, filesCount)
          }
        } else (0, 0, 0)

        Seq(
          // removed task / conversation views, since the toggle button was not being
          // reflected in the ui and history was bugged
          // also, the UI is sorta inconsistent, so we decided to have all views individually
          //item(currentView, pageStyle, View.Conversation, Icons.conversation, "messages", numMsg)
          //    (zIndex := ZIndex.tooltip-10),
          //item(currentView, pageStyle, View.Tasks, Icons.tasks, "tasks", numTasks)
          //    (zIndex := ZIndex.tooltip-20),
          //item(currentView, pageStyle, View.Magic, freeSolid.faMagic),
          item(currentView, pageStyle, View.Dashboard, Icons.dashboard, "dashboard", 0)(zIndex := ZIndex.tooltip-10),
          item(currentView, pageStyle, View.Chat, Icons.chat, "Chat", numMsg),
          item(currentView, pageStyle, View.Thread, Icons.thread, "Thread", numMsg),
          item(currentView, pageStyle, View.List, Icons.list, "List", numTasks),
          item(currentView, pageStyle, View.Kanban, Icons.kanban, "Kanban", numTasks),
          item(currentView, pageStyle, View.Files, Icons.files, "files", numFiles)(zIndex := ZIndex.tooltip-30),
//          item(currentView, pageStyle, View.Graph, freeBrands.faCloudsmith)
        )
      }
    )

  }

}

case class ConvertSelection(
  role: NodeRole,
  icon: IconLookup,
  description: String,
)
object ConvertSelection {
  val all =
    ConvertSelection(
      role = NodeRole.Message,
      icon = Icons.conversation,
      description = "Message of a workspace or chat.",
    ) ::
      ConvertSelection(
        role = NodeRole.Task,
        icon = Icons.task,
        description = "Task item of a list or kanban.",
      ) ::
      ConvertSelection(
        role = NodeRole.Project,
        icon = Icons.project,
        description = "A Project. It can contain conversations and tasks.",
      ) ::
      // ConvertSelection(
      //   role = NodeRole.Stage,
      //   icon = Icons.stage,
      //   description = "Stage, a period in a structure / progress, e.g. column in a kanban",
      // ) ::
      Nil
}
