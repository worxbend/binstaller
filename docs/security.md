# Security Model

Date: 2026-06-30

`binstaller` installs binary tool distributions from a user-provided manifest.
The manifest is trusted configuration, but downloads, archive metadata, terminal
text, and process output are treated as untrusted input at display and install
boundaries.

## Trust Boundaries

- YAML parsing and validation are the first trust boundary. Unsupported fields
  that would execute scripts are rejected before planning.
- Version resolver and download URLs must be HTTPS and have a host.
- Downloaded bytes are untrusted until bounded, checksum-verified when a
  checksum is configured, extracted or staged, and executable paths are verified.
- Archive member names and mapped targets are untrusted and must remain inside
  staging or install roots.
- CLI and TUI rendering scrub terminal control characters and redact sensitive
  environment-derived values at display boundaries.
- TUI modal bodies and log lines are display surfaces. They are sanitized before
  rendering even when the underlying failure came from process output, config
  diagnostics, or selected-entry action results.
- State files are local resume metadata, not a security authority.

## Unsupported Installer Scripts

Manifest installer scripts are deliberately unsupported. A profile containing an
`installer` block fails during config loading with:

```text
installer scripts are not supported; use direct binary or archive download
```

This keeps shell syntax, inherited environment, cleanup, timeout, and sudo
behavior out of the manifest contract.

## Command Boundaries

The remaining external process boundaries are intentionally narrow:

- `sudo ln -sfn <target> <path>` for privileged symlinks.
- `tar -xJf <archive> -C <extractDir>` for the `tar.xz` fallback.
- `stty` calls in the TUI terminal backend for raw-mode setup and restore.

Core command execution uses argv, not manifest-provided shell strings. Process
execution has a default 15 minute timeout. Failure messages quote arguments so
diagnostics preserve argument boundaries.

## Archive Safety

Native `zip` and `tar.gz` extraction rejects unsafe paths before writing mapped
files. The native `tar.gz` path rejects symlink, hardlink, and unsupported tar
entry types. Duplicate archive member sources and duplicate output targets are
rejected.

`tar.xz` remains a structured system-`tar` fallback. It extracts into private
staging and then copies only declared mapped members after path validation, but
it does not yet provide native pre-extraction metadata inspection. Do not treat
untrusted `tar.xz` archives as equivalent to native `zip` and `tar.gz` inputs.

Known deferred archive gaps are documented in
`docs/post-tui-readiness-review.md`.

## Checksum Policy

Configured checksums use SHA-256 and must be 64 hex characters. Apply verifies
the checksum before staging replacement.

Missing checksums are currently allowed to support dynamic upstream assets and
developer convenience. They are surfaced as `not configured`, `missing`, or
`no-checksum` risk markers in CLI/TUI plan views. Production profiles should add
checksums and use `policy.mode: strict`. Strict mode rejects missing checksums
unless `policy.allowMissingChecksums: true` is explicitly set.

## Strict Policy Mode

`policy.mode` defaults to `developer` for compatibility with local tool
profiles. Developer mode allows dynamic latest URLs, missing checksums, and the
system `tar.xz` fallback by default. Sudo symlinks still require
`policy.allowSudoSymlinks: true`.

`policy.mode: strict` rejects production-sensitive risks unless the manifest
explicitly opts in:

- Dynamic latest sources: `dynamic.latest-url` versions and download URLs that
  contain `/latest`.
- Missing SHA-256 checksums.
- Sudo symlinks, unless `policy.allowSudoSymlinks: true`.
- `tar.xz` archives, unless `policy.allowTarXzFallback: true`.
- Archive candidate fallback, if candidate discovery is added later, unless
  `policy.allowArchiveCandidateFallback: true`.

Strict-policy failures render as validation-style messages with stable
`strict-policy[...]` codes and matching `suggestion[...]` hints.

## Sudo Policy

Sudo is available only for symlink creation. It requires all of the following:

- The manifest declares `policy.allowSudoSymlinks: true`.
- The symlink entry sets `sudo: true`.
- Non-dry-run apply is confirmed, either with CLI `--yes` or the TUI
  confirmation modal.
- The sudo symlink destination path is absolute.

Ordinary downloads, archive extraction, executable checks, local symlinks, and
state writes do not cross the sudo boundary.

## TUI Selection And Confirmation

`binstaller tui` owns checkbox selection inside TUI-local state. The selected
tool names are converted to core `ToolSelection` only at the action boundary for
plan preview, dry-run apply, or confirmed real apply. Hidden entries preserved by
filtering are not executed unless their checkbox remains selected, and visible
bulk operations affect only the currently visible set.

Plan preview (`p`) and dry-run (`d`) operate only on selected entries. Dry-run
uses the core dry-run path, so it must not download, replace installs, create
symlinks, or write state. If no entries are selected, the TUI opens a
no-selection modal and does not call the plan/apply service.

Real apply (`r`) first opens an in-frame confirmation modal. Core apply is not
called with non-dry-run options until the user presses `Enter` in that modal.
`Escape`, `n`, `q`, or `Ctrl+C` leave the confirmation path without starting
real apply.

## State-File Policy

Apply state records schema version, profile name, manifest fingerprint, and
terminal per-tool status. It is used for resume only.

Current rules:

- The state path comes from `--state` or `spec.policy.stateFile`.
- Non-dry-run apply accepts only a current-directory filename.
- Absolute paths, nested relative paths, and empty names are rejected.
- Plan and dry-run apply do not validate or touch the state file.
- State is written after terminal tool results through a same-directory temp
  file and atomic move.
- Incompatible profile names or manifest fingerprints fail unless
  `--reset-state` is used.

## Redaction And Display Safety

Resolution derives redactions from sensitive runtime variable names. Renderers
scrub display lines, command-output tails, plan output, versions output, apply
errors, progress labels, state messages, TUI cells, modal bodies, root-cause
details, and TUI log lines.

Redaction is display-only: raw values are preserved for filesystem and network
behavior. This prevents corrupting legitimate paths or URLs while reducing
secret exposure in terminal output.

Terminal-control scrubbing is also display-only. Untrusted text is collapsed
into safe terminal lines before rendering so config values, resolver output,
download diagnostics, command stdout/stderr snippets, and modal details cannot
inject cursor movement, alternate-screen toggles, color resets, or mouse-mode
escape sequences into CLI/TUI output.

## Remaining Risks

- Downloads are bounded by default limits, but body deadlines are checked at
  chunk boundaries.
- Missing checksums are still accepted by developer-mode profiles.
- ZIP external attributes and native `tar.xz` pre-inspection remain deferred.
- Dry-run and real apply actions inside `binstaller tui` currently run
  synchronously while rendering execution updates; they do not provide
  preemptive cancellation for long-running work.
