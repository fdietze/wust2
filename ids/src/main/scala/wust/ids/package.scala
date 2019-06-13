package wust

import java.util.Date

import com.github.ghik.silencer.silent
import supertagged._

import scala.util.Try

package object ids {
  type UuidType = String

  object NodeId extends TaggedType[Cuid] {
    @inline def fresh: NodeId = apply(Cuid.fromCuidString(cuid.Cuid())).right.get //ok, because cuid comes from cuid function
    @inline def fromBase58String(str: String): Either[String, NodeId] = Cuid.fromBase58String(str).map(apply(_))
  }
  type NodeId = NodeId.Type

  object UserId extends OverTagged(NodeId) {
    @inline def fresh: UserId = apply(NodeId.fresh)
    @inline def fromBase58String(str: String): Either[String, UserId] = NodeId.fromBase58String(str).map(apply(_))
  }
  type UserId = UserId.Type


  object ChildId extends OverTagged(NodeId) {
    @inline def fresh: ChildId = apply(NodeId.fresh)
    @inline def fromBase58String(str: String): Either[String, ChildId] = NodeId.fromBase58String(str).map(apply(_))
  }
  type ChildId = ChildId.Type
  object ParentId extends OverTagged(NodeId) {
    @inline def fresh: ParentId = apply(NodeId.fresh)
    @inline def fromBase58String(str: String): Either[String, ParentId] = NodeId.fromBase58String(str).map(apply(_))
  }
  type ParentId = ParentId.Type
  object PropertyId extends OverTagged(NodeId) {
    @inline def fresh: PropertyId = apply(NodeId.fresh)
    @inline def fromBase58String(str: String): Either[String, PropertyId] = NodeId.fromBase58String(str).map(apply(_))
  }
  type PropertyId = PropertyId.Type
  object TemplateId extends OverTagged(NodeId) {
    @inline def fresh: TemplateId = apply(NodeId.fresh)
    @inline def fromBase58String(str: String): Either[String, TemplateId] = NodeId.fromBase58String(str).map(apply(_))
  }
  type TemplateId = TemplateId.Type

  object DurationMilli extends TaggedType[Long]
  type DurationMilli = DurationMilli.Type

  object DateTimeMilli extends OverTagged(EpochMilli)
  type DateTimeMilli = DateTimeMilli.Type

  object TimeMilli extends OverTagged(EpochMilli)
  type TimeMilli = TimeMilli.Type

  object DateMilli extends OverTagged(EpochMilli)
  type DateMilli = DateMilli.Type

  object EpochMilli extends TaggedType[Long] {
    var delta: Long = 0 //TODO we should not have a var here, we use the delta for something very specific in the client and not for every epochmilli instance!
    @inline def localNow: EpochMilli = EpochMilli(System.currentTimeMillis()) // UTC: https://docs.oracle.com/javase/8/docs/api/java/lang/System.html#currentTimeMillis--
    @inline def now: EpochMilli = EpochMilli(localNow + delta)
    @inline def zero: EpochMilli = EpochMilli(0L)
    @inline def second: Long = 1000L
    @inline def minute: Long = 60L * second
    @inline def hour: Long = 60L * minute
    @inline def day: Long = 24L * hour
    @inline def week: Long = 7L * day

    @silent("deprecated") 
    def parse(str: String) = Try(Date.parse(str)).toOption.map(EpochMilli(_))

    implicit class RichEpochMilli(val t: EpochMilli) extends AnyVal {
      @inline def <(that: EpochMilli): Boolean = t < that
      @inline def >(that: EpochMilli): Boolean = t > that
      @inline def plus(duration: DurationMilli): EpochMilli = EpochMilli((t: Long) + (duration:Long))
      @inline def minus(duration: DurationMilli): EpochMilli = EpochMilli((t: Long) - (duration:Long))
      @inline def isBefore(that: EpochMilli): Boolean = t < that
      @inline def isAfter(that: EpochMilli): Boolean = t > that
      @inline def newest(that:EpochMilli):EpochMilli = EpochMilli((t:Long) max (that:Long))
      @inline def oldest(that:EpochMilli):EpochMilli = EpochMilli((t:Long) min (that:Long))

      @silent("deprecated") 
      def humanReadable: String = {
        // java.util.Date is deprecated, but implemented in java and scalajs
        // and therefore a simple cross-compiling solution
        import java.util.Date
        val d = new Date(t)
        val year = d.getYear + 1900
        val month = d.getMonth + 1
        val day = d.getDate
        val hour = d.getHours
        val minute = d.getMinutes
        val second = d.getSeconds
        f"$year%04d-$month%02d-$day%02d $hour%02d:$minute%02d:$second%02d"
      }

      @silent("deprecated") 
      def isoDate: String = {
        // java.util.Date is deprecated, but implemented in java and scalajs
        // and therefore a simple cross-compiling solution
        import java.util.Date
        val d = new Date(t)
        val year = d.getYear + 1900
        val month = d.getMonth + 1
        val day = d.getDate
        f"$year%04d-$month%02d-$day%02d"
      }

      @silent("deprecated") 
      def isoDateAndTime: String = {
        // java.util.Date is deprecated, but implemented in java and scalajs
        // and therefore a simple cross-compiling solution
        import java.util.Date
        val d = new Date(t)
        val year = d.getYear + 1900
        val month = d.getMonth + 1
        val day = d.getDate
        val hour = d.getHours
        val minute = d.getMinutes
        f"$year%04d-$month%02d-$day%02d $hour%02d:$minute%02d"
      }
    }

    // https://www.postgresql.org/docs/9.1/static/datatype-datetime.html
    // use 1970 as minimum time (0L) due to inaccuracies in postgres when using 0001-01-01 00:00:00
    @inline def min = EpochMilli(0L)
    // use 4000-01-01 00:00:00 as maximum time instead of year 294276 (postgres maximum) for the same reason.
    @inline def max = EpochMilli(64060588800000L)

  }
  type EpochMilli = EpochMilli.Type
}