# TUI Guide

Date: 2026-06-29

The TUI is explicit. Default commands remain script-friendly.

```bash
binstaller plan --config config.example.yaml --tui
binstaller apply --config config.example.yaml --dry-run --tui
binstaller apply --config config.example.yaml --tui --yes
```

In non-interactive shells, the TUI renders a static frame and does not enter raw
mode or the alternate screen.

## Planning View

`plan --tui` shows:

- Header: app name/version, mode, manifest name, config path, state file, host
  summary, selection, and filter.
- Plan pane: selected tools with status, kind, version, install directory,
  checksum state, and risk markers.
- Details pane: full URL, archive mappings, symlinks, and dry-run operations for
  the highlighted tool.
- Logs pane: resolver and render messages.
- Footer/keybar: aggregate risks, state, and key hints.

## Execution View

`apply --tui` uses the execution renderer instead of the full planning table.
It shows:

- Current tool and current phase.
- Spinner/progress text and elapsed time.
- Download bytes and progress bar when total bytes are known.
- Recent log lines.
- Compact completed, failed, and skipped rows.
- Final summary with installed, failed, skipped, and exit code.

`apply --dry-run --tui` exits after rendering and includes the same concrete
operation lines as non-interactive `apply --dry-run`.

## Keybindings

- `Tab`: focus next pane.
- `Shift+Tab` or `b`: focus previous pane.
- `Left` / `Right`: focus previous or next pane.
- `Up` / `Down`: move selected plan row when Plan is focused; scroll when
  Details or Logs is focused.
- `PageUp` / `PageDown`: jump selection or scroll by a pane.
- `Home` / `End`: move to first/last row or scroll edge.
- `/`: edit filter.
- `Enter`: apply filter.
- `Escape`: cancel filter editing or close help.
- `?`: toggle in-frame help.
- `q` or `Ctrl+C`: exit the planning TUI and restore terminal state.
- Mouse wheel: scrolls the focused Details or Logs pane when the terminal sends
  SGR mouse-wheel sequences.

During synchronous non-dry-run apply, the live execution view does not advertise
`q`/`Ctrl+C` cancellation. Terminal cleanup still runs on normal completion and
handled failure.

## Pane Focus And Scrolling

The focused pane title includes `[focus]`. The Plan pane changes the selected
row; Details and Logs scroll independently.

When Details or Logs overflow, the pane title includes a range like
`scroll 2-7/12` and the right edge renders scrollbar markers. Full long values
that are truncated in the table remain visible in Details.

Filtering matches tool names, descriptions, versions, install directories,
URLs, and risk markers. If the filter removes the current row, selection clamps
to the visible result set.

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
- Live resize is size-at-open today. Restart the TUI after resizing for the new
  dimensions.
- Mouse wheel behavior depends on terminal emulator and multiplexer mouse
  settings.
- If a local terminal is left in raw mode after an external kill, recover with:

```bash
stty sane
printf '\033[?25h\033[?1049l\033[?1000l\033[?1006l'
```

See `docs/tui-smoke.md` for the full manual smoke workflow.
