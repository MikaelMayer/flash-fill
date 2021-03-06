/**
 *     _____ _       _         _____     _             
 *    |   __| |_ ___|_|___ ___|   __|___| |_ _ ___ ___ 
 *    |__   |  _|  _| |   | . |__   | . | | | | -_|  _|
 *    |_____|_| |_| |_|_|_|_  |_____|___|_|\_/|___|_|  
 *                        |___|      
 * 
 *  File:   ProgramSet.scala
 *  Author: Mikaël Mayer
 *  Date:   27.11.2013
 *  Purpose:Represent multiple programs in a single structure.
 */
package ch.epfl.lara.synthesis.stringsolver

import scala.collection.GenTraversableOnce
import scala.collection.mutable.PriorityQueue
import scala.collection.immutable.BitSet
import scala.collection.mutable.Queue
import scala.collection.mutable.ListBuffer

object ProgramSet {
  import Program._
  import scala.language._
  import SubStrFlag._
  import Weights._

   /**
     * Returns true if the identifier is used in this program.
     */
    def uses(s: Any, w: Identifier): Boolean = s match {
      case i: Identifier => i == w
      case p: Product => p.productIterator.toList exists { case arg => uses(arg, w) }
      case s: Set[_] => s exists { case arg => uses(arg, w) }
      case _ => false
    }
  
  /**
   * Implicit helpers.
   */
  implicit class addCrossProduct[N](s: Set[N]) {
    def x[M](t: Set[M]): Set[(N, M)] = for { x <- s; y <- t } yield (x, y)
  }
  implicit class addMappingTo[T](t: Set[T]) {
    def ==>[A](w: T => Set[A]): Map[T, Set[A]] = (t.toList map { case el => el -> w(el).filterNot(_ == SEmpty)}).filterNot{case (key, value) => value.isEmpty}.toMap
  }
  implicit def combinations[T <: Program](s: List[ProgramSet[T]]): Stream[List[T]] = {
    def rec(l: List[ProgramSet[T]], res: List[T]): Stream[List[T]] = l match {
      case Nil => Stream(res.reverse)
      case a::q => a flatMap { (prog: T) => rec(q, prog::res) }
    }
    rec(s, Nil)
  }
  def _2[A, B](t: Tuple2[A, B]) = t._2
  def _1[A, B](t: Tuple2[A, B]) = t._1
  
  /**
   * Set of programs described in Programs.scala
   */
  sealed trait ProgramSet[+A <: Program]  extends Traversable[A] { self: Product =>
    def foreach[T](f: A => T): Unit
    def map[T](f: A => T): Stream[T]
    def flatMap[T](f: A => GenTraversableOnce[T]) = map(f).flatten
    private var cacheBest: Option[Any] = None
    def takeBest: A = { if(cacheBest.isEmpty) cacheBest = Some(takeBestRaw); cacheBest.get.asInstanceOf[A]}
    private var cacheNBest: Map[Int, Seq[(Int, Any)]] = Map()
    def takeNBest(n: Int): Seq[(Int, A)]  = { if(cacheNBest.isEmpty) cacheNBest += n-> takeNBestRaw(n: Int); cacheNBest(n).asInstanceOf[Seq[(Int, A)]]}
    //def takeBestUsing(w: Identifier): A = takeBest
    def takeBestRaw: A
    def takeNBestRaw(n: Int): Seq[(Int, A)]
    override def isEmpty: Boolean = this == SEmpty || ProgramSet.sizePrograms(this) == 0
    def sizePrograms = ProgramSet.sizePrograms(this)
    override def toIterable: Iterable[A] = map((i: A) =>i)
    override def toString = this.getClass().getName().replaceAll(".*\\$","")+"("+self.productIterator.mkString(",")+")"
    var weightMalus = 0
    def examplePosition = 0 // Set only in SDag
  }


  def weighted[A <: Program](p: A): (Int, A) = (weight(p), p)

  /**
   * Set of switch expressions described in Programs.scala
   */
  case class SSwitch(s: List[(Bool, STraceExpr)]) extends ProgramSet[Switch] {
    def map[T](f: Switch => T): Stream[T] = {
      for(t <- combinations(s map _2)) yield f(Switch(s map _1 zip t))
    }
    def foreach[T](f: Switch =>T): Unit = {
      for(t <- combinations(s map _2)) f(Switch(s map _1 zip t))
    }
    def takeBestRaw = Switch((s map _1) zip ((s map _2) map (_.takeBest)))
    def takeNBestRaw(n: Int) = StreamUtils.cartesianProduct(s.map(_2).map(_.takeNBestRaw(n).toStream)).map{ x=>
      val (scores, progs) = x.unzip
      (scores.sum, Switch(s map _1 zip progs))
    }.sortBy(_1).take(n)
    //override def takeBestUsing(w: Identifier) = Switch((s map _1) zip ((s map _2) map (_.takeBestUsing(w))))
  }
  /**
   * Set of concatenate expressions described in Programs.scala
   */
  type STraceExpr = ProgramSet[TraceExpr]
  case class SDag[Node](ñ: Set[Node], ns: Node, nt: Node, ξ: Set[(Node, Node)], W: Map[(Node, Node), Set[SAtomicExpr]]) extends STraceExpr {
    def foreach[T](f: TraceExpr => T): Unit = {
      def rec(from: Node, path: List[AtomicExpr]): Unit = {
        if(from == nt) f(Concatenate(path.reverse)) else
        for(edge <- ξ; (n1, n2) = edge; if n1 == from; sa <- W(edge); a <- sa) {
          rec(n2, a::path)
        }
      }
      rec(ns, Nil)
    }
    def map[T](f: TraceExpr => T): Stream[T] = {
      def rec(from: Node, path: List[AtomicExpr]): Stream[List[AtomicExpr]] = {
        if(from == nt) Stream(path.reverse) else
        for(edge <- ξ.toStream; (n1, n2) = edge; if n1 == from; sa <- W(edge); a: AtomicExpr <- sa; p <- rec(n2, a::path)) yield p
      }
      rec(ns, Nil).map(e => f(Concatenate(e)))
    }
    def neighbors(n: Node, n_weight: Int): Set[(Int, AtomicExpr, Node)] = {
      for(e <- ξ if e._1 == n;
          versions = W.getOrElse(e, Set.empty);
          atomic <- versions.map(_.takeBest).toList.sortBy(w => weight(w)).headOption) yield {
        (-weight(atomic) + n_weight, atomic, e._2)
      }
    }
    def takeBestRaw = {
      var minProg = Map[Node, List[AtomicExpr]]()
      var nodesToVisit = new PriorityQueue[(Int, List[AtomicExpr], Node)]()(Ordering.by[(Int, List[AtomicExpr], Node), Int](e => e._1))
      nodesToVisit.enqueue((0, Nil, ns))
      while(!(minProg contains nt) && !nodesToVisit.isEmpty) {
        val (weight, path, node) = nodesToVisit.dequeue() // Takes the first node with the minimal path.
        minProg += node -> path
        for(e@(newweight, newAtomic, newNode) <- neighbors(node, weight)) {
          nodesToVisit.find{ case (w, p, n) => n == newNode } match {
            case Some((w, p, n)) => // New node already in nodes to visit.
              if(newweight > w && !(minProg contains newNode)) {
                nodesToVisit = nodesToVisit.filterNot{case (w, p, n) => n == newNode}
                nodesToVisit.enqueue((newweight, path.asInstanceOf[List[AtomicExpr]] ++ List(newAtomic).asInstanceOf[List[AtomicExpr]], newNode))
              } // Else we do nothing.
            case None =>
              nodesToVisit.enqueue((newweight, path.asInstanceOf[List[AtomicExpr]] ++ List(newAtomic).asInstanceOf[List[AtomicExpr]], newNode))
          }
        }
      }
      Concatenate(minProg(nt))//TODO : alternative.
    }

    def Nneighbors(quantity: Int, n: Node, prev_weight: Int): Option[(Seq[(Int, AtomicExpr)], Node)] = {
      ξ.collectFirst[(Node, Node)]{ case e@(start, end) if start == n => e } map { e =>
        val versions = W.getOrElse(e, Set.empty)
        val possibilities = for(atomic <- versions.flatMap(_.takeNBest(quantity)).toList.sortBy(_1).take(quantity)) yield {
          (-atomic._1 + prev_weight, atomic._2)
        }
        (possibilities, e._2)
      }
    }
    def takeNBestRaw(quantity: Int): Seq[(Int, TraceExpr)] = {
      var minProg = Map[Node, Seq[(Int, List[AtomicExpr])]]()
      var nodesToVisit = new PriorityQueue[((Int, List[AtomicExpr]), Node)]()(Ordering.by[((Int, List[AtomicExpr]), Node), Int](e => e._1._1))
      nodesToVisit.enqueue(((0, Nil), ns))
      while(!(minProg.getOrElse(nt, Nil).length >= quantity) && !nodesToVisit.isEmpty) {
        val ((weight, path), node) = nodesToVisit.dequeue() // Takes the first node with the minimal path.
        minProg += node -> (((weight, path)) +: minProg.getOrElse(node, Nil))
        for(e@(newWeightsAtomics, newNode) <- Nneighbors(quantity, node, weight)) {
//          (newweight, newAtomic
          for((newweight, newAtomic) <- newWeightsAtomics)
          {
            val alreadyLookingFor = nodesToVisit.toStream.filter{ case ((w, p), n) => n == newNode }
            //val shouldBeAdded = alreadyLookingFor.lengthCompare(quantity) < 0 || newweight > alreadyLookingFor(quantity - 1)._1._1
            //if(shouldBeAdded) { // We keep only the best quantity.
            nodesToVisit.enqueue(((newweight, path ++ List[AtomicExpr](newAtomic)), newNode))
            var i = 0
            nodesToVisit = nodesToVisit.filterNot {
              i += 1
              _._2 == newNode && i >= quantity // We still keep the first quantity best, we remove the rest.
            }
            //}

          }
        }
      }
      minProg(nt).map(x => (x._1, Concatenate(x._2)))
    }
    /*def neighborsUsing(n: Node, n_weight: Int, w: Identifier): Set[(Int, AtomicExpr, Node)] = {
      for(e <- ξ if e._1 == n;
          versions = W.getOrElse(e, Set.empty);
          atomic <- versions.map(_.takeBest).toList.sortBy(w => weight(w)).headOption) yield {
        (-weight(atomic) + n_weight, atomic, e._2)
      }
    }
    override def takeBestUsing(w: Identifier) = {
      var minProg = Map[Node, List[AtomicExpr]]()
      var weights = Map[Node, Int]()
      var nodesToVisit = new PriorityQueue[(Int, List[AtomicExpr], Node)]()(Ordering.by[(Int, List[AtomicExpr], Node), Int](e => e._1))
      nodesToVisit.enqueue((0, Nil, ns))
      while(!(minProg contains nt) && !nodesToVisit.isEmpty) {
        val (weight, path, node) = nodesToVisit.dequeue() // Takes the first node with the minimal path.
        minProg += node -> path
        for(e@(newweight, newAtomic, newNode) <- neighbors(node, weight)) {
          nodesToVisit.find{ case (w, p, n) => n == newNode } match {
            case Some((w, p, n)) => // New node already in nodes to visit.
              if(newweight > w && !(minProg contains newNode)) {
                nodesToVisit = nodesToVisit.filterNot{case (w, p, n) => n == newNode}
                nodesToVisit.enqueue((newweight, path.asInstanceOf[List[AtomicExpr]] ++ List(newAtomic).asInstanceOf[List[AtomicExpr]], newNode))
              } // Else we do nothing.
            case None =>
              nodesToVisit.enqueue((newweight, path.asInstanceOf[List[AtomicExpr]] ++ List(newAtomic).asInstanceOf[List[AtomicExpr]], newNode))
          }
        }
      }
      Concatenate(minProg(nt))//TODO : alternative.
    }*/
    
    def reduce: SDag[Int] = {
      val nodeMapping = ñ.toList.sortBy({ case (a: Int,b: Int) => a+b case _ => 1 }).zipWithIndex.toMap
      var ñ2 = nodeMapping.values.toSet
      val ns2 = nodeMapping(ns)
      val nt2 = nodeMapping(nt)
      var ξ2 = ξ map { case (n, m) => (nodeMapping.getOrElse(n,  -1), nodeMapping.getOrElse(m, -1))} filterNot {
        case (e1, e2) => e1 == -1 || e2 == -1
      }
      var finished = false
      while(!finished) { // Remove non reachable nodes
        finished = true
        val uselessNodes = ñ2 filter { n =>
          n != ns2 && !(ξ2 exists { case (n1, n2) => n2 == n}) ||
          n != nt2 && !(ξ2 exists { case (n1, n2) => n1 == n})
        }
        if(!uselessNodes.isEmpty) {
          ñ2 = ñ2 -- uselessNodes
          ξ2 = ξ2 filterNot { case (n1, n2) => (uselessNodes contains n1) || (uselessNodes contains n2) }
          finished = false
        }
      }

      val W2 = for(((e1, e2), v) <- W;
                   edge = (nodeMapping.getOrElse(e1, -1), nodeMapping.getOrElse(e2, -1));
                   if ξ2 contains edge)
               yield (edge -> v)
      SDag(ñ2, ns2, nt2, ξ2, W2)
    }
    var mIndex = 0
    override def examplePosition = mIndex
    def examplePosition_=(i: Int) = mIndex = i
    def setIndex(i: Int): this.type = { examplePosition = i; this }
  }
  
  type SAtomicExpr = ProgramSet[AtomicExpr]
  /**
   * Set of Loop expressions described in Programs.scala
   */
  case class SLoop(i: Identifier, e: STraceExpr, separator: Option[ConstStr]) extends SAtomicExpr {
    def map[T](f: AtomicExpr => T): Stream[T] = {
      for(prog: TraceExpr <- e.toStream) yield f(Loop(i, prog, separator))
    }
    def foreach[T](f: AtomicExpr => T): Unit = {
      for(prog <- e) f(Loop(i, prog, separator))
    }
    def takeBestRaw = Loop(i, e.takeBest, separator)//.withAlternative(this.toIterable)
    override def takeNBestRaw(n: Int): Seq[(Int, AtomicExpr)] = {
      e.takeNBest(n).map(x => weighted(Loop(i, x._2, separator)))
    }
  }

  /**
   * Set of SubStr expressions described in Programs.scala
   */
  case class SSubStr(vi: StringVariable, p1: Set[SPosition], p2: Set[SPosition], methods: SSubStrFlag) extends SAtomicExpr {
    def map[T](f: AtomicExpr => T): Stream[T] = {
      for(pp1 <- p1.toStream; ppp1: Position <- pp1; pp2 <- p2; ppp2: Position <- pp2; method: SubStrFlag <- methods) yield f(SubStr(vi, ppp1, ppp2, method))
    }
    def foreach[T](f: AtomicExpr => T): Unit = {
      for(pp1 <- p1; ppp1 <- pp1; pp2 <- p2; ppp2 <- pp2; method <- methods) f(SubStr(vi, ppp1, ppp2, method))
    }
    def takeBestRaw = SubStr(vi, p1.toList.map(_.takeBest).sortBy(weight(_)(true)).head, p2.toList.map(_.takeBest).sortBy(weight(_)(false)).head.withWeightMalus(this.weightMalus), methods.takeBest)//.withAlternative(this.toIterable)
    private var corresponding_string: (String, String, Int, Int) = ("", "", 0, -1)
    def setPos(from: String, s: String, start: Int, end: Int) = corresponding_string = (from, s, start, end)

    override def takeNBestRaw(n: Int): Seq[(Int, AtomicExpr)] = {
      val left = p1.flatMap(_.takeNBest(n)).toSeq.sortBy(_._1).take(n)
      val right = p2.flatMap(_.takeNBest(n)).toSeq.sortBy(_._1).take(n)
      val method = methods.takeNBest(n)
      StreamUtils.cartesianProduct(Seq(left.toStream, right.toStream, method.toStream)).take(n).map{
        case Seq((leftScore, l), (rightScore, r), (mScore, m)) => weighted(SubStr(vi, l.asInstanceOf[Position], r.asInstanceOf[Position], m.asInstanceOf[SubStrFlag]))
      } take n
    }
  }
  
  def isCommonSeparator(s: String) = s match {
    case "," | "/" | " " | "-"| "#" | ";" | ", " | "; " | "\t" | "  " | ". " | "." | ":" | "|" | "_" | ", " | "; " => true
    case _ => false
  }
  
  /**
   * Applies special conversion to a string
   */
  case class SSpecialConversion(s: SSubStr, converters: Set[SpecialConverter]) extends SAtomicExpr {
    def map[T](f: AtomicExpr => T): Stream[T] = {
      for(ss <- s.toStream; converter <- converters) yield f(SpecialConversion(ss.asInstanceOf[SubStr], converter))
    }
    def foreach[T](f: AtomicExpr => T): Unit = {
      for(ss <- s.toStream; converter <- converters) f(SpecialConversion(ss.asInstanceOf[SubStr], converter))
    }
    def takeBestRaw = SpecialConversion(s.takeBest.asInstanceOf[SubStr], converters.toList.sortBy(weight(_)(true)).head)
    private var corresponding_string: (String, String, Int, Int) = ("", "", 0, -1)
    def setPos(from: String, s: String, start: Int, end: Int) = corresponding_string = (from, s, start, end)

    override def takeNBestRaw(n: Int): Seq[(Int, AtomicExpr)] = {
      val sbest = s.takeNBest(n).toStream
      val converterBest = converters.toList.map(x => (-weight(x)(true), x)).sortBy(_1).take(n).toStream
      StreamUtils.cartesianProduct(Seq(sbest, converterBest)).take(n).map {
        case Seq((sScore, s), (convScore, converter)) => weighted(SpecialConversion(s.asInstanceOf[SubStr], converter.asInstanceOf[SpecialConverter]))
      }
    }
  }
  
  
  
  /**
   * Sets of integers for number decomposition.
   * The best one is the greatest one in this implementation
   */
  type SInt = ProgramSet[IntLiteral]
  case class SIntSemiLinearSet(start: Int, step: Int, max: Int) extends SInt {
    def map[T](f: IntLiteral => T): Stream[T] = {
      if(step > 0)
      for(i <- start to max by step toStream) yield f(i)
      else if(start <= max)
        Stream(f(start))
      else Stream.empty
    }
    def foreach[T](f: IntLiteral => T): Unit = {
      if(step > 0)
       for(i <- start to max by step toStream) f(i)
      else if(start <= max)
        Stream(f(start))
      else Stream.empty
    }
    def takeBestRaw = if(step == 0) IntLiteral(start) else IntLiteral(start+step*((max-start)/step))
    def apply(elem: Int): Boolean = elem >= start && elem <= max && (step == 0 && start == elem || step != 0 && (elem-start)%step == 0)

    override def takeNBestRaw(n: Int): Seq[(Int, IntLiteral)] = {
      if(step == 0) Seq((0, IntLiteral(start))) else {
        (start to max by step).reverse.zipWithIndex.map{ x => weighted(IntLiteral(x._1))} take n
      }
    }
  }
  /*case class SAnyInt(default: Int) extends SInt { 
    def map[T](f: IntLiteral => T): Stream[T] = {
      Stream(f(default))
    }
    def foreach[T](f: IntLiteral => T): Unit = {f(default)}
    def takeBestRaw = IntLiteral(default)
  }*/
  /**
   * Used to match any program on this string variable
   * Useful to intersect with working sub-expressions.
   */
  /*case class SAny(vi: PrevStringNumber) extends SAtomicExpr {
    def map[T](f: AtomicExpr => T): Stream[T] = {
      Stream(f(SubStr2(vi, NumTok, 1)))
    }
    def foreach[T](f: AtomicExpr => T): Unit = {
      f(SubStr2(vi, NumTok, 1))
    }
    def takeBestRaw = SubStr2(vi, NumTok, 1)
  }*/
  /**
   * Set of SubStr expressions described in Programs.scala
   */
  case class SNumber(a: SAtomicExpr, length: SInt, offset: Int) extends SAtomicExpr {
    def map[T](f: AtomicExpr => T): Stream[T] = {
      for(pp1: AtomicExpr <- a.toStream; l: IntLiteral <- length) yield f(NumberMap(pp1.asInstanceOf[SubStr], l.k, offset))
    }
    def foreach[T](f: AtomicExpr => T): Unit = {
      for(pp1: AtomicExpr <- a; l <- length) f(NumberMap(pp1.asInstanceOf[SubStr], l.k, offset))
    }
    def takeBestRaw = NumberMap(a.takeBest.asInstanceOf[SubStr], length.takeBest.k, offset)//.withAlternative(this.toIterable)
    override def takeNBestRaw(n: Int): Seq[(Int, AtomicExpr)] = {
      StreamUtils.cartesianProduct(Seq(a.takeNBest(n).toStream, length.takeNBest(n).toStream)).take(n) map {
        case Seq((aScore, a), (lengthScore, length)) => weighted(NumberMap(a.asInstanceOf[SubStr], length.asInstanceOf[IntLiteral].k, offset))
      }
    }
  }
  
  /**
   * Creates a counter set from a number and its position
   */
  object SCounter {
    //
    def fromExample(number: String, position: Int): SCounter = {
      val numberValue = number.toInt
      val possibleLengths = (if(number(0) != '0' && position != 0) {// It means that the generated length might be lower.
        // Except if the position is the first one, because counters are increasing.
        SIntSemiLinearSet(1, 1, number.length)
      } else SIntSemiLinearSet(number.length, 1, number.length))
      val possibleStarts = if(position == 0) {
        SIntSemiLinearSet(numberValue, 1, numberValue)
      } else {
        SIntSemiLinearSet(numberValue % position, position, numberValue)
      }
       SCounter(possibleLengths, possibleStarts, numberValue, position)
    }
  }
  /**
   * Step = (index - start) / count if the division is applicable
   * Except if count = 0, step can be anything from 1 to infinity.
   */
  case class SCounter(length: SInt, starts: SInt, index: Int, count: Int) extends SAtomicExpr {
    assert(length != SEmpty)
    assert(starts != SEmpty)
    def map[T](f: AtomicExpr => T): Stream[T] = {
      for(l <- length.toStream; s: IntLiteral <- starts; step <- if(count == 0) Stream.from(1) else List((index - s.k)/count)) yield f(Counter(l.k, s.k, step))
    }
    def foreach[T](f: AtomicExpr => T): Unit = {
      for(l <- length.toStream; s: IntLiteral <- starts; step <- if(count == 0) Stream.from(1) else List((index - s.k)/count)) f(Counter(l.k, s.k, step))
    }
    def takeBestRaw = Counter(length.takeBest.k, starts.takeBest.k, if(count == 0) 1 else (index - starts.takeBest.k)/count)//.withAlternative(this.toIterable)
    override def takeNBestRaw(n: Int): Seq[(Int, AtomicExpr)] = {
      StreamUtils.cartesianProduct(Seq(length.takeNBest(n).toStream, starts.takeNBest(n).toStream)).take(n) map {
        case Seq((lengthScore, length), (startsScore, start)) =>
          weighted(Counter(length.k, start.k, if(count == 0) 1 else (index - start.k)/count))
      }
    }
  }
  
  /**
   * Set of Constant string expressions described in Programs.scala
   */
  case class SConstStr(s: String) extends SAtomicExpr {
    def map[T](f: AtomicExpr => T): Stream[T] = {
      Stream(f(ConstStr(s)))
    }
    def foreach[T](f: AtomicExpr => T): Unit = {
      f(ConstStr(s))
    }
    def takeBestRaw = ConstStr(s)

    override def takeNBestRaw(n: Int): Seq[(Int, AtomicExpr)] = Seq(weighted(takeBest))
  }
  
  type SPosition = ProgramSet[Position]
  /**
   * Set of Constant positions described in Programs.scala
   */
  case class SCPos(k: Int) extends SPosition {
    def map[T](f: Position => T): Stream[T] = {
      Stream(f(CPos(k)))
    }
    def foreach[T](f: Position => T): Unit = {
      f(CPos(k))
    }
    def takeBestRaw = CPos(k)

    override def takeNBestRaw(n: Int): Seq[(Int, Position)] = Seq(weighted(takeBest))
  }
  /**
   * Set of regexp positions described in Programs.scala
   */
  case class SPos(r1: SRegExp, r2: SRegExp, c: SIntegerExpr) extends SPosition {
    def map[T](f: Position => T): Stream[T] = {
      for(rr1: RegExp <- r1.toStream; rr2: RegExp <- r2; cc <- c) yield f(Pos(rr1, rr2, cc))
    }
    def foreach[T](f: Position => T): Unit = {
      for(rr1 <- r1; rr2 <- r2; cc <- c) f(Pos(rr1, rr2, cc))
    }
    def takeBestRaw = Pos(r1.takeBest, r2.takeBest, c.toList.sortBy(weight).head)
    //var index = 0 // Index at which this position was computed
    override def takeNBestRaw(n: Int): Seq[(Int, Position)] = {
      StreamUtils.cartesianProduct(Seq(
      r1.takeNBest(n).toStream,
      r2.takeNBest(n).toStream,
      c.toList.sortBy(weight).toStream)) map {
        case Seq((_, rr1), (_, rr2), cc: IntLiteral) => weighted(Pos(rr1.asInstanceOf[RegExp], rr2.asInstanceOf[RegExp], cc.k))
      }
    }
  }
  
  type SRegExp = ProgramSet[RegExp]
  /**
   * Set of regexp described in Programs.scala
   */
  case class STokenSeq(s: List[SToken]) extends SRegExp {
    assert(s forall (_.sizePrograms != 0))
    def map[T](f: RegExp => T): Stream[T] = {
      for(t <- combinations(s)) yield f(TokenSeq(t))
    }
    def foreach[T](f: RegExp =>T): Unit = {
      for(t <- combinations(s)) f(TokenSeq(t))
    }
    def takeBestRaw = TokenSeq(s map (_.takeBest))

    override def takeNBestRaw(n: Int): Seq[(Int, RegExp)] = {
      StreamUtils.cartesianProduct(s.map(_.takeNBest(n).toStream)).take(n) map {
        x => weighted(TokenSeq(x.map(_2)))
      }
    }
  }
  
  /**
   * Empty set for everything
   */
  case object SEmpty extends ProgramSet[Nothing] with Iterable[Nothing] {
    def map[T](f: Nothing => T): Stream[T] = ???
    override def foreach[T](f: Nothing => T): Unit = ???
    def takeBestRaw = throw new Error("No program found")
    def iterator = Nil.toIterator
    override def toIterable = Nil
    override def isEmpty = true

    override def takeNBestRaw(n: Int): Seq[(Int, Nothing)] = Seq()
  }
  
  type SIntegerExpr = Set[IntegerExpr]
  
  object SToken {
    def apply(t: Token*)(l: List[Token]): SToken = SToken(t.toList)(l)
    def apply(s: Traversable[Token])(l: List[Token]): SToken = {
      assert(s forall (i => l.indexOf(i) >= 0))
      if(s.size == 1) {
        val i = l.indexOf(s.head)
        SToken(1L << i)(l)
      } else if(s.size == 2) {
        val mask = s.toList.map(i => 1L << l.indexOf(i)).reduce(_ | _)
        SToken(mask)(l)
      } else {
        val (newMask, _) = ((0L, 1L) /: l) { case ((res, inc), token) => if(s exists (_ == token)) (res + inc, inc << 1) else (res, inc << 1)}
        SToken(newMask)(l)
      }
    }
  }
  /**
   * Set of tokens represented as a 1 at position i in binary format if the token is in the set, 0 otherwise
   * // Works if there are less than 64 tokens.
   */
  case class SToken(mask: Long)(val l: List[Token]) extends ProgramSet[Token] {
    def intersect(other: SToken): SToken = {
      if(other.l eq l) {
        val intersection_mask = mask & other.mask
        SToken(intersection_mask)(l)
      } else {
        val intersection_list = (l ++ other.l).distinct
        val tokens1 = toIterable.toSet
        val tokens2 = other.toIterable.toSet
        val tokens = tokens1 intersect tokens2
        val (newMask, _) = ((0L, 1L) /: intersection_list) { case ((res, inc), token) => if(tokens(token)) (res + inc, inc << 1) else (res, inc << 1)}
        SToken(newMask)(intersection_list)
      }
    }
    override def sizePrograms = java.lang.Long.bitCount(mask)
    def map[T](f: Token => T): Stream[T] = {
      def rec(m: Long, l: List[Token]): Stream[T] = l match {
        case Nil => Stream.empty
        case a::b if (m & 1) != 0 => f(a) #:: rec(m >> 1, b)
        case a::b => rec(m >> 1, b)
      }
      rec(mask, l)
    }
    override def foreach[T](f: Token =>T): Unit = {
      def rec(m: Long, l: List[Token]): Unit = l match {
        case Nil => 
        case a::b if (m & 1) != 0 => f(a); rec(m >> 1, b)
        case a::b => rec(m >> 1, b)
      }
      rec(mask, l)
    }
    override def isEmpty = size == 0
    def takeBestRaw = map((i: Token) => i).toList.sortBy(weight).head
    def contains(t: Token): Boolean = ((1L << l.indexOf(t)) & mask) != 0
    override def toString = "SToken("+this.toList.mkString(",")+")"

    override def takeNBestRaw(n: Int): Seq[(Int, Token)] = {
      map((i: Token) => i).toList.sortBy(weight).take(n).map(weighted)
    }
  }
  
  /**
   * Constructor for set of flags for SSubStr
   */
  object SSubStrFlag {
    def apply(s: Traversable[SubStrFlag]): SSubStrFlag = {
      //assert(s forall (i => s.indexOf(i) >= 0))
      val l = SubStrFlag.registered
      if(s.size == 1) {
        val i = l.indexOf(s.head)
        SSubStrFlag(1L << i)
      } else if(s.size == 2) {
        val mask = s.toList.map(i => 1L << l.indexOf(i)).reduce(_ | _)
        SSubStrFlag(mask)
      } else {
        val (newMask, _) = ((0L, 1L) /: l) { case ((res, inc), token) => if(s exists (_ == token)) (res + inc, inc << 1) else (res, inc << 1)}
        SSubStrFlag(newMask)
      }
    }
  }
  case class SSubStrFlag(mask: Long) extends ProgramSet[SubStrFlag] with Traversable[SubStrFlag] {
    def intersect(other: SSubStrFlag): SSubStrFlag = if(other.mask == mask) this else SSubStrFlag(other.mask & mask)
    override def sizePrograms = java.lang.Long.bitCount(mask)
    def map[T](f: SubStrFlag => T): Stream[T] = {
      def rec(m: Long, id: Int = 0): Stream[T] = if(m == 0) Stream.empty else if((m & 1) == 1) f(SubStrFlag(id)) #:: rec(m >> 1, id + 1) else rec(m >> 1, id + 1)
      rec(mask)
    }
    override def foreach[T](f: SubStrFlag =>T): Unit = {
      def rec(m: Long, id: Int = 0): Unit = if(m == 0) Stream.empty else if((m & 1) == 1) { f(SubStrFlag(id)) ; rec(m >> 1, id + 1)} else rec(m >> 1, id + 1)
      rec(mask)
    }
    override def isEmpty = mask == 0
    def takeBestRaw = map((i: SubStrFlag) => i).toList.sortBy(weight).head
    override def toString = "SSubStrFlag("+this.toList.mkString(",")+")"

    override def takeNBestRaw(n: Int): Seq[(Int, SubStrFlag)] = {
      map((i: SubStrFlag) => i).toList.sortBy(weight).take(n).map(weighted)
    }
  }
  


  case class IntersectParam(unify: Option[Identifier], index1: Int, index2: Int, iterateInput: Boolean = true, useIndexForPosition: Boolean = false) {
    var timeout = false
  }
  /**
   * Intersection function
   */
  def intersect(ss: Set[SAtomicExpr], tt: Set[SAtomicExpr])(implicit unify: IntersectParam): Set[SAtomicExpr] = {
    for(s <- ss; t <- tt; r <- result(intersectAtomicExpr(s, t))) yield r
  }
  def result[T <: Program](a: ProgramSet[T]): Option[ProgramSet[T]] = if(sizePrograms(a)==0) None else Some(a)
  
  def intersect(p1: STraceExpr, p2: STraceExpr)(implicit unify: IntersectParam = IntersectParam(None, 0, 0)): STraceExpr = (p1, p2) match {
    case (p1: SDag[_], p2: SDag[_]) => 
      intersectDag(p1, p2)
    case _ => SEmpty 
  }
  def intersectDag[Node1, Node2, Node3](p1: SDag[Node1], p2: SDag[Node2])(implicit unify: IntersectParam): STraceExpr = (p1, p2) match {
    case (s1@SDag(ñ1, n1s, n1t, ξ1, w1),
          s2@SDag(ñ2, n2s, n2t, ξ2, w2)) => 
          //println(s"Intersecting two dags of size: ${s1.ñ.size} and ${s2.ñ.size}")
          //println("computing edges...")
          
          val W12f = {  (arg : ((Node1, Node2), (Node1, Node2))) => arg match { case ((n1, n2), (np1, np2)) =>
              for(f1 <- w1(n1, np1) if !unify.timeout; f2 <- w2(n2, np2)) yield {
                intersectAtomicExpr(f1, f2)
              }
            }}
          var W12 = Map[((Node1, Node2), (Node1, Node2)), Set[SAtomicExpr]]()
          var edges = Set[((Node1, Node2), (Node1, Node2))]()
          var nodesVisited = Set[(Node1, Node2)]()
          var nodesToVisit = Queue[(Node1, Node2)]((n1s, n2s))
          var nodesToVisitEnd = Queue[(Node1, Node2)]((n1t, n2t))
          //println("Grouping edges...")
          val edgeMap11 = ξ1.groupBy(_1)
          val edgeMap12 = ξ1.groupBy(_2)
          val edgeMap21 = ξ2.groupBy(_1)
          val edgeMap22 = ξ2.groupBy(_2)
          //println("Gathering edges...")
          val edgeMap = new  {
            def getOrElse(n1n2: (Node1, Node2), orElse: Iterable[((Node1, Node2), (Node1, Node2))]) = {
              for((_, n12) <- edgeMap11.getOrElse(n1n2._1, Set.empty).iterator; (_, n22) <- edgeMap21.getOrElse(n1n2._2, Set.empty))
                yield (n1n2, (n12, n22))
            }
          }
          val edgeMapEnd = new  {
            def getOrElse(n1n2: (Node1, Node2), orElse: Iterable[((Node1, Node2), (Node1, Node2))]) = {
              for((n12, _) <- edgeMap12.getOrElse(n1n2._1, Set.empty).iterator; (n22, _) <- edgeMap22.getOrElse(n1n2._2, Set.empty))
                yield ((n12, n22), n1n2)
            }
          }
          var i = 1;
          var emptyEdges = Set[((Node1, Node2), (Node1, Node2))]()
          // Alternate between nodes to visit on the end and on the start.
          while(!(nodesToVisitEnd.isEmpty || nodesToVisit.isEmpty) && !unify.timeout) {
            //println(s"Level $i - starting edges (${nodesToVisit.length} to visit)")
            //println(s"Nodes to visit start: ${nodesToVisit.size}", s"Nodes to visit end: ${nodesToVisitEnd.size}")
            val nFirst = nodesToVisit.dequeue()
            nodesVisited += nFirst
            for(newEdge <- edgeMap.getOrElse(nFirst, Set.empty) if !(W12 contains newEdge) && !(emptyEdges(newEdge));
                e = newEdge._2) {
              val res = W12f(newEdge).filterNot(_ == SEmpty)
              if(!res.isEmpty) {
                //println(s"Found expression for edge $newEdge")
                edges += newEdge
                W12 += newEdge -> res
                if(!(nodesVisited contains e) && !(nodesToVisit contains e))
                  nodesToVisit.enqueue(e)
              } else {
                emptyEdges += newEdge
              }
            }
            //println(s"Level $i - ending edges (${nodesToVisitEnd.length} to visit)")
            val nLast = nodesToVisitEnd.dequeue()
            nodesVisited += nLast
            for(newEdge <- edgeMapEnd.getOrElse(nLast, Set.empty) if !(W12 contains newEdge) && !(emptyEdges(newEdge));
                e = newEdge._1) {
              val res = W12f(newEdge).filterNot(_ == SEmpty)
              if(!res.isEmpty) {
                //println(s"Found expression for edge $newEdge")
                edges += newEdge
                W12 += newEdge -> res
                if(!(nodesVisited contains e) && !(nodesToVisitEnd contains e))
                  nodesToVisitEnd.enqueue(e)
              } else {
                emptyEdges += newEdge
              }
            }
            i += 1
          }
          val ñ = nodesVisited ++ (nodesToVisitEnd intersect nodesToVisit)

          val ξ12final = edges

          if(!nodesVisited((n1t, n2t))) SEmpty else
          if(ξ12final.size != 0) {
            val res = SDag[(Node1, Node2)](ñ, (n1s, n2s), (n1t, n2t), ξ12final, W12).reduce
            if(sizeDag(res) == 0) SEmpty else res
          } else SEmpty
  }
  def notEmpty[T <: Program](a: ProgramSet[T]): Option[ProgramSet[T]] = if(a == SEmpty) None else Some(a)
  def intersectAtomicExpr(a: SAtomicExpr, b: SAtomicExpr)(implicit unify: IntersectParam = IntersectParam(None, 0, 0)): SAtomicExpr = if(a eq b) a else ((a, b) match {
    case (SSpecialConversion(a, b), SSpecialConversion(c, d)) =>
        val ss = intersectAtomicExpr(a, c)
        if(sizePrograms(ss) > 0) {
          val i = b intersect d
          if(i.size > 0) {
            SSpecialConversion(ss.asInstanceOf[SSubStr], i)
          } else SEmpty
        } else SEmpty
    case (SLoop(i1, e1, sep1), SLoop(i2, e2, sep2)) if sep1 == sep2 =>
      val be2 = replaceSTraceExpr(e2){ case l@Linear(a, i, b) => if(i == i2) Linear(a, i1, b) else l }
      val intersectBody = intersect(e1, be2)
      if(!intersectBody.isEmpty) {
        SLoop(i1, intersectBody, sep1) 
      } else SEmpty
    case (SConstStr(aa), SConstStr(bb)) if aa == bb => a
    //case (SConstStr(aa), SConstStr(bb)) if aa.isNumber == bb.isNumber => a
    case (SSubStr(InputString(vi@IntLiteral(i)), pj, pk, m1), SSubStr(InputString(vj@IntLiteral(j)), pl, pm, m2)) =>
      if(i == j || (unify.unify.isDefined && ((i == j + 1) || (i == j - 1)) && unify.iterateInput)) {
        val mm = m1 intersect m2
        if(mm.isEmpty) SEmpty else {
        val pp1 = (for(p1 <- pj; p2 <- pl; res <- notEmpty(intersectPos(p1, p2))) yield res)
          if(pp1.isEmpty) SEmpty else {
            val pp2 = (for(p1 <- pk; p2 <- pm; res <- notEmpty(intersectPos(p1, p2))) yield res)
            if(pp2.isEmpty) SEmpty else {
              if(i == j) SSubStr(InputString(vi), pp1, pp2, mm)
              else if(i == j - 1 && unify.unify.isDefined) SSubStr(InputString(Linear(1, unify.unify.get, i)), pp1, pp2, mm)
              else if(i == j + 1 && unify.unify.isDefined) SSubStr(InputString(Linear(1, unify.unify.get, j)), pp1, pp2, mm)
              else SEmpty
            }
          }
        }
      } else SEmpty
    case (SSubStr(InputString(vi: Linear), pj, pk, m1), SSubStr(InputString(vj: Linear), pl, pm, m2)) =>
      if(vi == vj) {
        val mm = m1 intersect m2
        val pp1 = (for(p1 <- pj; p2 <- pl; res <- notEmpty(intersectPos(p1, p2))) yield res)
        val pp2 = (for(p1 <- pk; p2 <- pm; res <- notEmpty(intersectPos(p1, p2))) yield res)
        if(pp1.isEmpty || pp2.isEmpty || mm.isEmpty) SEmpty else {
          SSubStr(InputString(vi), pp1, pp2, mm)
        }
      } else SEmpty
    case (SNumber(ss1, l1, o1), SNumber(ss2, l2, o2)) if(o1 == o2)=>
      //val s = intersectIntSet(s1, s2)
      val l = intersectIntSet(l1, l2)
      if(sizePrograms(l) > 0) {
        val ss = intersectAtomicExpr(ss1, ss2)
        if(sizePrograms(ss)>0)
          SNumber(ss.asInstanceOf[SSubStr], l, o1)
        else SEmpty
      } else SEmpty
    case (SCounter(l1, s1, i1, c1), SCounter(l2, s2, i2, c2)) =>
      val s = intersectIntSet(s1, s2)
      val l = intersectIntSet(l1, l2)
      if(sizePrograms(l) > 0 && sizePrograms(s) > 0) {
        if(c1 == c2) {
          if(i1 == i2)
            SCounter(l, s, i1, c1)
          else
            SEmpty
        } else if(i1 == i2) {
          SEmpty
        } else {
          if((i2 - i1) % (c2 - c1) != 0) SEmpty else {
            val newStep = Math.abs((i2 - i1)/(c1-c2))
            val newStart = i1 - c1 * newStep
            val s2 = intersectIntSet(s, SIntSemiLinearSet(newStart, 0, newStart))
            s2 match {
              case si : SIntSemiLinearSet => 
                if(c2 != 0) {
                  SCounter(l, s2, i2, c2)
                } else if(c1 != 0)
                  SCounter(l, s2, i1, c1)
                else SEmpty
              case _ =>
                SEmpty
            }
            
          }
        }
      } else SEmpty
    case _ => SEmpty
  })
  final val INDEX_IDENTIFIER = Identifier("index")
  def intersectPos(p1: SPosition, p2: SPosition)(implicit unify: IntersectParam): SPosition = (p1, p2) match {
    case (SCPos(k1), SCPos(k2)) if k1 == k2 => p1
    
    case (SCPos(0), p2@SPos(r21,r22,c2)) if unify.useIndexForPosition => // Possible unification with an index.
      val newCC: SIntegerExpr = if(unify.unify.isEmpty) {
        c2 flatMap {
          case IntLiteral(k2) if k2 > 0 =>
            val i1 = unify.index1
            val i2 = unify.index2
            if(i1 == 0 && i2 != 0 && k2%i2 == 0) {
              val increment = k2/i2
              val first = 0
              List(Linear(increment, INDEX_IDENTIFIER, first))
            } else Nil
          case _ => Nil
        }
      } else {
        c2 flatMap {
          case IntLiteral(k2) if k2 > 0 =>
            val increment = k2
            val first = 0
            List(Linear(increment, unify.unify.get, first))
          case _ => Nil
        }
      }
      if(newCC.isEmpty) SEmpty else {
        SPos(r21, r22, newCC)
      }
    case (p1@SPos(r11, r12, c1), p2@SPos(r21, r22, c2)) =>
      val r2 = intersectRegex(r12,r22)
      if(r2 == SEmpty) return SEmpty
     
      val r1 = intersectRegex(r11,r21)
      if(r1 == SEmpty) return SEmpty
      
      val c: SIntegerExpr = if(unify.unify.isEmpty) {
        //c1 intersect c2
        // TODO : Better intersection (currently n²)
        val res = ((c1 x c2) flatMap {
          case (a, b) if a == b => List(a)
          case (a@Linear(k1, INDEX_IDENTIFIER, k), IntLiteral(k2)) if k1 * unify.index2 + k == k2 => List(a)
          case (IntLiteral(k2), a@Linear(k1, INDEX_IDENTIFIER, k)) if k1 * unify.index1 + k == k2 => List(a)
          case (IntLiteral(k1), IntLiteral(k2)) if unify.useIndexForPosition =>
            val i1 = unify.index1
            val i2 = unify.index2
            if(i2 != i1 && (k2 - k1)%(i2 - i1) == 0) {
              val increment = (k2-k1)/(i2-i1)
              val start = k1 - i1*increment
              if(start >= 0 && increment > 0 || start < 0 && increment < 0) {
                List(Linear(increment, INDEX_IDENTIFIER, start))
              } else Nil
            } else Nil
          case _ => Nil
        }).toSet
        res: SIntegerExpr
      } else {
        val res = ((c1 x c2) flatMap { 
          case (a, b) if a == b => List(a)
          case (IntLiteral(k1), IntLiteral(k2)) =>
            if(k1 < k2 && k1 >= 0) List(Linear((k2-k1), unify.unify.get, k1):IntegerExpr)
            else if(k2 < k1 && k1 < 0) List(Linear((k2-k1), unify.unify.get, k1):IntegerExpr)
            //else if(k2 < k1 && k2 >= 0) List(Linear((k1-k2), unify.get, k2):IntegerExpr)
            else Nil
          case _ => Nil
        }).toSet
        res: SIntegerExpr
      }
      if(r1 == SEmpty || r2 == SEmpty || c.isEmpty) SEmpty else {
        SPos(r1, r2, c)
      }
    case _ => SEmpty
  }
  def gcd(a: Int, b: Int): Int = if (a == 0) b else gcd(b%a, a)
  def extendGcd(a: Int, b: Int, s: Int = 0, t: Int= 1, old_s: Int = 1, old_t: Int = 0)(r: Int = b, old_r: Int = a): (Int, Int) = {
    if(r == 0) { (old_s, old_t)
    } else { val quotient = (old_r / r)
      extendGcd(a, b, old_s - quotient*s, old_t - quotient*t, s, t)(old_r - quotient*r, r)
    }
  }
  
  def intersectIntSet(p1: SInt, p2: SInt)(implicit unify: IntersectParam = IntersectParam(None, 0, 0)): SInt = (p1, p2) match {
    case (p1@SIntSemiLinearSet(start1, step1, max1), p2@SIntSemiLinearSet(start2, step2, max2)) => 
      // Multiple cases.
      val newMax = Math.min(max1, max2)
      if(step1 == 0 || step2 == 0) {
        if(step1 == 0) {
          if(p2(start1)) p1 else SEmpty
        } else { // step2 == 0
          if(p1(start2)) p2 else SEmpty
        }
      } else if(step1 == step2) {
        if(start1 == start2) {
          if(max1 <= max2) p1 else p2
        } else if((start2 - start1) % step1 == 0){
          val newStart = Math.max(start2, start1)
          if(newStart <= newMax) {
            SIntSemiLinearSet(newStart, step1, newMax)
          } else SEmpty
        } else SEmpty
      } else { // both steps are different. Will find the first one greater than the two starts.
        // Find a, b such that start1 + a*step1 == start2 + b*step2
        // It means that a*step1-b*step2=start2-start1
        val gcd2 = gcd(step1, step2)
        if((start2 - start1) % gcd2 != 0) SEmpty else {
          val c1 = step1/gcd2
          val c2 = step2/gcd2
          val i = (start2 - start1)/gcd2
          // Solve a*c1+b*c2 == 1 with bezout.
          val (a_wo_i, b_wo_i) = extendGcd(c1, c2)()
          val a = a_wo_i * i
          val b = b_wo_i * i
          // Now start1 + a * step1 == start2 + b * step2
          val newStep = step1 * step2 / gcd2 // The LCM is the new step.
          val possibleStart = start1 + a*step1
          val maxStart = Math.max(start1, start2)
          val base = maxStart - possibleStart
          val startI = (base + ((((newStep - base) % newStep) + newStep)%newStep))/newStep
          val newStart = possibleStart + newStep*startI
          if(newStart <= newMax)
          SIntSemiLinearSet(newStart, newStep, newMax)
          else SEmpty
        }
      }
    /*case (SAnyInt(default), b) => b
    case (a, SAnyInt(default)) => a*/
    case (SEmpty, _) => SEmpty
    case (_, SEmpty) => SEmpty
  }
  def intersectRegex(r1: SRegExp, r2: SRegExp): SRegExp = (r1, r2) match {
    case (STokenSeq(s1), STokenSeq(s2)) if s1.length == s2.length =>
      var i1 = s1
      var i2 = s2
      var res = ListBuffer[SToken]()
      while(i1 != Nil && i2 != Nil) {
        val tmp = i1.head intersect i2.head
        if(tmp.sizePrograms == 0) return SEmpty
        res += tmp
        i1 = i1.tail
        i2 = i2.tail
      }
      if(i1 != Nil || i2 != Nil) return SEmpty // should not happen
      STokenSeq(res.toList)
    case _ => SEmpty
  }
  def unify(s1: STraceExpr, s2: STraceExpr, w: Identifier, index1: Int, index2: Int, iterateInput: Boolean) = intersect(s1, s2)(unify=IntersectParam(Some(w), index1, index2, iterateInput))

  
  /**
   * Size function
   */
  def sizePrograms(p: ProgramSet[T forSome { type T <: Program} ]): Long = p match {
    case SNumber(s, digits, offset) => sizePrograms(s)*digits.size
    case SCounter(length, start, index, count) => if(count == 0) 100 else sizePrograms(start)*sizePrograms(length)
    case SSwitch(conds) => (1L /: (conds map _2 map sizePrograms)) (_ * _)
    case dag@SDag(ñ1, n1s, n1t, ξ1, w1) => sizeDag(dag)
    case SSubStr(vi, pj, pk, mm) => (pj.toList map sizePrograms).sum * (pk.toList map sizePrograms).sum * mm.sizePrograms
    case SLoop(w, e, _) => sizePrograms(e)
    case SConstStr(s) => 1
    case SCPos(k) => 1
    case SPos(r1, r2, c) => sizePrograms(r1) * sizePrograms(r2) * c.size
    case STokenSeq(tseq) => (1L /: (tseq map { (t:SToken) => t.size})) (_ * _)
    case s@ SToken(_) => s.size
    case SEmpty => 0
    case SSpecialConversion(a, b) => sizePrograms(a) * b.size
    /*case SAny(_) => 1
    case SAnyInt(i) => 1*/
    case SIntSemiLinearSet(start, offset, max) => if(offset == 0) 1 else (max - start)/offset + 1
    case s @ SSubStrFlag(mask) => s.size
  }
  def sizeDag[Node](p1: SDag[Node]): Long = {
    var sizeNode = Map[Node, Long](p1.ns -> 1)
    def rec(fromN: Node): Long = {
      if(sizeNode contains fromN) sizeNode(fromN) else {
        val res = (for(np <- p1.ñ.toList) yield {
          val pre_sum = (for(f <- p1.W.getOrElse((np, fromN),Set.empty)) yield sizePrograms(f))
          val sum = if(pre_sum exists { i => i >= Integer.MAX_VALUE }) Integer.MAX_VALUE else {
            Math.min(Integer.MAX_VALUE, pre_sum.sum)
          }
          if(sum != 0) {
            Math.min(sum * rec(np), Integer.MAX_VALUE)
          } else 0
        }).sum
        sizeNode += fromN -> res
        res
      }
    }
    rec(p1.nt)
  }
  
  /**
   * Replace routines (for intersection of loops)
   */
  
  /**
   * Replace routines
   */
  def replaceSTraceExpr(e: STraceExpr)(implicit w: Linear => Linear): STraceExpr = e match {
    case SDag(n, ns, nt, e, ww) =>  SDag(n, ns, nt, e, ww.mapValues(_.map(v => replaceSAtomicExpr(v)(w))))
    case e => e
  }
  def replaceSAtomicExpr(e: SAtomicExpr)(implicit w: Linear => Linear): SAtomicExpr = e match {
    case SSubStr(vi, p1, p2, m) => SSubStr(replaceStringVariable(vi)(w), p1.map(t=>replaceSPosition(t)(w)), p2.map(t=>replaceSPosition(t)(w)), m)
    case SConstStr(s) => e
    case SLoop(w2, _, separator) if w2 == w => e
    case SLoop(w2, e, separator) => SLoop(w2, replaceSTraceExpr(e)(w), separator)
    case SNumber(s, l, o) => SNumber(replaceSAtomicExpr(s)(w), l, o)
    case e => e
  }
  def replaceSPosition(e: SPosition)(implicit w: Linear => Linear): SPosition = e match {
    case SPos(p1, p2, t) => SPos(p1, p2, replaceSIntegerExpr(t)(w))
    case _ => e
  }
  def replaceStringVariable(e: StringVariable)(implicit w: Linear => Linear): StringVariable = e match {
    case InputString(i) => InputString(replaceIntegerExpr(i)(w))
    //case PrevStringNumber(i) => PrevStringNumber(replaceIntegerExpr(i)(w))
    case e => e
  }
  def replaceSIntegerExpr(e: SIntegerExpr)(implicit w: Linear => Linear): SIntegerExpr = e.map(t => replaceIntegerExpr(t)(w))
  def replaceIntegerExpr(e: IntegerExpr)(implicit w: Linear => Linear): IntegerExpr = e match {
    case e @ Linear(i, v, j) => w(e)
    case e => e
  }
 // addToEveryOccurence
  
}
