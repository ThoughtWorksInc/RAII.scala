package com.thoughtworks.raii

import java.io.{Closeable, StringWriter, Writer}

import com.thoughtworks.raii.ownership._
import com.thoughtworks.raii.ownership.implicits._
import org.scalatest.{FreeSpec, Matchers}

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class ownershipSpec extends FreeSpec with Matchers {

  final class MyOwner(gift: Nothing Owned Writer) extends Closeable {
    val myWriter: this.type Owned Writer = gift

    def foo(mine: this.type Owned Writer): Unit = {}

    override def close(): Unit = {
      myWriter.close()
    }
  }

  "Given a resource owned by an owner" - {
    val owner1 = new MyOwner(opacityTypes(new StringWriter()))

    "Then the resource should be able to borrow" in {
      def scope(notMine: Borrowing[Writer]) = {}
      scope(owner1.myWriter)
    }

    "And the resource should be used for the owner" in {
      owner1.foo(owner1.myWriter)
    }

    "And the resource should not be owned by another owned" in {
      "val owner2 = new MyOwner(owner1.myWriter)" shouldNot typeCheck
    }

    "And the resource should not be used for another owner" in {
      val owner3 = new MyOwner(opacityTypes(new StringWriter()))
      "owner3.foo(owner1.myWriter)" shouldNot typeCheck
    }
  }
}
