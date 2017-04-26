//package com.thoughtworks.raii.sde
//
//import com.thoughtworks.raii.ResourceFactory
//import com.thoughtworks.raii.ResourceFactoryTSpec.{FakeResource, managed}
//import org.scalatest.{FreeSpec, Matchers}
//import raii.using
//
//import scala.collection.mutable
//import scalaz.{-\/, \/-}
//
///**
//  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
//  */
//final class raiiSpec extends FreeSpec with Matchers {
//
//  "both of resources must acquire and release in complicated usage" in {
//    val allOpenedResources = mutable.HashMap.empty[String, FakeResource]
//    allOpenedResources.keys shouldNot contain("mr0")
//    allOpenedResources.keys shouldNot contain("mr1")
//
//    raii {
//      val r0 = using(new FakeResource(allOpenedResources, "mr0"))
//      val x = 0
//      val r1 = using(new FakeResource(allOpenedResources, "mr1"))
//      val y = 1
//      allOpenedResources("mr0") should be(r0)
//      allOpenedResources("mr1") should be(r1)
//    }
//
//    allOpenedResources.keys shouldNot contain("mr0")
//    allOpenedResources.keys shouldNot contain("mr1")
//
//  }
//
//  "during using, an AutoCloseable should automatically open and close" in {
//
//    var count = 0
//    var isClosed = true
//
//    final class FakeResouce extends AutoCloseable {
//      count += 1
//      isClosed should be(true)
//      isClosed = false
//      override def close(): Unit = {
//        isClosed should be(false)
//        isClosed = true
//      }
//    }
//
//    raii {
//      isClosed should be(true)
//      using(new FakeResouce)
//      isClosed should be(false)
//    }
//
//    isClosed should be(true)
//    count should be(1)
//
//  }
//
//  "during raii nested blocks, the AutoCloseable should automatically open and close" in {
//
//    var count = 0
//    var isClosed0 = true
//
//    final class FakeResouce0 extends AutoCloseable {
//      count += 1
//      isClosed0 should be(true)
//      isClosed0 = false
//      override def close(): Unit = {
//        isClosed0 should be(false)
//        isClosed0 = true
//      }
//    }
//    var isClosed1 = true
//
//    final class FakeResouce1 extends AutoCloseable {
//      count += 1
//      isClosed1 should be(true)
//      isClosed1 = false
//      override def close(): Unit = {
//        isClosed1 should be(false)
//        isClosed1 = true
//      }
//    }
//
//    raii {
//      isClosed1 should be(true)
//      using(new FakeResouce1)
//      isClosed1 should be(false)
//
//      raii {
//        isClosed1 should be(false)
//        isClosed0 should be(true)
//        using(new FakeResouce0)
//        isClosed1 should be(false)
//        isClosed0 should be(false)
//      }
//      isClosed1 should be(false)
//      isClosed0 should be(true)
//    }
//
//    isClosed1 should be(true)
//    isClosed0 should be(true)
//    count should be(2)
//
//  }
//
//  "during a raii block, the AutoCloseable should automatically open and close" in {
//
//    var count = 0
//    var isClosed = true
//
//    final class FakeResouce extends AutoCloseable {
//      count += 1
//      isClosed should be(true)
//      isClosed = false
//      override def close(): Unit = {
//        isClosed should be(false)
//        isClosed = true
//      }
//    }
//
//    raii {
//      isClosed should be(true)
//      using(new FakeResouce)
//      isClosed should be(false)
//    }
//
//    isClosed should be(true)
//    count should be(1)
//
//  }
//
//}
