package org.enso.interpreter.bench.fixtures.semantic

import org.enso.interpreter.Constants
import org.enso.interpreter.runtime.Builtins
import org.enso.interpreter.runtime.scope.TopLevelScope
import org.enso.interpreter.test.{InterpreterException, InterpreterRunner}
import org.graalvm.polyglot.{PolyglotException, Value}

import scala.util.Try

class AtomFixtures extends InterpreterRunner {
  val million: Long = 1000000

  def buildInputList(length: Long): Value = {
    val builtins =
      ctx.getBindings(Constants.LANGUAGE_ID).invokeMember("get_module", Builtins.MODULE_NAME)
    val nil  = builtins.invokeMember("get_constructor", "Nil")
    val cons = builtins.invokeMember("get_constructor", "Cons")
    1L.to(length).foldLeft(nil.newInstance()) {
      case (tail, el) => cons.newInstance(el.asInstanceOf[Object], tail)
    }
  }
  val millionElementList = buildInputList(million)

  val generateListCode =
    """
      |main = length ->
      |    generator = acc i -> ifZero i acc (generator (Cons i acc) (i - 1))
      |
      |    res = generator Nil length
      |    res
    """.stripMargin
  val generateList = getMain(generateListCode)

  val reverseListCode =
    """
      |main = list ->
      |    reverser = acc list -> case list of
      |        Cons h t -> reverser (Cons h acc) t
      |        Nil -> acc
      |
      |    res = reverser Nil list
      |    res
    """.stripMargin
  val reverseList = getMain(reverseListCode)

  val reverseListMethodsCode =
    """
      |Cons.reverse = acc -> case this of
      |    Cons h t -> reverse t (Cons h acc)
      |
      |Nil.reverse = acc -> acc
      |
      |main = list ->
      |    res = reverse list Nil
      |    res
      |""".stripMargin
  val reverseListMethods = getMain(reverseListMethodsCode)

  val sumListCode =
    """
      |main = list ->
      |    summator = acc list -> case list of
      |        Cons h t -> summator acc+h t
      |        Nil -> acc
      |
      |    res = summator 0 list
      |    res
    """.stripMargin
  val sumList = getMain(sumListCode)

  val sumListLeftFoldCode =
    """
      |main = list ->
      |    fold = f acc list -> case list of
      |        Cons h t -> fold f (f acc h) t
      |        _ -> acc
      |
      |    res = fold (x y -> x + y) 0 list
      |    res
    """.stripMargin
  val sumListLeftFold = getMain(sumListLeftFoldCode)

  val sumListFallbackCode =
    """
      |main = list ->
      |    summator = acc list -> case list of
      |        Cons h t -> summator acc+h t
      |        _ -> acc
      |
      |    res = summator 0 list
      |    res
    """.stripMargin
  val sumListFallback = getMain(sumListFallbackCode)

  val sumListMethodsCode =
    """
      |Nil.sum = acc -> acc
      |Cons.sum = acc -> case this of
      |    Cons h t -> sum t h+acc
      |
      |main = list ->
      |    res = sum list 0
      |    res
      |""".stripMargin
  val sumListMethods = getMain(sumListMethodsCode)

  val mapReverseListCode =
    """
      |Nil.mapReverse = f acc -> acc
      |Cons.mapReverse = f acc -> case this of
      |    Cons h t -> mapReverse t f (Cons (f h) acc)
      |
      |main = list ->
      |    res = mapReverse list (x -> x + 1) Nil
      |    res
      |""".stripMargin
  val mapReverseList = getMain(mapReverseListCode)

  val mapReverseListCurryCode =
    """
      |Nil.mapReverse = f acc -> acc
      |Cons.mapReverse = f acc -> case this of
      |    Cons h t -> mapReverse t f (Cons (f h) acc)
      |
      |main = list ->
      |    adder = x y -> x + y
      |    res = mapReverse list (adder 1) Nil
      |    res
      |""".stripMargin
  val mapReverseListCurry = getMain(mapReverseListCurryCode)
}
