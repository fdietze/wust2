package wust.css

import scalacss.DevDefaults._
import scalacss.internal.{Attr, Literal, Transform, CanIUse}
import scalacss.internal.ValueT.{TypedAttrT1, ZeroLit, Len}

// TODO: generate by sbt:
// https://stackoverflow.com/questions/23409993/defining-sbt-task-that-invokes-method-from-project-code

object ZIndex {
  val controls = 10
  val draggable = 100
  val overlay = 1000
}

object userDrag extends TypedAttrT1[Len] with ZeroLit {
  import CanIUse.Agent._
  import CanIUse.Support._

  val CanUseDrag: CanIUse.Subject = Map(
    AndroidBrowser -> Set(FullX),
    AndroidChrome -> Set(FullX),
    AndroidFirefox -> Set(FullX),
    AndroidUC -> Set(FullX),
    BlackberryBrowser -> Set(FullX),
    Chrome -> Set(FullX),
    Edge -> Set(FullX),
    Firefox -> Set(FullX),
    IE -> Set(FullX),
    IEMobile -> Set(FullX),
    IOSSafari -> Set(FullX),
    Opera -> Set(FullX),
    OperaMini -> Set(FullX),
    OperaMobile -> Set(FullX),
    Safari -> Set(FullX),
    Samsung -> Set(FullX)
  )

  /// FIXME: this should add -webkit-user-drag and -khtml-user-drag
  override val attr = Attr.real("user-drag", Transform keys CanUseDrag)
  def element = av("element")
}

object gridGap extends TypedAttrT1[Len] with ZeroLit {
  override val attr = Attr.real("grid-gap")
  // def normal = av(L.normal)
}

object Styles extends StyleSheet.Inline {
  import dsl._

  val slim = style(
    margin(0 px),
    padding(0 px)
  )

  /** width & height 100% */
  val growFull = style(
    width(100 %%),
    height(100 %%)
  )

  val flex = style(
    /* fixes overflow:scroll inside flexbox (https://stackoverflow.com/questions/28636832/firefox-overflow-y-not-working-with-nested-flexbox/28639686#28639686) */
    minWidth(0 px),
    /* fixes full page scrolling when messages are too long */
    minHeight(0 px),
    display.flex,
  )

  val flexStatic = style(
    flexGrow(0),
    flexShrink(0)
  )

  val gridOpts = style(
    display.grid,
    gridGap(0 px),
    gridTemplateColumns := "repeat(1, 1fr)",
    gridAutoRows := "minmax(50px, 1fr)"
  )
}

//TODO: port over to Style as inline and reference class via Styles
object CommonStyles extends StyleSheet.Standalone {
  import dsl._

  "*, *:before, *:after" - (
    boxSizing.borderBox
  )

  "html, body" - (
    Styles.slim,
    width(100 %%),
    height(100 %%),
  )

  "body" - (
    fontFamily :=! "'Roboto Slab', serif",
    overflow.hidden
  )

  ".shadow" - (
    boxShadow := "0px 7px 21px -6px rgba(0,0,0,0.75)"
  )

  ".mainview" - (
    flexDirection.column,
    Styles.growFull
  )

  // -- breadcrumb --
  ".breadcrumbs" - (
    padding(1 px, 3 px),
    Styles.flex,
    overflowX.auto,
    fontSize(12 px),
    Styles.flexStatic,
  )

  ".breadcrumb" - ()

  ".breadcrumbs .divider" - (
    marginLeft(1 px),
    marginRight(3 px),
    color(c"#666"),
    fontWeight.bold
  )

  // -- sidebar --
  ".sidebar" - (
    color.white,
    transition := "background-color 0.5s",
    Styles.flexStatic,
    height(100 %%),
    Styles.flex,
    flexDirection.column,
    justifyContent.flexStart,
    alignItems.stretch,
    alignContent.stretch,
  )

  ".noChannelIcon" - (
    margin(0 px),
  )

  ".channels .noChannelIcon" - (
    width(30 px),
    height(30 px),
  )

  ".channelIcons .noChannelIcon" - (
    width(40 px),
    height(40 px),
  )

  ".channels" - (
    overflowY.auto,
    color(c"#C4C4CA"),
  )

  ".channel" - (
    paddingRight(3 px),
    Styles.flex,
    alignItems.center,
    cursor.pointer,
    wordWrap.breakWord,
    wordBreak := "break-word",
  )

  ".channelIcons" - (
    overflowY.auto
  )

  /* must be more specific than .ui.button */
  ".ui.button.newGroupButton-large" - (
    marginRight(0 px),
    marginTop(5 px),
    alignSelf.center,
    Styles.flexStatic
  )

  ".ui.button.newGroupButton-small" - (
    marginRight(0 px),
    marginTop(3 px),
    paddingLeft(12 px),
    paddingRight(12 px),
    alignSelf.center,
    Styles.flexStatic,
  )

  ".viewgridAuto" - (
    Styles.slim,
    Styles.gridOpts,
    media.only.screen.minWidth(992 px) - (
      gridTemplateColumns := "repeat(2, 50%)"
    )
  )

  ".viewgridRow" - (
    Styles.slim,
    Styles.flex
  )

  /* TODO: too many columns overlaps the content because it autofits the screen height */
  ".viewgridColumn" - (
    Styles.slim,
    Styles.gridOpts,
  )

  /* inspired by https://github.com/markdowncss/air/blob/master/index.css */
  ".article" - (
    color(c"#444"),
    fontFamily :=! "'Open Sans', Helvetica, sans-serif",
    fontWeight :=! "300",
    margin(6 rem, auto, 1 rem),
    maxWidth(100 ch)
  )

  ".article p" - (
    color(c"#777")
  )

  ".article h1" - (
    paddingBottom(0.3 em),
    borderBottom(1 px, solid, c"#eaecef")
  )

  ".article.focuslink" - (
    float.left,
    marginLeft(2 em),
    width(2 em),
    textAlign.right,
    paddingRight(10 px),
    display.inlineBlock,
    color(c"#BBB"),
    fontWeight.normal,
    cursor.pointer
  )

  ".article.focuslink > *" - (
    visibility.hidden
  )

  /* Prevent the text contents of draggable elements from being selectable. */
  "[draggable]" - (
    userSelect := "none",
    // FIXME: support -khtml-user-drag
    userDrag.element
  )

  ".graphnode" - (
    wordWrap.breakWord,
    textRendering := "optimizeLegibility",
    position.absolute,
    padding(3 px, 5 px),
    /* border-radius : 3px; */ /* affects graph rendering performance */
  )

  // FIXME: also generate -moz-selection?
  ".splitpost".selection - (
    color(red),
    background := "blue", // why no background(...) possible?
  )

  // -- chatview --
  ".chatmsg-group-outer-frame" - (
    marginTop(10 px),
    // media.only.screen.minWidth(500 px) - (
      //TODO: how to use Styles.flex ?
      /* fixes overflow:scroll inside flexbox (https://stackoverflow.com/questions/28636832/firefox-overflow-y-not-working-with-nested-flexbox/28639686#28639686) */
      minWidth(0 px),
      /* fixes full page scrolling when messages are too long */
      minHeight(0 px),
      Styles.flex,
    // )
  )

  ".chatmsg-avatar" - (
    marginRight(10 px),
    width(40 px),
    media.only.screen.maxWidth(500 px) - (
      width(20 px)
    ),
  )

  ".chatmsg-group-inner-frame" - (
    width(100 %%),
    display.block,
    media.only.screen.maxWidth(500 px) - (
      marginLeft(-2 px)
    )
  )

  ".chatmsg-header" - (
    fontSize(0.8 em),
    lineHeight(100 %%),
    paddingBottom(3 px),
    paddingLeft(2 px)
  )

  ".chatmsg-author" - (
    fontWeight.bold,
    color(c"#50575f")
  )

  ".chatmsg-date" - (
    marginLeft(8 px),
    fontSize.smaller,
    color.grey
  )

  ".chatmsg-line" - (
    cursor.move,
    alignItems.center,
    padding(2 px, 20 px, 2 px, 0 px)
  )

  ".chatmsg-line .checkbox" - (
    visibility.hidden
  )

  val chatmsgIndent = marginLeft(3 px)
  ".chatmsg-line > .tag" - (
    chatmsgIndent, // when a tag is displayed at message position
    whiteSpace.normal, // displaying tags as content should behave like normal nodes
  )

  ".chatmsg-line > .nodecard" - (
    chatmsgIndent,
  )

  ".chatmsg-line > .tag *" - (
    wordWrap.breakWord,
    wordBreak := "break-word",
  )

  ".chatmsg-controls" - (
    media.only.screen.maxWidth(500 px) - (
      display.none
    ),
    visibility.hidden,
    Styles.flex,
    alignItems.center,
    paddingLeft(3 px),
    marginLeft.auto
  )

  ".chatmsg-controls > *" - (
    padding(3 px, 5 px)
  )


  // -- controls on hover --
  // TODO: Focus is only used as a quick hack in order to use controls on mobile browser
  ".chatmsg-line:hover, .chatmsg-line:focus" - (
    backgroundColor(c"rgba(255,255,255,0.5)")
  )

  //TODO: how to generate this combinatorial explosion with scalacss?
  ".chatmsg-line:hover .chatmsg-controls,"+
  ".chatmsg-line:hover .checkbox,"+
  ".chatmsg-line:hover .transitivetag,"+
  ".chatmsg-line:focus .chatmsg-controls,"+
  ".chatmsg-line:focus .checkbox,"+
  ".chatmsg-line:focus .transitivetag" - (
    visibility.visible
  )


  val nodeCardShadow = boxShadow := "0px 1px 0px 1px rgba(158,158,158,0.45)"
  val nodeCardBackgroundColor = backgroundColor(c"#FEFEFE")
  ".nodecard" - (
    cursor.move, /* TODO: What about cursor when selecting text? */
    borderRadius(3 px),
    nodeCardBackgroundColor,
    color(c"rgba(0, 0, 0, 0.87)"), // from semantic ui
    fontWeight.normal,
    overflowX.auto,

    border(1 px, solid, transparent), // when dragging this will be replaced with a color
//    borderTop(1 px, solid, rgba(158, 158, 158, 0.19)),
    nodeCardShadow
  )

  ".nodecard a" - (
    cursor.pointer
  )

  ".nodecard-content" - (
    wordWrap.breakWord,
    wordBreak := "break-word",
    padding(2 px, 4 px),
    /* display.inlineBlock, */
    border(1 px, solid, transparent) /* placeholder for the dashed border when dragging */
  )

  ".nodecard-content pre" - (
    whiteSpace.preWrap
  )

  ".node-deleted .nodecard-content" - (
    textDecoration := "line-through",
    cursor.default
  )

  ".tags" - (
    padding( 0 px, 3 px, 0 px, 5 px ),
    Styles.flex,
    flexWrap.wrap,
    alignItems.center
  )

  ".transitivetag" - (
    visibility.hidden
  )


  val tagBorderRadius = 2.px
  ".tag" - (
    fontWeight.bold,
    fontSize.small,
    color(c"#FEFEFE"),
    borderRadius(tagBorderRadius),
    border(1 px, solid, transparent), // when dragging this will be replaced with a color
    padding(0 px, 3 px),
    marginRight(2 px),
    marginTop(1 px),
    marginBottom(1 px),
    whiteSpace.nowrap,
    cursor.pointer,
    display.inlineBlock
  )

  ".tag a" - (
    color(c"#FEFEFE"),
    textDecoration := "underline"
  )

  ".tagdot" - (
    width(1 em),
    height(1 em),
    borderRadius(50%%),
    border(1 px, solid, transparent), // when dragging this will be replaced with a color
    padding(0 px, 3 px),
    marginRight(2 px),
    marginTop(1 px),
    marginBottom(1 px),
    cursor.pointer,
    display.inlineBlock
  )



  val kanbanColumnPaddingPx = 7
  val kanbanColumnPadding = (kanbanColumnPaddingPx px)
  val kanbanRowSpacing = (8 px)
  val kanbanPageSpacing = (5 px)
  val kanbanCardWidthPx = 200
  val kanbanCardWidth = (kanbanCardWidthPx px)
  val kanbanColumnWidth = ((kanbanColumnPaddingPx + kanbanCardWidthPx + kanbanColumnPaddingPx) px)
  val kanbanColumnBorderRadius = (3 px)

  ".kanbanview" - (
    padding(kanbanPageSpacing),
  )

  ".kanbancolumnarea" - (
    height(100 %%),
    paddingBottom(5 px)
  )

  ".kanbannewcolumnarea" - (
    minWidth(kanbanColumnWidth),
    maxWidth(kanbanColumnWidth), // prevents inner fluid textarea to exceed size
    height(100 px),
    backgroundColor(c"rgba(158, 158, 158, 0.25)"),
    borderRadius(kanbanColumnBorderRadius),
    cursor.pointer,
    padding(7 px)
  )

  ".kanbannewcolumnarea.draggable-container--over" - (
    backgroundColor(transparent),
  )

  ".kanbannewcolumnareacontent" - (
    width(kanbanColumnWidth),
    paddingTop(35 px),
    textAlign.center,
    fontSize.larger,
    color(c"rgba(0, 0, 0, 0.62)"),
  )

  ".kanbannewcolumnarea > .nodecard" - ( // when dragging card over, to create new column
    width(kanbanColumnWidth),
    height(100 px),
    margin(0 px).important
  )

  ".kanbannewcolumnarea .kanbancolumn" - (
    margin(0 px).important
  )

  ".kanbannewcolumnarea, " +
  ".kanbancolumn," + // when dragging sub-column to top-level area
  ".kanbantoplevelcolumn" - (
    marginTop(0 px),
    marginLeft(0 px),
    marginRight(10 px),
    marginBottom(20 px),
  )

  ".kanbantoplevelcolumn" - (
    boxShadow := "0px 1px 0px 1px rgba(158,158,158,0.45)", // lighter shadow than on sub-columns
    Styles.flex,
    flexDirection.column,
    maxHeight(100 %%)
  )

  ".kanbancolumntitle" - (
    maxWidth(kanbanCardWidth),
    // TODO: separate style for word-breaking in nodes
    wordWrap.breakWord,
    wordBreak := "break-word",
  )

  ".kanbancolumnheader .kanbanbuttonbar" - (
    padding(kanbanColumnPadding),
    visibility.hidden,
    fontSize.medium // same as in kanban card
  )

  ".kanbancolumnheader > p" - (
    marginBottom(0 em) // default was 1 em
  )

  ".kanbancolumn.draggable--over .kanbanbuttonbar" - (
    visibility.hidden.important // hide buttons when dragging over column
  )

  ".nodecard .kanbanbuttonbar" - (
    padding(2 px, 4 px),
    visibility.hidden
  )

  ".nodecard:hover .kanbanbuttonbar," +
  ".kanbancolumnheader:hover .kanbanbuttonbar" - (
    visibility.visible
  )

  ".kanbancolumnheader .kanbanbuttonbar > div," +
  ".nodecard .kanbanbuttonbar > div" - (
    borderRadius(2 px),
    marginLeft(2 px)
  )

  ".kanbancolumnheader .kanbanbuttonbar > div" - (
    padding(2 px),
    backgroundColor(c"hsla(0, 0%, 34%, 0.72)"),
    color(c"rgba(255, 255, 255, 0.83)")
  )

  ".kanbancolumnheader .kanbanbuttonbar > div:hover" - (
    backgroundColor(c"hsla(0, 0%, 0%, 0.72)"),
    color(white)
  )

  ".nodecard .kanbanbuttonbar > div" - (
    backgroundColor(c"rgba(254, 254, 254, 0.9)"), // nodeCardBackgroundColor, but transparent
    color(c"rgb(157, 157, 157)"),
    padding(2 px)
  )

  ".nodecard .kanbanbuttonbar > div:hover" - (
    backgroundColor(c"rgba(215, 215, 215, 0.9)"),
    color(c"rgb(71, 71, 71)")
  )


  ".kanbancolumnchildren > .nodecard," +
  ".kanbanisolatednodes > .nodecard" - (
    width(kanbanCardWidth),
    borderRadius(3 px),
    fontSize.medium,
  )

  ".kanbancolumn" - (
    color(c"#FEFEFE"),
    fontWeight.bold,
    fontSize.large,
    boxShadow := "0px 1px 0px 1px rgba(99,99,99,0.45)",
    cursor.move,
    borderRadius(kanbanColumnBorderRadius),
    Styles.flexStatic,
  )

  ".kanbancolumnchildren" - (
    minHeight(50 px), // enough vertical area to drag cards in
    minWidth(kanbanColumnWidth), // enough horizontal area to not flicker width when adding cards
    overflowY.auto,
    paddingBottom(5 px) // prevents column shadow from being cut off by scrolling
  )

  // we want the sortable container to consume the full width of the column.
  // So that dragging a card/subcolumn in from the side directly hovers the sortable area inside
  // the column, instead of sorting the top-level-columns.
  // therefore, instead setting a padding on the column, we set a margin/padding on the inner elements.
  ".kanbancolumn > .kanbancolumnheader" - (
    padding(kanbanColumnPadding, kanbanColumnPadding, 0 px, kanbanColumnPadding),
  )

  ".kanbancolumnchildren > .nodecard," +
  ".kanbancolumnchildren > .kanbantoplevelcolumn," + // when dragging top-level column into column
  ".kanbancolumnchildren > .kanbancolumn" - (
    marginTop(kanbanRowSpacing),
    marginRight(kanbanColumnPadding),
    marginLeft(kanbanColumnPadding),
    marginBottom(0 px)
  )
  ".kanbancolumn > .kanbanaddnodefield" - (
    padding(kanbanRowSpacing, kanbanColumnPadding, kanbanColumnPadding, kanbanColumnPadding),
  )

  ".kanbanisolatednodes" - (
    Styles.flex,
    flexWrap.wrap,
    alignItems.flexStart,
    marginBottom(20 px),
    minHeight(50 px),
    maxHeight(200 px),
    overflowY.auto,
    paddingBottom(10 px) // prevents card shadow from being cut off by scrolling
  )

  ".kanbanisolatednodes > .nodecard" - (
    marginRight(5 px),
    marginTop(5 px),
  )







  ".actionbutton" - (
    cursor.pointer,
    padding(0 px, 5 px),
    marginLeft(2 px),
    borderRadius(50 %%)
  )

  ".actionbutton:hover" - (
    backgroundColor(c"rgba(255,255,255,0.5)")
    )

  ".selectednodes" - (
    backgroundColor(c"#85D5FF"),
    padding(5 px, 5 px, 2 px, 5 px),
    cursor.move
  )

  ".selectednodes .nodecard" - (
    marginLeft(3 px)
  )

  ".draggable" - (
    outline.none, // hides focus outline
//    border(2 px, solid, green)
  )

  ".dropzone" - (
    backgroundColor(c"rgba(184,65,255,0.5)")
  )

  // -- draggable node
  ".draggable-container .node.draggable--over, .chatmsg-line.draggable--over .nodecard" - (
    backgroundColor(c"rgba(65,184,255, 1)").important,
    color.white.important,
    opacity(1).important,
    cursor.move.important
  )

  ".draggable-mirror" - (
    opacity(1).important,
    zIndex(ZIndex.draggable), // needs to overlap checkboxes, selectednodesbar
  )


  // -- draggable nodecard
  ".nodecard.draggable--over," +
  ".chatmsg-line.draggable--over .nodecard" - (
    borderTop(1 px, solid, transparent).important,
    (boxShadow := "0px 1px 0px 1px rgba(93, 120, 158,0.45)").important
  )

  ".chatmsg-line .nodecard.draggable-mirror" - (
    nodeCardBackgroundColor.important,
    nodeCardShadow.important,
    color.inherit.important
  )

  ".chatmsg-line.draggable-mirror .tag," +
  ".chatmsg-line.draggable-mirror .tagdot" - (
    visibility.hidden
  )

  val onDragNodeCardColor = c"rgba(0,0,0,0.5)"
  ".nodecard.draggable-source--is-dragging," +
  ".chatmsg-line.draggable-source--is-dragging .nodecard,"+
  ".chatmsg-line.draggable--over.draggable-source--is-dragging .nodecard,"+
  ".chatmsg-line.draggable--over .nodecard.draggable-source--is-dragging" - (
    backgroundColor(white).important,
    (boxShadow := none).important,
    border(1 px, dashed, onDragNodeCardColor).important,
    color(onDragNodeCardColor).important,
  )

  // -- draggable chanelicon
  ".channelicon.draggable-mirror" - (
    border(2 px, solid, c"#383838").important
  )

  ".channelicon.draggable-source--is-dragging" - (
    border(2 px, dashed, c"#383838").important
    )


  // -- draggable tag
  val onDragNodeTagColor = c"rgba(255,255,255,0.8)"
  ".tag.draggable-source--is-dragging," +
  ".tag.draggable-source--is-dragging.draggable--over," +
  ".tagdot.draggable-source--is-dragging," +
  ".tagdot.draggable-source--is-dragging.draggable--over" - (
    border(1 px, dashed, onDragNodeTagColor).important,
    color(onDragNodeTagColor).important,
    backgroundColor(c"#98A3AB").important
  )

  // -- sortable
  ".sortable-container .draggable-source--is-dragging" - (
    backgroundColor(c"rgba(102, 102, 102, 0.71)").important,
    color(transparent).important,
    borderColor(transparent).important,
    pointerEvents := "none" // avoid hover effects, like buttonbar in kanban
  )

  ".sortable-container .draggable-source--is-dragging > *" - (
    visibility.hidden
  )

  // -- draggable selectednodes
  ".selectednodes.draggable--over" - (
    backgroundColor(c"rgba(65,184,255, 1)").important,
  )

  ".selectednodes.draggable-mirror > *" - (
    display.none
  )

  ".selectednodes.draggable-mirror > .nodelist" - (
    Styles.flex,
  )

  ".selectednodes.draggable-mirror" - (
    borderRadius(3 px)
  )

  // -- draggable actionbutton, transitivetag
  ".node.draggable--over .actionbutton" - (
    backgroundColor.inherit.important,
    cursor.move.important
  )

  ".chatmsg-line.draggable-source--is-dragging .transitivetag" - (
    visibility.visible
    )

  ".text" - (
    cursor.text
  )

  val nonBookmarkShadowOpts = "0px 2px 10px 0px rgba(0,0,0,0.75)"
  ".non-bookmarked-page-frame" - (
    padding(20 px),
    media.only.screen.maxWidth(500 px) - (
      padding(7 px)
    ),
    boxShadow := "inset " + nonBookmarkShadowOpts
  )

  ".non-bookmarked-page-frame > *" - (
    boxShadow := nonBookmarkShadowOpts
  )

  ".feedbackhint" - (
    opacity(0.5)
    )

  ".feedbackhint:hover" - (
    opacity(1)
  )
}

object StyleRendering {
  def renderAll: String = CommonStyles.renderA[String] ++ Styles.renderA[String]

  //    final def render[Out](implicit r: Renderer[Out], env: Env): Out =
  // cssRegister.render

  // import java.io.{File, PrintWriter}

  // /** Generates css files from scalacss styles */
  // def Build() = {
  //   // val w = new PrintWriter(new File("../webApp/src/css/generated.css"))
  //   val w = new PrintWriter(new File("../webApp/src/css/style.css"))
  //   w.write(renderAll)
  //   w.close()
  // }
}
