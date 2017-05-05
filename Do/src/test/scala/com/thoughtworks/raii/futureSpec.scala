package com.thoughtworks.raii

import java.io.StringWriter

import com.thoughtworks.raii.future.Do
import org.scalatest.{FreeSpec, Matchers}

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

}
