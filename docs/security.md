# Security Model

Date: 2026-07-18

`binstaller` installs binary tool distributions from a user-provided manifest.
The manifest is trusted configuration, but downloads, archive metadata, terminal
text, and process output are treated as untrusted input at display and install
boundaries.

## Trust Boundaries

- YAML parsing and validation are the first trust boundary. Unsupported fields
  that would execute scripts are rejected before planning.
- Version resolver and download URLs must be HTTPS and have a host. Hosts that
  name or resolve to loopback, link-local, private, site-local, multicast, or
  known cloud-metadata endpoints are rejected, and each redirect hop is
  re-validated against the same guard before the request is followed.
- Only a fixed allowlist of non-secret environment variables (`HOME`, `USER`,
  `LOGNAME`, `SHELL`, `XDG_*`) is exposed to manifest interpolation, so a
  manifest cannot interpolate an arbitrary process secret into an outbound URL.
- Downloaded bytes are untrusted until bounded, checksum-verified when a
  checksum is configured, extracted or staged, and executable paths are verified.
- Archive member names and mapped targets are untrusted and must remain inside
  staging or install roots.
- CLI rendering scrubs terminal control characters and redacts sensitive
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

- `sudo -n true`, `sudo -n ln -sfn <target> <path>`, and when an injected
  credential provider supplies a password,
  `sudo -S -p "" ln -sfn <target> <path>` for privileged symlinks.

Archive extraction no longer shells out to the system `tar`; all archive types
are decoded in-process. Core command execution uses argv, not manifest-provided
shell strings. Process
execution has a default 15 minute timeout. Failure messages quote arguments so
diagnostics preserve argument boundaries.

Password-backed sudo uses modeled secret stdin. The password is not included in
argv, environment variables, command previews, command diagnostics, installer
events, apply state, or logs. Command failures redact the secret
from command messages and captured stdout/stderr before they reach renderers or
state.

## Archive Safety

Native `zip`, `tar.gz`, and `tar.xz` extraction rejects unsafe paths before
writing mapped files. `tar.xz` is decoded with an in-process XZ reader and flows
through the same tar member handling as `tar.gz`, so it no longer depends on the
system `tar`. The native tar path rejects symlink, hardlink, and unsupported tar
entry types. Duplicate archive member sources and duplicate output targets are
rejected. All archive types are equivalent with respect to path confinement.

Extraction enforces an aggregate expanded-byte budget across all copied members,
independent of the compressed download cap, to bound decompression bombs that
would otherwise exhaust disk.

Known deferred archive gaps are documented in [Hardening Review](hardening-review.md).

## Checksum Policy

Configured checksums use SHA-256 and must be 64 hex characters. Apply verifies
the checksum before staging replacement.

Missing checksums are currently allowed to support dynamic upstream assets and
developer convenience. They are surfaced as `not configured`, `missing`, or
`no-checksum` risk markers in CLI plan views. Production profiles should add
checksums and use `policy.mode: strict`. Strict mode rejects missing checksums
unless `policy.allowMissingChecksums: true` is explicitly set.

## Strict Policy Mode

`policy.mode` defaults to `developer` for compatibility with local tool
profiles. Developer mode allows dynamic latest URLs and missing checksums by
default. Sudo symlinks still require `policy.allowSudoSymlinks: true`.

`policy.mode: strict` rejects production-sensitive risks unless the manifest
explicitly opts in:

- Dynamic latest sources: `dynamic.latest-url` versions and download URLs that
  contain `/latest`.
- Missing SHA-256 checksums.
- Sudo symlinks, unless `policy.allowSudoSymlinks: true`.
- Archive candidate fallback, if candidate discovery is added later, unless
  `policy.allowArchiveCandidateFallback: true`.

Strict-policy failures render as validation-style messages with stable
`strict-policy[...]` codes and matching `suggestion[...]` hints.

## Sudo Policy

Sudo is available only for symlink creation. It requires all of the following:

- The manifest declares `policy.allowSudoSymlinks: true`.
- The symlink entry sets `sudo: true`.
- The sudo symlink destination path is absolute.

Ordinary downloads, archive extraction, executable checks, local symlinks, and
state writes do not cross the sudo boundary.

The privileged-command flow is:

1. Core probes cached credentials with fixed `sudo -n true` argv.
2. If cached credentials are valid, core creates the symlink with fixed
   `sudo -n ln -sfn <target> <path>` argv and does not request a password.
3. If the cache probe fails, core asks an injected `SudoCredentialProvider` for
   one password for that privileged operation.
4. CLI production runs request a password from the interactive terminal when a
   cached sudo credential is unavailable. Non-interactive runs fail closed with
   an unavailable-credentials diagnostic.
5. The password-backed command uses fixed `sudo -S -p "" ln -sfn <target>
   <path>` argv plus secret stdin.

Credential cancellation is typed as `SudoCredentialError.Canceled` and becomes a
tool failure for the current privileged symlink. It does not cancel the whole
process by itself; existing `continueOnError` behavior determines whether later
tools continue.

## Selection And Apply

CLI selection uses `--only` and `--skip`. The selected names are converted to
core `ToolSelection` before plan rendering, lock generation, or apply. Plan
must not download, replace installs, create symlinks, or write state.

CLI apply is confirmed by default. Running `apply` performs downloads, install
replacement, symlinks, and state writes subject to manifest policy and selected
tools.

## State-File Policy

Apply state records schema version, profile name, manifest fingerprint, and
per-tool status. It is used for resume only.

Current rules:

- The state path comes from `--state` or `spec.policy.stateFile`.
- Apply accepts only a current-directory filename.
- Absolute paths, nested relative paths, and empty names are rejected.
- Plan does not validate or touch the state file.
- State is written after terminal tool results through a same-directory temp
  file and atomic move.
- Incompatible profile names or manifest fingerprints fail unless
  `--reset-state` is used.

## Redaction And Display Safety

Resolution derives redactions from sensitive runtime variable names. Renderers
scrub display lines, command-output tails, plan output, versions output, apply
errors, progress labels, and state messages.

Redaction is display-only: raw values are preserved for filesystem and network
behavior. This prevents corrupting legitimate paths or URLs while reducing
secret exposure in terminal output.

Passwords have a stronger guarantee than ordinary display redactions: password
values are wrapped as secret command input and are never part of the stable
renderable data model. Tests cover no leakage into command argv/spec strings,
command diagnostics, installer events, or apply state.

Terminal-control scrubbing is also display-only. Untrusted text is collapsed
into safe terminal lines before rendering so config values, resolver output,
download diagnostics, and command stdout/stderr snippets cannot inject cursor
movement, alternate-screen toggles, color resets, or mouse-mode escape sequences
into CLI output.

## Remaining Risks

- Downloads are bounded by default limits, but body deadlines are checked at
  chunk boundaries.
- Missing checksums are still accepted by developer-mode profiles.
- End-to-end release integrity is anchored by keyless Sigstore signatures over
  each release artifact, verified by `scripts/install.sh` when `cosign` is
  present. The per-release SHA-256 files defend against corruption but share the
  release origin, so they are not an independent trust anchor on their own.
- ZIP external attributes remain deferred.
- Credential-provider cancellation is scoped to the credential request/current
  privileged operation. It is not a general cancellation mechanism for an
  already-running download, extraction, or command.
- Native image validation is environment-bound when `native-image` is not
  installed locally; the release workflow remains the native build boundary.
