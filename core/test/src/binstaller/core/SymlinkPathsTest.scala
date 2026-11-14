package binstaller.core

import binstaller.config.SymlinkPrivilege
import utest.*

import java.nio.file.Path

/** The shared symlink rule: where a link is created, what it points at, and how plan prints it. */
object SymlinkPathsTest extends TestSuite:

  private val installDir = Path.of("/apps/alpha")

  val tests: Tests = Tests:
    test("a user link path resolves inside installDir"):
      assert(SymlinkPaths.linkPath(installDir, "bin/a", SymlinkPrivilege.User) ==
        Right(Path.of("/apps/alpha/bin/a")))
      assert(SymlinkPaths.linkPath(installDir, "/usr/local/bin/a", SymlinkPrivilege.User) ==
        Left("must be relative"))

    test("a sudo link path must be absolute and is used as written"):
      assert(SymlinkPaths.linkPath(installDir, "/usr/local/bin/a", SymlinkPrivilege.Sudo) ==
        Right(Path.of("/usr/local/bin/a")))
      assert(SymlinkPaths.linkPath(installDir, "bin/a", SymlinkPrivilege.Sudo) ==
        Left("must be absolute"))

    test("a target is taken from installDir unless absolute, and must stay inside it"):
      assert(SymlinkPaths.target(installDir, "bin/alpha") ==
        Right(Path.of("/apps/alpha/bin/alpha")))
      assert(SymlinkPaths.target(installDir, "/apps/alpha/bin/alpha") ==
        Right(Path.of("/apps/alpha/bin/alpha")))
      assert(SymlinkPaths.target(installDir, "/apps/beta/bin/beta") ==
        Left("must resolve inside installDir"))
      assert(SymlinkPaths.target(installDir, "../beta/bin/beta") ==
        Left("must resolve inside installDir"))

    test("a link must not be its own target"):
      val target = Path.of("/apps/alpha/bin/alpha")

      assert(SymlinkPaths.distinctEnds(Path.of("/apps/alpha/bin/alpha"), target) ==
        Left("must not equal its target"))
      assert(SymlinkPaths.distinctEnds(Path.of("/apps/alpha/bin/../bin/alpha"), target) ==
        Left("must not equal its target"))
      assert(SymlinkPaths.distinctEnds(Path.of("/apps/alpha/bin/a"), target) == Right(()))

    test("plan text names the same paths that apply resolves"):
      val ends = Vector(
        "bin/a"            -> SymlinkPaths.linkPath(installDir, "bin/a", SymlinkPrivilege.User),
        "/usr/local/bin/a" ->
          SymlinkPaths.linkPath(installDir, "/usr/local/bin/a", SymlinkPrivilege.Sudo),
        "bin/alpha"             -> SymlinkPaths.target(installDir, "bin/alpha"),
        "/apps/alpha/bin/alpha" -> SymlinkPaths.target(installDir, "/apps/alpha/bin/alpha")
      )

      ends.foreach: (written, resolved) =>
        assert(resolved.map(_.toString) == Right(SymlinkPaths.planText("/apps/alpha", written)))

    test("plan text keeps a relative installDir as the manifest wrote it"):
      assert(SymlinkPaths.planText("apps/alpha", "bin/a") == "apps/alpha/bin/a")
      assert(SymlinkPaths.planText("apps/alpha/", "bin/a") == "apps/alpha/bin/a")
