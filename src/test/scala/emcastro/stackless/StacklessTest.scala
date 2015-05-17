package emcastro.stackless

import utest.framework.TestSuite

import scala.annotation.tailrec
import scala.language.implicitConversions

object StacklessTest extends TestSuite {
  def tests = TestSuite {

    import Stackless._
    import utest._

    "Stackless is a monad" - {
      /**
       * Checking the 3 monad laws - https://wiki.haskell.org/Monad_laws
       * return : done()
       * >>=    : .andThen()
       */

      // Some arbitrary monadic functions
      def f(a: String): Stackless[String] = delayed(done(a.toLowerCase))
      def g(a: String): Stackless[String] = done(a + a).andThen(v => done(v))
      def m = done("B").andThen(_.toLowerCase)

      assert(f("A").result == "a")

      "Left identity" - {
        assert(done("A").andThen(f).result == f("A").result)
      }

      assert(m.result == "b")

      "Right identity" - {
        assert(m.andThen(done).result == m.result)
      }

      assert(m.andThen(f).andThen(g).result == "bb")

      "Associativity" - {
        assert(m.andThen(f).andThen(g).result == m.andThen(v => f(v).andThen(g)).result)
      }

    }

    "Trampoline for biphasic algorithm" - {

      case class ANode(name: String, children: BNode*)

      case class BNode(qty: Int, children: ANode*)

      // Construction of a chain of ANode and BNode
      def longABChain(i: Int): BNode = {
        @tailrec
        def longABChain(acc: BNode, i: Int): BNode =
          if (i > 0) longABChain(BNode(i, ANode("x" + i, acc)), i - 1)
          else acc

        longABChain(BNode(i), i-1)
      }

      val data =
        ANode("root",
          BNode(1,
            ANode("a"),
            ANode("b",
              BNode(2),
              BNode(3, ANode("c"))
            )),
          BNode(4, ANode("d", longABChain(100000)))
        )

      type StringOrInt = Any

      // Trampoline for biphasic algorithm.
      def breadthFirstA(acc: List[Seq[StringOrInt]], aNodes: Seq[ANode]): Stackless[Seq[Seq[StringOrInt]]] = {
        if (aNodes.isEmpty) done(acc)
        else delayed(breadthFirstB(aNodes.map(_.name) :: acc, aNodes.flatMap(_.children)))
      }

      def breadthFirstB(acc: List[Seq[StringOrInt]], bNodes: Seq[BNode]): Stackless[Seq[Seq[StringOrInt]]] = {
        if (bNodes.isEmpty) acc
        else delayed(breadthFirstA(bNodes.map(_.qty) :: acc, bNodes.flatMap(_.children)))
      }

      val asserted =
        List(
          List("root"),
          List(1, 4),
          List("a", "b", "d"),
          List(2, 3, 1),
          List("c", "x1"),
          List(2),
          List("x2"),
          List(3),
          List("x3") // etc.
        )
      assert(breadthFirstA(Nil, Seq(data)).result.reverse.take(9) == asserted)

    }

    "We really are stackless" - {
      // Stupid way building the range of integer from a to b.

      def range(a: Int, b: Int): List[Int] =
        if (a == b) Nil
        else {
          // a :: range(a + 1, b)
          val nextRange = range(a + 1, b)
          a :: nextRange
        }

      assert(range(1, 10) == List(1, 2, 3, 4, 5, 6, 7, 8, 9))

      // range(1,100000) throws StackOverflowError exception when run with standard JVM parameters

      def mrange(a: Int, b: Int): Stackless[List[Int]] =
        if (a == b) Nil // implicit call to 'done(Nil)'
        else {
          delayed(mrange(a + 1, b)).andThen(nextRange =>
            a :: nextRange
          )
        }

      assert(mrange(1, 10).result == List(1, 2, 3, 4, 5, 6, 7, 8, 9))
      assert(mrange(0, 100000).result.size == 100000)
    }

    "Scala 'for' notation" - {
      def mrange(a: Int, b: Int): Stackless[List[Int]] =
        if (a == b) Nil
        else {
          for {nextRange <- delayed(mrange(a + 1, b))}
            yield a :: nextRange
        }

      assert(mrange(1, 10).result == List(1, 2, 3, 4, 5, 6, 7, 8, 9))
      assert(mrange(0, 100000).result.size == 100000)
    }


  }

}
