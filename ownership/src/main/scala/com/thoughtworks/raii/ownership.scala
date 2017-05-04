package com.thoughtworks.raii

import scala.language.higherKinds
import scala.language.implicitConversions

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object ownership {
  private[ownership] trait OpacityTypes {
    type Owned[+Owner <: Singleton, +A] <: Scoped[A]
    type Scoped[+A] <: Borrowing[A]
    type GarbageCollectable[+A] <: Borrowing[A]
    type Borrowing[+A] <: A
    private[ownership] def own[Owner <: Singleton, A](a: A): Owner Owned A
    private[ownership] def garbageCollectable[A](a: A): GarbageCollectable[A]
  }

  @inline
  private[ownership] val opacityTypes: OpacityTypes = new OpacityTypes {
    override type Owned[+Owner <: Singleton, +A] = A
    override type Scoped[+A] = A
    override type GarbageCollectable[+A] = A
    override type Borrowing[+A] = A
    @inline override def own[Owner <: Singleton, A](a: A): A = a
    @inline override def garbageCollectable[A](a: A): A = a
  }

  type Scoped[+A] = opacityTypes.Scoped[A]

  type Owned[+Owner <: Singleton, +A] = opacityTypes.Owned[Owner, A]

  object Owned {

    def apply[Owner <: Singleton, A](a: A) = opacityTypes.own[Owner, A](a)
  }

  type GarbageCollectable[+A] = opacityTypes.GarbageCollectable[A]
  type Borrowing[+A] = opacityTypes.Borrowing[A]

  trait Move[OldOwner <: Singleton, A] {
    def apply[NewOwner <: Singleton](owned: OldOwner Owned A): NewOwner Owned A
  }

  trait Duplicate[A] {
    def apply[NewOwner <: Singleton](borrowing: Borrowing[A]): NewOwner Owned A
  }

  final class OwnOps[Owner <: Singleton] {
    def own[A](a: A): Owner Owned A = opacityTypes.own(a)
  }

  def garbageCollectable[A](a: A): GarbageCollectable[A] = opacityTypes.garbageCollectable(a)

  object implicits {
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
}
