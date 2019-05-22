package wust.webApp.views

import wust.sdk.{BaseColors, NodeColor}
import wust.css.{Styles, ZIndex}
import wust.webApp.outwatchHelpers._
import wust.ids.{Cuid, NodeId}
import outwatch.dom._
import outwatch.dom.dsl._
import rx.Ctx
import wust.webApp.state.GlobalState

object AvatarView {
  def apply(state: GlobalState)(implicit ctx: Ctx.Owner) = {
    val n = 100
    val size = 40
    div(
      width := "100%",
      overflow.auto,

      backgroundColor := "#a8a8a8",
      div(
        padding := "10px",
        // display := "grid",
        // style("grid-gap") := "10px",
        // style("grid-template-columns") := s"repeat(auto-fill, minmax(${size}px, 1fr))",
        (5 to 5).map { res =>
          div(
            Styles.flex,
            flexWrap.wrap,
            // width := s"${n * (size + 10)}px",
            List.tabulate(n)(
              i =>
                Avatar.verticalMirror(i, res)(
                  width := s"${size}px",
                  height := s"${size}px",
                  padding := "4px",
                  borderRadius := "2px",
                  marginBottom := "10px",
                  marginRight := "10px",
                  backgroundColor := "rgb(255,255,255,0.9)",
                )
            )
          ),
        },
        (8 to 10).map { res =>
          div(
            Styles.flex,
            flexWrap.wrap,
            List.tabulate(n){
              i =>
                val nodeId = NodeId(Cuid(0,i))
                div(
                  width := s"${size}px",
                  height := s"${size}px",
                  padding := "4px",
                  borderRadius := "2px",
                  marginBottom := "10px",
                  marginRight := "10px",
                  Avatar.twoMirror(i, res)
                )
            }
          ),
        },
        // List.tabulate(n)(i => Avatar(i, 10, false)(width := s"${size}px", height := s"${size}px")  ),
        // div(),
        // List.tabulate(n)(i => Avatar(i, 11, false)(width := s"${size}px", height := s"${size}px")  ),
        // div(),
        // List.tabulate(n)(i => Avatar(i, 12, false)(width := s"${size}px", height := s"${size}px")  ),
        // div(),
        // List.tabulate(n)(i => Avatar(i, 13, true)(width := s"${size}px", height := s"${size}px")  ),
        // // div(),
        // List.tabulate(n)(i => Avatar(i, 25, true)(width := s"${size}px", height := s"${size}px")  ),
        {
          // to measure, disable the static rendering in Avatar
          val sw = new wust.util.time.StopWatch()
          sw.benchmark(200000)(Avatar.twoMirror(scala.util.Random.nextInt(), 10))
          sw.readMicros
        }
      )
    )
  }
}
