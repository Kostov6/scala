/*
 * Scala (https://www.scala-lang.org)
 *
 * Copyright EPFL and Lightbend, Inc.
 *
 * Licensed under Apache License 2.0
 * (http://www.apache.org/licenses/LICENSE-2.0).
 *
 * See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 */

package scala
package collection
package mutable

import java.util.Arrays

import scala.annotation.nowarn
import scala.collection.Stepper.EfficientSplit
import scala.collection.generic.DefaultSerializable
import scala.util.chaining._

/** An implementation of the `Buffer` class using an array to
  *  represent the assembled sequence internally. Append, update and random
  *  access take constant time (amortized time). Prepends and removes are
  *  linear in the buffer size.
  *
  *  @see [[https://docs.scala-lang.org/overviews/collections/concrete-mutable-collection-classes.html#array-buffers "Scala's Collection Library overview"]]
  *  section on `Array Buffers` for more information.

  *
  *  @tparam A    the type of this arraybuffer's elements.
  *
  *  @define Coll `mutable.ArrayBuffer`
  *  @define coll array buffer
  *  @define orderDependent
  *  @define orderDependentFold
  *  @define mayNotTerminateInf
  *  @define willNotTerminateInf
  */
@SerialVersionUID(-1582447879429021880L)
class ArrayBuffer[A] private (initialElements: Array[AnyRef], initialSize: Int)
  extends AbstractBuffer[A]
    with IndexedBuffer[A]
    with IndexedSeqOps[A, ArrayBuffer, ArrayBuffer[A]]
    with StrictOptimizedSeqOps[A, ArrayBuffer, ArrayBuffer[A]]
    with IterableFactoryDefaults[A, ArrayBuffer]
    with DefaultSerializable {

  def this() = this(new Array[AnyRef](ArrayBuffer.DefaultInitialSize), 0)

  def this(initialSize: Int) = this(new Array[AnyRef](initialSize max 1), 0)

  @transient private[this] var mutationCount: Int = 0

  protected[collection] var array: Array[AnyRef] = initialElements
  protected var size0 = initialSize

  override def stepper[S <: Stepper[_]](implicit shape: StepperShape[A, S]): S with EfficientSplit = {
    import scala.collection.convert.impl._
    shape.parUnbox(new ObjectArrayStepper(array, 0, length).asInstanceOf[AnyStepper[A] with EfficientSplit])
  }

  override def knownSize: Int = super[IndexedSeqOps].knownSize

  /** Ensure that the internal array has at least `n` cells. */
  protected def ensureSize(n: Int): Unit = {
    mutationCount += 1
    array = ArrayBuffer.ensureSize(array, size0, n)
  }

  def sizeHint(size: Int): Unit =
    if(size > length && size >= 1) ensureSize(size)

  /** Reduce length to `n`, nulling out all dropped elements */
  private def reduceToSize(n: Int): Unit = {
    mutationCount += 1
    Arrays.fill(array, n, size0, null)
    size0 = n
  }

  /** Trims the ArrayBuffer to an appropriate size for the current
   *  number of elements (rounding up to the next natural size),
   *  which may replace the array by a shorter one.
   *  This allows releasing some unused memory.
   */
  def trimToSize(): Unit = {
    mutationCount += 1
    resize(length)
  }

  /** Trims the `array` buffer size down to either a power of 2
   *  or Int.MaxValue while keeping first `requiredLength` elements.
   */
  private def resize(requiredLength: Int): Unit =
    array = ArrayBuffer.downsize(array, requiredLength)

  @inline private def checkWithinBounds(lo: Int, hi: Int) = {
    if (lo < 0) throw new IndexOutOfBoundsException(s"$lo is out of bounds (min 0, max ${size0 - 1})")
    if (hi > size0) throw new IndexOutOfBoundsException(s"${hi - 1} is out of bounds (min 0, max ${size0 - 1})")
  }

  def apply(n: Int): A = {
    checkWithinBounds(n, n + 1)
    array(n).asInstanceOf[A]
  }

  def update(@deprecatedName("n", "2.13.0") index: Int, elem: A): Unit = {
    checkWithinBounds(index, index + 1)
    mutationCount += 1
    array(index) = elem.asInstanceOf[AnyRef]
  }

  def length = size0

  // TODO: return `IndexedSeqView` rather than `ArrayBufferView`
  override def view: ArrayBufferView[A] = new ArrayBufferView(this, () => mutationCount)

  override def iterableFactory: SeqFactory[ArrayBuffer] = ArrayBuffer

  /** Note: This does not actually resize the internal representation.
    * See clearAndShrink if you want to also resize internally
    */
  def clear(): Unit = reduceToSize(0)

  /**
    * Clears this buffer and shrinks to @param size (rounding up to the next
    * natural size)
    * @param size
    */
  def clearAndShrink(size: Int = ArrayBuffer.DefaultInitialSize): this.type = {
    clear()
    resize(size)
    this
  }

  def addOne(elem: A): this.type = {
    val i = size0
    ensureSize(size0 + 1)
    size0 += 1
    this(i) = elem
    this
  }

  // Overridden to use array copying for efficiency where possible.
  override def addAll(elems: IterableOnce[A]): this.type = {
    elems match {
      case elems: ArrayBuffer[_] =>
        val elemsLength = elems.size0
        if (elemsLength > 0) {
          ensureSize(length + elemsLength)
          Array.copy(elems.array, 0, array, length, elemsLength)
          size0 = length + elemsLength
        }
      case _ => super.addAll(elems)
    }
    this
  }

  def insert(@deprecatedName("n", "2.13.0") index: Int, elem: A): Unit = {
    checkWithinBounds(index, index)
    ensureSize(size0 + 1)
    Array.copy(array, index, array, index + 1, size0 - index)
    size0 += 1
    this(index) = elem
  }

  def prepend(elem: A): this.type = {
    insert(0, elem)
    this
  }

  def insertAll(@deprecatedName("n", "2.13.0") index: Int, elems: IterableOnce[A]): Unit = {
    checkWithinBounds(index, index)
    elems match {
      case elems: collection.Iterable[A] =>
        val elemsLength = elems.size
        if (elemsLength > 0) {
          val len = size0
          val newSize = len + elemsLength
          ensureSize(newSize)
          Array.copy(array, index, array, index + elemsLength, len - index)
          // if `elems eq this`, this copy is safe because
          //   - `elems.array eq this.array`
          //   - we didn't overwrite the values being inserted after moving them in
          //     the previous line
          //   - `copyElemsToArray` will call `System.arraycopy`
          //   - `System.arraycopy` will effectively "read" all the values before
          //     overwriting any of them when two arrays are the the same reference
          IterableOnce.copyElemsToArray(elems, array.asInstanceOf[Array[Any]], index, elemsLength)
          size0 = newSize // update size AFTER the copy, in case we're inserting a proxy
        }
      case _ => insertAll(index, ArrayBuffer.from(elems))
    }
  }

  /** Note: This does not actually resize the internal representation.
    * See trimToSize if you want to also resize internally
    */
  def remove(@deprecatedName("n", "2.13.0") index: Int): A = {
    checkWithinBounds(index, index + 1)
    val res = this(index)
    Array.copy(array, index + 1, array, index, size0 - (index + 1))
    reduceToSize(size0 - 1)
    res
  }

  /** Note: This does not actually resize the internal representation.
    * See trimToSize if you want to also resize internally
    */
  def remove(@deprecatedName("n", "2.13.0") index: Int, count: Int): Unit =
    if (count > 0) {
      checkWithinBounds(index, index + count)
      Array.copy(array, index + count, array, index, size0 - (index + count))
      reduceToSize(size0 - count)
    } else if (count < 0) {
      throw new IllegalArgumentException("removing negative number of elements: " + count)
    }

  @deprecated("Use 'this' instance instead", "2.13.0")
  @deprecatedOverriding("ArrayBuffer[A] no longer extends Builder[A, ArrayBuffer[A]]", "2.13.0")
  @inline def result(): this.type = this

  @deprecated("Use 'new GrowableBuilder(this).mapResult(f)' instead", "2.13.0")
  @deprecatedOverriding("ArrayBuffer[A] no longer extends Builder[A, ArrayBuffer[A]]", "2.13.0")
  @inline def mapResult[NewTo](f: (ArrayBuffer[A]) => NewTo): Builder[A, NewTo] = new GrowableBuilder[A, ArrayBuffer[A]](this).mapResult(f)

  @nowarn("""cat=deprecation&origin=scala\.collection\.Iterable\.stringPrefix""")
  override protected[this] def stringPrefix = "ArrayBuffer"

  override def copyToArray[B >: A](xs: Array[B], start: Int, len: Int): Int = {
    val copied = IterableOnce.elemsToCopyToArray(length, xs.length, start, len)
    if(copied > 0) {
      Array.copy(array, 0, xs, start, copied)
    }
    copied
  }

  /** Sorts this $coll in place according to an Ordering.
    *
    * @see [[scala.collection.mutable.IndexedSeqOps.sortInPlace]]
    * @param  ord the ordering to be used to compare elements.
    * @return modified input $coll sorted according to the ordering `ord`.
    */
  override def sortInPlace[B >: A]()(implicit ord: Ordering[B]): this.type = {
    if (length > 1) {
      mutationCount += 1
      scala.util.Sorting.stableSort(array.asInstanceOf[Array[B]], 0, length)
    }
    this
  }
}

/**
  * Factory object for the `ArrayBuffer` class.
  *
  * $factoryInfo
  *
  * @define coll array buffer
  * @define Coll `mutable.ArrayBuffer`
  */
@SerialVersionUID(3L)
object ArrayBuffer extends StrictOptimizedSeqFactory[ArrayBuffer] {
  final val DefaultInitialSize = 16

  // Avoid reallocation of buffer if length is known.
  def from[B](coll: collection.IterableOnce[B]): ArrayBuffer[B] = {
    val k = coll.knownSize
    if (k >= 0) {
      val array = new Array[AnyRef](k max DefaultInitialSize)
      IterableOnce.copyElemsToArray(coll, array.asInstanceOf[Array[Any]])
      new ArrayBuffer[B](array, k)
    }
    else new ArrayBuffer[B] ++= coll
  }

  def newBuilder[A]: Builder[A, ArrayBuffer[A]] =
    new GrowableBuilder[A, ArrayBuffer[A]](empty) {
      override def sizeHint(size: Int): Unit = elems.ensureSize(size)
    }

  def empty[A]: ArrayBuffer[A] = new ArrayBuffer[A]()

  // if necessary, copy (n elements of) the array to a new array of capacity n.
  // Should use Array.copyOf(array, resizeEnsuring(array.length, n))?
  //
  private def ensureSize(array: Array[AnyRef], end: Int, n: Int): Array[AnyRef] = {
    def resizeEnsuring(length: Int, end: Int, n: Int): Int = {
      var newSize: Long = length
      newSize = math.max(newSize * 2, DefaultInitialSize)
      while (newSize < n) newSize *= 2
      if (newSize <= Int.MaxValue) newSize.toInt
      else if (end == Int.MaxValue) throw new Exception(s"Collections can not have more than ${Int.MaxValue} elements")
      else Int.MaxValue
    }
    if (n <= array.length) array
    else new Array[AnyRef](resizeEnsuring(array.length, end, n)).tap(Array.copy(array, 0, _, 0, end))
  }
  private def downsize(array: Array[AnyRef], requiredLength: Int): Array[AnyRef] = {
    def resizeDown(length: Int, requiredLength: Int): Int = {
      var newSize: Long = length
      // ensure that newSize is a power of 2 (this tweak is not motivated)
      if (newSize == Int.MaxValue) newSize += 1
      val minLength = math.max(requiredLength, DefaultInitialSize)
      while (newSize / 2 >= minLength) newSize /= 2
      if (newSize <= Int.MaxValue) newSize.toInt
      else Int.MaxValue
    }
    if (requiredLength >= array.length) array
    else new Array[AnyRef](resizeDown(array.length, requiredLength)).tap(Array.copy(array, 0, _, 0, requiredLength))
  }
}

// TODO: use `CheckedIndexedSeqView.Id` once we can change the return type of `ArrayBuffer#view`
final class ArrayBufferView[A] private[mutable](underlying: ArrayBuffer[A], mutationCount: () => Int)
  extends AbstractIndexedSeqView[A] {
  @deprecated("never intended to be public; call ArrayBuffer#view instead", since = "2.13.7")
  def this(array: Array[AnyRef], length: Int) = {
    // this won't actually track mutation, but it would be a pain to have the implementation
    // check if we have a method to get the current mutation count or not on every method and
    // change what it does based on that. hopefully no one ever calls this.
    this({
      val _array = array
      val _length = length
      new ArrayBuffer[A](0) {
        this.array = _array
        this.size0 = _length
      }
    }, () => 0)
  }

  @deprecated("never intended to be public", since = "2.13.7")
  def array: Array[AnyRef] = underlying.toArray[Any].asInstanceOf[Array[AnyRef]]

  @throws[IndexOutOfBoundsException]
  def apply(n: Int): A = underlying(n)
  def length: Int = underlying.length
  override protected[this] def className = "ArrayBufferView"

  // we could inherit all these from `CheckedIndexedSeqView`, except this class is public
  override def iterator: Iterator[A] = new CheckedIndexedSeqView.CheckedIterator(this, mutationCount())
  override def reverseIterator: Iterator[A] = new CheckedIndexedSeqView.CheckedReverseIterator(this, mutationCount())

  override def appended[B >: A](elem: B): IndexedSeqView[B] = new CheckedIndexedSeqView.Appended(this, elem)(mutationCount)
  override def prepended[B >: A](elem: B): IndexedSeqView[B] = new CheckedIndexedSeqView.Prepended(elem, this)(mutationCount)
  override def take(n: Int): IndexedSeqView[A] = new CheckedIndexedSeqView.Take(this, n)(mutationCount)
  override def takeRight(n: Int): IndexedSeqView[A] = new CheckedIndexedSeqView.TakeRight(this, n)(mutationCount)
  override def drop(n: Int): IndexedSeqView[A] = new CheckedIndexedSeqView.Drop(this, n)(mutationCount)
  override def dropRight(n: Int): IndexedSeqView[A] = new CheckedIndexedSeqView.DropRight(this, n)(mutationCount)
  override def map[B](f: A => B): IndexedSeqView[B] = new CheckedIndexedSeqView.Map(this, f)(mutationCount)
  override def reverse: IndexedSeqView[A] = new CheckedIndexedSeqView.Reverse(this)(mutationCount)
  override def slice(from: Int, until: Int): IndexedSeqView[A] = new CheckedIndexedSeqView.Slice(this, from, until)(mutationCount)
  override def tapEach[U](f: A => U): IndexedSeqView[A] = new CheckedIndexedSeqView.Map(this, { (a: A) => f(a); a})(mutationCount)

  override def concat[B >: A](suffix: IndexedSeqView.SomeIndexedSeqOps[B]): IndexedSeqView[B] = new CheckedIndexedSeqView.Concat(this, suffix)(mutationCount)
  override def appendedAll[B >: A](suffix: IndexedSeqView.SomeIndexedSeqOps[B]): IndexedSeqView[B] = new CheckedIndexedSeqView.Concat(this, suffix)(mutationCount)
  override def prependedAll[B >: A](prefix: IndexedSeqView.SomeIndexedSeqOps[B]): IndexedSeqView[B] = new CheckedIndexedSeqView.Concat(prefix, this)(mutationCount)
}
