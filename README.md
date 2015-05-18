# Stackless

Stackless is an implementation of a trampoline and stackless monad, as described in [Stackless
Scala  With  Free  Monads](http://blog.higher-order.com/assets/trampolines.pdf) by Rúnar Óli
Bjarnason. If you do not find [Scalaz] too intimidating, you should use its FreeMonad and
Trampoline as described at the end of the paper. This implementation is not based on Scalaz 
and does not required prior knowledge – reading the [Usage](#usage) section is enough.


# Usage

## Setting up

The library is not yet published on Ivy repositories. To use it, just copy the `Stackless.java`
file into your project in the `emcastro.stackless` package.

You can clone the _github_ project locally, and run the tests in the SBT console or in
IntellijIDEA.


## Quick start

Before all: 
```scala
import emcastro.stackless.Stackless
import Stackless._
```

Here are the main methods of Stackless:

 - **done**\[A] (value: A): Builds a constant Stackless value. Useful to start and to end a 
    Stackless computation. _Note:_ **done** is implicit, most of the time you can omit it and 
    use simple values instead. 

```scala
done("initValue").andThen(...).andthen(y => {... done("final result")}).result
// or simply (using implicit done())
"initValue".andThen(...).andthen(y => {... "final result"}).result
```       

 - Stackless.**result**:
    Launches the **Stackless** runtime and returns the result of the computation.  
    
 - Stackless.**andThen**\[A] (f: B ⇒ Stackless[A]):
    Add a new step to a Stackless chain. When `.result` is called, `f` receives the result
    of the previous computation. Its result type is `Stackless`, it can either be:    
   
    * a simple result, implicitly or explicity encapsulated in a `done(_)`;
   
    * or new chain of computation build with `.andThen`, or return by a function;
          
    The typical use of `.andThen` is to process the result of function that is encapsulated in
    a Stackless value:
```scala
def stacklessFunction: Stackless[String] = ???
stacklessFunction.andThen(str => /* some computation using str */).result
```

 - **delayed**\[A] (thunk: ⇒ Stackless[A]):
    Build a delayed Stackless value. It is semantically equivalent to 
    `done(()).andThen(_ ⇒ thunk)`, however it has a optimised implementation.


## Simple trampoline
Let's take as an example a breadth-first walk on the tree starting from `root`. The tree is
2-layered; one layer of `ANode` (drawn using round brackets), one layer of `BNode` [drawn using
square brackets], and an other layer of `ANode` and so on.
 
            ____(root)____
           ╱              ╲
         [1]              [0]     // ABNodeChain(100000)  
         ╱ ╲               │     
       (a) (b)            (x0)
           ╱ ╲             │ 
         [2] [3]          [1]
              │            │
             (c)          (x1)
                           ⁝
                        (x99999)
                           │
                        [100000]

The walk yields to `root`, `1`, `0`, `a`, `b`, `x0`, `2`, `3`, `1`, `c`, `x1`, `2`, `x2`, `3`, `x3`...


### Simple (buggy) implementation

```scala
...

val data =
  ANode("root",
    BNode(1,
      ANode("a"),
      ANode("b",
        BNode(2),
        BNode(3, ANode("c"))
      )),
    ABNodeChain(100000) 
  )

type StringOrInt = Any  // make the code more explicit

// Biphasic breadth-first walk
def breadthFirstA(acc: List[Seq[StringOrInt]], 
                  aNodes: Seq[ANode]): Seq[Seq[StringOrInt]] = {
  if (aNodes.isEmpty) acc
  else breadthFirstB(aNodes.map(_.name) :: acc, 
                     aNodes.flatMap(_.children))
}

def breadthFirstB(acc: List[Seq[StringOrInt]], 
                  bNodes: Seq[BNode]): Seq[Seq[StringOrInt]] = {
  if (bNodes.isEmpty) acc
  else breadthFirstA(bNodes.map(_.qty) :: acc, 
                     bNodes.flatMap(_.children))
}

breadthFirstA(Nil, Seq(data))
/* yield to => List(
                 List("root"),
                 List(1, 0),
                 List("a", "b", "x0"),
                 ...
*/
```

The problem with the code above is that mutual calls of `breadthFirstA` and `breadthFirstB` stack
up and yield to _StackOverflowError_, because of the size of the `ABNodeChain`. It is not
possible to use the `@tailrec` annotation that ensures that recursive call is transformed into a
_flat_ loop, and thus does not consume the stack. 


### Standard solution

The standard solution to this problem is to mix the functions `breadthFirstA` and `breadthFirstB`
into a single one using a parameter telling if it is in the `breadthFirstA` case, or in the
`breadthFirstB` case:

```scala
@tailrec
def breadthFirstAB(acc: List[Seq[StringOrInt]], 
                   nodes: Either[Seq[ANode], Seq[BNode]]): Seq[Seq[StringOrInt]] = {
  nodes match {
    case Left(aNodes) =>
      if (aNodes.isEmpty) acc
      else breadthFirstAB(aNodes.map(_.name) :: acc, Right(aNodes.flatMap(_.children)))
    case Right(bNodes) =>
      if (bNodes.isEmpty) acc
      else breadthFirstAB(bNodes.map(_.qty) :: acc, Left(bNodes.flatMap(_.children)))
  }
}

breadFirstAB(Nil, Left[Seq(root)])
```


### The **Stackless** solution

**Stackless** gives an other solution, syntactically closer to the 
[Simple solution](#simple-buggy-implementation) which relies on the Stackless monad:

```scala
import emcastro.stackless.Stackless
import Stackless.{delayed, done}

...

// Biphasic breadth-first walk with Trampoline
def breadthFirstA(acc: List[Seq[StringOrInt]], 
                  aNodes: Seq[ANode]): Stackless[Seq[Seq[StringOrInt]]] = { // (1)
  if (aNodes.isEmpty) done(acc)                          // (3)
  else delayed(breadthFirstB(aNodes.map(_.name) :: acc,  // (2)
                             aNodes.flatMap(_.children)))
}

def breadthFirstB(acc: List[Seq[StringOrInt]], 
                  bNodes: Seq[BNode]): Stackless[Seq[Seq[StringOrInt]]] = {
  if (bNodes.isEmpty) acc // implicit call to node(acc)
  else delayed(breadthFirstA(bNodes.map(_.qty) :: acc, 
                             bNodes.flatMap(_.children)))
}

breadthFirstA(Nil, Seq(data)).result // (4)
``` 

Compared to the previous code, there are 4 subtle differences:

 1. The return type of the `breadthFirstA` and `breadthFirstB` is encapsulated into a
 	`Stackless[_]` parametrised type;
 2. The tail recursion to `breadthFirstA` and `breadthFirstB` in the _else_ clause is
 	encapsulated in a `delayed(_)`, returning a thunk to be executed by the Stackless runtime
    after the stack has popped;
 3. The result `acc` in the _then_ clause is encapsulated in a `done(_)`, in order to
    Stacklessify it. The explicit call to `done` is optional as it is an implicit function 
    (as shown in `breadthFirstB`);
 4. The main call to `breadthFirstA`, at the end of the code now returns a
    `Stackless[Seq[Seq[StringOrInt]]]` instead of a `Seq[Seq[StringOrInt]]`. This call does not
    perform any computation per se. In order to perform the computation, and thus converting the
    `Stackless[Seq[Seq[StringOrInt]]]` result into a `Seq[Seq[ANodeOrBNode]]` result, we need to
    call the `.result` method, that leverage the Stackless runtime.

The size of the `ABNodeChain` is no more a problem and does not cause any _StackOverflowError_,
at a minimal syntactical price.


## Generalised stackless

The **Stackless** monad also lets you transform, with minimum effort, any recursive 
algorithm into a stack constant.


### Standard implementation

Consider a simple depth first tree walking: 

```scala
case class BinNode(name: String, a: Option[BinNode], b: Option[BinNode]);

def depthFirst(node: Option[BinNode]): Seq[String] = {
  node match {
    case None => Seq.empty
    case Some(n) =>
      depthFirst(n.a) ++ depthFirst(n.b) :+ n.name
  }
}

depthFirst(Some(someData))
```

As in the previous example, it will eventually end into a `StackOverflowError` for very 
deep trees. Let's see how it can be rewritten into a **Stackless** monadic way.


### Stackless monadification

First, let's rewrite `depthFirst(n.a) ++ depthFirst(n.b) :+ n.name` into a form with more
variables. It will make the transformation into a monad easier to understand:
```scala
def depthFirst(node: Option[BinNode]): Seq[String] = {
  node match {
    case None => Seq.empty
    case Some(n) =>
      val as = depthFirst(n.a)
      val bs = depthFirst(n.b)
      as ++ bs :+ n.name
  }
}
```

Then, we can do the following simple transformations: 

 - As previously, we encapsulate the return type of the `depthFirst` function in 
    a `Stackless[_]` type. 
    
 - We transform the expressions of the form `val v = depthFirst(???)` into their 
    monadic counterparts `depthFirst.andThen(v => ???)`
    
 - In order to get a real result of type `Seq[String]`, and not just some mysterious
    `Stackless[Seq[String]]`, don't forget to call `.result` at the end.
          
```scala
def depthFirst(node: Option[BinNode]): Stackless[Seq[String]] = {
  node match {
    case None => done(Seq.empty)
    case Some(n) =>
      depthFirst(n.a).andThen { as =>
        depthFirst(n.b).andThen { bs =>
          as ++ bs :+ n.name
        }
      }
  }
}

depthFirst(Some(data)).result // don't forget .result
```

Now, your code is not limited by the stack anymore.


### Making it more beautiful using `for`

Scala has standard naming conventions for monads. Monad chaining is usually done using 
`flatMap` and some derived methods such as `map` and `foreach`. **Stackless** includes theses
methods, `flatMap` being a synonym of `andThen`. This renders possible the use of the `for`
notation:

```scala
def depthFirst(node: Option[BinNode]): Stackless[Seq[String]] = {
  node match {
    case None => done(Seq.empty)
    case Some(n) =>
      for {
        as <- depthFirst(n.a)
        bs <- depthFirst(n.b)
      } yield as ++ bs :+ n.name
  }
}
```

Using the `.andThen` notation or the `for` notation is just a matter of style 
(as for collections). I have no rule, it varies according to the context.


## Why is it a monad after all?

Just because it obeys the three monad laws (https://wiki.haskell.org/Monad_laws)

 - **Left identity**: `done(x).andThen(f)` ≡ `f(x)`

 - **Right identity**: `m.andThen(x => done(x))` ≡ `m` **_or_** `m.andThen(done)` ≡ `m`

 - **Associativity**: `m.andThen(f).andThen(g)` ≡ `m.andThen(x => f(x).andThen(g))`



## Caveats

Readability becomes questionable when it comes to mixing collections `.map` or `.flatMap`
with monads. I let you try...

[Scalaz]: http://scalaz.github.io
