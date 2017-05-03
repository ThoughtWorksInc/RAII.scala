package com.thoughtworks.raii

import java.security.acl.Owner

import scala.language.higherKinds
import scala.language.implicitConversions
import simulacrum.typeclass
import shapeless._

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object ownership {
  trait OwnedExtractor {
    type Owned[+Owner, +Ownage] <: Borrowing[Ownage]
    type Borrowing[+Ownage] <: Ownage
    def apply[Ownage, Owner](ownage: Ownage): Owner Owned Ownage
  }

  val Owned: OwnedExtractor = new OwnedExtractor {
    override type Owned[+Owner, +Ownage] = Ownage
    override type Borrowing[+Ownage] = Ownage

    override def apply[Ownage, Owner](ownage: Ownage): Ownage = ownage
  }

  type Owned[+Owner, +Ownage] = Owned.Owned[Owner, Ownage]
  type Borrowing[+Ownage] = Owned.Borrowing[Ownage]

  @typeclass
  trait Move[Ownage] {
    def apply[OldOwner: Witness.Aux, NewOwner](owned: OldOwner Owned Ownage): NewOwner Owned Ownage
  }
  @typeclass
  trait Copy[Ownage] {
    def apply[NewOwner](borrowing: Owned.Borrowing[Ownage]): NewOwner Owned Ownage
  }

  final class OwnOps[Owner] {
    def own[Ownage](ownage: Ownage): Owner Owned Ownage = Owned(ownage)
  }

  object implicits {
    implicit def toOwnOps(owner: AnyRef): OwnOps[owner.type] = new OwnOps[owner.type]

    implicit final class MoveOps[Ownage, OldOwner: Witness.Aux](owned: OldOwner Owned Ownage) {
      def move[NewOwner](implicit move: Move[Ownage]): NewOwner Owned Ownage = {
        move(owned)
      }
    }
    implicit final class DuplicateOps[Ownage](borrowing: Owned.Borrowing[Ownage]) {
      def move[NewOwner](implicit copy: Copy[Ownage]): NewOwner Owned Ownage = {
        copy(borrowing)
      }
    }
  }
}
