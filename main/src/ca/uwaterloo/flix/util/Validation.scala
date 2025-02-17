/*
 * Copyright 2018 Magnus Madsen
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.uwaterloo.flix.util

import scala.collection.mutable

sealed trait Validation[+T, +E] {

  /**
    * Returns the value inside `this` [[Validation.Success]] object.
    *
    * Throws an exception if `this` is a [[Validation.Failure]] object.
    */
  final def get: T = this match {
    case Validation.Success(value) => value
    case Validation.SoftFailure(_, errors) => throw new RuntimeException(s"Attempt to retrieve value from SoftFailure. The errors are: ${errors.mkString(", ")}")
    case Validation.Failure(errors) => throw new RuntimeException(s"Attempt to retrieve value from Failure. The errors are: ${errors.mkString(", ")}")
  }

  /**
    * Returns a [[Validation.Success]] containing the result of applying `f` to the value in this validation (if it exists).
    *
    * Preserves the errors.
    */
  final def map[U](f: T => U): Validation[U, E] = this match {
    case Validation.Success(value) => Validation.Success(f(value))
    case Validation.SoftFailure(value, errors) => Validation.SoftFailure(f(value), errors)
    case Validation.Failure(errors) => Validation.Failure(errors)
  }

  /**
    * Returns the errors in this [[Validation.Success]] or [[Validation.Failure]] object.
    */
  def errors: LazyList[E]

  /**
    * Converts a soft failure to a hard failure.
    */
  def toHardFailure: Validation[T, E] = this match {
    case Validation.Success(t) => Validation.Success(t)
    case Validation.SoftFailure(t, errors) => Validation.Failure(errors)
    case Validation.Failure(errors) => Validation.Failure(errors)
  }

  /**
    * Transform exactly one hard error into a soft error using the given function `f`.
    */
  def recoverOne[U >: T](f: PartialFunction[E, U]): Validation[U, E] = this match {
    case Validation.Failure(errors) if errors.length == 1 =>
      val one = errors.head
      if (f.isDefinedAt(one))
        Validation.SoftFailure(f(one), LazyList(one))
      else
        this
    case _ => this
  }

}

object Validation {

  /**
    * Implicit class to hide dangerous extensions.
    */
  object Implicit {

    /**
      * Treats the Validation as a monad in order to enable `for`-notation.
      * Care should be used when enabling this,
      * as flatMap results in short-circuiting behavior which may not be desirable.
      */
    implicit class AsMonad[T, E](v: Validation[T, E]) {
      /**
        * Similar to `map` but does not wrap the result in a [[Validation.Success]].
        *
        * Preserves the errors.
        */
      final def flatMap[U, A >: E](f: T => Validation[U, A]): Validation[U, A] = v match {
        case Validation.Success(input) => f(input) match {
          case Validation.Success(value) => Validation.Success(value)
          case Validation.SoftFailure(value, thatErrors) => Validation.SoftFailure(value, v.errors #::: thatErrors)
          case Validation.Failure(thatErrors) => Validation.Failure(v.errors #::: thatErrors)
        }

        case Validation.SoftFailure(input, errors) => f(input) match {
          case Validation.Success(value) => Validation.SoftFailure(value, errors)
          case Validation.SoftFailure(value, thatErrors) => Validation.SoftFailure(value, errors #::: thatErrors)
          case Validation.Failure(thatErrors) => Validation.Failure(errors #::: thatErrors)
        }

        case Validation.Failure(errors) => Validation.Failure(errors)
      }
    }
  }

  /**
    * Represents a successful validation with the empty list.
    */
  final val SuccessNil = Success(Nil)

  /**
    * Represents a successful validation with the empty option.
    */
  final val SuccessNone = Success(None)

  /**
    * Represents a successful validation with the unit value.
    */
  final val SuccessUnit = Success(())

  /**
    * Represents a success `value`.
    */
  case class Success[T, E](t: T) extends Validation[T, E] {
    def errors: LazyList[E] = LazyList.empty
  }

  /**
    * Represents a success that contains a value and non-critical `errors`.
    */
  case class SoftFailure[T, E](t: T, errors: LazyList[E]) extends Validation[T, E]

  /**
    * Represents a failure with no value and `errors`.
    */
  case class Failure[T, E](errors: LazyList[E]) extends Validation[T, E]

  /**
    * Sequences the given list of validations `xs`.
    */
  def sequence[T, E](xs: Iterable[Validation[T, E]]): Validation[List[T], E] = {
    val zero = SuccessNil: Validation[List[T], E]
    xs.foldRight(zero) {
      case (Success(curValue), Success(accValue)) =>
        Success(curValue :: accValue)
      case (Success(curValue), SoftFailure(accValue, accErrors)) =>
        SoftFailure(curValue :: accValue, accErrors)
      case (Success(_), Failure(accErrors)) =>
        Failure(accErrors)

      case (SoftFailure(curValue, curErrors), Success(accValue)) =>
        SoftFailure(curValue :: accValue, curErrors)
      case (SoftFailure(curValue, curErrors), SoftFailure(accValue, accErrors)) =>
        SoftFailure(curValue :: accValue, curErrors #::: accErrors)
      case (SoftFailure(_, curErrors), Failure(accErrors)) =>
        Failure(curErrors #::: accErrors)

      case (Failure(curErrors), Success(_)) =>
        Failure(curErrors)
      case (Failure(curErrors), SoftFailure(_, accErrors)) =>
        Failure(curErrors #::: accErrors)
      case (Failure(curErrors), Failure(accErrors)) =>
        Failure(curErrors #::: accErrors)
    }
  }

  /**
    * Sequences the given list of validations `xs`, ignoring non-error results.
    */
  def sequenceX[T, E](xs: Iterable[Validation[T, E]]): Validation[Unit, E] = {
    sequence(xs).map(_ => ())
  }

  /**
    * Traverses `xs` applying the function `f` to each element.
    */
  def traverse[T, S, E](xs: Iterable[T])(f: T => Validation[S, E]): Validation[List[S], E] = fastTraverse(xs)(f)

  /**
    * Traverses the given map, applying the function `f` to each value.
    */
  def traverseValues[K, V1, V2, E](xs: Map[K, V1])(f: V1 => Validation[V2, E]): Validation[Map[K, V2], E] = {
    traverse(xs) {
      case (k, v0) => mapN(f(v0))(v => k -> v)
    }.map(_.toMap)
  }

  /**
    * Traverses `o` applying the function `f` to the value, if it exists.
    */
  def traverseOpt[T, S, E](o: Option[T])(f: T => Validation[S, E]): Validation[Option[S], E] = o match {
    case None => Validation.SuccessNone
    case Some(x) => f(x) match {
      case Success(t) => Success(Some(t))
      case SoftFailure(t, errs) => SoftFailure(Some(t), errs)
      case Failure(errs) => Failure(errs)
    }
  }

  /**
    * Traverses `xs` applying the function `f` to each element, ignoring non-error results.
    */
  def traverseX[T, E](xs: Iterable[T])(f: T => Validation[_, E]): Validation[Unit, E] = {
    traverse(xs)(f).map(_ => ())
  }

  /**
    * A fast implementation of traverse.
    */
  private def fastTraverse[T, S, E](xs: Iterable[T])(f: T => Validation[S, E]): Validation[List[S], E] = {
    // Check if the sequence is empty.
    if (xs.isEmpty)
      return Validation.SuccessNil

    // Two mutable arrays to hold the intermediate results.
    val successValues = mutable.ArrayBuffer.empty[S]
    val failureStream = mutable.ArrayBuffer.empty[LazyList[E]]

    // Flag to signal fatal (non-recoverable) errors
    var isFatal = false

    // Apply f to each element and collect the results.
    for (x <- xs) {
      f(x) match {
        case Success(v) => successValues += v
        case SoftFailure(v, e) =>
          successValues += v
          failureStream += e
        case Failure(e) =>
          failureStream += e
          isFatal = true
      }
    }

    // Check whether we were successful or not.
    if (isFatal) {
      Failure(failureStream.foldLeft(LazyList.empty[E])(_ #::: _))
    } else if (failureStream.nonEmpty) {
      SoftFailure(successValues.toList, failureStream.foldLeft(LazyList.empty[E])(_ #::: _))
    }
    else {
      Success(successValues.toList)
    }
  }

  /**
    * Returns the `Validation` inside `t1`.
    *
    * Preserves all errors.
    */
  def flatten[U, E](t1: Validation[Validation[U, E], E]): Validation[U, E] = t1 match {
    case Success(Success(t)) =>
      Success(t)
    case Success(SoftFailure(t, e)) =>
      SoftFailure(t, e)
    case Success(Failure(e)) =>
      Failure(e)
    case SoftFailure(Success(t), errors) =>
      SoftFailure(t, errors)
    case SoftFailure(SoftFailure(t, e), errors) =>
      SoftFailure(t, errors #::: e)
    case SoftFailure(Failure(e), errors) =>
      Failure(errors #::: e)
    case Failure(errors) =>
      Failure(errors)
  }

  /**
    * Applies the function inside `f` to the value inside `t`.
    *
    * Preserves all errors.
    */
  def ap[T1, U, E](f: Validation[T1 => U, E])(t1: Validation[T1, E]): Validation[U, E] =
    (f, t1) match {
      case (Success(g), Success(v)) =>
        Success(g(v))
      case (Success(g), SoftFailure(v, e2)) =>
        SoftFailure(g(v), e2)
      case (Success(_), Failure(e2)) =>
        Failure(e2)

      case (SoftFailure(g, e1), Success(v)) =>
        SoftFailure(g(v), e1)
      case (SoftFailure(g, e1), SoftFailure(v, e2)) =>
        SoftFailure(g(v), e1 #::: e2)
      case (SoftFailure(_, e1), Failure(e2)) =>
        Failure(e1 #::: e2)

      case (Failure(e1), Success(_)) =>
        Failure(e1)
      case (Failure(e1), SoftFailure(_, e2)) =>
        Failure(e1 #::: e2)
      case (Failure(e1), Failure(e2)) =>
        Failure(e1 #::: e2)
    }

  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3](f: (T1, T2) => T3): T1 => T2 => T3 =
    (t1: T1) => (t2: T2) => f(t1, t2)

  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4](f: (T1, T2, T3) => T4): (T1, T2) => T3 => T4 =
    (t1: T1, t2: T2) => (t3: T3) => f(t1, t2, t3)

  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5](f: (T1, T2, T3, T4) => T5): (T1, T2, T3) => T4 => T5 =
    (t1: T1, t2: T2, t3: T3) => (t4: T4) => f(t1, t2, t3, t4)


  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5, T6](f: (T1, T2, T3, T4, T5) => T6): (T1, T2, T3, T4) => T5 => T6 =
    (t1: T1, t2: T2, t3: T3, t4: T4) => (t5: T5) => f(t1, t2, t3, t4, t5)


  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5, T6, T7](f: (T1, T2, T3, T4, T5, T6) => T7): (T1, T2, T3, T4, T5) => T6 => T7 =
    (t1: T1, t2: T2, t3: T3, t4: T4, t5: T5) => (t6: T6) => f(t1, t2, t3, t4, t5, t6)


  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5, T6, T7, T8](f: (T1, T2, T3, T4, T5, T6, T7) => T8): (T1, T2, T3, T4, T5, T6) => T7 => T8 =
    (t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6) => (t7: T7) => f(t1, t2, t3, t4, t5, t6, t7)


  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5, T6, T7, T8, T9](f: (T1, T2, T3, T4, T5, T6, T7, T8) => T9): (T1, T2, T3, T4, T5, T6, T7) => T8 => T9 =
    (t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7) => (t8: T8) => f(t1, t2, t3, t4, t5, t6, t7, t8)


  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10](f: (T1, T2, T3, T4, T5, T6, T7, T8, T9) => T10): (T1, T2, T3, T4, T5, T6, T7, T8) => T9 => T10 =
    (t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8) => (t9: T9) => f(t1, t2, t3, t4, t5, t6, t7, t8, t9)

  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11](f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10) => T11): (T1, T2, T3, T4, T5, T6, T7, T8, T9) => T10 => T11 =
    (t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9) => (t10: T10) => f(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)

  /**
    * Returns `f` with the last parameter curried.
    */
  private def curry[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, T12](f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11) => T12): (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10) => T11 => T12 =
    (t1: T1, t2: T2, t3: T3, t4: T4, t5: T5, t6: T6, t7: T7, t8: T8, t9: T9, t10: T10) => (t11: T11) => f(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, t11)

  /**
    * Maps over t1.
    */
  def mapN[T1, U, E](t1: Validation[T1, E])
                    (f: T1 => U): Validation[U, E] =
    t1 match {
      case Success(v1) => Success(f(v1))
      case SoftFailure(v1, err1) => SoftFailure(f(v1), err1)
      case Failure(errors) => Failure(errors)
    }

  /**
    * Maps over t1 and t2.
    */
  def mapN[T1, T2, U, E](t1: Validation[T1, E], t2: Validation[T2, E])
                        (f: (T1, T2) => U): Validation[U, E] =
    ap(mapN(t1)(curry(f)))(t2)

  /**
    * Maps over t1, t2, and t3.
    */
  def mapN[T1, T2, T3, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E])
                            (f: (T1, T2, T3) => U): Validation[U, E] =
    ap(mapN(t1, t2)(curry(f)))(t3)

  /**
    * Maps over t1, t2, t3, and t4.
    */
  def mapN[T1, T2, T3, T4, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                 t4: Validation[T4, E])
                                (f: (T1, T2, T3, T4) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3)(curry(f)))(t4)

  /**
    * Maps over t1, t2, t3, t4, and t5.
    */
  def mapN[T1, T2, T3, T4, T5, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                     t4: Validation[T4, E], t5: Validation[T5, E])
                                    (f: (T1, T2, T3, T4, T5) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3, t4)(curry(f)))(t5)

  /**
    * Maps over t1, t2, t3, t4, t5, and t6.
    */
  def mapN[T1, T2, T3, T4, T5, T6, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                         t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E])
                                        (f: (T1, T2, T3, T4, T5, T6) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3, t4, t5)(curry(f)))(t6)

  /**
    * Maps over t1, t2, t3, t4, t5, t6, and t7.
    */
  def mapN[T1, T2, T3, T4, T5, T6, T7, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                             t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E],
                                             t7: Validation[T7, E])
                                            (f: (T1, T2, T3, T4, T5, T6, T7) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3, t4, t5, t6)(curry(f)))(t7)

  /**
    * Maps over t1, t2, t3, t4, t5, t6, t7, and t8.
    */
  def mapN[T1, T2, T3, T4, T5, T6, T7, T8, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                                 t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E],
                                                 t7: Validation[T7, E], t8: Validation[T8, E])
                                                (f: (T1, T2, T3, T4, T5, T6, T7, T8) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3, t4, t5, t6, t7)(curry(f)))(t8)

  /**
    * Maps over t1, t2, t3, t4, t5, t6, t7, t8, and t9.
    */
  def mapN[T1, T2, T3, T4, T5, T6, T7, T8, T9, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                                     t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E],
                                                     t7: Validation[T7, E], t8: Validation[T8, E], t9: Validation[T9, E])
                                                    (f: (T1, T2, T3, T4, T5, T6, T7, T8, T9) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3, t4, t5, t6, t7, t8)(curry(f)))(t9)

  /**
    * Maps over t1, t2, t3, t4, t5, t6, t7, t8, t9, and t10
    */
  def mapN[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                                          t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E],
                                                          t7: Validation[T7, E], t8: Validation[T8, E], t9: Validation[T9, E],
                                                          t10: Validation[T10, E])
                                                         (f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3, t4, t5, t6, t7, t8, t9)(curry(f)))(t10)

  /**
    * Maps over t1, t2, t3, t4, t5, t6, t7, t8, t9, t10, and t11
    */
  def mapN[T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                                               t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E],
                                                               t7: Validation[T7, E], t8: Validation[T8, E], t9: Validation[T9, E],
                                                               t10: Validation[T10, E], t11: Validation[T11, E])
                                                              (f: (T1, T2, T3, T4, T5, T6, T7, T8, T9, T10, T11) => U): Validation[U, E] =
    ap(mapN(t1, t2, t3, t4, t5, t6, t7, t8, t9, t10)(curry(f)))(t11)

  /**
    * FlatMaps over t1.
    */
  def flatMapN[T1, U, E](t1: Validation[T1, E])(f: T1 => Validation[U, E]): Validation[U, E] =
    t1 match {
      case Success(v1) => f(v1)
      case SoftFailure(v1, e1) => f(v1) match {
        case Success(x) => SoftFailure(x, e1)
        case SoftFailure(x, funcErrors) => SoftFailure(x, e1 #::: funcErrors)
        case Failure(funcErrors) => Failure(e1 #::: funcErrors)
      }
      case _ => Failure(t1.errors)
    }

  /**
    * FlatMaps over t1 and t2.
    */
  def flatMapN[T1, T2, U, E](t1: Validation[T1, E], t2: Validation[T2, E])
                            (f: (T1, T2) => Validation[U, E]): Validation[U, E] =
    flatten(ap(mapN(t1)(curry(f)))(t2))

  /**
    * FlatMaps over t1, t2, and t3.
    */
  def flatMapN[T1, T2, T3, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E])
                                (f: (T1, T2, T3) => Validation[U, E]): Validation[U, E] =
    flatten(ap(mapN(t1, t2)(curry(f)))(t3))

  /**
    * FlatMaps over t1, t2, t3, and t4.
    */
  def flatMapN[T1, T2, T3, T4, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                     t4: Validation[T4, E])
                                    (f: (T1, T2, T3, T4) => Validation[U, E]): Validation[U, E] =
    flatten(ap(mapN(t1, t2, t3)(curry(f)))(t4))

  /**
    * FlatMaps over t1, t2, t3, t4, and t5.
    */
  def flatMapN[T1, T2, T3, T4, T5, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                         t4: Validation[T4, E], t5: Validation[T5, E])
                                        (f: (T1, T2, T3, T4, T5) => Validation[U, E]): Validation[U, E] =
    flatten(ap(mapN(t1, t2, t3, t4)(curry(f)))(t5))

  /**
    * FlatMaps over t1, t2, t3, t4, t5, and t6.
    */
  def flatMapN[T1, T2, T3, T4, T5, T6, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                             t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E])
                                            (f: (T1, T2, T3, T4, T5, T6) => Validation[U, E]): Validation[U, E] =
    flatten(ap(mapN(t1, t2, t3, t4, t5)(curry(f)))(t6))

  /**
    * FlatMaps over t1, t2, t3, t4, t5, and t6.
    */
  def flatMapN[T1, T2, T3, T4, T5, T6, T7, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                                 t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E],
                                                 t7: Validation[T7, E])
                                                (f: (T1, T2, T3, T4, T5, T6, T7) => Validation[U, E]): Validation[U, E] =
    flatten(ap(mapN(t1, t2, t3, t4, t5, t6)(curry(f)))(t7))

  /**
    * Sequences over t1, t2, and t3.
    */
  def sequenceT[T1, T2, T3, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E]): Validation[(T1, T2, T3), E] =
    mapN(t1, t2, t3)(Function.untupled(identity))

  /**
    * Sequences over t1, t2, t3, and t4.
    */
  def sequenceT[T1, T2, T3, T4, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                      t4: Validation[T4, E]): Validation[(T1, T2, T3, T4), E] =
    mapN(t1, t2, t3, t4)(Function.untupled(identity))

  /**
    * Sequences over t1, t2, t3, t4, and t5.
    */
  def sequenceT[T1, T2, T3, T4, T5, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                          t4: Validation[T4, E], t5: Validation[T5, E]): Validation[(T1, T2, T3, T4, T5), E] =
    mapN(t1, t2, t3, t4, t5)(Function.untupled(identity))

  /**
    * Sequences over t1, t2, t3, t4, t5, and t6.
    */
  def sequenceT[T1, T2, T3, T4, T5, T6, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                              t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E]): Validation[(T1, T2, T3, T4, T5, T6), E] =
    mapN(t1, t2, t3, t4, t5, t6) {
      case (u1, u2, u3, u4, u5, u6) => (u1, u2, u3, u4, u5, u6)
    }

  /**
    * Sequences over t1, t2, t3, t4, t5, and t6.
    */
  def sequenceT[T1, T2, T3, T4, T5, T6, T7, U, E](t1: Validation[T1, E], t2: Validation[T2, E], t3: Validation[T3, E],
                                                  t4: Validation[T4, E], t5: Validation[T5, E], t6: Validation[T6, E],
                                                  t7: Validation[T7, E]): Validation[(T1, T2, T3, T4, T5, T6, T7), E] =
    mapN(t1, t2, t3, t4, t5, t6, t7) {
      case (u1, u2, u3, u4, u5, u6, u7) => (u1, u2, u3, u4, u5, u6, u7)
    }

  /**
    * Folds Right over `xs` using the function `f` with the initial value `zero`.
    */
  def foldRight[T, U, E](xs: Seq[T])(zero: Validation[U, E])(f: (T, U) => Validation[U, E]): Validation[U, E] = {
    xs.foldRight(zero) {
      case (a, acc) => flatMapN(acc) {
        case v => f(a, v)
      }
    }
  }

  /**
    * Adds an implicit `toSuccess` method.
    */
  implicit class ToSuccess[+T](val t: T) {
    def toSuccess[U >: T, E]: Validation[U, E] = Success(t)
  }

  /**
    * Adds an implicit `toSoftFailure` method.
    */
  implicit class ToSoftFailure[+T](val t: T) {
    /**
      * Returns a [[Validation.SoftFailure]] containing [[t]] with no errors.
      */
    def toSoftFailure[U >: T, E](e: E): Validation[U, E] = Validation.SoftFailure(t, LazyList(e))
  }

  /**
    * Adds an implicit `toFailure` method.
    */
  implicit class ToFailure[+E](val e: E) {
    def toFailure[V, F >: E]: Validation[V, F] = Failure(e #:: LazyList.empty)
  }

  // TODO: Everything below this line is deprecated.

  /**
    * Folds the given function `f` over all elements `xs`.
    *
    * Returns a sequence of successful elements wrapped in [[Success]].
    */
  def fold[In, Out, Error](xs: Iterable[In], zero: Out)(f: (Out, In) => Validation[Out, Error]): Validation[Out, Error] = {
    xs.foldLeft(Success(zero): Validation[Out, Error]) {
      case (acc, a) => flatMapN(acc) {
        case value => f(value, a)
      }
    }
  }

}

