package com.thoughtworks.raii

import scala.language.higherKinds
import scala.language.implicitConversions

/** The name space for types that models ownership of resources.
  *
  * @example {{{
  * import com.thoughtworks.raii.ownership._
  *
  * class TheOwner extends AutoCloseable {
  *   private val myStream: this.type Owned InputStream = this.own(new FileInputStream("foo.txt"))
  *   def close() = myStream.close()
  *   def withMyStream(f: Borrowing[InputStream] => Unit) = {
  *     f(myStream)
  *   }
  * }
  *
  * val owner = new TheOwner
  * owner.withMyStream { inputStream: Borrowing[InputStream] =>
  *   println(inputStream.read())
  * }
  * }}}
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object ownership {
  private[ownership] trait OpacityTypes {
    type Owned[-Owner <: Singleton, +A] <: Borrowing[A]
    type GarbageCollectable[+A] <: Borrowing[A]
    type Borrowing[+A] <: A
    private[ownership] def own[Owner <: Singleton, A](a: A): Owner Owned A
    private[ownership] def garbageCollectable[A](a: A): GarbageCollectable[A]
  }

  @inline
  private[ownership] val opacityTypes: OpacityTypes = new OpacityTypes {
    override type Owned[-Owner <: Singleton, +A] = A
    override type GarbageCollectable[+A] = A
    override type Borrowing[+A] = A
    @inline override def own[Owner <: Singleton, A](a: A): A = a
    @inline override def garbageCollectable[A](a: A): A = a
  }

  /** An resource `A` that is only available in certain scope.
    *
    * For example, a `FileInputStream` that will be automatically `close`d when exiting a scope.
    *
    * @note You should not store [[Scoped]] resource for out-of-scope usage.
    *
    * @example {{{
    * val outOfScopeStore = ArrayBuffer.empty[FileInputStream]
    * def read(stream: Scoped[FileInputStream]) = {
    *   // OK! You can use a scoped resource inside `read` method
    *   stream.read();
    *
    *   // Wrong! A scoped resource should not be stored.
    *   outOfScopeStore += stream
    *
    *   // Wrong! A scoped resource is managed by another owner. It should not be closed inside the scope.
    *   stream.close();
    * }
    * }}}
    */
  type Scoped[+A] = Nothing Owned A

  /** A [[Scoped]] resource managed by `Owner`.
    *
    * It's `Owner`'s responsibility to `close` or `release` the underlying resource when exiting the scope.
    */
  type Owned[-Owner <: Singleton, +A] = opacityTypes.Owned[Owner, A]

  object Owned {

    /** Declares ownership between `a` and `Owner`. */
    def apply[Owner <: Singleton, A](a: A) = opacityTypes.own[Owner, A](a)
  }

  /** An object `A` that is managed by garbage collector. */
  type GarbageCollectable[+A] = opacityTypes.GarbageCollectable[A]

  /** An object `A` that may be scoped.
    *
    * A method that accepts a [[Borrowing]] parameter must follows all instructions on [[Scoped]] resource.
    * However, a method that provides a [[Borrowing]] may create it either from [[GarbageCollectable]] or from [[Owned]].
    */
  type Borrowing[+A] = opacityTypes.Borrowing[A]

  trait Move[OldOwner <: Singleton, A] {
    def apply[NewOwner <: Singleton](owned: OldOwner Owned A): NewOwner Owned A
  }

  trait Duplicate[A] {
    def apply[NewOwner <: Singleton](borrowing: Borrowing[A]): NewOwner Owned A
  }

  final class OwnOps[Owner <: Singleton] {

    /** Declares ownership between `a` and `Owner`. */
    def own[A](a: A): Owner Owned A = opacityTypes.own(a)
  }

  /** Marks `a` as garbage collectable. */
  def garbageCollectable[A](a: A): GarbageCollectable[A] = opacityTypes.garbageCollectable(a)

  implicit def toOwnOps(owner: AnyRef): OwnOps[owner.type] = new OwnOps[owner.type]

  implicit final class MoveOps[OldOwner <: Singleton, A](owned: OldOwner Owned A) {
    def move[NewOwner <: Singleton](implicit move: Move[OldOwner, A]): NewOwner Owned A = {
      move(owned)
    }
  }
  implicit final class DuplicateOps[A](borrowing: Borrowing[A]) {
    def duplicate[NewOwner <: Singleton](implicit duplicate: Duplicate[A]): NewOwner Owned A = {
      duplicate(borrowing)
    }
  }

}
