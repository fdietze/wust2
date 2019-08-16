package wust.webApp.views

import acyclic.file
import wust.facades.crisp._
import wust.facades.googleanalytics.Analytics
import wust.webApp.DeployedOnly
import scala.scalajs.js
import scala.util.Try
import wust.webApp.{ DevOnly, DebugOnly }
import fontAwesome._
import monix.reactive.Observable
import outwatch.dom._
import outwatch.dom.dsl._
import rx._
import wust.css.{ Styles, ZIndex }
import wust.ids.Feature
import wust.sdk.Colors
import wust.webApp.state.{ FeatureDetails, FeatureState, GlobalState, ScreenSize }
import wust.webUtil.Elements
import wust.webUtil.outwatchHelpers._
import wust.webUtil.UI

object FeatureExplorer {
  //TODO: rating for completed features: "I liked it", "too complicated", "not useful"
  def apply(implicit ctx: Ctx.Owner) = {
    val showPopup = Var(true)

    val stats = div(
      div(
        textAlign.center,
        span(
          fontSize := "80px",
          lineHeight := "50px",
          progress
        ),
        span (
          "%",
          fontSize := "40px"
        ),
        scoreBadge(
          fontSize := "20px",
          marginLeft := "15px",
          padding := "6px",
          Rx{ FeatureState.firstTimeUsed().size }
        ),
      ),
      div(
        textAlign.center,
        "Explored Features",
      ),
    )

    def helpButton(feature: Feature) = span(
      freeSolid.faQuestionCircle,
      cursor.pointer,
      color := "#60758a",
      UI.popup("bottom right") := ("Is this feature unclear?"),
      onClick.stopPropagation.foreach { _ =>
        Try{
          DeployedOnly { FeedbackForm.initCrisp }
          crisp.push(js.Array("do", "chat:show"))
          crisp.push(js.Array("do", "chat:open"))
        }
        Analytics.sendEvent("unclear-feature", feature.toString)
      }
    )

    // TODO: show category labels next to suggestions and recents
    val tryNextList = div(
      Rx{
        VDomModifier.ifTrue(FeatureState.next().nonEmpty)(
          "Things to try next:",
          FeatureState.next().map { feature =>
            val details = FeatureDetails(feature)
            val showDescription = Var(false)
            div(
              div(
                helpButton(feature)(float.right),
                details.title, fontWeight.bold, fontSize := "1em",
              ),
              onClick.stopPropagation.foreach { showDescription() = !showDescription.now },
              cursor.pointer,
              Rx{ VDomModifier.ifTrue(showDescription())(div(details.description)) },
              backgroundColor := "#dbf5ff",
              padding := "8px",
              marginBottom := "3px",
              borderRadius := "4px",
              Styles.wordWrap,
            )
          }
        )
      }
    )

    val recentFirstTimeList = div(
      "Recent:",
      Rx{
        FeatureState.recentFirstTimeUsed().take(5).map { feature =>
          val details = FeatureDetails(feature)
          div(
            div(
              scoreBadge("+1", float.right, marginLeft := "0.5em"),
              span(
                details.title,
                opacity := 0.8,
                Styles.wordWrap,
                fontWeight.bold,
              ),
            ),
            padding := "8px",
            marginBottom := "3px",
          )
        }
      }
    )

    def recentList = div(
      "Recent:",
      Rx{
        FeatureState.recentlyUsed().map { feature =>
          val details = FeatureDetails(feature)
          div(
            Styles.flex,
            verticalAlign.middle,
            alignItems.flexStart,
            div(
              details.title,
              fontSize := "16px",
              fontWeight.bold,
              opacity := 0.8,
              marginRight := "10px",
            ),
            padding := "8px",
            marginBottom := "3px",
          )
        }
      }
    )

    div(
      keyed,
      cls := "feature-explorer",
      stats(marginTop := "5px"),
      tryNextList(marginTop := "30px"),
      DebugOnly(Rx{ recentList(marginTop := "30px") }),
      Rx{
        VDomModifier.ifTrue(FeatureState.recentFirstTimeUsed().nonEmpty)(
          recentFirstTimeList(marginTop := "30px")
        )
      },

      onClick.stopPropagation.discard, // prevents closing feedback form by global click
    )
  }

  val progress: Rx[String] = Rx {
    val total = Feature.allWithoutSecrets.length
    val used = (FeatureState.firstTimeUsed() -- Feature.secrets).size
    val ratio = (used.toDouble / total.toDouble).min(1.0)
    f"${ratio * 100}%0.0f"
  }

  val progressBar = div(
    Rx{ VDomModifier.ifTrue(FeatureState.firstTimeUsed().isEmpty)(visibility.hidden) },
    backgroundColor := "rgba(0,0,0,0.2)",
    div(
      width <-- progress.map(p => s"$p%"),
      transition := "width 1s",
      backgroundColor := "black",
      height := "2px"
    )
  )

  val scoreBadge = span(
    color.white,
    backgroundColor := "#5FBA7D",
    borderRadius := "4px",
    padding := "2px 5px",
    display.inlineBlock,
  )

  val usedFeatureAnimation = {
    import outwatch.dom.dsl.styles.extra._

    import scala.concurrent.duration._
    val shake = 0.2
    div(
      scoreBadge("+1"),
      // visibility.hidden,
      transition := s"visibility 0s, transform ${shake}s",
      transform := "rotate(0deg)",
      Observable(visibility.hidden) ++ FeatureState.usedNewFeatureTrigger.switchMap{ _ =>
        Observable(visibility.visible) ++
          Observable(transform := "rotate(20deg)") ++
          Observable(transform := "rotate(-20deg)").delayExecution(shake seconds) ++
          Observable(transform := "rotate(0deg)").delayExecution(shake seconds) ++
          Observable(visibility.hidden).delayExecution(5 seconds)
      }
    )
  }

  val toggleButton = {
    span(
      span(
        "Explored Features: ",
        b(progress, "% "),
      ),
      progressBar(
        marginLeft := "20px",
        marginRight := "25px"
      ),
      marginBottom := "10px",

      position.relative,
      paddingRight := "30px",
      usedFeatureAnimation(
        position.absolute,
        top := "5px",
        right := "0",
      )
    )
  }

}
