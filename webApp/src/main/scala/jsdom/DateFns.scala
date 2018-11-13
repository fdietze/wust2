package wust.webApp.jsdom

import scala.scalajs.js
import scala.scalajs.js.Date
import scala.scalajs.js.annotation._

@js.native
@JSImport("date-fns", JSImport.Default)
object dateFns extends js.Object {

  // https://date-fns.org/v2.0.0-alpha.16/docs
  def format(date: js.Date, format: String): String = js.native
  def formatDistance(date: js.Date, baseDate: js.Date): String = js.native
  def addWeeks(date: js.Date, amount: Int): js.Date = js.native
  def differenceInCalendarDays(date: Date, createdDate: Date):Int = js.native
}
