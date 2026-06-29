# TUI Smoke Workflow

Date: 2026-06-29

Scope: manual smoke checks for the explicit TUI entrypoints:

- `plan --tui`
- `apply --tui`
- `apply --dry-run --tui`

Run these checks from a real terminal emulator, not from an IDE task runner,
pipe, or non-interactive shell. Non-interactive shells intentionally render a
static frame and do not enter raw mode.

## Temporary No-Network Profile

Create a disposable profile with pinned versions and `example.invalid` HTTPS
URLs. The planning TUI and dry-run execution TUI must not download these URLs,
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
./mill app.run plan --config "$SMOKE_ROOT/tui-smoke.yaml" --tui
./mill app.run apply --config "$SMOKE_ROOT/tui-smoke.yaml" --dry-run --tui
test ! -e "$SMOKE_ROOT/apps"
test ! -e "$SMOKE_STATE"
```

Expected results:

- The plan command prints a TUI-shaped static frame plus
  `non-interactive terminal detected; rendered a static TUI frame`.
- The apply dry-run command prints `mode apply execution`,
  `Dry-run operations`, and the exact dry-run operation lines.
- The execution frame does not include the planning table header.
- The displayed state line is `state $SMOKE_STATE`.
- Neither `$SMOKE_ROOT/apps` nor `$SMOKE_STATE` is created.

## Normal Terminal Planning Smoke

Run in a real terminal at roughly 100 columns by 30 rows or larger:

```bash
./mill app.run plan --config "$SMOKE_ROOT/tui-smoke.yaml" --tui
```

Expected results:

- The TUI enters the alternate screen, clears the display, and hides the normal
  shell prompt while it is open.
- The header shows `binstaller`, `mode plan`, manifest `tui-smoke`, the config
  path, `state $SMOKE_STATE`, host summary, selection, and filter.
- The Plan pane shows `alpha` and `beta`. The selected row begins with `>` and
  the Plan title shows `[focus]`.
- The Details pane shows the selected tool's full download URL, archive mapping
  for `alpha`, symlink preview for `beta`, and dry-run operation preview.
- The Logs pane is visible. For this no-network profile it normally contains
  the default resolution lines and may not overflow.
- `q` exits cleanly and returns to the shell prompt.

## Focus, Details, Filter, And Help

Start the planning smoke again and use these keys:

1. Press `Tab`.
   Expected: focus moves from `Plan [focus]` to `Details [focus]`.
2. Press `Tab` again.
   Expected: focus moves to `Logs [focus]`.
3. Press `b` or `Shift+Tab`.
   Expected: focus moves backward one pane.
4. Return focus to Plan, then press `Down`.
   Expected: selection changes from `1/2 alpha` to `2/2 beta`, and Details
   changes to `Details: beta`.
5. Press `/`, type `alp`, then press `Enter`.
   Expected: the header filter changes to `alp`, the visible table contains
   only `alpha`, and selection changes to `1/1 alpha`.
6. Press `?`.
   Expected: an in-frame Help section appears. It must not open a pager or
   leave the alternate screen.
7. Press `Escape`.
   Expected: Help closes.
8. Press `q`.
   Expected: the terminal returns to normal shell mode.

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

## Dry-Run Execution View

Run the apply TUI in dry-run mode:

```bash
./mill app.run apply --config "$SMOKE_ROOT/tui-smoke.yaml" --dry-run --tui
test ! -e "$SMOKE_ROOT/apps"
test ! -e "$SMOKE_STATE"
```

Expected results:

- The header says `mode apply execution`.
- The main section is `Execution`; the full Plan table is not the main view.
- The frame includes current tool or `no active tool`, recent logs, final
  summary, and `Dry-run operations`.
- The dry-run operations include the `https://example.invalid/...` URLs, but no
  network request is made and no apps or state paths are created.
- The command exits on its own after rendering the final dry-run frame.

Do not run `apply --tui --yes` against a real profile for this smoke unless the
profile uses an isolated temporary `appsDir` and a disposable current-directory
state filename.

## Narrow Terminal Smoke

Use a terminal emulator window near 70 columns by 18 rows. Where supported, this
escape sequence asks the emulator to resize the window:

```bash
printf '\033[8;18;70t'
./mill app.run plan --config "$SMOKE_ROOT/tui-smoke.yaml" --tui
```

Expected results:

- Text stays inside pane width. Long paths and URLs truncate in table cells
  with an ellipsis; full values remain available in Details.
- The keybar remains visible if there is enough height.
- Pane titles and status lines do not overlap.
- The renderer enforces a minimum width internally, so extremely narrow
  terminals may clip at the terminal edge even though the frame remains stable.

## Resize Smoke

Current behavior is size-at-open for the system terminal backend.

1. Start `plan --tui` in a normal terminal.
2. Resize the terminal narrower or taller while the TUI is open.
3. Press a navigation key such as `Tab` or `Down`.
4. Quit with `q`.
5. Restart the command after resizing.

Expected results:

- During the already-open session, the frame may continue using the viewport
  measured at startup.
- After restart, the frame uses the new terminal size.
- The pure resize model is covered by automated tests, but the current
  `SystemTuiTerminal` does not yet convert live `SIGWINCH` notifications into
  resize events.

## Terminal Cleanup

Check both normal quit paths from the planning TUI:

```bash
./mill app.run plan --config "$SMOKE_ROOT/tui-smoke.yaml" --tui
# press q
printf 'after-q\n'

./mill app.run plan --config "$SMOKE_ROOT/tui-smoke.yaml" --tui
# press Ctrl+C
printf 'after-ctrl-c\n'
```

Expected results:

- The alternate screen closes and the previous shell contents return.
- The cursor is visible.
- Typed characters echo normally after exit.
- `after-q` and `after-ctrl-c` print at the shell prompt, not inside the TUI.

If a local terminal is left in raw mode after an interrupted process, recover
with:

```bash
stty sane
printf '\033[?25h\033[?1049l\033[?1000l\033[?1006l'
```

## Known Terminal Limitations

- The TUI uses local terminal primitives, `fansi` ANSI styling, `/dev/tty`, and
  `stty`; Tamboui and JLine are not runtime dependencies in this phase.
- Interactive mode is detected through `System.console()`. IDE consoles,
  pipes, and some task runners may be treated as non-interactive and render the
  static fallback frame.
- Live resize is not fully wired in the system terminal backend. Restart the
  TUI after resizing to verify the new layout.
- Mouse wheel support depends on the terminal emulator sending SGR mouse
  events. Some multiplexers require mouse mode to be enabled.
- Planning TUI reads `q` and Ctrl+C as raw key inputs and restores terminal
  state in `finally`. The apply execution TUI currently runs the apply loop
  synchronously; dry-run exits quickly, but long non-dry-run TUI cancellation
  needs a future nonblocking input boundary.
- Raw-mode cleanup is best-effort around normal exits and handled failures.
  Process kills such as `kill -9` cannot run cleanup handlers.
