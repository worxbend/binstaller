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

  private def reflectionConfigClassNames(): Set[String] =
    val root = Path.of(System.getProperty("binstaller.repoRoot"))
    val json = Files.readString(
      root.resolve("app/resources/META-INF/native-image/binstaller/binstaller/reflect-config.json")
    )
    "\"name\"\\s*:\\s*\"([^\"]+)\"".r.findAllMatchIn(json).map(_.group(1)).toSet

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
