package wust.webApp.views

import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.dom.dsl.styles.extra._
import rx._
import wust.api._
import wust.css.Styles
import wust.graph._
import wust.ids._
import wust.util.time.StopWatch
import wust.webApp.outwatchHelpers._
import wust.webApp.state.GlobalState
import wust.graph.EdgeComponents._

import scala.collection.mutable
import scala.concurrent.duration.{span => _}
object DevView {

  def benchGraphLookup(state:GlobalState, n:Int) = {
    var i = 0
    var g = state.graph.now
    val stopWatch = new StopWatch
    stopWatch.measure {
      while(i < n) {
        g = g.copy(g.nodes, g.edges)
        g.lookup
        i += 1
      }
    }
    stopWatch.totalPassedTime /= n
    println("Graph lookup: " + (stopWatch.readMicros/1000.0) + "ms")
  }

  def button =
    outwatch.dom.dsl.button(fontSize := "14px", padding := "0px 5px", margin := "0px 1px")

  def devPeek(state: GlobalState, additions: Seq[VDomModifier] = Nil)(implicit ctx: Ctx.Owner) = {
    val show = Var(false)
    val activeDisplay = display <-- show.map(if (_) "block" else "none")
    val inactiveDisplay = display <-- show.map(if (_) "none" else "block")

    val baseDiv = div(
      position.fixed,
      top := "100px",
      right := "0",
      boxSizing.borderBox,
      padding := "5px",
      state.pageStyle.map { pageStyle =>
        Seq(
          backgroundColor := pageStyle.bgColor,
          border := s"1px solid ${pageStyle.borderColor}"
        )
      },
      cls := "shadow"
    )

    div(
      baseDiv(
        inactiveDisplay,
        "DevView",
        transform := "rotate(-90deg) translate(0,-100%)",
        transformOrigin := "100% 0",
        cursor.pointer,
        borderBottom := "none", //TODO: this is not recognized. outwatch-bug?
        onClick(true) --> show
      ),
      baseDiv(
        minWidth := "200px",
        activeDisplay,
        div("×", cursor.pointer, float.right, onClick(false) --> show),
        DevView(state, additions),
        borderRight := "none",
      )
    )
  }

  import scala.util.Random.{nextInt => rInt}
  def rWord = scala.util.Random.alphanumeric.take(3 + rInt(6)).mkString
  def rSentence = Array.fill(1 + rInt(5))(rWord).mkString(" ")

  val apiEvents = Var[List[ApiEvent]](Nil)

  //  def jsError(state: GlobalState)(implicit ctx: Ctx.Owner) = {
  //    span(
  //      Rx {
  //        (state.jsError() match {
  //          case Some(error) =>
  //            pre(
  //              position.fixed, right := 0, bottom := 50,
  //              border := "1px solid #FFD7D7", backgroundColor := "#FFF0F0", color := "#C41A16",
  //              width := "90%", margin := 10, padding := 10, whiteSpace := "pre-wrap",
  //              error
  //            )
  //          case None => span()
  //        }).render
  //      }
  //    )
  //  }

  def apply(state: GlobalState, additions: Seq[VDomModifier] = Nil)(implicit ctx: Ctx.Owner) = {
    span(
      div(
        Styles.flex,
        flexDirection.column,
//        Rx {
//          val users = List("a", "b", "c", "d", "e", "f", "g")
//          div(
//            "login: ",
//            users.map(u => button(u, onClick foreach {
//              Client.auth.register(u, u).call().filter(_ == false).foreach { _ =>
//                Client.auth.logout().call().foreach { _ =>
//                  Client.auth.login(u, u).call()
//                }
//              }
//            }))
//          ).render
//        },
        Rx {
          def addRandomPost(count: Int): Unit = {
            val newPosts =
              List.fill(count)(Node.MarkdownMessage(rSentence))
            val changes = GraphChanges.addNodesWithParents(newPosts, state.page.now.parentId)
            state.eventProcessor.changes.onNext(changes)
          }

          div(
            "create: ",
            button("1", onClick foreach { addRandomPost(1) }),
            button("10", onClick foreach { addRandomPost(10) }),
            button("100", onClick foreach { addRandomPost(100) })
          )
        },
        Rx {
          val posts = scala.util.Random.shuffle(state.graph().nodeIds.toSeq)

          def deletePost(ids: Seq[NodeId]): Unit = {
            ids.foreach { nodeId =>
              state.eventProcessor.changes
                .onNext(GraphChanges.delete(nodeId, state.page.now.parentId))
            }
          }

          div(
            "archive: ",
            button("1", onClick foreach { deletePost(posts.take(1)) }),
            button("10", onClick foreach { deletePost(posts.take(10)) }),
            button("100", onClick foreach { deletePost(posts.take(100)) })
          )
        },
        Rx {
          val posts = state.graph().nodeIds.toArray
          def randomConnection =
            Edge.LabeledProperty(posts(rInt(posts.length)), EdgeData.LabeledProperty(rWord), PropertyId(posts(rInt(posts.length))))

          def connect(_count: Int): Unit = {
            if (posts.length > 1) {
              val count = _count min ((posts.length * posts.length - 1) / 2)
              val selected = mutable.HashSet.empty[Edge]
              while (selected.size < count) {
                selected += randomConnection
              }

              state.eventProcessor.changes.onNext(GraphChanges(addEdges = selected.toSet))
            }
          }

          div(
            "connect: ",
            button("1", onClick foreach { connect(1) }),
            button("10", onClick foreach { connect(10) }),
            button("100", onClick foreach { connect(100) })
          )
        },
        Rx {
          val posts = state.graph().nodeIds.toArray
          def randomConnection = Edge.Child(ParentId(posts(rInt(posts.length))), ChildId(posts(rInt(posts.length))))

          def contain(count: Int): Unit = {
            state.eventProcessor.changes
              .onNext(GraphChanges(addEdges = Array.fill(count)(randomConnection).toSet))
          }

          div(
            "contain: ",
            button("1", onClick foreach { contain(1) }),
            button("10", onClick foreach { contain(10) }),
            button("100", onClick foreach { contain(100) })
          )
        },
        Rx {
          val connections = scala.util.Random.shuffle(state.graph().edges.toSeq)

          def disconnect(count: Int): Unit = {
            state.eventProcessor.changes
              .onNext(GraphChanges(delEdges = connections.take(count).toSet))
          }

          div(
            "disconnect: ",
            button("1", onClick foreach { disconnect(1) }),
            button("10", onClick foreach { disconnect(10) }),
            button("100", onClick foreach { disconnect(100) })
          )
        },
        additions,
        //        div(
        //          "Random Events:",
        //          br(),
        //          {
        //            import scalajs.js.timers._
        //            def graph = state.rawGraph.now
        //
        //            def randomNodeId: Option[NodeId] = if (graph.postsById.size > 0) Option((graph.nodeIds.toIndexedSeq) (rInt(graph.postsById.size))) else None
        //
        //            def randomConnection: Option[Connection] = if (graph.connections.size > 0) Option((graph.connections.toIndexedSeq) (rInt(graph.connections.size))) else None
        //
        //            def randomContainment: Option[Containment] = if (graph.containments.size > 0) Option((graph.containments.toIndexedSeq) (rInt(graph.containments.size))) else None
        //
        //            val numberOfConcurrentEvents = 3
        //            val events: Array[() => Option[GraphChanges]] = {
        //              val distribution: List[(Int, () => Option[GraphChanges])] = (
        //                (4, () => Option(GraphChanges(addPosts = Set(Post.newId(rStr(1 + rInt(20))))))) ::
        //                  (3, () => randomNodeId.map(p => GraphChanges(updatePosts = Set(Post(p, rStr(1 + rInt(20))))))) ::
        //                  (2, () => randomNodeId.map(p => GraphChanges(delPosts = Set(p)))) ::
        //                  (3, () => for (p1 <- randomNodeId; p2 <- randomNodeId) yield GraphChanges(addConnections = Set(Connection(p1, p2)))) ::
        //                  (2, () => randomConnection.map(c => GraphChanges(delConnections = Set(c)))) ::
        //                  (3, () => for (p1 <- randomNodeId; p2 <- randomNodeId) yield GraphChanges(addContainments = Set(Containment(p1, p2)))) ::
        //                  (2, () => randomContainment.map(c => GraphChanges(delContainments = Set(c)))) ::
        //                  Nil
        //                )
        //              distribution.flatMap { case (count, f) => List.fill(count)(f) }(breakOut)
        //            }
        //
        //            def randomEvent = events(rInt(events.size))()
        //
        //            def emitRandomEvent() {
        //              val changes = List.fill(numberOfConcurrentEvents)(randomEvent)
        //                .flatten
        //                .foldLeft(GraphChanges.empty)(_ merge _)
        //              state.persistence.addChanges(changes)
        //            }
        //
        //            var interval: Option[SetIntervalHandle] = None
        //            val intervals = (
        //              5.seconds ::
        //                2.seconds ::
        //                1.seconds ::
        //                0.5.seconds ::
        //                0.1.seconds ::
        //                Duration.Inf ::
        //                Nil
        //              )
        //            val prefix = "DevViewRandomEventTimer"
        //            for (i <- intervals) yield {
        //              val iid = s"$prefix$i"
        //              i match {
        //                case i: FiniteDuration =>
        //                  span(radio(name := prefix, id := iid), labelfor(iid)(s"${i.toMillis / 1000.0}s"), onclick := { () =>
        //                    interval.foreach(clearInterval)
        //                    interval = Option(setInterval(i)(emitRandomEvent))
        //                  })
        //                case _ =>
        //                  span(radio(name := prefix, id := iid, checked), labelfor(iid)(s"none"), onclick := { () =>
        //                    interval.foreach(clearInterval)
        //                    interval = None
        //                  })
        //              }
        //            }
        //          }
        //        ) // ,Rx {
        //   state.rawGraph().toSummaryString
        // },
//        pre(maxWidth := "400px", maxHeight := "300px", overflow.scroll, fontSize := "11px", Rx {
//          apiEvents().mkString("\n")
//          // pre(apiEvents().mkString("\n")).render
//        }), button("clear", onClick foreach {
//          apiEvents() = Nil
//        })
      ),
//      Rx {
//        (state.jsError() match {
//          case Some(error) =>
//            pre(
//              position.fixed, right := 0, bottom := 50,
//              border := "1px solid #FFD7D7", backgroundColor := "#FFF0F0", color := "#C41A16",
//              width := "90%", margin := 10, padding := 10, whiteSpace := "pre-wrap",
//              error
//            )
//          case None => span()
//        }).render
//      }
      div(
        "Benchmark Graph lookup:",
        Rx{s"Graph(${state.graph().nodes.size}, ${state.graph().edges.size})"},
        List(1,10,100, 1000, 10000, 100000).map(n => button(s"${n}x", onClick.foreach{benchGraphLookup(state, n)}))
      )

    )
  }
}
