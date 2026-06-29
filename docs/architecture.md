# Architecture

Date: 2026-06-29

`binstaller` is a Scala 3/Mill application for one manifest shape:
`binstaller.io/v1alpha1` / `BinaryDistributionProfile`. The runtime graph is
acyclic:

```text
app -> cli -> {core, tui}
tui -> core
core -> config
```

`core` does not import CLI or TUI code. Business rules live below the rendering
layers, and both renderers consume resolved plans or renderer-agnostic events.

## Module Responsibilities

- `config`: reads YAML with SnakeYAML Engine, decodes typed manifest models,
  validates supported enum values, rejects unsupported installer scripts, checks
  duplicate tool names, checks unknown `versionRef` values, validates SHA-256
  value shape, and gates sudo symlink declarations through
  `policy.allowSudoSymlinks`.
- `core`: resolves variables and versions, validates HTTPS URLs, applies
  `--only`/`--skip` selection, creates resolved plans, downloads bounded binary
  bodies, verifies checksums, extracts archives, stages and replaces installs,
  creates symlinks, persists apply state, and emits typed installer events.
- `cli`: owns Picocli command parsing, exit codes, script-friendly default
  output, colored apply progress, global flags, and explicit routing to the TUI
  for `plan --tui` and `apply --tui`.
- `tui`: owns deterministic planning/execution models, ANSI rendering, pane
  focus, scrolling/filtering state, terminal input parsing, interactive terminal
  lifecycle, and static non-interactive fallback frames.
- `app`: owns process entry and exit-code propagation only.
- `build/release`: `build.mill` defines modules and native-image settings;
  `.github/workflows/native-release.yml` builds, smokes, packages, checksums,
  and publishes Linux amd64 artifacts.

## Data Flow

1. CLI parses command flags into `InstallerOptions`.
2. `ConfigModule.load` reads YAML into `BinaryDistributionProfile`.
3. `PlanResolver` resolves runtime variables, manifest vars, policy paths,
   version sources, download URLs, archive mappings, executable paths, and
   symlinks into `ResolvedPlan`.
4. `ToolSelection` applies `--only` first and `--skip` second while preserving
   manifest order.
5. `plan` renders the selected `ResolvedPlan` directly as script-friendly text.
   `plan --tui` resolves a `ResolvedPlanSnapshot` and renders a planning frame.
6. `apply --dry-run` uses the same resolution and selection path, then renders
   concrete operations without downloads, install writes, symlink writes, or
   state writes. `apply --dry-run --tui` renders those same operation lines in
   the execution TUI.
7. Non-dry-run `apply` checks confirmation and state compatibility, executes
   each selected tool, writes apply state after terminal tool results, and emits
   `InstallerEvent` values.
8. CLI apply progress and TUI execution rendering consume the same event
   contract differently. CLI keeps a compact progress line and summary; TUI
   shows active tool, phase, progress, recent logs, completed/failed/skipped
   rows, and final summary.

## Event Contract

Core emits the following renderer-agnostic events:

- `ResolvingStarted(configPath, elapsedTime)`
- `PlanReady(toolCount, stateFilePath, elapsedTime)`
- `ToolStarted(toolName, phase, elapsedTime)`
- `ToolPhaseChanged(toolName, phase, elapsedTime)`
- `DownloadProgress(toolName, url, downloadedBytes, totalBytes, status, elapsedTime)`
- `LogLine(toolName, line, elapsedTime)`
- `ToolResult(toolName, status, installDir, failureSummary, elapsedTime)`
- `ToolSkipped(toolName, reason, stateFilePath, elapsedTime)`
- `Summary(status, installed, failed, skipped, exitCode, stateFilePath, elapsedTime)`

Current phases are `Resolving`, `Planning`, `LoadingState`, `Downloading`,
`VerifyingChecksum`, `Staging`, `ApplyingModes`, `ReplacingInstall`,
`VerifyingExecutables`, `CreatingSymlinks`, and `SavingState`.

## Invariants

- Default `plan`, `apply`, `apply --dry-run`, and `versions` output remains
  non-interactive and script-friendly.
- TUI entrypoints are explicit and optional.
- Dry-run paths do not touch install directories or state files.
- Apply state is filename-only in the current working directory.
- Manifest installer scripts are unsupported and rejected during config loading.
- Display surfaces use render safety and redaction at renderer boundaries while
  preserving raw values for filesystem and network operations.
