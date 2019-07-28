package wust.graph

import wust.ids._

final case class Page(parentId: Option[NodeId]) {
  @inline def isEmpty: Boolean = parentId.isEmpty
  @inline def nonEmpty: Boolean = parentId.nonEmpty

  override def toString = {
    parentId match {
      case Some(parentId) => s"Page(${parentId.toBase58}  ${parentId.toUuid})"
      case None => "Page.empty"
    }

  }
}

object Page {
  @inline def apply(parentId: NodeId): Page = Page(Some(parentId))
  @inline def empty: Page = Page(None)
}
