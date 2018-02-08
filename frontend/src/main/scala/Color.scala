package wust.frontend

import d3v4._
import wust.graph.Graph
import wust.ids._

object Color {
  val postDefaultColor = d3.lab("#f8f8f8")

  def baseHue(id: PostId):Int = (id.hashCode * 137) % 360
  def baseColor(id: PostId) = d3.hcl(baseHue(id), 50, 75)
  def baseColorDark(id: PostId) = d3.hcl(baseHue(id), 65, 60)
  def baseColorMixedWithDefault(id: PostId) = mixColors(d3.hcl(baseHue(id), 50, 75), postDefaultColor)

  //TODO: implicit color conversions in d3 facade
  def mixColors(a: Color, b: Color): Color = {
    val aLab = d3.lab(a)
    val bLab = d3.lab(b)
    import aLab.{a => aa, b => ab, l => al}
    import bLab.{a => ba, b => bb, l => bl}
    d3.lab((al + bl) / 2, (aa + ba) / 2, (ab + bb) / 2)
  }
  def mixColors(colors: Iterable[Color]): Color = {
    if (colors.isEmpty) return d3.lab("#FFFFFF")
    val colorSum = colors.map(d3.lab).reduce((c1, c2) => d3.lab(c1.l + c2.l, c1.a + c2.a, c1.b + c2.b))
    val colorCount = colors.size
    d3.lab(colorSum.l / colorCount, colorSum.a / colorCount, colorSum.b / colorCount)
  }

  implicit def d3ColorToString(c:Color) = c.toString
}

object ColorPost {
  import wust.frontend.Color._

  def mixedDirectParentColors(graph: Graph, postId: PostId) = mixColors(graph.parents(postId).map(baseColor))

  def computeColor(graph: Graph, postId: PostId): Color = {
    val calculatedBorderColor = if (graph.hasChildren(postId)) {
        baseColor(postId)
    } else {
      if (graph.hasParents(postId))
        mixColors(mixedDirectParentColors(graph, postId), postDefaultColor)
      else
        postDefaultColor
    }
    calculatedBorderColor
  }

  def computeTagColor(graph: Graph, postId: PostId): Color = {
    baseColorDark(postId)
  }
}
