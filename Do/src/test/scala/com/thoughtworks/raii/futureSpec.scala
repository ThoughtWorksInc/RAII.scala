package com.thoughtworks.raii

import java.io.StringWriter

import com.thoughtworks.raii.future.Do
import com.thoughtworks.raii.future.Do._

import scalaz.syntax.all._
import org.scalatest.{Assertion, FreeSpec, Matchers}

import scala.concurrent.Promise

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
final class futureSpec extends FreeSpec with Matchers {

  "Do.run must not compile for scoped resource" in {
    "Do.run(Do.scoped(new StringWriter))" shouldNot typeCheck
  }

  "Do.run must not compile for garbage collectable resource" in {
    "Do.run(Do.now('foo))" should compile
  }

  "Given a scoped resource" - {
    var isSourceClosed = false
    val source = Do.scoped(new AutoCloseable {
      isSourceClosed should be(false)
      override def close(): Unit = {
        isSourceClosed should be(false)
        isSourceClosed = true
      }
    })
    "And flatMap the resource to an new autoReleaseDependencies resource" - {
      var isResultClosed = false
      val result = autoReleaseDependencies(source.flatMap { sourceCloseable =>
        Do.scoped(new AutoCloseable {
          isResultClosed should be(false)
          override def close(): Unit = {
            isResultClosed should be(false)
            isResultClosed = true
          }
        })
      })
      "When map the new resource" - {
        "Then dependency resource should have been released" in {
          val p = Promise[Assertion]
          Do.run(result.map { r =>
              isSourceClosed should be(true)
              isResultClosed should be(false)
            })
            .unsafePerformAsync { either =>
              isSourceClosed should be(true)
              isResultClosed should be(true)
              p.complete(scalaz.std.`try`.fromDisjunction(either))
            }
          p.future
        }
      }
    }
  }
}
