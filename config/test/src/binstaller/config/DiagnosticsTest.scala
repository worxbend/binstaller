package binstaller.config

import utest.*

import java.nio.file.AccessDeniedException
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

/** Filesystem failures say why they failed: a path alone never stands in for the reason. */
object DiagnosticsTest extends TestSuite:

  val tests: Tests = Tests:
    test("a filesystem failure whose message is only the path gains its reason"):
      val stage = "/opt/apps/.tool.stage-1"

      assert(Diagnostics.describe(AccessDeniedException(stage)) == s"$stage: permission denied")
      assert(Diagnostics.describe(NoSuchFileException(stage)) == s"$stage: no such file")
      assert(Diagnostics.describe(FileAlreadyExistsException(stage)) == s"$stage: already exists")
      assert(
        Diagnostics.describe(DirectoryNotEmptyException(stage)) == s"$stage: directory not empty"
      )

    test("a failed move keeps both of its paths in front of the reason"):
      val failure = NoSuchFileException("/opt/apps/.tool.stage-1", "/opt/apps/tool", null)

      assert(
        Diagnostics.describe(failure) == "/opt/apps/.tool.stage-1 -> /opt/apps/tool: no such file"
      )

    test("a filesystem failure that states its own reason is returned unchanged"):
      val readOnly = FileSystemException("/opt/apps/tool", null, "Read-only file system")
      val denied   = AccessDeniedException("/opt/apps/tool", null, "mount is read-only")

      assert(Diagnostics.describe(readOnly) == "/opt/apps/tool: Read-only file system")
      assert(Diagnostics.describe(denied) == "/opt/apps/tool: mount is read-only")

    test("a read failure gives the reason alone because its caller names the path"):
      val profile = "/home/test/config.yaml"

      assert(Diagnostics.describeReadFailure(NoSuchFileException(profile)) == "no such file")
      assert(Diagnostics.describeReadFailure(AccessDeniedException(profile)) == "permission denied")
