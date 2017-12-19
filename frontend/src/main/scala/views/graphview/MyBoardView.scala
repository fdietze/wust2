package wust.frontend.views

import rx._
import wust.frontend._

import org.scalajs.dom.{window, document, console}
import wust.frontend.Color._


/// Reusable components that do not depend on a state
trait MyBoardViewComponents {
	import outwatch.dom._


	/// Renders a todo entry in the list view
	def boardEntry(todo: String,
								 remEntry : outwatch.Sink[String],
								 dragStartEvents : outwatch.Sink[String]) = {
		div(
			draggable := true,
			dragstart(todo) --> dragStartEvents,
			span(todo),
			button(click(todo) --> remEntry, "X")
		)
	}


	/// returns a clickable entry that spawns an input field to enter a new text
	def inputBoardEntry(newEntries: outwatch.Sink[String]) = {
		val clickedEvents = createBoolHandler()
		val showNewEntryMessageEvents = clickedEvents.map(ev => {
															  console.log(s"Click Event: $ev")
															  ev
														  })
		val showInputEvents = clickedEvents.map(!_).startWith(true)
		val inputKeyUpEvents = createHandler[org.scalajs.dom.KeyboardEvent]()
		val newEntryEvents = inputKeyUpEvents.map(keyEvent => {
													  val k = keyEvent.keyCode.toInt
													  console.log(s"Key pressed: $k")
													  Seq(
														  org.scalajs.dom.ext.KeyCode.Enter
													  ).contains(k)
												  }).filter(_ == true)
		val inputEvents = createStringHandler()
		val sentTextEvents = newEntryEvents.combineLatestWith(inputEvents)((_, text) => text)
		newEntries <-- sentTextEvents
		div(
			// visible while no input visible
			div(
				"Add new entry...",
				click(true) --> clickedEvents,
				hidden <-- showNewEntryMessageEvents
			),
			// visible only after click
			div(
				input(
					inputString --> inputEvents,
					keyup --> inputKeyUpEvents
				),
				hidden <-- showInputEvents
			))
	}


	/// Displays a board with vertically aligned entries
	def entryBoardComponent(title : String,
										  entries : rxscalajs.Observable[Seq[String]],
										  newEntries : outwatch.Sink[String],
										  remEntries : outwatch.Sink[String],
										  entryDragStartEvents : outwatch.Sink[String],
										  entryDropEvents : outwatch.Sink[String]) = {
		// val entries = store.map(_.todos.map(boardEntry) :+ inputBoardEntry())
		def buildBoardEntry(text : String) = {
			boardEntry(text, remEntries, entryDragStartEvents)
		}
		val entriesWrapped = entries.map(_.map(buildBoardEntry(_)) :+ inputBoardEntry(newEntries)).map(l => l.map(li(_)))
		val dragOverEvents = createHandler[org.scalajs.dom.DragEvent]()
		dragOverEvents.subscribe(e => {
									 // console.log("dragOverEvent")
									 // TODO: what does this actually do?
									 e.preventDefault()
									 e.dataTransfer.dropEffect = "move"
						   })
		val dropEvents = createHandler[org.scalajs.dom.DragEvent]()
		entryDropEvents <-- dropEvents.map(_ => title)
		div(
			h2(title),
			ul(
				dragover --> dragOverEvents,
				drop --> dropEvents,
				children <-- entriesWrapped),
		)
	}


	/// Aligns all nodes horizontally via css float left
	def horizontalLayout(nodes: VNode*) = {
		def floatLeftWrapper(nodes: VNode*) = {
			nodes.map(x => div(
						  outwatch.dom.Attributes.style := "float: left",
						  x))
		}

		val wrappedNodes = floatLeftWrapper(nodes:_*) :+
			div(
				outwatch.dom.Attributes.style := "clear: both"
			)
		div(
			wrappedNodes:_*
		)
	}


}

object MyBoardView extends MyBoardViewComponents {
	import outwatch.util.Store

	// - Actions on the view state -
	sealed trait Action
	case class AddToBoard(board: String, text: String) extends Action
	case class RemFromBoard(board: String, text: String) extends Action
	case class SetDragSource(board: String, text: String) extends Action
	case class SetDragDest(board: String) extends Action

	/// State used within this view
	case class State(text: String,
					 // TODO: We need a mapping from context (e.g. "Work")
					 //       -> board (e.g. "In-Progress") -> entry (e.g. "Do Stuff")
					 entryMap : Map[String, Seq[String]],
					 dragSource : Option[(String, String)] = None,
					 dragDest : Option[(String, String)] = None)
	val initialState = State("",
							 Map("Todo" -> Seq("Create Stuff"),
								 "In-Progress" -> Seq("Create More Stuff", "Do Things"),
								 "Done" -> Seq.empty))
	val store = Store(initialState, actionHandler)

	/// Handler for actions sent to the store which update it
	private[this] def actionHandler(state: State, action: Action) : State = action match {
		case AddToBoard(board, text) => state.copy(
			entryMap = state.entryMap + (board -> (state.entryMap.getOrElse(board, Seq.empty) ++ Seq(text)))
		)
		case RemFromBoard(board, text) => state.copy(
			entryMap = state.entryMap + (board -> (state.entryMap.getOrElse(board, Seq.empty).filter(_!=text)))
		)
		case SetDragSource(board, text) => {
			console.log(s"Setting dragSource to: $board $text")
			state.copy(dragSource = Some((board, text)))
		}
		case SetDragDest(board) => {
			console.log(s"Setting dragDest to: $board")
			if(board == state.dragSource.get._1)
				state
			else {
				actionHandler(actionHandler(state, AddToBoard(board, state.dragSource.get._2)),
							  RemFromBoard(state.dragSource.get._1, state.dragSource.get._2))
			}
		}
	}


	/// Main method invoked to render this view
	def apply(state: GlobalState)(implicit ctx: Ctx.Owner) = {
		import state.persistence

		// we return a raw element, because that is what the main view can handle
		val elem = document.createElement("div")
		import snabbdom._
		patch(elem, render().asProxy)
		elem
	}


	import outwatch.dom._
	/// construct an entryBoardComponent that is connected to the store
	private[this] def buildConnectedBoardComponent(name : String) = {
		val newEntries = createStringHandler()
		val remEntries = createStringHandler()
		val entryDragStartEvents = createHandler[String]()
		val entryDropEvents = createHandler[String]()

		// - connect outgoing streams to store via actions -
		store.sink <-- newEntries.map(AddToBoard(name, _))
		store.sink <-- remEntries.map(RemFromBoard(name, _))
		store.sink <-- entryDragStartEvents.map(SetDragSource(name, _))
		store.sink <-- entryDropEvents.map(_ => SetDragDest(name))

		entryBoardComponent(name,
							store.map(_.entryMap.getOrElse(name, Seq.empty)).startWith(Seq.empty),
							newEntries,
							remEntries,
							entryDragStartEvents,
							entryDropEvents)
	}


	/// Constructs a view with multiple horizontally aligned list views
	private[this] def kanbanBoard(boards : Seq[String],
								  maybeTitle : Option[String] = None) = {
		val builtBoards = boards.map(buildConnectedBoardComponent(_))

		div(
			h3(maybeTitle match {
					case Some(title) => title
					case None => ""
				}
			),
			horizontalLayout(
				builtBoards:_*
			)
		)
	}


	// - actual render template & logic -
	private[this] def render() = {
		div(
			h1("Outwatch based kanban"),
			kanbanBoard(maybeTitle = Some("Work"), boards = Seq("Todo", "In-Progress", "Done")),
			kanbanBoard(maybeTitle = Some("Finance"), boards = Seq("Unchecked", "Checked")),
		)

	}
}
