package org.enso.syntax

import org.enso.macros.Func0

class Parser extends Flexer {
  import org.enso.syntax.AST

  implicit def charToExpr(char: Char): Pattern = Ran(char, char)
  implicit def stringToExpr(s: String): Pattern =
    s.tail.foldLeft(char(s.head))(_ >> _)

  class ExtendedChar(_this: Char) {
    def ||(that: Char): Pattern = Or(char(_this), char(that))
  }
  implicit def extendChar(i: Char):  ExtendedChar = new ExtendedChar(i)
  def char(c: Char):                 Pattern      = range(c, c)
  def range(start: Char, end: Char): Pattern      = Ran(start, end)
  def range(start: Int, end: Int):   Pattern      = Ran(start, end)
  val any: Pattern  = range(0, Int.MaxValue)
  val pass: Pattern = Pass
  val eof: Pattern  = char('\3')

  def replaceGroupSymbols(s: String, lst: List[Group[Unit]]): String = {
    var out = s
    for ((grp, ix) <- lst.zipWithIndex) {
      out = out.replaceAll(s"___${ix}___", grp.index.toString)
    }
    out
  }

  ////////////////////////////////////

  var result: AST         = null
  var astStack: List[AST] = Nil

  def pushAST(): Unit = {
    logger.log("Push AST")
    astStack +:= result
    result = null
  }

  def popAST(): Unit = {
    logger.log("Pop AST")
    result   = astStack.head
    astStack = astStack.tail
  }

  ////// Offset Management //////

  var lastOffset: Int = 0

  def useLastOffset(): Int = {
    val offset = lastOffset
    lastOffset = 0
    offset
  }

  ////// Group Management //////

  var groupOffsetStack: List[Int] = Nil

  def pushGroupOffset(offset: Int): Unit = {
    groupOffsetStack +:= offset
  }

  def popGroupOffset(): Int = {
    val offset = groupOffsetStack.head
    groupOffsetStack = groupOffsetStack.tail
    offset
  }

  def onGroupBegin(): Unit = {
    pushAST()
    pushGroupOffset(useLastOffset())
  }

  def onGroupEnd(): Unit = {
    val offset  = popGroupOffset()
    val grouped = AST.grouped(offset, result, useLastOffset())
    popAST()
    appResult(grouped)
  }

  ////// Indent Management //////

  class BlockState(
    var isValid: Boolean,
    var indent: Int,
    var lines: List[AST.Line]
  )
  var currentBlock: BlockState     = new BlockState(true, 0, Nil)
  var blockStack: List[BlockState] = Nil

  def pushBlock(newIndent: Int): Unit = {
    logger.log("Push block")
    blockStack +:= currentBlock
    currentBlock = new BlockState(true, newIndent, Nil)
  }

  def popBlock(): Unit = {
    logger.log("Pop block")
    currentBlock = blockStack.head
    blockStack   = blockStack.tail
  }

  def submitBlock(): Unit = {
    logger.log("Block end")
    submitLine()
    val rlines = currentBlock.lines.reverse
    val block = AST.block(
      currentBlock.indent,
      List(),
      rlines.head.symbol.get,
      rlines.tail,
      currentBlock.isValid
    )
    popAST()
    popBlock()
    appResult(block)
    logger.endGroup()
  }

  def submitLine(): Unit = {
    val optResult = Option(result)
    currentBlock.lines +:= AST.Line(optResult, useLastOffset())
  }

  def onBlockBegin(newIndent: Int): Unit = {
    logger.log("Block begin")
    logger.beginGroup()
    pushAST()
    pushBlock(newIndent)
  }

  def onBlockNewline(): Unit = {
    logger.log("Block newline")
    submitLine()
    result = null
  }

  def onEmptyLine(): Unit = {
    logger.log("Empty line")

  }

  def onBlockEnd(newIndent: Int): Unit = {
    while (newIndent < currentBlock.indent) {
      submitBlock()
    }
    if (newIndent > currentBlock.indent) {
      logger.log("Block with invalid indentation")
      onBlockBegin(newIndent)
      currentBlock.isValid = false
    }
  }

  ////// Utils //////

  def ast(sym: String => AST.Symbol): AST =
    ast(sym(currentMatch))

  def ast(sym: AST.Symbol): AST =
    AST(currentMatch.length(), sym)

  def app(sym: String => AST.Symbol): Unit =
    appResult(ast(sym))

  def appResult(t: AST): Unit =
    if (result == null) {
      result = t
    } else {
      result = AST.app(result, useLastOffset(), t)
    }

  def onWhitespace(): Unit =
    lastOffset += currentMatch.length

  ////// Cleaning //////

  def onEOF(): Unit = {
    logger.log("EOF")
    onBlockEnd(0)
    submitBlock()
  }

  def description() = {

    ///////////////////////////////////////////

    val lowerLetter = range('a', 'z')
    val upperLetter = range('A', 'Z')
    val digit       = range('0', '9')
    val indentChar  = lowerLetter | upperLetter | digit | '_'
    val identBody   = indentChar.many >> '\''.many
    val variable    = lowerLetter >> identBody
    val constructor = upperLetter >> identBody
    val whitespace  = ' '.many1
    val newline     = '\n'

    val kwDef  = "def"
    val number = digit.many1

    val NORMAL   = defineGroup[Unit]("Normal")
    val PARENSED = defineGroup[Unit]("Parensed")
    val NEWLINE  = defineGroup[Unit]("Newline")

    // format: off
    
    ////// NORMAL //////
    NORMAL rule whitespace run Func0 {onWhitespace()}
    NORMAL rule kwDef      run Func0 {println("def!!!")}
    NORMAL rule variable   run Func0 {app(AST.Var)}
    NORMAL rule number     run Func0 {}
    NORMAL rule newline    run Func0 {beginGroup(NEWLINE)}
    NORMAL rule "("        run Func0 {beginGroup(PARENSED); onGroupBegin()}
    NORMAL rule eof        run Func0 {onEOF()}

    ////// PARENSED //////
    PARENSED.cloneRulesFrom(NORMAL)
    PARENSED rule ")" run Func0 {
      onGroupEnd()
      endGroup()
    }

    ////// NEWLINE //////
    NEWLINE rule (whitespace | pass) run Func0 {
      onWhitespace()
      if (result == null) {
        onEmptyLine()
      } else if (lastOffset == currentBlock.indent) {
        onBlockNewline()
      } else if (lastOffset > currentBlock.indent) {
        onBlockBegin(lastOffset)
      } else {
        onBlockEnd(lastOffset)
      }
      endGroup()
    }
    // format: on

    onBlockBegin(0)

  }
}