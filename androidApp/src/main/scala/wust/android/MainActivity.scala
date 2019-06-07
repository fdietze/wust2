package space.woost

import java.nio.ByteBuffer

object Id extends IdGenerator(start = 1000)

class MainActivity extends Activity with Contexts[Activity] {


  val token = FirebaseInstanceId.getInstance().getToken()
  println(s"got initial token: $token")


  def cuid = scala.util.Random.alphanumeric.take(36).mkString

  val con = new OkHttpWebsocketConnection[ByteBuffer]
  val wustClient = new WustClientFactory(WsClient.fromConnection[ByteBuffer, ApiEvent, ApiError]("wss://core.staging.woost.space/ws", con, WustClient.config, new sloth.LogHandler[Future]))
  val client = wustClient.sendWith(SendType.WhenConnected, 30 seconds)

  val assumedLogin = UserId(cuid)
  wustClient.observable.connected.foreach(_ => client.auth.assumeLogin(assumedLogin)); //TODO: loginflow

  val eventProcessor = EventProcessor(
    rawEventStream = wustClient.observable.event,
    syncDisabled = Observable(false),
    enrich = (changes, graph) => changes,
    sendChange = client.api.changeGraph _
  )
  val rawGraph:Observable[Graph] = eventProcessor.rawGraph
  var rawGraphNow = Graph.empty // TODO: replace with rx


  var chatHistorySlot = slot[RecyclerView]
  def chatHistory(implicit ctx: ContextWrapper) = {
    w[RecyclerView] <~ rvLayoutManager({
      val llm = new LinearLayoutManager(ctx.application)
      llm.setStackFromEnd(true)
      llm
    }) <~ wire(chatHistorySlot)
  }
  def updateChatHistory(posts:IndexedSeq[Post]) = {
    // TODO: https://android.jlelse.eu/smart-way-to-update-recyclerview-using-diffutil-345941a160e0
   (chatHistorySlot <~ rvAdapter(new PostsAdapter(posts)) <~ vInvalidate)
  }

  rawGraph.foreach { graph => 
    updateChatHistory(graph.chronologicalPostsAscending).run
    rawGraphNow = graph
  }

  def chatInput = {
    var value = slot[EditText]
    l[HorizontalLinearLayout](
      w[EditText] <~ wire(value) <~ llMatchWeightHorizontal,
      w[Button] <~ text("Send") <~
      On.click {
        val content = value.get.getText.toString.trim
        Ui{ 
          if(content.nonEmpty) {
            val post = Post(NodeId(cuid), value.get.getText.toString, assumedLogin)
            value.get.getText.clear()
            eventProcessor.applyChanges(GraphChanges.addPost(post)).foreach { _ =>
              (chatHistorySlot <~ Tweak[RecyclerView](_.smoothScrollToPosition(rawGraphNow.posts.size - 1))).run
            }
          }
        }
      }
    )
  }

  override def onCreate(savedInstanceState: Bundle) = {
    super.onCreate(savedInstanceState)

    setContentView {
      Ui.get {
        l[VerticalLinearLayout](
          chatHistory <~ llMatchWeightVertical,
          chatInput
        )
      }
    }
  }
}

class PostsAdapter(posts: IndexedSeq[Post])
    (implicit context: ActivityContextWrapper)
    extends RecyclerView.Adapter[ViewHolderPostsAdapter] {

  override def onCreateViewHolder(parentViewGroup: ViewGroup, i: Int): ViewHolderPostsAdapter = {
    val adapter = new PostsLayoutAdapter()
    new ViewHolderPostsAdapter(adapter)
  }

  override def getItemCount: Int = posts.size

  override def onBindViewHolder(viewHolder: ViewHolderPostsAdapter, position: Int): Unit = {
    val post = posts(position)
    viewHolder.view.setTag(position)
    Ui.run(
      (viewHolder.content <~ text(post.content))
    )
  }
}

class ViewHolderPostsAdapter(adapter: PostsLayoutAdapter)(implicit context: ActivityContextWrapper)
    extends RecyclerView.ViewHolder(adapter.view) {

  val view: LinearLayout = adapter.view
  val content: Option[TextView] = adapter.content
}

class PostsLayoutAdapter(implicit context: ActivityContextWrapper) {

  var content: Option[TextView] = slot[TextView]
  val view: LinearLayout = layout

  private def layout(implicit context: ActivityContextWrapper) = Ui.get(
    l[LinearLayout](
      w[TextView] <~ wire(content) <~ llMatchWeightHorizontal
    ) <~ vBackgroundColor(0xFFEEEEEE) <~ vPaddings(2)
  )
}

