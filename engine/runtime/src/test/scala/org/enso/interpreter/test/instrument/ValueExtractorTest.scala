package org.enso.interpreter.test.instrument
import org.enso.interpreter.test.InterpreterTest

import scala.collection.mutable

class ValueExtractorTest extends InterpreterTest {
  val subject = "Value extractor"

  subject should "extract values in a simple expression" in {
    val code    = "main = 2 + 2"
    val results = mutable.HashMap[(Int, Int), Any]()
    getValueExtractorInstrument.bindTo(7, 5, { res =>
      results.put((7, 5), res)
    })
    eval(code)
    results shouldEqual Map((7, 5) -> 4)
  }

  subject should "work for multiple callbacks" in {
    val code =
      """
        |main = arg ->
        |    x = arg + 5
        |    y = x * 5
        |    z = y + 5
        |    z
        |""".stripMargin
    val results      = mutable.HashMap[String, Any]()
    val instrumenter = getValueExtractorInstrument
    instrumenter.bindTo(23, 7, { x =>
      results.put("x", x)
    })
    instrumenter.bindTo(39, 5, { y =>
      results.put("y", y)
    })
    instrumenter.bindTo(53, 5, { z =>
      results.put("z", z)
    })

    getMain(code).execute(5L.asInstanceOf[AnyRef])
    results shouldEqual Map("x" -> 10, "y" -> 50, "z" -> 55)
  }

  subject should "trigger only once, even if attached in a recursive function" in {
    val code =
      """
        |main = arg ->
        |    x = arg - 1
        |    IO.println x
        |    ifZero x x (here.main x)
        |""".stripMargin

    var results = List[Any]()
    getValueExtractorInstrument.bindTo(23, 7, { x =>
      results ::= x
    })

    getMain(code).execute(5L.asInstanceOf[AnyRef])
    results shouldEqual List(4)
  }

  subject should "work for function arguments" in {
    val code =
      """
        |main = arg ->
        |    x = here.foo (arg + 1)
        |    x + 1
        |
        |foo = x -> x + 1
        |
        |""".stripMargin

    var result: Option[Any] = None
    getValueExtractorInstrument.bindTo(33, 7, { x =>
      result = Some(x)
    })
    getMain(code).execute(5L.asInstanceOf[AnyRef])
    result shouldEqual Some(6)
  }
}
