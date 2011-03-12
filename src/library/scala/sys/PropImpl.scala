/*                     __                                               *\
**     ________ ___   / /  ___     Scala API                            **
**    / __/ __// _ | / /  / _ |    (c) 2003-2011, LAMP/EPFL             **
**  __\ \/ /__/ __ |/ /__/ __ |    http://scala-lang.org/               **
** /____/\___/_/ |_/____/_/ | |                                         **
**                          |/                                          **
\*                                                                      */

package scala.sys

import scala.collection.mutable

/** The internal implementation of scala.sys.Prop.
 */
private[sys] class PropImpl[T](val key: String, valueFn: String => T) extends Prop[T] {
  def value: T = if (isSet) valueFn(get) else zero
  def isSet    = underlying contains key
  def set(newValue: String): String = {
    val old = if (isSet) get else null
    underlying(key) = newValue
    old
  }
  def get: String =
    if (isSet) underlying(key)
    else ""

  def clear() = underlying -= key

  /** The underlying property map, in our case always sys.props */
  protected def underlying: mutable.Map[String, String] = scala.sys.props
  protected def zero: T = null.asInstanceOf[T]
  private def getString = if (isSet) "currently: " + get else "unset"
  override def toString = "%s (%s)".format(key, getString)
}

trait PropCompanion {
  self: Prop.type =>

  private[sys] class BooleanPropImpl(key: String, valueFn: String => Boolean) extends PropImpl[Boolean](key, valueFn) with BooleanProp {
    def enable()  = this set "true"
    def disable() = this.clear()
    def toggle()  = if (value) disable() else enable()
  }
  private[sys] abstract class CreatorImpl[T](f: String => T) extends Creator[T] {
    def apply(key: String): Prop[T] = new PropImpl[T](key, f)
  }
  /** Implicit objects for the standard types.  Custom ones can also be
   *  created by implementing Creator[T].
   */
  private[sys] trait BooleanCreatorImpl extends Creator[Boolean] {
    self: BooleanProp.type =>

    /** As implemented in java.lang.Boolean.getBoolean. */
    private def javaStyleTruth(s: String) = s.toLowerCase == "true"

    def apply(key: String): BooleanProp = new BooleanPropImpl(key, javaStyleTruth)
  }
}
