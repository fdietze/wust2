package wust.webApp.state

import cats.data.NonEmptyList
import wust.util.macros.SubObjects

import scala.collection.breakOut

sealed trait View {
  def viewKey: String
  def isContent: Boolean = true
}
object View {
  sealed trait Visible extends View
  case object Detail extends Visible {
    def viewKey = "detail"
  }
  case object Magic extends Visible {
    def viewKey = "magic"
  }
  case object Split extends Visible {
    def viewKey = "split"
  }
  case object Thread extends Visible {
    def viewKey = "thread"
  }
  case object Chat extends Visible {
    def viewKey = "chat"
  }
  case object Files extends Visible {
    def viewKey = "files"
  }
  case object Kanban extends Visible {
    def viewKey = "kanban"
  }
  case object List extends Visible {
    def viewKey = "list"
  }
  case object Property extends Visible {
    def viewKey = "property"
  }
  case object Graph extends Visible {
    def viewKey = "graph"
  }
  case object Dashboard extends Visible {
    def viewKey = "dashboard"
  }
  case object Login extends Visible {
    def viewKey = "login"
    override def isContent = false
  }
  case object Signup extends Visible {
    def viewKey = "signup"
    override def isContent = false
  }
  case object Welcome extends Visible {
    def viewKey = "welcome"
    override def isContent = false
  }
  case object UserSettings extends Visible {
    def viewKey = "usersettings"
    override def isContent = false
  }
  case object Empty extends Visible {
    def viewKey = "empty"
    override def isContent = true
  }
  case class Tiled(operator: ViewOperator, views: NonEmptyList[Visible]) extends Visible {
    def viewKey = views.map(_.viewKey).toList.mkString(operator.separator)
    override def isContent = views.exists(_.isContent)
  }

  sealed trait Heuristic extends View
  case object Conversation extends Heuristic {
    def viewKey = "conversation"
  }
  case object Tasks extends Heuristic {
    def viewKey = "tasks"
  }
  case object New extends Visible {
    def viewKey = "new"
    override def isContent = true
  }

  def list: List[View] = macro SubObjects.list[View]
  def contentList: List[View] = list.filter(_.isContent)

  val map: Map[String, View] = list.map(v => v.viewKey -> v)(breakOut)
}

sealed trait ViewOperator {
  val separator: String
}
object ViewOperator {
  case object Row extends ViewOperator { override val separator = "|" }
  case object Column extends ViewOperator { override val separator = "/" }
  case object Auto extends ViewOperator { override val separator = "," }
  case object Optional extends ViewOperator { override val separator = "?" }

  val fromString: PartialFunction[String, ViewOperator] = {
    case Row.separator      => Row
    case Column.separator   => Column
    case Auto.separator     => Auto
    case Optional.separator => Optional
  }
}
