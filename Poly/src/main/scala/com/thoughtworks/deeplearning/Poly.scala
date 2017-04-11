package com.thoughtworks.deeplearning

import com.thoughtworks.deeplearning.Tape.Literal
import com.thoughtworks.raii.RAIITask
import shapeless.{DepFn1, Lazy, Poly1, Poly2}

/**
  * A namespace of common math operators.
  *
  * [[Poly.MathMethods MathMethods]] and [[Poly.MathFunctions MathFunctions]] provide functions like [[Poly.MathMethods.+ +]], [[Poly.MathMethods.- -]], [[Poly.MathMethods.* *]], [[Poly.MathMethods./ /]],
  * [[Poly.MathFunctions.log log]], [[Poly.MathFunctions.abs abs]], [[Poly.MathFunctions.max max]], [[Poly.MathFunctions.min min]] and [[Poly.MathFunctions.exp exp]], those functions been implements in specific Differentiable Object such as [[???]]
  *
  * @author 杨博 (Yang Bo) &lt;pop.atry@gmail.com&gt;
  */
object Poly {

  trait ToRAIITask[From] extends DepFn1[From] {
    type Data
    type Delta
    override final type Out = RAIITask.Covariant[Tape.Aux[Data, Delta]]
  }
  object ToRAIITask {

    type Aux[From, Data0, Delta0] = ToRAIITask[From] {
      type Data = Data0
      type Delta = Delta0
    }

    implicit def fromTape[Data0, Delta0, A <: Tape.Aux[Data0, Delta0]]: ToRAIITask.Aux[A, Data0, Delta0] = {
      new ToRAIITask[A] {
        override type Data = Data0
        override type Delta = Delta0

        override def apply(a: A): RAIITask.Covariant[Tape.Aux[Data0, Delta0]] = RAIITask.unmanaged(a)
      }

    }

    implicit def fromSubtype[Data0, Delta0, A <: RAIITask.Covariant[Tape.Aux[Data0, Delta0]]]
      : ToRAIITask.Aux[A, Data0, Delta0] = {
      new ToRAIITask[A] {
        override type Data = Data0
        override type Delta = Delta0
        override def apply(a: A): RAIITask.Covariant[Tape.Aux[Data0, Delta0]] = a
      }
    }

    implicit def fromData[Data0, Delta0]: ToRAIITask.Aux[Data0, Data0, Delta0] = {
      new ToRAIITask[Data0] {
        override type Data = Data0
        override type Delta = Delta0

        override def apply(a: Data): RAIITask.Covariant[Tape.Aux[Data0, Delta0]] = RAIITask.unmanaged(Literal(a))
      }

    }

  }

  object MathMethods {
    object - extends Poly2
    object + extends Poly2 {
      implicit def raiiTaskCase[Operand0, Operand1, Data0, Delta0, Data1, Delta1](
          implicit toRAIITask0: ToRAIITask.Aux[Operand0, Data0, Delta0],
          toRAIITask1: ToRAIITask.Aux[Operand1, Data1, Delta1],
          raiiTaskCase: Lazy[
            Case[RAIITask.Covariant[Tape.Aux[Data0, Delta0]], RAIITask.Covariant[Tape.Aux[Data1, Delta1]]]]
      ): Case.Aux[Operand0, Operand1, raiiTaskCase.value.Result] = {
        at { (operand0: Operand0, operand1: Operand1) =>
          def forceApply[A, B](lazyCase: Lazy[Case[A, B]], a: A, b: B): lazyCase.value.Result = {
            lazyCase.value(a, b)
          }
          forceApply[RAIITask.Covariant[Tape.Aux[Data0, Delta0]], RAIITask.Covariant[Tape.Aux[Data1, Delta1]]](
            raiiTaskCase,
            toRAIITask0(operand0),
            toRAIITask1(operand1))
        }
      }
    }
    object * extends Poly2
    object / extends Poly2
  }

  implicit final class MathOps[Left](left: Left) {
    def -[Right](right: Right)(implicit methodCase: MathMethods.-.Case[Left, Right]): methodCase.Result =
      MathMethods.-(left, right)

    def +[Right](right: Right)(implicit methodCase: MathMethods.+.Case[Left, Right]): methodCase.Result =
      MathMethods.+(left, right)

    def *[Right](right: Right)(implicit methodCase: MathMethods.*.Case[Left, Right]): methodCase.Result =
      MathMethods.*(left, right)

    def /[Right](right: Right)(implicit methodCase: MathMethods./.Case[Left, Right]): methodCase.Result =
      MathMethods./(left, right)
  }

  object MathFunctions {

    object log extends Poly1
    object exp extends Poly1
    object abs extends Poly1
    object max extends Poly2
    object min extends Poly2

  }

}
