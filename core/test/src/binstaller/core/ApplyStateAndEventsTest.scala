package binstaller.core

import utest.*

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import upickle.default.read
import upickle.default.write

/** Apply-state persistence and resume, and the renderer-agnostic event contract. */
object ApplyStateAndEventsTest extends TestSuite with CoreTestSupport:

  val tests: Tests = Tests:
    test("installer event context measures elapsed time from injected monotonic clock"):
      var now     = 1_000L
      val context = InstallerEventContext.start(InstallerEventObserver.none, () => now)

      now = 1_250L

      assert(context.elapsedTime == Duration.ofNanos(250L))

    test("run statistics use structured results rather than rendered wording"):
      val statistics = InstallerRunStatistics.fromResult(InstallerResult(
        lines = Vector("wording can change freely"),
        status = InstallerRunStatus.Failed,
        terminalResults = Vector(
          TerminalToolResult.Completed(toolName("alpha"), "/apps/alpha"),
          TerminalToolResult.Failed(toolName("beta"), "boom")
        ),
        skippedTools = 3
      ))

      assert(statistics == InstallerRunStatistics(installed = 1, failed = 1, skipped = 3))

    test("completed state entries are skipped and failed entries are retried"):
      val tempRoot     = tempDirectory("core-state-resume")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "resume.state.json"))
      val firstService = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Left("network unavailable")
        ))
      )

      val firstResult = firstService.apply(applyOptions(config))

      assert(firstResult.status == InstallerRunStatus.Failed)
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val secondService = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      val skippedResult = secondService.apply(
        applyOptions(config).copy(selection = ToolSelection(Vector.empty, Vector("beta")))
      )

      assert(skippedResult.status == InstallerRunStatus.Succeeded)
      assert(skippedResult.lines == Vector("skipped alpha: already completed in state"))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))

      val retryResult = secondService.apply(applyOptions(config))
      val state       = loadState(tempRoot, "resume.state.json")

      assert(retryResult.status == InstallerRunStatus.Succeeded)
      assert(retryResult.lines.exists(_.contains("skipped alpha")))
      assert(retryResult.lines.exists(_.contains("installed beta")))
      assert(Files.isRegularFile(tempRoot.resolve("apps/beta/bin/beta")))
      assert(state.tools.map(tool => tool.name.value -> tool.status) ==
        Vector(
          "alpha" -> ApplyStateToolStatus.Completed,
          "beta"  -> ApplyStateToolStatus.Completed
        ))
      assert(!hasTempStateFile(tempRoot, "resume.state.json"))

    test("incompatible state fails clearly unless reset-state is enabled"):
      val tempRoot  = tempDirectory("core-state-reset")
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, "mismatch.state.json"))
      val store     = ApplyStateStore.nio(tempRoot)
      val statePath = tempRoot.resolve("mismatch.state.json")
      store.save(
        statePath,
        ApplyState.empty("other-profile", "other-fingerprint")
      ) match
        case Right(())   => ()
        case Left(error) => abort(s"failed to seed state: $error")
      val service = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val mismatchResult = service.apply(applyOptions(config))
      val resetResult    = service.apply(applyOptions(config).copy(resetState = ResetState.Enabled))

      assert(mismatchResult.status == InstallerRunStatus.Failed)
      assert(mismatchResult.lines.exists(_.contains("does not match this manifest")))
      assert(mismatchResult.lines.exists(_.contains("--reset-state")))
      assert(resetResult.status == InstallerRunStatus.Succeeded)
      assert(loadState(tempRoot, "mismatch.state.json").profileName == "resume-profile")

    test("state schema version is validated before resume"):
      val tempRoot  = tempDirectory("core-state-schema")
      val stateFile = "schema.state.json"
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, stateFile))
      val service   = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      assert(service.apply(applyOptions(config)).status == InstallerRunStatus.Succeeded)
      val store        = ApplyStateStore.nio(tempRoot)
      val incompatible = loadState(tempRoot, stateFile).copy(schemaVersion = 999)
      store.save(tempRoot.resolve(stateFile), incompatible) match
        case Left(error) => abort(s"failed to seed state: $error")
        case Right(())   => ()

      val result = service.apply(applyOptions(config))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(_.contains("schema version 999")))
      assert(result.lines.exists(_.contains("expected 1")))

    test("completed state is retried when installed executables disappear"):
      val tempRoot  = tempDirectory("core-state-drift")
      val stateFile = "drift.state.json"
      val config    = writeConfig(tempRoot, twoToolYaml(tempRoot, stateFile))
      val service   = statefulService(tempRoot, RoutingBinaryDownloadClient.success)
      assert(service.apply(applyOptions(config)).status == InstallerRunStatus.Succeeded)
      val alpha = tempRoot.resolve("apps/alpha/bin/alpha")
      Files.delete(alpha)

      val result = service.apply(
        applyOptions(config).copy(selection = ToolSelection(Vector("alpha"), Vector.empty))
      )

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(result.lines.exists(_.contains("installed alpha")))
      assert(Files.isRegularFile(alpha))

    test("state paths must be cwd-local filenames"):
      val tempRoot = tempDirectory("core-state-path")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "valid.state.json"))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val absoluteResult = service.apply(
        applyOptions(config).copy(statePath =
          Some(tempRoot.resolve("absolute.state.json").toString)
        )
      )
      val nestedResult = service.apply(
        applyOptions(config).copy(statePath = Some("nested/state.json"))
      )
      val malformedResult = service.apply(
        applyOptions(config).copy(statePath = Some("bad\u0000state.json"))
      )

      assert(absoluteResult.status == InstallerRunStatus.Failed)
      assert(absoluteResult.lines.exists(_.contains("absolute state paths are not allowed")))
      assert(nestedResult.status == InstallerRunStatus.Failed)
      assert(nestedResult.lines.exists(_.contains("current working directory")))
      assert(malformedResult.status == InstallerRunStatus.Failed)
      assert(StatePathResolver.resolve("bad\u0000state.json", tempRoot).left.exists(
        _.isInstanceOf[ApplyStateError.InvalidPath]
      ))
      assert(!Files.exists(tempRoot.resolve("apps")))

    test("state is saved after each terminal tool result"):
      val tempRoot = tempDirectory("core-state-writes")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "writes.state.json"))
      val store    = RecordingApplyStateStore(ApplyStateStore.nio(tempRoot))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        store
      )

      val result = service.apply(applyOptions(config))

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(store.savedStates.size == 2)
      assert(store.savedStates.map(_.tools.map(tool => tool.name.value -> tool.status)) ==
        Vector(
          Vector("alpha" -> ApplyStateToolStatus.Completed),
          Vector(
            "alpha" -> ApplyStateToolStatus.Completed,
            "beta"  -> ApplyStateToolStatus.Completed
          )
        ))

    test("state write failure keeps its wording and discards the next prepared stage"):
      val tempRoot     = tempDirectory("core-state-write-failure")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "failed.state.json"))
      val delegate     = ApplyStateStore.nio(tempRoot)
      val failingStore = new ApplyStateStore:
        def cwd: Path = delegate.cwd

        def load(path: Path): Either[ApplyStateError, Option[ApplyState]] = delegate.load(path)

        def save(path: Path, state: ApplyState): Either[ApplyStateError, Unit] =
          Left(ApplyStateError.WriteFailed(path, "disk full"))
      val service = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        failingStore
      )

      val result = service.apply(applyOptions(config))

      assert(result.status == InstallerRunStatus.Failed)
      assert(result.lines.exists(line =>
        line.startsWith("state write failed:") && line.contains("disk full")
      ))
      assert(Files.isRegularFile(tempRoot.resolve("apps/alpha/bin/alpha")))
      assert(!Files.exists(tempRoot.resolve("apps/beta")))
      assert(!hasStagedInstall(tempRoot, "beta"))

    test("temporary cleanup failure cannot escape an atomic write failure"):
      val tempRoot      = tempDirectory("core-persisted-json-cleanup")
      val blockedParent = tempRoot.resolve("not-a-directory")
      Files.writeString(blockedParent, "block the state directory")
      val target   = blockedParent.resolve("state.json")
      val occupied = Files.createDirectory(tempRoot.resolve("occupied-temp"))
      Files.writeString(occupied.resolve("child"), "keep cleanup from deleting the directory")

      val result = PersistedJson.writeAtomically(target, "{}", (_, _) => occupied)

      assert(result.left.exists(_.contains(blockedParent.toString)))
      assert(!result.left.exists(_.contains(occupied.toString)))
      assert(Files.isDirectory(occupied))

    test("rendered terminal lines pair each apply line with its status"):
      val tempRoot = tempDirectory("core-rendered-lines")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "rendered.state.json"))
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.apply(applyOptions(config))

      // Each paired text must be one of the lines actually printed, so a renderer can match on it
      // rather than re-derive it, and the statuses must line up with the terminal results.
      assert(result.renderedTerminalLines.nonEmpty)
      assert(result.renderedTerminalLines.forall(rendered => result.lines.contains(rendered.text)))
      assert(result.renderedTerminalLines.size == result.terminalResults.size)
      assert(result.renderedTerminalLines.map(_.status) == result.terminalResults.map:
        case _: TerminalToolResult.Completed => ToolResultStatus.Completed
        case _: TerminalToolResult.Failed    => ToolResultStatus.Failed)

    test("a hand-edited state file with an unsafe tool name fails to decode"):
      // Tool name keys the state row and is used as a path segment. Reading it back through the
      // same validation the manifest goes through means a hand-edited file cannot smuggle one in.
      val tempRoot  = tempDirectory("core-state-toolname")
      val stateFile = tempRoot.resolve("evil.state.json")
      Files.writeString(
        stateFile,
        """
          |{
          |  "schemaVersion": 1,
          |  "profileName": "profile",
          |  "manifestFingerprint": "fingerprint",
          |  "tools": [
          |    {
          |      "name": "../evil",
          |      "status": "completed",
          |      "installDir": null,
          |      "message": null,
          |      "download": null
          |    }
          |  ]
          |}
          |""".stripMargin
      )

      ApplyStateStore.nio(tempRoot).load(stateFile) match
        case Left(ApplyStateError.DecodeFailed(_, message)) =>
          assert(message.contains("invalid tool name"))
        case other => abort(s"expected a decode failure, got $other")

    test("apply state status remains serialized as a stable string"):
      val state = ApplyState.empty("profile", "fingerprint").copy(tools =
        Vector(ApplyStateTool(
          toolName("alpha"),
          ApplyStateToolStatus.Completed,
          Some("/tmp/apps/alpha"),
          None
        ))
      )

      val encoded = write(state, indent = 2)
      val decoded = read[ApplyState](encoded)

      assert(encoded.contains(""""status": "completed""""))
      assert(decoded.tools.head.status == ApplyStateToolStatus.Completed)

    test("apply state status decodes existing string state files"):
      val decoded = read[ApplyState](
        """
          |{
          |  "schemaVersion": 1,
          |  "profileName": "profile",
          |  "manifestFingerprint": "fingerprint",
          |  "tools": [
          |    {
          |      "name": "alpha",
          |      "status": "failed",
          |      "installDir": null,
          |      "message": "network unavailable",
          |      "download": null
          |    }
          |  ]
          |}
          |""".stripMargin
      )

      assert(decoded.tools.head.status == ApplyStateToolStatus.Failed)
      assert(decoded.tools.head.message.contains("network unavailable"))

    test("an unrecognised apply state status is a decode failure, not a read failure"):
      // The state file is a user-editable JSON artifact in the working directory, so a hand-typed
      // or hand-merged status is a realistic input. Reporting it as "could not read" sends the
      // user looking at file permissions instead of at the line they edited.
      assert(ApplyStateToolStatus.fromString("pending").isEmpty)
      assert(ApplyStateToolStatus.fromString("completed").contains(ApplyStateToolStatus.Completed))

      val tempRoot  = tempDirectory("core-state-status")
      val stateFile = tempRoot.resolve("corrupt.state.json")
      Files.writeString(
        stateFile,
        """
          |{
          |  "schemaVersion": 1,
          |  "profileName": "profile",
          |  "manifestFingerprint": "fingerprint",
          |  "tools": [
          |    {
          |      "name": "alpha",
          |      "status": "pending",
          |      "installDir": null,
          |      "message": null,
          |      "download": null
          |    }
          |  ]
          |}
          |""".stripMargin
      )

      ApplyStateStore.nio(tempRoot).load(stateFile) match
        case Left(ApplyStateError.DecodeFailed(_, message)) =>
          assert(message.contains("unknown apply state tool status"))
        case other => abort(s"expected a decode failure, got $other")

    test("plan emits resolving plan-ready and summary events in order"):
      val tempRoot = tempDirectory("core-events-plan")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "plan.state.json"))
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.planWithEvents(applyOptions(config), observer)
      val planReady: PartialFunction[InstallerEvent, Boolean] = {
        case InstallerEvent.PlanReady(names, Some(_), _)
            if names.map(_.value) == Vector("alpha", "beta") => true
      }

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(eventIndex(observer.events, { case InstallerEvent.ResolvingStarted(_, _) => true }) <
        eventIndex(observer.events, planReady))
      assert(eventIndex(observer.events, planReady) <
        eventIndex(
          observer.events,
          {
            case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 0, 0, 0, Some(_), _) => true
          }
        ))

    test("successful apply emits tool start progress result and summary in order"):
      val tempRoot = tempDirectory("core-events-success")
      val config   = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val observer = RecordingInstallerEventObserver()
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(
          ProgressingBinaryDownloadClient("alpha-binary".getBytes(StandardCharsets.UTF_8)),
          InstallFileSystem.nio
        ),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.applyWithEvents(applyOptions(config), observer)
      val alphaCompleted: PartialFunction[InstallerEvent, Boolean] = {
        case InstallerEvent.ToolResult(
              named("alpha"),
              ToolResultStatus.Completed,
              Some(_),
              None,
              _
            ) => true
      }

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolStarted(named("alpha"), InstallerPhase.Downloading, _) => true
        }
      ) < eventIndex(
        observer.events,
        {
          case InstallerEvent.DownloadProgress(
                named("alpha"),
                "https://example.invalid/alpha",
                _,
                Some(_),
                DownloadProgressStatus.Advanced,
                _
              ) => true
        }
      ))
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.DownloadProgress(_, _, _, _, DownloadProgressStatus.Finished, _) =>
            true
        }
      ) < eventIndex(observer.events, alphaCompleted))
      assert(eventIndex(observer.events, alphaCompleted) < eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 1, 0, 0, None, _) => true
        }
      ))

    test("failed apply emits failed result with root-cause summary"):
      val tempRoot = tempDirectory("core-events-failed")
      val config   = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(tempRoot, FakeBinaryDownloadClient.failure("network down"))

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.status == InstallerRunStatus.Failed)
      assert(observer.events.exists:
        case InstallerEvent.ToolResult(
              named("alpha"),
              ToolResultStatus.Failed,
              None,
              Some(summary),
              _
            ) => summary.contains("download:") && summary.contains("network down")
        case _ => false)
      assert(observer.events.exists:
        case InstallerEvent.Summary(InstallerRunStatus.Failed, 0, 1, 0, None, _) => true
        case _                                                                   => false)

    test("completed state entries emit skipped events with state file path"):
      val tempRoot     = tempDirectory("core-events-skipped")
      val config       = writeConfig(tempRoot, twoToolYaml(tempRoot, "resume.state.json"))
      val firstService = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Right("alpha".getBytes(StandardCharsets.UTF_8)),
          "https://example.invalid/beta"  -> Left("network unavailable")
        ))
      )
      val _             = firstService.apply(applyOptions(config))
      val observer      = RecordingInstallerEventObserver()
      val secondService = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = secondService.applyWithEvents(
        applyOptions(config).copy(selection = ToolSelection(Vector.empty, Vector("beta"))),
        observer
      )

      assert(result.status == InstallerRunStatus.Succeeded)
      val skipIndex = eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolSkipped(
                named("alpha"),
                "already completed in state",
                Some(path),
                _
              ) => path.endsWith("resume.state.json")
        }
      )
      val summaryIndex = eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Succeeded, 0, 0, 1, Some(_), _) => true
        }
      )
      assert(skipIndex < summaryIndex)

    test("apply with a state file emits a state-loading event without a phantom tool"):
      val tempRoot = tempDirectory("core-events-state-loading")
      val config   = writeConfig(tempRoot, twoToolYaml(tempRoot, "loading.state.json"))
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(tempRoot, RoutingBinaryDownloadClient.success)

      val result = service.applyWithEvents(applyOptions(config), observer)

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(observer.events.exists:
        case InstallerEvent.StateLoading(path, _) => path == "loading.state.json"
        case _                                    => false)
      assert(!observer.events.exists:
        case InstallerEvent.ToolPhaseChanged(name, _, _) => name.value == "state"
        case _                                           => false)

    test("a throwing event observer does not fail the install it observes"):
      val tempRoot = tempDirectory("core-events-throwing-observer")
      val config   = writeConfig(tempRoot, directBinaryYaml(tempRoot.resolve("alpha")))
      val service  = BinaryInstallerService.resolving(
        FakeHttpTextClient(""),
        DirectBinaryInstaller(RoutingBinaryDownloadClient.success, InstallFileSystem.nio),
        ApplyStateStore.nio(tempRoot)
      )

      val result = service.applyWithEvents(
        applyOptions(config),
        _ => throw RuntimeException("renderer exploded")
      )

      assert(result.status == InstallerRunStatus.Succeeded)
      assert(Files.isRegularFile(tempRoot.resolve("alpha/bin/alpha")))

    test("continue-on-error emits failed then completed results before failed summary"):
      val tempRoot = tempDirectory("core-events-continue")
      val config   = writeConfig(
        tempRoot,
        twoToolYaml(tempRoot, "continue.state.json", continueOnError = true)
      )
      val observer = RecordingInstallerEventObserver()
      val service  = statefulService(
        tempRoot,
        RoutingBinaryDownloadClient(Map(
          "https://example.invalid/alpha" -> Left("network unavailable"),
          "https://example.invalid/beta"  -> Right("beta".getBytes(StandardCharsets.UTF_8))
        ))
      )

      val result = service.applyWithEvents(applyOptions(config), observer)
      val betaCompleted: PartialFunction[InstallerEvent, Boolean] = {
        case InstallerEvent.ToolResult(
              named("beta"),
              ToolResultStatus.Completed,
              Some(_),
              None,
              _
            ) => true
      }

      assert(result.status == InstallerRunStatus.Failed)
      assert(eventIndex(
        observer.events,
        {
          case InstallerEvent.ToolResult(
                named("alpha"),
                ToolResultStatus.Failed,
                None,
                Some(_),
                _
              ) => true
        }
      ) < eventIndex(observer.events, betaCompleted))
      assert(eventIndex(observer.events, betaCompleted) < eventIndex(
        observer.events,
        {
          case InstallerEvent.Summary(InstallerRunStatus.Failed, 1, 1, 0, Some(_), _) => true
        }
      ))
