# TUI Guide

Date: 2026-06-30

The TUI is explicit. Default commands remain script-friendly.

```bash
binstaller tui --config config.example.yaml
binstaller tui --config config.example.yaml --state binstaller-state.json
binstaller tui --config config.example.yaml --reset-state --verbose
```

`plan`, `apply`, `versions`, and `lock` stay non-interactive. The TUI command
loads one manifest, owns checkbox selection internally, and lets the user run
plan preview, dry-run, and confirmed apply without leaving the workspace.

In non-interactive shells, the TUI renders a static frame and does not enter raw
mode or the alternate screen.

## Browsing Workspace

`binstaller tui` starts in browsing mode and shows:

- Header: app name/version, mode, manifest name, config path, state file, host
  summary, selection, and filter.
- Plan pane: every resolved tool with a checkbox, status, kind, version, install
  directory, checksum state, and risk markers.
- Details pane: the highlighted tool's version, URL, final-URL/provenance note,
  checksum status, archive mappings, symlinks, sudo risk, and dry-run operation
  preview.
- Logs pane: resolver messages plus plan, dry-run, apply, and error details.
- Footer/keybar: aggregate risks, state, and key hints.

The TUI selection is independent of CLI `--only`/`--skip`. It is converted to
core selection only when `p`, `d`, or confirmed `r` calls the underlying service.
Hidden filtered entries keep their checkbox state.

## Execution View

Pressing `d` or confirmed `r` switches the primary view to execution mode. It
shows:

- Current tool and current phase.
- Spinner/progress text and elapsed time.
- Download bytes and progress bar when total bytes are known.
- Recent log lines.
- Compact completed, failed, and skipped rows.
- Final summary with installed, failed, skipped, and exit code.

Dry-run (`d`) renders concrete operations without downloads, install writes,
symlink writes, or state writes. Real apply (`r`, then `Enter` in the
confirmation modal) runs the same core apply path as CLI apply with confirmation
enabled.

## Actions

- `p`: append a selected-entry plan preview to Logs. It does not install or
  write state.
- `d`: run dry-run apply for selected entries and show the execution view.
- `r`: open the real-apply confirmation modal for selected entries.
- `Enter` in the confirmation modal: run real apply for those selected entries.
- `Escape` or `n` in the confirmation modal: close it without writes.

If no entries are checked, `p`, `d`, and `r` open a message modal and do not call
the plan or apply service.

## Keybindings

- `Tab`: focus next pane.
- `Shift+Tab` or `b`: focus previous pane.
- `Left` / `Right`: focus previous or next pane.
- `Up` / `Down`: move selected plan row when Plan is focused; scroll when
  Details or Logs is focused.
- `PageUp` / `PageDown`: jump selection or scroll by a pane.
- `Home` / `End`: move to first/last row or scroll edge.
- `Space`: toggle the current visible row checkbox.
- `a`: select all visible rows.
- `c`: clear all visible rows.
- `i`: invert visible row selection.
- `/`: edit the filter.
- `Enter`: apply filter while editing, focus Details from browsing mode, or
  confirm a modal action.
- `l`: focus Logs.
- `Escape`: cancel filter editing or close a modal.
- `?`: toggle in-frame help.
- `q` or `Ctrl+C`: exit the TUI and restore terminal state.
- Mouse wheel: scrolls the focused Details or Logs pane when the terminal sends
  SGR mouse-wheel sequences.

During synchronous apply work, input is processed between rendered frames rather
than as a preemptive cancellation mechanism. Terminal cleanup still runs on
normal completion and handled failure.

## Selection And Filtering

The Plan pane has both a highlighted row and checkbox state. `Up`/`Down` moves
the highlighted row. `Space`, `a`, `c`, and `i` change checkbox state. The
header shows `selected N / total M` after each change.

Filtering matches visible entry text such as tool names and descriptions. If
the filter hides the highlighted row, the row index clamps to the visible result
set. Hidden selections are preserved, so filtering does not accidentally clear
or add entries.

## Pane Focus And Scrolling

The focused pane title includes `[focus]`. The Plan pane changes the highlighted
row; Details and Logs scroll independently.

When Details or Logs overflow, the pane title includes a range like
`scroll 2-7/12` and the right edge renders scrollbar markers. Full long values
that are truncated in the table remain visible in Details.

Filtering currently matches tool names and descriptions. Full URL, archive,
symlink, and operation details remain visible in the Details pane for the
highlighted row.

## Modals

The TUI uses in-frame modals for help, no-selection messages, real-apply
confirmation, startup failures, plan/dry-run/apply failures, and root-cause
details for failed execution rows. Error modals include category, action, root
cause, suggestion, bounded stdout/stderr snippets when available, and compact
details. Modal and log text uses the same display safety path as other TUI
rendering: terminal control characters are scrubbed and sensitive
environment-derived values are redacted before display.

`Enter` or `Escape` closes informational/error modals. In execution mode,
`Enter` opens the first failed row's root-cause modal when a failed row exists.

## Progress States

Planning rows can show active, warning, inactive, completed, failed, and skipped
styles. Current planning risk markers include missing checksums, dynamic
versions, and sudo symlinks.

Execution consumes core events:

- resolving and plan ready
- tool started
- phase changed
- download started, advanced, and finished
- log line
- tool completed or failed
- tool skipped from state
- final summary

## Terminal Troubleshooting

- Run interactive TUI checks from a real terminal emulator, not a pipe, IDE task
  runner, or CI shell.
- If you see `non-interactive terminal detected`, the process used a static
  fallback frame.
- The system backend uses `/dev/tty` and `stty` to enter raw mode.
- Live resize is polled through the terminal boundary and rerenders on the next
  input/read cycle when the terminal reports a changed size.
- Mouse wheel behavior depends on terminal emulator and multiplexer mouse
  settings.
- If a local terminal is left in raw mode after an external kill, recover with:

```bash
stty sane
printf '\033[?25h\033[?1049l\033[?1000l\033[?1006l'
```

See `docs/tui-smoke.md` for the full manual smoke workflow.
