package com.thoughtworks.raii

import scala.language.higherKinds
import scala.language.implicitConversions
import simulacrum.typeclass

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object ownership {

  @typeclass
  trait Move[A] {
    def unsafeMove(a: A): A
  }

  @typeclass
  trait Duplicate[A] {
    def unsafeDuplicate(a: A): A
  }

  @typeclass
  trait RAII[A] extends Duplicate[A] with Move[A]

  private[ownership] trait Pimp {

    type Ownage[A, Owner <: Singleton] <: A
    type Borrow[A] <: A

    def borrow[A, Owner <: Singleton](ownage: Ownage[A, Owner]): Borrow[A]
    def duplicate[A: Duplicate, OldOwner <: Singleton, NewOwner <: Singleton](
        ownage: Ownage[A, OldOwner]): Ownage[A, NewOwner]
    def move[A: Move, OldOwner <: Singleton, NewOwner <: Singleton](ownage: Ownage[A, OldOwner]): Ownage[A, NewOwner]

  }

  private[ownership] val pimp: Pimp = new Pimp {
    override type Ownage[A, Owner <: Singleton] = A
    override type Borrow[A] = A

    override def borrow[A, Owner <: Singleton](a: A): A = a

    override def duplicate[A: Duplicate, Owner <: Singleton, NewOwner <: Singleton](ownage: A): A =
      Duplicate[A].unsafeDuplicate(ownage)

    override def move[A: Move, Owner <: Singleton, NewOwner <: Singleton](ownage: A): A = Move[A].unsafeMove(ownage)
  }

  type Ownage[A, Owner <: Singleton] = pimp.Ownage[A, Owner]
  type Borrow[A] = pimp.Borrow[A]

  implicit final class OwnageOps[A, Owner <: Singleton](underlying: Ownage[A, Owner]) {
    def move[NewOwner <: Singleton](implicit move: Move[A]) = pimp.move(underlying)
    def duplicate[NewOwner <: Singleton](implicit duplicate: Duplicate[A]) = pimp.duplicate(underlying)
  }

}
