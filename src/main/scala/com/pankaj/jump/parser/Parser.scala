package com.pankaj.jump.parser

import com.pankaj.jump.{Path, Pos}

import scala.tools.nsc.ast.parser.Parsers
import scala.tools.nsc.{Global, Settings}
import scala.tools.nsc.reporters.ConsoleReporter
import scala.reflect.internal.util.{BatchSourceFile, NoSourceFile}
import scala.io.Source
import scala.annotation.tailrec

case class PosShort(fileId: Long, row: Int, col: Int)

// @param rfqn  reverse fully qualified name e.g. "util" : "twitter" : "com"
// JSymbol stands for Jump Symbol, longer name to avoid confusion with global.Symbol
case class JSymbol(rfqn: List[String], loc: Pos, typ: String) {
  def name = rfqn.head
  def qualName = rfqn.reverse.mkString(".")
}

case class JSymbolShort(rfqn: List[String], loc: PosShort, typ: String) {
  def name = rfqn.head
  def qualName = rfqn.reverse.mkString(".")
  def toJSymbol(idToFile: Long => Option[Path]): Option[JSymbol] =
    for(file <- idToFile(loc.fileId)) yield
      JSymbol(rfqn, Pos(file, loc.row, loc.col), typ)
}

// qual is in reverse
// todo rename qual to indicate reverse
case class JImport(qual: List[String], name: String, rename: String)

class Parser {
  val settings = new Settings
  settings processArgumentString "-usejavacp"
  val global = Global(settings, new ConsoleReporter(settings))

  import global._

  class SymbolCollector(call: JSymbol => Unit) extends Traverser {
    import collection.mutable
    private val _path: mutable.Stack[Tree] = mutable.Stack()

    // blank namespace is error, ignore such elements
    // namespace is reverse fully qualified name
    private def namespace(): List[String] = {
      val partNamess = for (part <- _path.toList if part.isInstanceOf[NameTree]) yield {
        part match {
          case p: PackageDef =>
            packagePidToNamespace(p.pid)

          // Take care of package object
          case m:ModuleDef if m.name.toString == "package" =>
            Nil
          case v:ValOrDefDef if v.name.toString == "<init>" => Nil
          case n: NameTree =>
            List(n.name.toString)
        }
      }
      partNamess.flatten
    }

    private def storeSymbol(t: Tree, typ: String) = {
      val ns = namespace()
      val sym = JSymbol(ns, positionToPos(t.pos), typ)
      call(sym)
      //_symbols = sym :: _symbols
    }

    override def traverse(t: Tree) = {
      _path push t
      try {
        t match {
          case _:ClassDef =>
            // todo handle traits
            storeSymbol(t, "class")

          case _:ModuleDef =>
            storeSymbol(t, "object")

          case v:ValOrDefDef if v.name.toString != "<init>" =>
            storeSymbol(t, "val")

          case TypeDef(mods, name, tparams, rhs) =>
            storeSymbol(t, "type")

          case _ =>
        }
        super.traverse(t)
      } finally _path.pop()
    }
  }

  def astForFile(file: Path): Tree = {
    val run = new Run
    val filename = file.toString
    val compUnit = new CompilationUnit(
        new BatchSourceFile(filename, Source.fromFile(filename).mkString)
      )
    file.extension match {
      case Some("java") => new syntaxAnalyzer.JavaUnitParser(compUnit).parse()
      case _ => new syntaxAnalyzer.UnitParser(compUnit).parse()
    }
  }

  // list of first elements in the tree
  // Meant for the case where tree really is a list
  def treeToList(tree: Tree): List[String] = {
    @tailrec
    def go(tree: Tree, ls: List[String]): List[String] = {
      tree match {
        case n: NameTree  =>
          if (!tree.children.isEmpty)
            go(tree.children.head, n.name.toString :: ls)
          else n.name.toString :: ls
        case _ => ls
      }
    }
    go(tree, Nil).reverse
  }

  def packagePidToNamespace(pid: RefTree): List[String] =
    pid.name.toString :: treeToList(pid.qualifier)

  def positionToPos(pos: Position): Pos =
    Pos(pos.source.path, pos.line, pos.column)

  // @return (Imports, Package)
  // package is reverse
  def trackDownSymbol(word: String, loc: Pos): (List[JImport], List[String]) = {
    class FindWithTrace extends Traverser {
      import collection.mutable
      private val _path: mutable.Stack[Tree] = mutable.Stack()
      private var _trace: List[Tree] = Nil
      private var _found = false
      private var _imports: List[Import] = Nil

      def trace: List[Tree] = _trace
      def imports: List[Import] = _imports

      //private def isMatch(t: Tree) = hasLoc(t.pos) && hasName(t)
      private def isMatch(t: Tree) = {
        t match {
          case n: NameTree =>
            val name = n.name.toString
            val p = t.pos
            name == word &&
            p.line == loc.row &&
            loc.col >= p.column &&
            loc.col < p.column + name.size
          case _ => false
        }
      }

      override def traverse(t: Tree) = {
        if (!_found) {
          _path push t
          if(isMatch(t)) {
            _found = true
            _trace = _path.toList
          } else {
            try {
              t match {
                case i: Import =>
                  _imports = i :: _imports
                case _ =>
              }
              super.traverse(t)
            } finally _path.pop()
          }
        }
      }
    }

    val tree = astForFile(loc.file)
    val traceFinder = new FindWithTrace
    traceFinder.traverse(tree)
    val trace = (traceFinder.trace)
    val packages = trace.foldLeft(List[String]()) { (acc, t) =>
      t match {
        case PackageDef(pid, stats) =>
          // treeToList(pid.qualifier) gives package qual in reverse order
          val x = acc ++ List(pid.name.toString) ++ treeToList(pid.qualifier)
          x
        case _ => acc
      }
    }
    val imports = traceFinder.imports flatMap { case Import(expr, selectors) =>
      val qual = treeToList(expr)
      for (ImportSelector(name, _, rename, _) <- selectors) yield {
        val ren = if (rename == null) name else rename
        JImport(qual, name.toString, ren.toString)
      }
    }
    (imports, packages)
  }


  def forSymbols(file: Path)(call: JSymbol => Unit) = {
    val tree = astForFile(file)
    val symc = new SymbolCollector(call)
    symc.traverse(tree)
    //symc.symbols
  }

  def listSymbols(file: Path): List[JSymbol] = {
    var xs = List[JSymbol]()
    forSymbols(file){ x =>
      xs = x :: xs
    }
    xs
  }

}
