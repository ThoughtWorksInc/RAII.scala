package com.thoughtworks.raii.sde

import com.thoughtworks.raii.{ResourceFactory, ResourceFactoryT}
import com.thoughtworks.sde.core.MonadicFactory
import macrocompat.bundle
import scala.language.higherKinds
import scala.reflect.macros.whitebox
import scala.language.experimental.macros
import scalaz._
import scala.util.control.NonFatal

/**
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object raii {
  object RAIIFactory extends MonadicFactory.WithTypeClass[MonadError[?[_], Throwable], ResourceFactory] {
    override val typeClass = ResourceFactoryT.resourceFactoryTMonadError[Throwable \/ ?, Throwable]
  }
  @bundle
  private[raii] class Macros(val c: whitebox.Context) {
    import c.universe._

    def apply(block: Tree): Tree = {
      q"_root_.com.thoughtworks.raii.sde.raii.reduce(_root_.com.thoughtworks.raii.sde.raii.RAIIFactory($block))"
    }

    def using[A: WeakTypeTag](autoCloseable: Tree): Tree = {
      q"""
        _root_.com.thoughtworks.sde.core.MonadicFactory.Instructions.each[
          _root_.com.thoughtworks.raii.ResourceFactory,
          ${weakTypeOf[A]}
        ](_root_.com.thoughtworks.raii.sde.raii.managed[${weakTypeOf[A]}]($autoCloseable))
      """
    }

  }

  def reduce[A](resourceFactory: ResourceFactory[A]): A = {
    resourceFactory.run match {
      case \/-(a) => a
      case -\/(e) => throw e
    }
  }

  def managed[A <: AutoCloseable](autoCloseable: => A): ResourceFactory[A] = {
    new ResourceFactory[A] {
      override def acquire(): Throwable \/ ResourceFactoryT.ReleasableT[Throwable \/ ?, A] = {
        try {
          \/-(new ResourceFactoryT.ReleasableT[Throwable \/ ?, A] {
            override val value = autoCloseable

            override def release() = {
              try {
                \/-(value.close())
              } catch {
                case NonFatal(e) =>
                  -\/(e)
              }
            }
          })
        } catch {
          case NonFatal(e) =>
            -\/(e)
        }
      }
    }
  }

  def using[A <: AutoCloseable](autoCloseable: => A): A = macro Macros.using[A]
  def apply[A](block: => A): A = macro Macros.apply
}
