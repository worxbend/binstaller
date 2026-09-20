package binstaller.core

import binstaller.config.PolicyOverride
import binstaller.config.SymlinkPrivilege
import utest.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** Symlink creation, sudo credential handling, and redaction of sensitive values. */
object SudoAndRedactionTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("sudo password requests stay serialized after parallel downloads"):
      val tempRoot = tempDirectory("core-parallel-sudo")
      val config   = writeConfig(tempRoot, twoSudoToolYaml(tempRoot))
      val client   = ConcurrentTrackingDownloadClient(
        Vector("https://example.invalid/alpha", "https://example.invalid/beta")
      )
      val credentials = ConcurrentTrackingSudoCredentialProvider()
      val service     = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          client,
          InstallFileSystem.nio,
          PasswordPromptCommandExecutor(),
          credentials
        ),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.apply(applyOptions(config).copy(applyParallelism = parallelism(2)))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(client.maxInFlight >= 2)
      assert(credentials.maxInFlight == 1)
      assert(credentials.toolNames == Vector("alpha", "beta"))

    test("production runtime-variable allowlist produces no redactions"):
      // Guards the documented invariant that redaction is empty in production: every allowlisted
      // env name is non-secret, so none is redacted (control scrubbing in display() is the
      // always-on protection).
      val allowlisted =
        RuntimeTemplateEnvironment.allowed.map(name => name -> "some-non-secret-value").toMap
      assert(
        SensitiveValueRedactions.fromRuntimeVariables(allowlisted) == SensitiveValueRedactions.empty
      )

    test("sensitive-name substrings and the length threshold drive runtime-variable redaction"):
      // One row per marker isSensitiveName matches on: the uppercased name contains the marker
      // and the value meets the >= 4 length floor. The last row pins case-insensitivity.
      val sensitiveNames = Vector(
        "GH_TOKEN",
        "SERVICE_SECRET",
        "DB_PASSWORD",
        "SUDO_PASS",
        "APP_API_KEY",
        "AWS_ACCESS_KEY_ID",
        "TLS_PRIVATE_KEY",
        "CLIENT_CREDENTIAL",
        "PROXY_AUTHORIZATION",
        "AUTH_BEARER",
        "APP_SESSION",
        "HTTP_COOKIE",
        "lowercase_token"
      )
      sensitiveNames.foreach: name =>
        val value      = s"value-for-$name"
        val redactions = SensitiveValueRedactions.fromRuntimeVariables(Map(name -> value))
        assert(redactions.values == Vector(value))

      // A marker-free name, and a marked name whose value is under the length floor, stay out.
      assert(SensitiveValueRedactions.fromRuntimeVariables(Map(
        "HOME"        -> "/home/test",
        "SHORT_TOKEN" -> "abc"
      )).values.isEmpty)

    test("overlapping sensitive values redact longest-first without leaving a suffix"):
      // "shared-secret" is a prefix of "shared-secret-suffix": redacting the shorter first would
      // turn the longer into "<redacted>-suffix", leaking its tail. The constructor sorts
      // longest-first so the whole longer value is consumed in one replacement.
      val redactions = SensitiveValueRedactions.fromRuntimeVariables(Map(
        "FIRST_TOKEN"  -> "shared-secret",
        "SECOND_TOKEN" -> "shared-secret-suffix"
      ))

      assert(redactions.redact("auth shared-secret-suffix!") == "auth <redacted>!")

    test("apply errors redact sensitive runtime values and scrub terminal controls"):
      val secret = "secret-token-value"
      val plan   = ResolvedPlan(
        ResolvedPolicy.restricted("/tmp/apps"),
        Vector(directTool(Path.of("/tmp/apps/alpha")).copy(download =
          ResolvedDownload(
            url = s"https://example.invalid/$secret/alpha",
            filename = "alpha",
            checksum = None,
            archive = None
          )
        )),
        SensitiveValueRedactions(Vector(secret))
      )
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.failure(s"network \u001b[31m failure for $secret"),
        InstallFileSystem.nio
      )

      val result = installer.installPlan(plan)
      val output = result.lines.mkString("\n")

      assert(result.status == InstallerRunStatus.Failed)
      assert(!output.contains(secret))
      assert(!output.contains("\u001b"))
      assert(output.contains("<redacted>"))

    test("local symlinks are created under installDir with targets resolved from installDir"):
      val tempRoot   = tempDirectory("core-local-symlink")
      val installDir = tempRoot.resolve("alpha")
      val installer  = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio
      )
      val tool = directTool(
        installDir,
        symlinks = Vector(ResolvedSymlink("bin/a", "bin/alpha", SymlinkPrivilege.User))
      )

      val result = installer.installTool(tool)

      assertInstallSuccess(result, installDir.toString)
      assert(Files.isSymbolicLink(installDir.resolve("bin/a")))
      assert(Files.readSymbolicLink(installDir.resolve("bin/a")) ==
        installDir.toAbsolutePath.normalize().resolve("bin/alpha"))

    test("sudo symlink apply requires policy before writes"):
      val tempRoot        = tempDirectory("core-sudo-gate")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val installer       = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor
      )
      val plan = ResolvedPlan(
        ResolvedPolicy.restricted(tempRoot.toString),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("policy.allowSudoSymlinks")))
      assert(commandExecutor.commands.isEmpty)
      assert(!Files.exists(installDir))

    test("sudo symlink apply uses structured argv after policy allowance"):
      val tempRoot        = tempDirectory("core-sudo-apply")
      val installDir      = tempRoot.resolve("alpha")
      val commandExecutor = RecordingCommandExecutor()
      val credentials     =
        RecordingSudoCredentialProvider(Right(SudoPassword.fromString("unused-secret")))
      val installer = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy.restricted(tempRoot.toString)
          .copy(allowSudoSymlinks = PolicyOverride.Enabled),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(credentials.requests.isEmpty)
      assert(commandExecutor.commands.map(_.argv) == Vector(
        Vector("sudo", "-n", "true"),
        Vector(
          "sudo",
          "-n",
          "ln",
          "-sfn",
          installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
          "/usr/local/bin/alpha"
        )
      ))
      assert(commandExecutor.commands.forall(_.env == CommandEnvironment.baseline))

    test("sudo symlink apply requests credentials when sudo cache is unavailable"):
      val tempRoot        = tempDirectory("core-sudo-credentials")
      val installDir      = tempRoot.resolve("alpha")
      val password        = "core-test-password"
      val commandExecutor =
        SequencedCommandExecutor(Vector(Left("sudo password required"), Right(())))
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy.restricted(tempRoot.toString)
          .copy(allowSudoSymlinks = PolicyOverride.Enabled),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(credentials.requests == Vector(SudoCredentialRequest(
        toolName("alpha"),
        "/usr/local/bin/alpha",
        installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
        s"create sudo symlink /usr/local/bin/alpha -> " +
          installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString
      )))
      assert(commandExecutor.commands.map(_.argv) == Vector(
        Vector("sudo", "-n", "true"),
        Vector(
          "sudo",
          "-S",
          "-p",
          "",
          "ln",
          "-sfn",
          installDir.toAbsolutePath.normalize().resolve("bin/alpha").toString,
          "/usr/local/bin/alpha"
        )
      ))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
      assert(!commandExecutor.commands.map(_.toString).exists(_.contains(password)))

    test("sudo credential cancellation fails current operation and continues when policy allows"):
      val tempRoot        = tempDirectory("core-sudo-cancel")
      val alphaInstall    = tempRoot.resolve("alpha")
      val betaInstall     = tempRoot.resolve("beta")
      val commandExecutor = SequencedCommandExecutor(Vector(Left("sudo password required")))
      val credentials     = RecordingSudoCredentialProvider(Left(SudoCredentialError.Canceled))
      val installer       = DirectBinaryInstaller(
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha-binary".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Right("beta-binary".getBytes(StandardCharsets.UTF_8))
        )),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val beta = directTool(betaInstall).copy(name = toolName("beta"))
      val plan = ResolvedPlan(
        ResolvedPolicy.restricted(tempRoot.toString)
          .copy(
            allowSudoSymlinks = PolicyOverride.Enabled,
            continueOnError = PolicyOverride.Enabled
          ),
        Vector(sudoSymlinkTool(alphaInstall), beta)
      )

      val result = installer.installPlan(plan)

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("sudo credentials canceled")))
      assert(result.lines.exists(_.contains("installed beta")))
      assert(credentials.requests.map(_.toolName.value) == Vector("alpha"))

    test("sudo command failure rendering redacts password from diagnostics"):
      val tempRoot        = tempDirectory("core-sudo-redaction")
      val installDir      = tempRoot.resolve("alpha")
      val password        = "super-secret-password"
      val commandExecutor = PasswordLeakingCommandExecutor(password)
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val plan = ResolvedPlan(
        ResolvedPolicy.restricted(tempRoot.toString)
          .copy(allowSudoSymlinks = PolicyOverride.Enabled),
        Vector(sudoSymlinkTool(installDir))
      )

      val result = installer.installPlan(plan)

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.mkString("\n").contains("<redacted>"))
      assert(!result.lines.mkString("\n").contains(password))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
      assert(!commandExecutor.commands.map(_.toString).exists(_.contains(password)))

    test("sudo password is redacted from events and apply state"):
      val tempRoot        = tempDirectory("core-sudo-state-redaction")
      val installDir      = tempRoot.resolve("alpha")
      val stateFile       = "sudo-redaction.state.json"
      val password        = "state-secret-password"
      val commandExecutor = PasswordLeakingCommandExecutor(password)
      val credentials = RecordingSudoCredentialProvider(Right(SudoPassword.fromString(password)))
      val installer   = DirectBinaryInstaller(
        FakeBinaryDownloadClient.success("alpha-binary".getBytes(StandardCharsets.UTF_8)),
        InstallFileSystem.nio,
        commandExecutor,
        credentials
      )
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        installer,
        ApplyStateStore.nio(tempRoot)
      )
      val config   = writeConfig(tempRoot, sudoSymlinkYaml(tempRoot, installDir, stateFile))
      val observer = RecordingInstallerEventObserver()

      val result   = service.applyWithEvents(applyOptions(config), observer)
      val state    = loadState(tempRoot, stateFile)
      val rendered =
        (result.lines ++
          observer.events.map(_.toString) ++
          state.tools.flatMap(_.message)).mkString("\n")

      assert(result.status == InstallerRunStatus.Failed)
      assert(rendered.contains("<redacted>"))
      assert(!rendered.contains(password))
      assert(!commandExecutor.commands.exists(_.argv.contains(password)))
