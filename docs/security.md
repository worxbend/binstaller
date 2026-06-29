# Security Model

Date: 2026-06-29

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
checksums or wait for a future strict policy or lock-file provenance feature.

## Sudo Policy

Sudo is available only for symlink creation. It requires all of the following:

- The manifest declares `policy.allowSudoSymlinks: true`.
- The symlink entry sets `sudo: true`.
- Non-dry-run apply is confirmed with `--yes`.
- The sudo symlink destination path is absolute.

Ordinary downloads, archive extraction, executable checks, local symlinks, and
state writes do not cross the sudo boundary.

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
errors, progress labels, state messages, and TUI cells.

Redaction is display-only: raw values are preserved for filesystem and network
behavior. This prevents corrupting legitimate paths or URLs while reducing
secret exposure in terminal output.

## Remaining Risks

- Downloads are bounded by default limits, but body deadlines are checked at
  chunk boundaries.
- Missing checksums are accepted until strict policy or lock-file work lands.
- ZIP external attributes and native `tar.xz` pre-inspection remain deferred.
- Long-running live `apply --tui` does not yet have nonblocking cancellation.
