# Architecture

Date: 2026-06-30

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
  output, colored apply progress, global flags, and routing for the first-class
  `tui` subcommand. `plan`, `apply`, `versions`, and `lock` remain
  non-interactive command paths.
- `tui`: owns the interactive installer workspace for `binstaller tui`,
  including TUI-local app state, checkbox selection, filtering, pane focus,
  selected-entry plan/dry-run/apply actions, confirmation and error modals,
  deterministic ANSI rendering, terminal input parsing, interactive terminal
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
6. `apply --dry-run` uses the same resolution and selection path, then renders
   concrete operations without downloads, install writes, symlink writes, or
   state writes.
7. Non-dry-run `apply` checks confirmation and state compatibility, executes
   each selected tool, writes apply state after terminal tool results, and emits
   `InstallerEvent` values.
8. `tui` resolves one `ResolvedPlanSnapshot` up front, builds a `TuiAppState`
   with TUI-local selected tool names, and renders the browsing workspace.
9. Inside `tui`, `Space`, `a`, `c`, and `i` update checkbox selection without
   mutating CLI `ToolSelection`. `p`, `d`, and confirmed `r` convert selected
   TUI entries to core `ToolSelection` only at the service boundary.
10. CLI apply progress and TUI execution rendering consume the same event
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
- The TUI entrypoint is explicit and optional: `binstaller tui --config FILE`.
- Dry-run paths do not touch install directories or state files.
- TUI plan preview and dry-run operate only on checked entries.
- TUI real apply requires the in-frame confirmation modal before core apply is
  called with non-dry-run options.
- Apply state is filename-only in the current working directory.
- Manifest installer scripts are unsupported and rejected during config loading.
- Display surfaces use render safety and redaction at renderer boundaries while
  preserving raw values for filesystem and network operations.
