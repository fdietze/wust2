package wust.webApp.views

import wust.webApp._
import dateFns.DateFns
import fontAwesome._
import googleAnalytics.Analytics
import monix.eval.Task
import monix.execution.Ack
import monix.reactive.{Observable, Observer}
import monix.reactive.subjects.{PublishSubject, ReplaySubject}
import org.scalajs.dom
import org.scalajs.dom.window
import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.dom.helpers.EmitterBuilder
import rx._
import wust.api.{ApiEvent, AuthUser}
import wust.css.{CommonStyles, Styles, ZIndex}
import wust.graph._
import wust.ids._
import wust.sdk.NodeColor
import wust.util._
import wust.webApp.dragdrop.DragItem.DisableDrag
import wust.webApp.dragdrop.{DragItem, DragPayload, DragTarget}
import wust.webApp.outwatchHelpers._
import wust.webApp.state._
import wust.webApp.views.Components._
import wust.webApp.views.Elements._
import wust.webApp.views.SharedViewElements._

import scala.collection.breakOut
import scala.concurrent.Future
import scala.scalajs.js

object InputRow {

  def apply(
    state: GlobalState,
    submitAction: String => Unit,
    fileUploadHandler: Option[Var[Option[AWS.UploadableFile]]] = None,
    blurAction: Option[String => Unit] = None,
    scrollHandler:Option[ScrollBottomHandler] = None,
    triggerFocus:Observable[Unit] = Observable.empty,
    autoFocus:Boolean = false,
    placeholder: Placeholder = Placeholder.empty,
    preFillByShareApi:Boolean = false,
    submitIcon:VDomModifier = freeRegular.faPaperPlane,
    showSubmitIcon: Boolean = BrowserDetect.isMobile,
    textAreaModifiers:VDomModifier = VDomModifier.empty,
    allowEmptyString: Boolean = false,
    enforceUserName: Boolean = false,
    showMarkdownHelp: Boolean = false
  )(implicit ctx: Ctx.Owner): VNode = {
    val initialValue = if(preFillByShareApi) Rx {
      state.urlConfig().shareOptions.fold("") { share =>
        val elements = List(share.title, share.text, share.url).filter(_.nonEmpty)
        elements.mkString(" - ")
      }
    }.toObservable.dropWhile(_.isEmpty) else Observable.empty // drop starting sequence of empty values. only interested once share api defined.

    val autoResizer = new TextAreaAutoResizer

    val heightOptions = VDomModifier(
      rows := 1,
      resize := "none",
      minHeight := "42px",
      autoResizer.modifiers
    )

    var currentTextArea: dom.html.TextArea = null
    def handleInput(str: String): Unit = if (allowEmptyString || str.trim.nonEmpty || fileUploadHandler.exists(_.now.isDefined)) {
      def handle() = {
        submitAction(str)
        if (preFillByShareApi && state.urlConfig.now.shareOptions.isDefined) {
          state.urlConfig.update(_.copy(shareOptions = None))
        }
        if(BrowserDetect.isMobile) currentTextArea.focus() // re-gain focus on mobile. Focus gets lost and closes the on-screen keyboard after pressing the button.
      }
      if (enforceUserName && !state.askedForUnregisteredUserName.now) {
        state.askedForUnregisteredUserName() = true
        state.user.now match {
          case user: AuthUser.Implicit if user.name.isEmpty =>
            val sink = state.eventProcessor.changes.redirectMapMaybe[String] { str =>
              val userNode = user.toNode
              userNode.data.updateName(str).map(data => GraphChanges.addNode(userNode.copy(data = data)))
            }
            state.uiModalConfig.onNext(Ownable(implicit ctx => newNamePromptModalConfig(state, sink, "Give yourself a name, so others can recognize you.", placeholder = Placeholder(Components.implicitUserName), onClose = () => { handle(); true })))
          case _ => handle()
        }
      } else {
        handle()
      }
    }

    val initialValueAndSubmitOptions = {
      if (BrowserDetect.isMobile) {
        value <-- initialValue
      } else {
        valueWithEnterWithInitial(initialValue) foreach handleInput _
      }
    }

    val placeholderString = if(BrowserDetect.isMobile || state.screenSize.now == ScreenSize.Small) placeholder.short else placeholder.long

    val immediatelyFocus = {
      autoFocus.ifTrue(
        onDomMount.asHtml --> inNextAnimationFrame(_.focus())
      )
    }

    val pageScrollFixForMobileKeyboard = BrowserDetect.isMobile.ifTrue(VDomModifier(
      scrollHandler.map { scrollHandler =>
        VDomModifier(
          onFocus foreach {
            // when mobile keyboard opens, it may scroll up.
            // so we scroll down again.
            if(scrollHandler.isScrolledToBottomNow) {
              window.setTimeout(() => scrollHandler.scrollToBottomInAnimationFrame(), 500)
              // again for slower phones...
              window.setTimeout(() => scrollHandler.scrollToBottomInAnimationFrame(), 2000)
              ()
            }
          },
          eventProp("touchstart") foreach {
            // if field is already focused, but keyboard is closed:
            // we do not know if the keyboard is opened right now,
            // but we can detect if it was opened: by screen-height changes
            if(scrollHandler.isScrolledToBottomNow) {
              val screenHeight = window.screen.availHeight
              window.setTimeout({ () =>
                val keyboardWasOpened = screenHeight > window.screen.availHeight
                if(keyboardWasOpened) scrollHandler.scrollToBottomInAnimationFrame()
              }, 500)
              // and again for slower phones...
              window.setTimeout({ () =>
                val keyboardWasOpened = screenHeight > window.screen.availHeight
                if(keyboardWasOpened) scrollHandler.scrollToBottomInAnimationFrame()
              }, 2000)
              ()
            }
          }
        )
      }
    ))

    val submitButton = VDomModifier.ifTrue(showSubmitIcon)(
      div( // clickable box around circular button
        padding := "3px",
        button(
          margin := "0px",
          Styles.flexStatic,
          cls := "ui circular icon button",
          submitIcon,
          fontSize := "1.1rem",
          backgroundColor := "#545454",
          color := "white",
        ),
        onClick.stopPropagation foreach {
          val str = currentTextArea.value
          handleInput(str)
          currentTextArea.value = ""
          autoResizer.trigger()
        },
      )
    )

    div(
      emitter(triggerFocus).foreach { currentTextArea.focus() },
      Styles.flex,

      // show textarea above of tags-button on right hand side
      zIndex := ZIndex.overlayMiddle + 1,

      alignItems.center,
      fileUploadHandler.map(uploadField(state, _).apply(flex := "1")),
      div(
        margin := "3px",
        BrowserDetect.isMobile.ifTrue[VDomModifier](marginRight := "0"),
        width := "100%",
        cls := "ui form",

        VDomModifier.ifTrue(showMarkdownHelp)(
          position.relative,
          a(
            position.absolute,
            right := "4px",
            top := "4px",
            float.right,
            freeSolid.faQuestion,
            Elements.safeTargetBlank,
            UI.tooltip("left center") := "Use Markdown to format your text. Click for more details.",
            href := "https://www.markdownguide.org/basic-syntax/"
          ),
        ),

        textArea(
          onDomUpdate.foreach(autoResizer.trigger()),
          maxHeight := "400px",
          cls := "field",
          initialValueAndSubmitOptions,
          heightOptions,
          dsl.placeholder := placeholderString,

          immediatelyFocus,
          blurAction.map(onBlur.value foreach _),
          pageScrollFixForMobileKeyboard,
          onDomMount foreach { e => currentTextArea = e.asInstanceOf[dom.html.TextArea] },
          textAreaModifiers,

        )
      ),
      submitButton
    )
  }
}