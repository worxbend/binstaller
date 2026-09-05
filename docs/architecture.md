# Architecture

Date: 2026-06-30

`binstaller` is a Scala 3/Mill application for one manifest shape:
`binstaller.io/v1alpha1` / `BinaryDistributionProfile`. The runtime graph is
acyclic:

```text
app -> cli -> core
core -> config
```

`core` does not import CLI code. Business rules live below the command layer,
and command output consumes resolved plans or renderer-agnostic events.

Core reports the outcome of a run as an `InstallerRunStatus`, never as a process
exit code: a POSIX status is a property of shipping as a CLI, not of installing
anything. `binstaller.cli.CliExitCode.of` is the only place that translation
happens.

## Module Responsibilities

- `config`: owns the shared validated value types — `ToolName` and
  `Sha256Digest` in particular. A tool name is an identity, not a label: it keys
  apply-state rows, lock-file rows, event correlation and `--only`/`--skip`
  selection, so it is parsed once at decode and carried as a type from there.
  `--only`/`--skip` input stays raw text, because that is what the user typed
  and what the "unknown tool" message echoes back.
  `Sha256Digest` normalizes to lowercase at construction, so every digest
  comparison is a plain `==` and the `^[0-9a-f]{64}$` rule exists in exactly one
  place.
- `config` also reads YAML with SnakeYAML Engine, decodes typed manifest models,
  validates supported enum values, rejects unsupported installer scripts, checks
  duplicate tool names, checks unknown `versionRef` values, validates SHA-256
  value shape, and gates sudo symlink declarations through
  `policy.allowSudoSymlinks`.
- `core`: resolves variables and versions, validates HTTPS URLs, applies
  `--only`/`--skip` selection, creates resolved plans, downloads bounded binary
  bodies, verifies checksums, extracts archives, stages and replaces installs,
  creates symlinks, persists apply state, emits typed installer events, and
  stays independent from command parsing. HTTP response bodies have a deadline
  for their full consumption, and owned download files, install stages, and
  external processes are cleaned up when their operation fails or is cancelled.
- `cli`: owns Picocli command parsing, exit codes, script-friendly default
  output, colored apply progress, global flags, and routing for `plan`,
  `apply`, `versions`, and `lock`. It adapts typed core output rather than
  reparsing rendered text: plain `versions` output keeps the core-authored
  lines, while ANSI output uses `VersionSummaryRow` values for styling.
- `app`: owns process entry and exit-code propagation only.
- `build/release`: `build.mill` defines modules and native-image settings;
  `.github/workflows/release.yml` builds, smokes, packages, checksums,
  and publishes Linux amd64 artifacts.

## Data Flow

1. CLI parses command flags into `InstallerOptions`.
2. `ProfileSource` turns the configured manifest location into a
   `BinaryDistributionProfile`. The production implementation
   (`ProfileSource.yamlFile`) delegates to `ConfigModule.load`; tests can
   substitute `ProfileSource.yamlText` to resolve a manifest without touching
   the filesystem.
3. `PlanResolver` resolves runtime variables, manifest vars, policy paths,
   version sources, download URLs, archive mappings, executable paths, and
   symlinks into `ResolvedPlan`. Host OS and architecture — which decide the
   `when:` selectors — are an explicit `ResolutionOptions` input with no
   default; only `ResolutionOptions.fromEnvironment()` detects them.
4. `ToolSelection` applies `--only` first and `--skip` second while preserving
   manifest order. Every command that resolves a plan — `plan`, `apply`,
   `versions` and `lock` — applies the selection.
5. `plan` renders the selected `ResolvedPlan` directly as script-friendly text.
6. `apply` checks state compatibility, executes each selected tool, writes apply
   state after terminal tool results, and emits `InstallerEvent` values. Tools
   are downloaded and staged with bounded parallelism (`ApplyParallelism`,
   default 4, overridable with `apply --parallelism N`).
7. CLI apply progress consumes the event contract to keep a compact progress
   line and summary without changing core execution behavior.

`ConfiguredCommand`, `SelectableCommand`, and `LockAwareCommand` build
`InstallerOptions` through the command hierarchy. This keeps global, selection,
and lock options consistent across the commands that support them; `apply` adds
its validated parallelism setting at the leaf command.

## Command Surface

The supported executable surface is intentionally small:

- `plan`: resolve and render the selected plan without writing.
- `apply`: perform an install apply.
- `versions`: print a package/version summary table and show newer GitHub
  release versions when available.
- `lock`: resolve and write reproducible lock metadata.

## Event Contract

Core emits the following renderer-agnostic events:

- `ResolvingStarted(configPath, elapsedTime)`
- `PlanReady(toolNames, stateFilePath, elapsedTime)`
- `ToolStarted(toolName, phase, elapsedTime)`
- `ToolPhaseChanged(toolName, phase, elapsedTime)`
- `DownloadProgress(toolName, url, downloadedBytes, totalBytes, status, elapsedTime)`
- `LogLine(toolName, line, elapsedTime)`
- `ToolResult(toolName, status, installDir, failureSummary, elapsedTime)`
- `ToolSkipped(toolName, reason, stateFilePath, elapsedTime)`
- `Summary(status, installed, failed, skipped, stateFilePath, elapsedTime)`

Current phases are `Resolving`, `Planning`, `LoadingState`, `Downloading`,
`VerifyingChecksum`, `Staging`, `ApplyingModes`, `ReplacingInstall`,
`VerifyingExecutables`, `CreatingSymlinks`, and `SavingState`.

## Invariants

- `plan`, `apply`, and `versions` output remains script-friendly.
- GitHub latest-release lookup failures do not turn version reporting into a
  failed command.
- CLI commands default to `./config.yaml` from the process current directory
  when `--config` is omitted.
- `plan` does not touch install directories or state files.
- Apply state is filename-only in the current working directory.
- Manifest installer scripts are unsupported and rejected during config loading.
- Display surfaces use render safety and redaction at renderer boundaries while
  preserving raw values for filesystem and network operations.
- CLI renderers never parse core's rendered text. `versions` returns structured
  `VersionSummaryRow` values alongside the script-friendly lines, and the CLI
  colours those rows rather than splitting the padded table back apart.
- CLI renderers never parse core's wording. Rendered apply lines cross the
  boundary as `RenderedTerminalLine`, each paired with a typed
  `ToolResultStatus`, so colour is chosen by status rather than by testing a
  line for a prefix.
- JSON state and lock persistence share a same-directory temporary-file
  and atomic-move mechanism.

## Prerelease Config API

Profiles built outside the YAML loader use
`BinaryDistributionProfile.validated(...)`; direct construction and `copy` are
restricted to `config` so duplicate names, version references, and sudo policy
checks are not skipped. `ChecksumSpec` now stores one `ChecksumSource` enum
(`Literal` or `Discovery`) instead of the former optional value/discovery pair.
`ChecksumSpec(algorithm, digest)` remains the literal convenience constructor,
and `ChecksumSpec.discovery(algorithm, source)` constructs discovery-backed
checksums.

See [Developer API](developer-api.md) for the prerelease typed profile input,
shared HTTP-client, and default-service entry points.
