package wust.webApp.views

import cats.effect.IO
import fontAwesome.freeSolid
import monix.reactive.Observable
import monix.reactive.subjects.PublishSubject
import org.scalajs.dom
import org.scalajs.dom.experimental._
import outwatch.dom._
import outwatch.dom.dsl._
import outwatch.dom.helpers.EmitterBuilder
import rx.{Ctx, Rx, Var}
import wust.css.Styles
import wust.external.{trello, wunderlist, meistertask}
import wust.graph.{Edge, GraphChanges, Node, Page}
import wust.ids._
import wust.util.StringOps
import wust.util.collection._
import wust.webApp.jsdom.FileReaderOps
import wust.webApp.outwatchHelpers._
import wust.webApp.state.GlobalState
import wust.webApp.{Icons, Ownable}

import scala.collection.{breakOut, mutable}
import scala.scalajs.js.JSON
import scala.util.Try
import scala.util.control.NonFatal

// A prompt to import from other platforms/files

object Importing {
  //TODO: instruction how to export each source

  sealed trait Input
  object Input {
    case class FromFile(description: VDomModifier, acceptType: Option[String]) extends Input
    case class FromRemoteFile(description: VDomModifier, url: String => Option[String]) extends Input
    case class FromText(description: VDomModifier) extends Input
  }

  object Parser {
    type Result = IO[Either[String, GraphChanges.Import]]
    type Function = String => Parser.Result
    type Provider = Rx[Option[Function]]
  }

  case class Source(icon: VNode, title: String, description: String, inputs: List[Input], parser: Parser.Provider, form: VDomModifier)
  object Source {
    def apply(icon: VNode, title: String, description: String, inputs: List[Input], parser: Parser.Function): Source = Source(icon, title, description, inputs, Var(Some(parser)), VDomModifier.empty)

    def withForm[T](icon: VNode, title: String, description: String, inputs: List[Input], parser: Rx[Option[T]] => Parser.Provider, form: Var[Option[T]] => VDomModifier): Source = {
      val formVar = Var[Option[T]](None)
      Source(icon, title, description, inputs, parser(formVar), form(formVar))
    }

    def all(implicit ctx: Ctx.Owner): List[Source] = List(

      Source(
        icon = img(src := "/trello.svg"),
        title = "Trello",
        description = "Trello Board (JSON)",
        inputs = List(
          // Input.FromText("Paste the exported JSON from Trello here."),
          Input.FromFile("Upload the exported JSON file from Trello.", Some("application/json")),
          Input.FromRemoteFile("Paste the url of a public Trello Board.", { url =>
            Try(new URL(url)).toOption.collect {
              case uri if uri.host == "trello.com" && uri.pathname.startsWith("/b/") => uri.toString + ".json"
            }
          })
        ),
        parser = str => IO {
          trello.Trello.decodeJson(str).map(trello.Trello.translate(_))
        }
      ),

      Source(
        icon = img(src := "/trello.svg"),
        title = "Wunderlist",
        description = "Wunderlist Export (JSON)",
        inputs = List(
          // Input.FromText("Paste the exported JSON from Wunderlist here."),
          Input.FromFile("Upload the exported JSON file from Wunderlist.", Some("application/json")),
        ),
        parser = str => IO {
          wunderlist.Wunderlist.decodeJson(str).map(wunderlist.Wunderlist.translate(_))
        }
      ),

      Source.withForm[String](
        icon = img(src := "/meistertask.svg"),
        title = "MeisterTask",
        description = "MeisterTask Project (CSV)",
        inputs = List(
          // Input.FromText("Paste the exported CSV from MeisterTask here."),
          Input.FromFile("Upload the exported CSV file from MeisterTask.", Some("application/csv")),
        ),
        parser = _.map(_.map[String => Parser.Result] { projectName => str => IO {
          meistertask.MeisterTask.decodeCSV(projectName = projectName, csv = str).map(meistertask.MeisterTask.translate(_))
        }}),

        form = { formVar =>
          div(
            Styles.flex,
            alignItems.center,
            cls := "ui mini form",

            b("Project Name:", marginRight := "10px", Styles.flexStatic),
            EditableContent.editor[NonEmptyString](EditableContent.Config(
              submitMode = EditableContent.SubmitMode.OnInput,
              modifier = placeholder := "Project Name"
            )).editValueOption.map(_.map(_.string)) --> formVar
          )
        }
      ),

      Source(
        icon = Icons.tasks,
        title = "Tasklist",
        description = "Multiline Tasklist",
        inputs = List(
          Input.FromText("Enter a multiline tasklist, where each tasks is separated with a newline."),
          Input.FromFile("Upload text file with a multiline tasklist, where each task is separated with a newline.", Some("application/text")),
        ),
        parser = str => IO {
          val nodes = str.lines.map[Node] { line => Node.MarkdownTask(line.trim) }.toIterable

          Right(GraphChanges.Import(
            GraphChanges(addNodes = nodes.to[Array]),
            topLevelNodeIds = nodes.to[Array].reverseMap(_.id),
            focusNodeId = None
          ))
        },
      )
    )
  }

  case class Importer(title: String, description: VDomModifier, result: Rx[Option[Parser.Result]], edit: VDomModifier)
  object Importer {

    private def combineParser(current: Rx[Option[IO[Either[String, String]]]], parser: Parser.Provider)(implicit ctx: Ctx.Owner): Rx[Option[Parser.Result]] = Rx {
      for {
        current <- current()
        parse <- parser()
      } yield current.flatMap {
        case Right(value) => parse(value)
        case Left(err) => IO.pure(Left(err))
      }
    }

    private def stringImporter(title: String, description: VDomModifier, parser: Parser.Provider)(form: Var[Option[IO[Either[String, String]]]] => VDomModifier)(implicit ctx: Ctx.Owner) = {
      val current = Var[Option[IO[Either[String, String]]]](None)
      Importer(
        title,
        description,
        combineParser(current, parser),
        form(current)
      )
    }

    def fromSource(source: Source)(implicit ctx: Ctx.Owner): List[Importer] = source.inputs.map {

     case Input.FromText(description) =>
       stringImporter("Insert text", description, source.parser) { current =>
         textArea(
           width := "100%",
           placeholder := source.description,
           onInput.value.map(s => Some(IO.pure(Right(s)))) --> current,
           rows := 15,
         )
       }

     case Input.FromFile(description, acceptType) =>
       stringImporter("Upload file", description, source.parser) { current =>
         EditableContent.editor[dom.File](EditableContent.Config(
           submitMode = EditableContent.SubmitMode.OnChange,
           modifier = acceptType.map(accept := _)
         )).editValueOption.map(_.map(file => IO.fromFuture(IO(FileReaderOps.readAsText(file))).map(Right(_)))) --> current
       }

     case Input.FromRemoteFile(description, getUrl) =>
       stringImporter("Import from Url", description, source.parser) { current =>
         EditableContent.editor[NonEmptyString](EditableContent.Config(
           submitMode = EditableContent.SubmitMode.OnInput,
           modifier = placeholder := "Url to public Trello board"
         )).mapResult(_.apply(cls := "ui mini form")).editValueOption.map(_.map { value =>
           getUrl(value.string) match {
             case Some(url) => IO.fromFuture(IO(
               dom.experimental.Fetch.fetch(url).toFuture
                 .flatMap { response => response.json().toFuture.map { json => Right(JSON.stringify(json))} }
                 .recover { case NonFatal(t) =>
                   Left(s"Cannot connect to URL '$url'")
                 }
             ))
             case None => IO.pure(Left("Invalid URL, expected a Trello Board URL."))
           }
         }) --> current
       }
    }
  }

  private val separatorColor = "rgba(0, 0, 0, 0.1)"

  private def importerForm(importer: Importer)(implicit ctx: Ctx.Owner): EmitterBuilder[Parser.Result, VDomModifier] = EmitterBuilder.ofModifier { sink =>
    div(
      padding := "15px",
      Styles.growFull,
      Styles.flex,
      flexDirection.column,
      justifyContent.spaceBetween,
      alignItems.center,

      div(
        div(importer.title + ":"),
        div(color.gray, fontSize.xSmall, importer.description, padding := "5px 0px"),
        div(importer.edit, padding := "5px")
      ),

      button(
        cls := "ui primary button",
        dsl.cursor.pointer,
        "Import",
        disabled <-- importer.result.map(_.isEmpty),
        onClick.mapTo(importer.result.now).collect { case Some(r) => r } --> sink
      )
    )
  }

  private def importerSeparator = {
    val line = div(backgroundColor := separatorColor, flexGrow := 1, width := "2px")
    div(
      width := "20px",
      Styles.flex,
      flexDirection.column,
      alignItems.center,
      line,
      div("OR", Styles.flexStatic, padding := "5px 0px"),
      line
    )
  }

  private def selectSource(sources: List[Source])(implicit ctx: Ctx.Owner): EmitterBuilder[Option[Source], VDomModifier] = EmitterBuilder.ofModifier { sink =>
    div(
      Styles.growFull,
      Styles.flex,
      flexDirection.column,
      alignItems.center,
      padding := "10px",

      div(
        Styles.flex,
        alignItems.center,
        padding := "20px",

        h3("What do you want to import?"),
      ),

      div(
        Styles.flex,
        alignItems.center,
        flexWrap.wrap,

        sources.map { source =>
          button(
            margin := "10px",
            cls := "ui button",
            Styles.flex,
            flexDirection.column,
            alignItems.center,
            source.icon(height := "2em"),
            div(source.description, paddingTop := "3px"),
            cursor.pointer,
            onClick.stopPropagation(Some(source)) --> sink
          )
        }
      )
    )
  }

  private def renderSource(state: GlobalState, focusedId: NodeId, source: Source)(implicit ctx: Ctx.Owner): EmitterBuilder[Option[Source], VDomModifier] = EmitterBuilder.ofModifier { sink =>
    val importers = Importer.fromSource(source)
    div(
      Styles.flex,
      flexDirection.column,
      alignItems.center,

      div(padding := "10px", alignSelf.flexStart, freeSolid.faArrowLeft, cursor.pointer, onClick(None) --> sink),

      source.form,

      div(
        Styles.growFull,
        Styles.flex,
        justifyContent.center,
        padding := "5px 0",
        height := "400px",
        overflow.auto,

        importers.foldLeft(Seq.empty[VDomModifier]) { (prev, importer) =>
          val field = importerForm(importer).transform(_.flatMap { result =>
            Observable.fromIO(result).flatMap {
              case Right(importChanges) =>
                val addToParentChanges =
                  if (importChanges.topLevelNodeIds.isEmpty) GraphChanges.empty
                  else if (importChanges.topLevelNodeIds.size == 1) GraphChanges.addToParent(importChanges.topLevelNodeIds.map(ChildId(_)), ParentId(focusedId))
                  else {
                    //TODO: fix ordering...
                    val g = state.graph.now
                    val focusedIdx = g.idToIdxOrThrow(focusedId)
                    val children = state.graph.now.childEdgeIdx(focusedIdx)
                    val minOrderingNum: BigDecimal = if (children.isEmpty) BigDecimal(EpochMilli.now) else children.minBy[BigDecimal](edgeIdx => g.edges(edgeIdx).as[Edge.Child].data.ordering) - 1
                    GraphChanges(
                      addEdges = importChanges.topLevelNodeIds.mapWithIndex { (idx, nodeId) =>
                        Edge.Child(ParentId(focusedId), EdgeData.Child(ordering = minOrderingNum - idx), ChildId(nodeId))
                      }(breakOut)
                    )
                  }

                val changes = importChanges.changes merge addToParentChanges
                UI.toast("Successfully imported Project")

                //TODO: do not sideeffect with state changes here...
                importChanges.focusNodeId.foreach { focusNodeId =>
                  state.urlConfig.update(_.focus(Page(focusNodeId), needsGet = false))
                }
                state.uiModalClose.onNext(())

                Observable(changes)
              case Left(error)     =>
                UI.toast(s"${StringOps.trimToMaxLength(error, 200)}", title = "Failed to import Project", level = UI.ToastLevel.Error)
                Observable.empty
            }
          }) --> state.eventProcessor.changes

          if(prev.isEmpty) Seq(field) else prev ++ Seq(importerSeparator, field) // TODO: proper with css?
        }
      ),
    )
  }

  // returns the modal config for rendering a modal for making an import
  def modalConfig(state: GlobalState, focusedId: NodeId)(implicit ctx: Ctx.Owner): UI.ModalConfig = {
    val selectedSource = Var[Option[Source]](None)
    val allSources = Source.all

    val header: VDomModifier = Rx {
      state.rawGraph().nodesById(focusedId).map { node =>
        val header = selectedSource() match {
          case Some(source) => VDomModifier(
            span(
              Styles.flex,
              alignItems.center,
              padding := "6px",
              backgroundColor := "white",
              source.icon(height := "1em", Styles.flexStatic),
              marginRight := "10px"
            ),
            span(s"Import from ${source.description}", marginRight := "10px")
          )
          case None => span("Import your data", marginRight := "10px")
        }

        UI.ModalConfig.defaultHeader(
          state,
          node,
          modalHeader = div(
            Styles.flex,
            flexWrap.wrap,
            alignItems.center,
            header,
            Components.experimentalSign(color = "white").apply(fontSize.xSmall)
          ),
          icon = Icons.`import`
        )
      }
    }

    val description: VDomModifier = div(
      selectedSource.map {
        case Some(source) => renderSource(state, focusedId, source) --> selectedSource
        case None => selectSource(allSources) --> selectedSource
      }
    )

    UI.ModalConfig(header = header, description = description, contentModifier = VDomModifier(styleAttr := "padding : 0px !important")) // overwrite padding of modal
  }

  // a settings button for importing that opens the modal on click.
  def settingsItem(state: GlobalState, focusedId: NodeId)(implicit ctx: Ctx.Owner): VNode = {
    div(
      cls := "item",
      Elements.icon(Icons.`import`),
      span("Import"),

      dsl.cursor.pointer,
      onClick(Ownable(implicit ctx => modalConfig(state, focusedId))) --> state.uiModalConfig
    )
  }
}
