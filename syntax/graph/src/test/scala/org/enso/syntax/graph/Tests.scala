package org.enso.syntax.graph

import org.enso.data.List1
import org.enso.syntax.graph
import org.enso.syntax.graph.Extensions._
import org.enso.syntax.text.AST
import org.enso.syntax.graph.API._
import org.enso.syntax.text.AST.Cons
import org.scalatest._
import matchers._
import org.scalatest.LoneElement._
import org.scalatest.matchers.MatchResult

import scala.reflect.ClassTag

/** Mock project state - contains a single module named `Main` with given body.
  */
final case class StateManagerMock(var program: String) extends StateManager {
  var ast: AST.Module = ParserUtils.parse(program)

  override def availableModules(): Seq[Module.Id] =
    Seq(StateManagerMock.mainModule)

  override def getModule(module: Module.Id): AST.Module = {
    if (module == StateManagerMock.mainModule)
      ast
    else throw graph.StateManager.ModuleNotFoundException(module)
  }

  override def setModule(module: Module.Id, ast: AST.Module): Unit = {
    if (module != StateManagerMock.mainModule)
      throw new Exception(s"no such module: $module")

    this.program = ast.show()
    this.ast     = ast
    println(s"New AST for module $module: $ast")
    println(s"New Program text for module $module:\n$program")
  }
}

final case class NotificationSinkMock() extends NotificationSink {
  var notificationsReceived: Seq[API.Notification] = Seq()
  override def retrieve(notification: API.Notification): Unit = {
    println(s"Got notification: $notification")
    notificationsReceived = notificationsReceived :+ notification
  }
}

object StateManagerMock {
  val mainModule: Module.Id = List1(Cons("Main"))
}

class Tests extends FunSuite with org.scalatest.Matchers {
  val mockModule = StateManagerMock.mainModule

  def withDR[R](
    program: String,
    f: DoubleRepresentation => R
  ): (R, AST.Module) = {
    val state                = StateManagerMock(program)
    val notificationConsumer = NotificationSinkMock()
    val result               = f(DoubleRepresentation(state, notificationConsumer))
    (result, state.ast)
  }

  def checkThatTransforms[R](
    initialProgram: String,
    expectedFinalProgram: String,
    action: DoubleRepresentation => R
  ): R = {
    val (result, finalAst) = withDR(initialProgram, action)
    val actualFinalProgram = finalAst.show()
    actualFinalProgram should be(expectedFinalProgram.replace("\r\n", "\n"))
    result
  }
  def expectTransformationError[E: ClassTag](
    initialProgram: String,
    action: DoubleRepresentation => Unit
  ): Unit = {
    an[E] should be thrownBy { withDR(initialProgram, action) }
    ()
  }
  def checkModuleSingleNodeGraph[R](
    program: String,
    action: API.Node.Info => R
  ): R = {
    withDR(
      program,
      dr => {
        val graph = dr.getGraph(Module.Graph.Location(mockModule))
        graph.nodes should have size 1
        graph.links should have size 0
        action(graph.nodes.head)
      }
    )._1
  }

  def expectImports(
    value: Seq[Module.Name],
    expected: Module.Name*
  ): Unit = {
    if (value.size != expected.size)
      fail(
        s"Imports list $value has ${value.size} elements"
        + s" while expected ${expected.size}"
      )

    value.zip(expected).foreach {
      case (lhs, rhs) => lhs.nameAsString() shouldEqual rhs.nameAsString()
    }
  }

  test("recognizing lack of imports") {
    withDR(
      "",
      dr => {
        val imports = dr.importedModules(mockModule)
        imports should have length 0
      }
    )
  }
  test("recognizing single import") {
    withDR(
      "import Foo.Baz",
      dr => {
        val imports = dr.importedModules(mockModule)
        expectImports(imports, Module.Name("Foo.Baz"))
      }
    )
  }
  test("adding first import") {
    checkThatTransforms(
      "",
      "import Foo.Baz",
      _.importModule(mockModule, Module.Name("Foo.Baz"))
    )
  }
  test("adding second import") {
    checkThatTransforms(
      "import Foo.Bar",
      "import Foo.Bar\nimport Foo.Baz",
      _.importModule(mockModule, Module.Name("Foo.Baz"))
    )
  }
  test("adding import when there is empty line and definition") {
    checkThatTransforms(
      """import Foo
        |
        |import Foo.Bar
        |
        |add x y = x + y""".stripMargin,
      """import Foo
        |
        |import Foo.Bar
        |import Foo.Baz
        |
        |add x y = x + y""".stripMargin,
      _.importModule(mockModule, Module.Name("Foo.Baz"))
    )
  }
  test("adding duplicated import") {
    expectTransformationError[API.ImportAlreadyExistsException](
      "import Foo.Bar",
      _.importModule(mockModule, Module.Name("Foo.Bar"))
    )
  }
  test("removing the only import") {
    checkThatTransforms(
      "import Foo.Baz",
      "",
      _.removeImport(mockModule, Module.Name("Foo.Baz"))
    )
  }
  test("removing one of several imports") {
    checkThatTransforms(
      """import Foo
        |import Foo.Baz
        |import Foo.Bar
        |add x y = x + y""".stripMargin,
      """import Foo
        |import Foo.Bar
        |add x y = x + y""".stripMargin,
      _.removeImport(mockModule, Module.Name("Foo.Baz"))
    )
  }
  test("removing import between empty lines") {
    checkThatTransforms(
      """import Foo
        |
        |import Foo.Baz
        |
        |add x y = x + y""".stripMargin,
      """import Foo
        |
        |
        |add x y = x + y""".stripMargin,
      _.removeImport(mockModule, Module.Name("Foo.Baz"))
    )
  }
  test("get empty module graph") {
    withDR(
      "",
      dr => {
        val graph = dr.getGraph(Module.Graph.Location(mockModule))
        graph.nodes should have size 0
        graph.links should have size 0
      }
    )
  }
  test("no nodes from function def") {
    withDR(
      "add a b = a + b",
      dr => {
        val graph = dr.getGraph(Module.Graph.Location(mockModule))
        graph.nodes should have size 0
        graph.links should have size 0
      }
    )
  }
  test("node trivial literal") {
    checkModuleSingleNodeGraph(
      "15",
      node => {
        node.expr.text should equal("15")
        node.inputs should have size 0
        node.output.name should equal(None)
        node.flags shouldBe empty
      }
    )
  }
//  test("node literal in parens") {
//    checkModuleSingleNodeGraph(
//      "(15)",
//      node => {
//        node.expr.text should equal("(15)")
//        node.inputs should have size 0
//        node.output.name should equal(None)
//        node.flags shouldBe empty
//      }
//    )
//  }
  test("node trivial var") {
    checkModuleSingleNodeGraph(
      "foo",
      node => {
        node.expr.text should equal("foo")
        node.inputs should have size 0
        node.output.name should equal(None)
        node.flags shouldBe empty
      }
    )
  }
  test("node trivial infix operator") {
    checkModuleSingleNodeGraph(
      "+",
      node => {
        node.expr.text should equal("+")
        node.inputs should have size 2
        node.inputs should equal(Seq(Port.Empty, Port.Empty))
        node.flags shouldBe empty
      }
    )
  }
  test("node single arg app") {
    checkModuleSingleNodeGraph(
      "foo 4",
      node => {
        node.expr.text should equal("foo 4")
        node.inputs should have size 1
        node.inputs should equal(Seq(Port.Empty))
        node.flags shouldBe empty
      }
    )
  }
  test("node single paren arg app") {
    checkModuleSingleNodeGraph(
      "foo (4)",
      node => {
        node.expr.text should equal("foo (4)")
        node.inputs should have size 1
        node.inputs should equal(Seq(Port.Empty))
        node.flags shouldBe empty
      }
    )
  }
  test("node unary minus number") {
    checkModuleSingleNodeGraph(
      "-5",
      node => {
        node.expr.text should equal("-5")
        node.inputs should equal(Port.Empty(1))
        node.flags shouldBe empty
      }
    )
  }
  test("node number plus") {
    checkModuleSingleNodeGraph(
      "+5",
      node => {
        node.expr.text should equal("+5")
        node.inputs should equal(Port.Empty(2))
        node.flags shouldBe empty
      }
    )
  }
  test("node plus number") {
    checkModuleSingleNodeGraph(
      "5+",
      node => {
        node.expr.text should equal("5+")
        node.inputs should equal(Port.Empty(2))
        node.flags shouldBe empty
      }
    )
  }
  test("node two arg app") {
    checkModuleSingleNodeGraph(
      "foo a _",
      node => {
        node.expr.text should equal("foo a _")
        node.inputs should equal(Port.Empty(2))
        node.flags shouldBe empty
      }
    )
  }
  test("node two arg app with paren") {
    checkModuleSingleNodeGraph(
      "(foo a) _",
      node => {
        node.expr.text should equal("(foo a) _")
        node.inputs should equal(Port.Empty(2))
        node.flags shouldBe empty
      }
    )
  }
  test("get trivial named node") {
    checkModuleSingleNodeGraph(
      "a = 15",
      node => {
        node.expr.text should equal("15")
        node.inputs should have size 0
        node.output.name should equal(Some("a"))
        node.flags shouldBe empty
      }
    )
  }
  test("get infix node") {
    checkModuleSingleNodeGraph(
      "a+2",
      node => {
        node.expr.text should equal("a+2")
        node.inputs should have size 2
        node.inputs should equal(Port.Empty(2))
        node.flags shouldBe empty
      }
    )
  }
}
