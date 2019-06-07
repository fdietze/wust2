package wust.util

import scala.collection.{immutable, mutable}
import scala.reflect.ClassTag

//copied from scalaz:
// - https://github.com/scalaz/scalaz/blob/series/7.3.x/core/src/main/scala/scalaz/Memo.scala

/** A function memoization strategy.  See companion for various
  * instances employing various strategies.
  */
sealed abstract class Memo[@specialized(Int) K, @specialized(Int, Long, Double) V] {
  def apply(z: K => V): K => V
}

/** @define immuMapNote As this memo uses a single var, it's
  * thread-safe. */
object Memo {
  def memo[@specialized(Int) K, @specialized(Int, Long, Double) V](
      f: (K => V) => K => V
  ): Memo[K, V] = new Memo[K, V] {
    def apply(z: K => V) = f(z)
  }

  def nilMemo[@specialized(Int) K, @specialized(Int, Long, Double) V]: Memo[K, V] =
    memo[K, V](z => z)

  private class ArrayMemo[V >: Null: ClassTag](n: Int) extends Memo[Int, V] {
    override def apply(f: (Int) => V) = {
      var _array: Array[V] = null
      @inline def getArray = {
        if (_array == null) {
          _array = new Array[V](n)
        }
        _array
      }
      k =>
        if (k < 0 || k >= n) f(k)
        else {
          val a = getArray
          val t = a(k)
          if (t == null) {
            val v = f(k)
            a(k) = v
            v
          } else t
        }
    }
  }

  private class DoubleArrayMemo(n: Int, sentinel: Double) extends Memo[Int, Double] {
    override def apply(f: (Int) => Double) = {
      var _array: Array[Double] = null
      @inline def getArray = {
        if (_array == null) {
          _array = if (sentinel == 0d) new Array[Double](n) else Array.fill(n)(sentinel)
        }
        _array
      }
      k =>
        if (k < 0 || k >= n) f(k)
        else {
          val a = getArray
          val t = a(k)
          if (t == sentinel || (sentinel.isNaN && t.isNaN)) {
            val v = f(k)
            a(k) = v
            v
          } else t
        }
    }
  }

  private class IntArrayMemo(n: Int, sentinel: Int) extends Memo[Int, Int] {
    override def apply(f: (Int) => Int) = {
      var _array: Array[Int] = null
      @inline def getArray = {
        if (_array == null) {
          _array = if (sentinel == 0) new Array[Int](n) else Array.fill(n)(sentinel)
        }
        _array
      }
      k =>
        if (k < 0 || k >= n) f(k)
        else {
          val a = getArray
          val t = a(k)
          if (t == sentinel || (sentinel.isNaN && t.isNaN)) {
            val v = f(k)
            a(k) = v
            v
          } else t
        }
    }
  }

  /** Cache results in an `n`-long array. */
  def arrayMemo[V >: Null: ClassTag](n: Int): Memo[Int, V] = new ArrayMemo(n)

  /** As with `arrayMemo`, but memoizing double results !=
    * `sentinel`.
    */
  def doubleArrayMemo(n: Int, sentinel: Double = 0d): Memo[Int, Double] =
    new DoubleArrayMemo(n, sentinel)

  def intArrayMemo(n: Int, sentinel: Int = 0): Memo[Int, Int] =
    new IntArrayMemo(n, sentinel)

  private def mutableMapMemo[K, V](a: mutable.Map[K, V]): Memo[K, V] =
    memo[K, V](f => k => a.getOrElseUpdate(k, f(k)))

  /** Cache results in a [[scala.collection.mutable.HashMap]].
    * Nonsensical if `K` lacks a meaningful `hashCode` and
    * `java.lang.Object.equals`.
    */
  def mutableHashMapMemo[K, V]: Memo[K, V] = mutableMapMemo(new mutable.HashMap[K, V])

  def immutableMapMemo[K, V](m: immutable.Map[K, V]): Memo[K, V] = {
    var a = m

    memo[K, V](
      f =>
        k =>
          a.getOrElse(k, {
            val v = f(k)
            a = a updated (k, v)
            v
          })
    )
  }

  /** Cache results in a hash map.  Nonsensical unless `K` has
    * a meaningful `hashCode` and `java.lang.Object.equals`.
    * $immuMapNote
    */
  def immutableHashMapMemo[K, V]: Memo[K, V] = immutableMapMemo(new immutable.HashMap[K, V])

  /** Cache results in a tree map. $immuMapNote */
  def immutableTreeMapMemo[K: scala.Ordering, V]: Memo[K, V] =
    immutableMapMemo(new immutable.TreeMap[K, V])
}
