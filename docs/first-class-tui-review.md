# First-Class TUI Review

Date: 2026-06-30

Scope: final review of the `binstaller tui` refactor after the command
migration, TUI state/action work, modal handling, terminal hardening, and
implementation docs were completed.

## Command Boundaries

- `binstaller tui --config FILE` is the only interactive entrypoint. It accepts
  the inherited `--config`, `--state`, `--reset-state`, and `--verbose` options.
- `plan`, `apply`, `versions`, and `lock` remain non-interactive command paths.
  `plan` and `apply` no longer advertise or own `--tui`.
- TUI checkbox state is UI-local. It converts to core `ToolSelection` only when
  the user runs plan preview, dry-run, or confirmed apply inside the TUI.
- Core remains renderer-agnostic. CLI and TUI consume resolved plans and
  installer events without importing UI types into core.
- Release and local smoke commands should use `binstaller tui --config
  config.example.yaml` or `./mill app.run tui --config config.example.yaml` for
  the static TUI fallback check.

## TUI Control Flow

Startup loads the configured profile, resolves one plan snapshot, builds
`TuiAppState`, and starts in browsing mode. The state owns header metadata,
visible entries, selected tool names, filter text, focus, scroll offsets,
modal state, logs, and optional execution state.

Browsing control flow is deterministic:

- `Space`, `a`, `c`, and `i` update checkbox selection for the highlighted or
  visible entries.
- `/`, `Enter`, and `Escape` manage filter editing without losing hidden
  selections.
- `Tab`, `Shift+Tab`, arrows, paging keys, `Home`, `End`, and `l` move focus,
  highlighted rows, or scroll offsets according to the focused pane.
- `p` appends a selected-entry plan preview to logs.
- `d` runs selected-entry dry-run apply and switches to execution view.
- `r` opens the real-apply confirmation modal; only `Enter` in that modal starts
  non-dry-run apply.
- `q` and `Ctrl+C` exit through the terminal cleanup path when input is being
  processed.

Execution rendering consumes core installer events and shows active phase,
progress, bytes, elapsed time, recent logs, completed/failed/skipped rows, and
the final summary.

## Modal Rendering

The TUI renders modals in-frame for help, no-selection messages, real-apply
confirmation, startup failures, action failures, and root-cause details for
failed execution rows. Informational and error modals close with `Enter` or
`Escape`; real-apply confirmation starts only with `Enter` and cancels with
`Escape` or `n`.

Modal bodies and log lines use the same render-safety path as other terminal
output. Sensitive environment-derived values are replaced with `<redacted>`,
and terminal control characters are scrubbed before display. Failure modals use
bounded stdout/stderr snippets so process output cannot dominate the frame.

## Selection Guarantees

- The selected count in the header is derived from TUI-local checkbox state.
- Filtering never clears hidden selected entries.
- Bulk selection shortcuts operate on the current visible result set only.
- Plan preview and dry-run apply operate only on checked entries.
- Empty selection opens a no-selection modal and does not call the plan or apply
  service.
- Real apply cannot call core with non-dry-run options until the confirmation
  modal is accepted.
- CLI `--only` and `--skip` remain the selection mechanism for non-interactive
  `plan`, `apply`, and `lock`.

## Security Risks

The refactor preserves the existing trust boundaries: manifest installer scripts
are rejected, runtime URLs must be HTTPS, archive writes are staged and path
checked, sudo is limited to symlink creation, and display surfaces scrub
untrusted text.

TUI-specific risk controls are now documented and tested:

- Error modals, root-cause details, and logs apply redaction and terminal
  control scrubbing.
- Dry-run actions use the core dry-run path and do not download, install, create
  symlinks, or write state.
- Real apply requires the TUI confirmation modal before non-dry-run core apply.
- Non-interactive shells render a static fallback frame instead of entering raw
  mode or the alternate screen.
- Terminal setup and restore use direct `stty` argv calls through the terminal
  backend.

## Test Evidence

Current automated coverage includes:

- `cli/test/src/binstaller/cli/CliModuleTest.scala`: root help lists `tui`,
  `tui --help` advertises inherited shared options, `plan --help` and
  `apply --help` omit `--tui`, `tui` requires `--config`, and `tui --config`
  routes to the TUI without calling the injected plan/apply service.
- `tui/test/src/binstaller/tui/TuiModuleTest.scala`: TUI state ownership,
  selection/filter guarantees, selected-entry plan/dry-run/apply actions,
  no-selection modals, confirmation modal behavior, root-cause modals,
  terminal cleanup, non-interactive fallback, resize bounds, and direct `stty`
  process boundaries.
- `tui/test/src/binstaller/tui/TuiModuleTest.scala`: failed dry-run opens an
  error modal with redacted bounded snippets and scrubbed terminal controls.
- `core/test/src/binstaller/core/CoreModuleTest.scala`: apply errors redact
  sensitive runtime values and scrub terminal controls before display.

## Documented Deferrals

- Live raw-terminal startup, resize, modal close, `q`, Ctrl+C, and cleanup
  remain manual smoke checks in `docs/tui-smoke.md` when the agent or CI shell is
  not attached to a real TTY.
- Native-image validation remains environment-dependent locally; the release
  workflow is the required native build and smoke boundary when `native-image`
  is unavailable on PATH.
- `tar.xz` still uses the structured system-`tar` fallback without native
  pre-extraction metadata inspection.
- ZIP external-attribute inspection remains deferred.
- Missing checksums remain accepted in developer mode and should be rejected by
  strict profiles or locked workflows for production use.
- TUI dry-run and apply execution are synchronous and do not yet provide
  preemptive cancellation for long-running work.
