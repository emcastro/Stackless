package emcastro.stackless

import scala.language.implicitConversions

/**
 * Trampoline system from http://days2012.scala-lang.org/sites/days2012/files/bjarnason_trampolines.pdf
 */
sealed trait Stackless[+A] {

  import Stackless._

  final def resume: Either[() => Stackless[A], A] =
    this match {
      case Done(v) => Right(v)
      case More(k) => Left(k)
      case AndThen(sub, k) => sub match {
        case Done(v) => k(v).resume
        case More(k2) => Left(() => k2() andThen k)
        case AndThen(sub2, k2) =>
          sub2.andThen((s: Any) => k2(s) andThen k).resume
      }
    }

  final def run: A = resume match {
    case Right(a) => a
    case Left(k) => k().run
  }

  def andThen[B](f: A => Stackless[B]): Stackless[B] =
    this match {
      case AndThen(a, g) => AndThen(a, (x: Any) => AndThen(g(x), f)) // Modification inspired by scalaz 7.2 Free.flatMap to prevent StackOverflow
      case x => AndThen(x, f)
    }

  def flatMap[B](f: A => Stackless[B]): Stackless[B] = andThen(f)

  def map[B](f: A => B): Stackless[B] = andThen(a => Done(f(a)))

  def foreach[B](f: A => Unit): Unit = map(f).run

  def result = run

}

object Stackless {

  private[stackless] case class More[+A](k: () => Stackless[A]) extends Stackless[A]

  private[stackless] case class Done[+A](res: A) extends Stackless[A]

  /** Concatenation of two computation. Named FlatMap in bjarnason_trampolines.pdf */
  private[stackless] case class AndThen[A, +B](sub: Stackless[A], k: A => Stackless[B]) extends Stackless[B]

  @inline implicit def done[A](result: A): Stackless[A] = Done(result)

  @inline def delayed[A](k: => Stackless[A]): Stackless[A] = More(() => k)
}
