package binstaller.app

import utest.*

import java.nio.file.Files
import java.nio.file.Path
import scala.jdk.CollectionConverters.*
import scala.util.Using
import picocli.CommandLine.Command

object MainTest extends TestSuite:

  val tests: Tests = Tests:
    test("main object is loadable"):
      val mainClass = Class.forName("binstaller.app.Main$")
      assert(mainClass.getName == "binstaller.app.Main$")

    test("native reflection config covers every picocli command class"):
      val configured = reflectionConfigClassNames()
      val commands   = cliClasses().filter: className =>
        val loaded = Class.forName(className)
        loaded.getAnnotation(classOf[Command]) != null

      assert(commands.nonEmpty)
      assert(commands.toSet.subsetOf(configured))

      val cliAncestors = commands.flatMap(superclassChain).filter(_.startsWith("binstaller.cli."))
      assert(cliAncestors.toSet.subsetOf(configured))

  private def superclassChain(className: String): Vector[String] = Iterator
    .iterate(Class.forName(className): Class[?])(_.getSuperclass)
    .takeWhile(_ != null)
    .map(_.getName)
    .toVector

  private def reflectionConfigClassNames(): Set[String] =
    val entries = ujson.read(Files.readString(reflectConfigPath))
    entries.arr.map(entry => entry("name").str).toSet

  private def reflectConfigPath: Path = repoRootCandidates
    .map(_.resolve("app/resources/META-INF/native-image/binstaller/binstaller/reflect-config.json"))
    .find(Files.exists(_))
    .getOrElse(abort("could not locate reflect-config.json"))

  private def repoRootCandidates: Iterator[Path] =
    sys.props.get("binstaller.repoRoot").iterator.map(Path.of(_).toAbsolutePath) ++
      upwardPaths(Path.of("").toAbsolutePath)

  private def upwardPaths(start: Path): Iterator[Path] =
    Iterator.iterate(start)(_.getParent).takeWhile(_ != null)

  private def abort(message: String): Nothing = throw java.lang.AssertionError(message)

  private def cliClasses(): Vector[String] =
    System.getProperty("java.class.path").split(java.io.File.pathSeparator).toVector
      .map(Path.of(_))
      .filter(Files.isDirectory(_))
      .flatMap: root =>
        val cliRoot = root.resolve("binstaller/cli")
        if !Files.isDirectory(cliRoot) then Vector.empty
        else
          Using.resource(Files.walk(cliRoot)): paths =>
            paths.iterator().asScala.toVector
              .filter(path => path.toString.endsWith(".class"))
              .filterNot(path => path.getFileName.toString.contains("$"))
              .map: path =>
                root.relativize(path).toString.stripSuffix(".class").replace('/', '.')
