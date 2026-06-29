# TUI Smoke Workflow

Date: 2026-06-30

Scope: manual smoke checks for the first-class TUI command:

```bash
./mill app.run tui --config config.example.yaml
```

Run live checks from a real terminal emulator, not from an IDE task runner,
pipe, or non-interactive shell. Non-interactive shells intentionally render a
static frame and do not enter raw mode or the alternate screen.

## Temporary No-Network Profile

Create a disposable profile with pinned versions and `example.invalid` HTTPS
URLs. Browsing, plan preview, and dry-run checks must not download these URLs,
create the apps directory, or write the state file.

```bash
SMOKE_ROOT=$(mktemp -d)
SMOKE_STATE="tui-smoke-$(date +%s).state.json"
cat > "$SMOKE_ROOT/tui-smoke.yaml" <<EOF
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile
metadata:
  name: tui-smoke
spec:
  policy:
    appsDir: "$SMOKE_ROOT/apps"
    stateFile: "$SMOKE_STATE"
    requireConfirmation: true
    allowSudoSymlinks: false
    continueOnError: true
  vars: {}
  versions:
    alpha: "1.0.0"
    beta: "2.0.0"
  plan:
    - name: alpha
      kind: binary-tool
      description: Alpha TUI smoke tool with archive details.
      spec:
        versionRef: alpha
        installDir: "$SMOKE_ROOT/apps/alpha"
        download:
          url: https://example.invalid/binstaller-smoke/alpha.tar.gz
          filename: alpha.tar.gz
          checksum:
            algorithm: sha256
            value: "1111111111111111111111111111111111111111111111111111111111111111"
          archive:
            type: tar.gz
            extract:
              files:
                - from: alpha
                  to: bin/alpha
        executables:
          - path: bin/alpha
    - name: beta
      kind: binary-tool
      description: Beta TUI smoke tool with a direct binary preview.
      spec:
        versionRef: beta
        installDir: "$SMOKE_ROOT/apps/beta"
        download:
          url: https://example.invalid/binstaller-smoke/beta-linux-amd64
          filename: beta
          checksum:
            algorithm: sha256
            value: "2222222222222222222222222222222222222222222222222222222222222222"
        executables:
          - path: bin/beta
        symlinks:
          - path: bin/beta
            target: bin/beta
EOF
```

Clean up after the smoke:

```bash
rm -rf "$SMOKE_ROOT"
rm -f "$SMOKE_STATE"
```

## Static Fallback Check

This check may be run from the agent shell or CI because it does not require an
interactive TTY.

```bash
./mill app.run tui --config "$SMOKE_ROOT/tui-smoke.yaml"
test ! -e "$SMOKE_ROOT/apps"
test ! -e "$SMOKE_STATE"
```

Expected results:

- The command prints a TUI-shaped browsing frame plus
  `non-interactive terminal detected; rendered a static TUI frame`.
- The frame includes `mode browse`, `Plan`, `Details`, `Logs`, selected count,
  config path, and `state $SMOKE_STATE`.
- The output does not contain alternate-screen setup sequences such as
  `?1049h`.
- Neither `$SMOKE_ROOT/apps` nor `$SMOKE_STATE` is created.

## Startup And Browsing

Run in a real terminal at roughly 100 columns by 30 rows or larger:

```bash
./mill app.run tui --config "$SMOKE_ROOT/tui-smoke.yaml"
```

Expected results:

- The TUI enters the alternate screen, clears the display, hides the normal
  shell prompt, and shows `binstaller` with `mode browse`.
- The header shows manifest `tui-smoke`, the config path, `state $SMOKE_STATE`,
  host summary, action mode, and `selected 2 / total 2`.
- The Plan pane shows checked rows for `alpha` and `beta`.
- The Details pane shows the selected tool URL, archive mapping for `alpha`,
  symlink preview for `beta`, sudo risk, and dry-run operation preview.
- The Logs pane is visible and focusable.

## Modal Close And Navigation

Use these keys inside the same TUI session:

1. Press `?`.
   Expected: an in-frame Help modal appears and the TUI stays in the alternate
   screen.
2. Press `Escape`.
   Expected: Help closes and the same browsing frame returns.
3. Press `Tab`, then `Tab` again.
   Expected: focus cycles from Plan to Details to Logs.
4. Press `b` or `Shift+Tab`.
   Expected: focus moves backward one pane.
5. Return focus to Plan, then press `Down`.
   Expected: the selected row changes from `alpha` to `beta`, and Details
   changes to `Details: beta`.
6. Press `/`, type `alp`, then press `Enter`.
   Expected: the header filter changes to `alp`, the visible table contains
   only `alpha`, and checked selection state is preserved.
7. Press `Escape` if a modal or filter edit is open.
   Expected: the modal/filter closes without exiting the TUI.

## Selection And Actions

With the no-network profile loaded:

1. Press `Space`.
   Expected: the current row checkbox toggles and the selected count updates.
2. Press `a`, `c`, and `i`.
   Expected: visible rows are selected, cleared, or inverted without losing
   hidden selections after filtering.
3. Press `p`.
   Expected: selected-entry plan preview lines are appended to Logs; no apps
   directory or state file is created.
4. Press `d`.
   Expected: the primary view changes to execution mode, shows dry-run
   operations, recent logs, and a final summary; no apps directory or state
   file is created.
5. Restart the TUI, press `r`.
   Expected: a confirmation modal opens before any apply work starts.
6. Press `Escape` or `n`.
   Expected: the confirmation modal closes and no apps directory or state file
   is created.

Do not press `Enter` in the real-apply confirmation modal unless the profile
uses an isolated temporary `appsDir` and a disposable current-directory state
filename.

## Detail And Log Scrolling

Details can overflow with long URLs, archive mappings, and symlink previews.
Use the `alpha` row and put focus on Details:

```text
Tab
PageDown
Down
Home
End
```

Expected results:

- When Details overflows, its pane title includes a label like
  `scroll 2-7/12`.
- The scrollbar column uses `█` for the thumb and `│` for the track.
- `PageDown`, `Down`, `Home`, and `End` move the visible detail window without
  changing the selected plan row.

Logs use the same controls when Logs has enough lines to overflow:

```text
Tab
Tab
PageDown
End
Home
```

Expected results:

- If the Logs pane title shows `scroll`, the same scrollbar markers move as the
  log offset changes.
- If there is no `scroll` label, the current smoke profile has too few log
  lines to overflow; focus movement is still valid, and overflowing log
  behavior is covered by `tui.test`.
- Mouse wheel events scroll the focused Details or Logs pane in terminal
  emulators that send SGR mouse wheel sequences.

## Resize Smoke

Use a terminal emulator window near 100 columns by 30 rows, start the TUI, then
resize the window narrower and taller while it is still open. Where supported,
this escape sequence asks the emulator to resize the window before startup:

```bash
printf '\033[8;18;70t'
./mill app.run tui --config "$SMOKE_ROOT/tui-smoke.yaml"
```

Expected results:

- After a resize, the next input polling cycle or navigation key rerenders the
  frame using the new terminal dimensions.
- Text stays inside pane width. Long paths and URLs truncate in table cells
  with an ellipsis; full values remain available in Details.
- Pane titles, status lines, keybar, and modal text do not overlap.
- Repeat the resize after pressing `d`; the execution view should also rerender
  within the new width.

## Quit, Ctrl+C, And Cleanup

Check both normal quit paths from the first-class TUI command:

```bash
./mill app.run tui --config "$SMOKE_ROOT/tui-smoke.yaml"
# press q
printf 'after-q\n'

./mill app.run tui --config "$SMOKE_ROOT/tui-smoke.yaml"
# press Ctrl+C
printf 'after-ctrl-c\n'
```

Expected results:

- The alternate screen closes and the previous shell contents return.
- The cursor is visible.
- Typed characters echo normally after exit.
- `after-q` and `after-ctrl-c` print at the shell prompt, not inside the TUI.

Also verify cleanup after modal close:

```bash
./mill app.run tui --config "$SMOKE_ROOT/tui-smoke.yaml"
# press ?, Escape, q
printf 'after-modal-close\n'
```

Expected: the help modal closes in-frame, `q` exits, and
`after-modal-close` prints at the shell prompt with normal echo.

If a local terminal is left in raw mode after an external interruption, recover
with:

```bash
stty sane
printf '\033[?25h\033[?1049l\033[?1000l\033[?1006l'
```

## Known Terminal Limitations

- The TUI uses local terminal primitives, `fansi` ANSI styling, `/dev/tty`, and
  direct `stty` process arguments; Tamboui and JLine are not runtime
  dependencies in this phase.
- Interactive mode is detected through `System.console()`. IDE consoles,
  pipes, and some task runners may be treated as non-interactive and render the
  static fallback frame.
- Mouse wheel support depends on the terminal emulator sending SGR mouse
  events. Some multiplexers require mouse mode to be enabled.
- Dry-run and real apply actions currently run synchronously inside the TUI
  input handler. Terminal cleanup still runs after normal exits and handled
  failures; external process kills such as `kill -9` cannot run cleanup
  handlers.
