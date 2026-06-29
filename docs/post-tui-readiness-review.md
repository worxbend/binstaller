# Post-TUI Production Readiness Review

Date: 2026-06-29

Scope: post-TUI review of `config`, `core`, `cli`, `tui`, `app`, `docs`,
`build.mill`, and `.github/workflows/native-release.yml` after the explicit
`plan --tui` and `apply --tui` flows landed.

This review is a backlog gate. Items classified as must fix either reference the
implementation task that must address them or carry an explicit deferral
rationale.

## Reviewed Responsibilities

- `config`: owns YAML loading, typed manifest decoding, enum validation,
  installer-script rejection, duplicate tool and version-reference checks, and
  sudo symlink policy validation. It should not perform network, filesystem, or
  terminal side effects.
- `core`: owns environment interpolation, version resolution, HTTPS URL checks,
  selection, planning, downloads, checksum verification, archive extraction,
  staged filesystem replacement, executable modes, symlink creation, state
  persistence, command execution boundaries, typed expected errors, and
  renderer-agnostic apply events.
- `cli`: owns Picocli command shape, global flags, exit codes, script-friendly
  default rendering, colored progress, and routing explicit `--tui` requests
  into the TUI module. It should not duplicate install business rules.
- `tui`: owns deterministic planning/execution models, ANSI rendering, input
  parsing, terminal lifecycle, static non-interactive fallback, and event-driven
  execution display. It should consume core snapshots/events and avoid direct
  install logic.
- `app`: owns process entry and exit-code propagation only.
- `build/release`: Mill defines the acyclic graph `app -> cli -> {core, tui}`,
  `tui -> core`, and `core -> config`. The native release workflow builds and
  smoke-tests the native binary, but currently only covers non-TUI app smokes.

## Required Audit

- Shell injection: core process boundaries use argv for sudo symlinks and the
  `tar.xz` fallback. Manifest installer scripts are rejected. The TUI terminal
  backend still invokes `stty` through `sh -c`, but with internally generated
  arguments only; this is a should-fix boundary simplification, not a current
  manifest injection path.
- Path traversal: install directories are constrained under `appsDir`; state
  files are current-directory filenames; executable paths, archive targets, and
  local symlink paths are resolved under the install or staging root. Continue
  testing interpolated path fields because interpolation can change safety
  classes.
- Archive metadata: native `tar.gz` rejects links and unsupported tar entry
  types. Zip path traversal is checked, but symlink/external-attribute metadata
  is not explicitly rejected or tested. `tar.xz` extracts with system `tar`
  before indexing copied members, so it is not equivalent to the native paths
  for pre-extraction metadata control.
- Symlinks: local symlink paths resolve inside `installDir`, and targets must
  resolve inside `installDir`. This prevents ordinary manifest fields from
  linking arbitrary external targets.
- Sudo: sudo symlinks require `policy.allowSudoSymlinks`, non-dry-run
  confirmation through `--yes`, absolute sudo destinations, and argv execution
  of `sudo ln -sfn`. Ordinary path, archive, and download fields do not reach
  sudo.
- State files: state paths are filename-only, loaded from the current working
  directory, checked against profile name and manifest fingerprint, and written
  through a same-directory temp file plus `ATOMIC_MOVE`.
- Checksums: SHA-256 is enforced when configured and happens before install
  replacement. Missing checksums are visible in CLI/TUI output but are still
  accepted. Config does not yet validate checksum value shape beyond string
  type.
- Redirects: runtime clients require initial HTTPS URLs and follow normal JDK
  redirects. The final effective URL and redirect chain are not exposed in
  plan, versions, state, or apply diagnostics.
- Max size: binary downloads are buffered into memory without a configured max
  size or enforced `Content-Length` ceiling.
- Timeouts: HTTP requests have 30 second request/connect timeouts and external
  commands have a 15 minute timeout. Streaming body reads still need an overall
  body deadline or bounded read policy.
- Redaction: command environment rendering redacts non-safe env values. Other
  rendered surfaces can still expose env-derived secrets interpolated into URLs,
  paths, failure strings, TUI logs, or state messages.
- Terminal control: TUI raw mode is restored in `finally` for normal failures
  and exits, and non-interactive mode renders static frames. Manifest, path, and
  log strings are not centrally scrubbed for terminal control characters before
  CLI/TUI rendering. Interactive `apply --tui` does not read input while the
  synchronous apply loop runs, so the advertised `q`/Ctrl+C cancellation path is
  incomplete for long-running non-dry-run apply.

## Must Fix

- MUST-001 - Bound download size and body time. Implemented in T011. The
  runtime JDK binary download client now uses an explicit
  `BinaryDownloadLimits` policy, rejects oversized `Content-Length` values
  before buffering, stops no-length reads when the accumulated body exceeds the
  limit, and enforces a body-read deadline between chunks. Core tests cover all
  three paths.
- MUST-002 - Close archive metadata gaps. Partially implemented in T011 with
  explicit deferrals. Native archive planning now rejects duplicate archive
  member sources, and regression tests cover duplicate ZIP local headers and
  unsafe tar.gz hardlink metadata in addition to existing traversal/link
  rejection. Zip external-attribute symlink/permission inspection remains
  deferred because the current JDK `ZipInputStream`/`ZipEntry` path does not
  expose enough portable metadata for reliable rejection. Native pre-extraction
  `tar.xz` inspection also remains deferred until the project selects either a
  native xz/tar dependency or a stronger sandbox. Until then, `tar.xz` remains a
  structured fallback that is not production-equivalent for untrusted archives.
- MUST-003 - Sanitize terminal control output. Implemented in T011. Core now
  has a shared `RenderSafety` scrubber for display text, command output tails,
  structured errors, plan/versions output, apply result lines, download progress
  URLs, state messages, and TUI cell rendering. Tests cover ANSI/control
  injection in apply errors.
- MUST-004 - Make interactive apply TUI cancellation honest. Implemented in
  T011 by removing the misleading `q/Ctrl+C quit` keybar from the live
  execution view. Long-running `apply --tui` still runs synchronously without a
  nonblocking input/cancellation boundary; that richer cancellation design is
  deferred to a later terminal-backend task. Existing terminal cleanup tests
  still cover normal success and failure cleanup.
- MUST-005 - Centralize redaction for env-derived values. Implemented in T011.
  `ResolutionOptions` derives `SensitiveValueRedactions` from sensitive runtime
  variable names, and renderers apply those redactions at display boundaries so
  raw values are preserved for filesystem/network behavior but hidden from
  plan, versions, apply errors, progress/log lines, and state messages. Command
  env redaction now delegates to the same render-safety boundary.
- MUST-006 - Tighten checksum policy. Partially implemented in T011 with an
  explicit deferral. Config validation now requires SHA-256 values to be exactly
  64 hexadecimal characters. Missing checksums remain allowed for developer
  convenience and for dynamic upstream assets in `config.example.yaml`; this is
  deferred until strict/developer policy profiles or lock-file provenance are
  introduced. The visible warning path remains unchanged: CLI/TUI plan surfaces
  show `not configured`, `missing`, and `no-checksum` risk markers.
- MUST-007 - Restore or prove install replacement atomicity. Implemented in
  T011. Failed replacement after an existing install has been moved to backup
  now attempts to restore the previous install directory, reports rollback
  failure if restore also fails, and core tests prove the previous install is
  restored when the staged move fails.

## Should Fix

- Record redirect provenance: capture and render the final effective URL for
  downloads and `http-text` resolvers, and fail or warn if provenance changes
  expected host boundaries.
- Add optional content-type checks for downloads where upstreams provide stable
  values.
- Add retry policy for transient HTTP failures, bounded and off by default until
  idempotence is modeled.
- Replace the TUI `stty` shell wrapper with a direct process boundary or a small
  terminal backend abstraction that redirects `/dev/tty` without `sh -c`.
- Add live resize support by turning `SIGWINCH` or periodic size checks into
  `TuiInput.Resize` events.
- Expand config validation for non-empty, path-safe tool names and normalized
  path fields after interpolation.
- Split the large `core/src/binstaller/core/CoreModule.scala` into focused
  files after the must-fix pass: resolution, state, download, archive,
  filesystem, command, rendering, and events.
- Add native release static TUI smokes to the workflow, at least
  `plan --tui` and `apply --dry-run --tui` in non-interactive fallback mode.
- Add ScalaDoc and concise invariant comments for public boundaries and
  security-sensitive logic in T012.

## Later

- Add a lock file containing resolved versions, final URLs, sizes, checksums,
  and provenance.
- Add checksum auto-discovery for upstreams that publish checksum files.
- Add strict/developer policy profiles so production users can reject dynamic
  versions, missing checksums, sudo symlinks, and fallback archive paths without
  hand-editing every manifest.
- Add signature, SLSA, or SBOM verification for release assets.
- Revisit sandboxed installer-script support only if a future product decision
  explicitly reintroduces scripts.
- Consider JLine or another terminal backend only if local primitives remain too
  brittle after cancellation, resize, and cleanup hardening.

## Validation Notes

Existing automated coverage is strong for typed config failures, HTTPS checks,
checksum mismatch, archive path traversal, state fingerprint mismatch, sudo
gating, dry-run no-write behavior, deterministic TUI rendering, input model
navigation, and non-interactive TUI fallbacks.

Remaining validation gaps are mostly production-edge cases: oversized downloads,
body stalls, archive metadata beyond traversal, terminal control injection,
env-derived secret redaction, failed replacement rollback, live raw-terminal
long-running cancellation, native-image TUI smoke, and release workflow evidence.
