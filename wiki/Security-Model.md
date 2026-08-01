# 🔐 Security Model

A practical summary. The authoritative version is
[`docs/security.md`](https://github.com/worxbend/binstaller/blob/main/docs/security.md).

The short version: **the manifest is trusted configuration; everything it points at is not.**
Downloads, archive metadata, terminal text and process output are treated as untrusted input at
display and install boundaries.

---

## 🚧 Trust boundaries

| Boundary | Rule |
|---|---|
| 📄 YAML parsing | Fields that would execute scripts are rejected before planning. |
| 🌐 URLs | HTTPS with a host, always. |
| 🕳️ Hosts | Names that resolve to loopback, link-local, private, site-local, multicast or known cloud-metadata endpoints are rejected — **and every redirect hop is re-validated** before it is followed. |
| 🔤 Interpolation | Only a fixed allowlist of non-secret environment variables (`HOME`, `USER`, `LOGNAME`, `SHELL`, `XDG_*`) is exposed. A manifest cannot interpolate a process secret into an outbound URL. |
| 📥 Downloaded bytes | Untrusted until bounded, checksum-verified when configured, extracted or staged, and executables verified. |
| 🗜️ Archive members | Names and mapped targets must stay inside staging or install roots. |
| 🖥️ Terminal output | Control characters scrubbed, sensitive environment-derived values redacted at display time. |
| 💾 State files | Local resume metadata — **not** a security authority. |

---

## 🚫 Installer scripts are not supported

A profile containing an `installer:` block fails at config load:

```text
installer scripts are not supported; use direct binary or archive download
```

This is deliberate. It keeps shell syntax, inherited environment, cleanup semantics, timeouts and
sudo behaviour out of the manifest contract entirely.

---

## 🗜️ Archive safety

- `zip`, `tar.gz` and `tar.xz` are all decoded **in-process**. Extraction does not shell out to
  the system `tar` or `unzip`.
- Unsafe member paths are rejected before anything is written.
- The tar path rejects symlink, hardlink and unsupported entry types.
- Duplicate member sources and duplicate output targets are rejected.
- An aggregate expanded-byte budget bounds decompression bombs, independent of the compressed
  download cap.
- Path confinement is equivalent across all three archive types.

---

## 🔐 Checksum policy

- SHA-256 only, exactly 64 hex characters.
- Verified **before** the staged install replaces the previous one.
- Missing checksums are allowed in `developer` mode and surfaced loudly as `not configured` /
  `missing` / `no-checksum` risk markers in plan output.
- `policy.mode: strict` rejects missing checksums unless `policy.allowMissingChecksums: true`
  is explicitly set.

Strict-policy failures render with stable `strict-policy[...]` codes and matching
`suggestion[...]` hints, so they are easy to grep in CI logs.

---

## 🧑‍💼 Sudo policy

Sudo is available for **one** thing: creating symlinks. It requires *all* of:

1. `policy.allowSudoSymlinks: true` in the manifest
2. `sudo: true` on the symlink entry
3. An absolute destination path

Ordinary downloads, extraction, executable checks, local symlinks and state writes never cross
the sudo boundary.

**The flow:**

1. Probe cached credentials with fixed `sudo -n true` argv.
2. If cached, create the link with fixed `sudo -n ln -sfn <target> <path>` argv — no password
   requested.
3. Otherwise ask the injected credential provider for one password, for that one operation.
4. Interactive CLI runs prompt on the terminal. **Non-interactive runs fail closed.**
5. Password-backed commands use fixed `sudo -S -p "" ln -sfn <target> <path>` argv plus secret
   stdin.

Passwords never appear in argv, environment variables, command previews, diagnostics, installer
events, apply state or logs. Cancelling a credential prompt fails that symlink only;
`continueOnError` decides whether later tools proceed.

---

## 🖥️ Redaction and display safety

- Sensitive runtime-variable-derived values are scrubbed from plan output, `versions` output,
  apply errors, progress labels, command output tails and state messages.
- Redaction is **display-only** — raw values are preserved for filesystem and network behaviour,
  so legitimate paths and URLs are never corrupted.
- Untrusted text is collapsed into safe terminal lines, so config values, resolver output and
  command stdout/stderr cannot inject cursor movement, alternate-screen toggles, colour resets or
  mouse-mode escapes into your terminal.

---

## 💾 State-file policy

- The state path comes from `--state` or `spec.policy.stateFile`.
- Only current-directory filenames are accepted — absolute, nested and empty paths are rejected.
- `plan` never validates or touches state.
- State is written after terminal tool results via a same-directory temp file and an atomic move.
- Incompatible profile names or manifest fingerprints fail unless `--reset-state` is passed.

---

## 📦 Release integrity

- Each release artifact carries a `.sha256` file.
- Releases are signed with **keyless Sigstore** signatures.
- `scripts/install.sh` verifies the checksum always, and the signature when `cosign` is present.

> ℹ️ The per-release SHA-256 files share the release origin, so on their own they defend against
> corruption rather than acting as an independent trust anchor. The Sigstore signature is the
> stronger anchor — install `cosign` before running the install script if that matters to you.

---

## ⚠️ Known remaining risks

Documented honestly rather than hidden:

- Download body deadlines are checked at chunk boundaries, not continuously.
- Missing checksums are still accepted by `developer`-mode profiles.
- ZIP external attributes are deferred.
- Credential-provider cancellation is scoped to the current privileged operation — it is not a
  general cancellation mechanism for a running download, extraction or command.
- Native-image validation is environment-bound when `native-image` is not installed locally; the
  release workflow remains the native build boundary.

See [`docs/hardening-review.md`](https://github.com/worxbend/binstaller/blob/main/docs/hardening-review.md)
for deferred items and rationale.

---

## 🛡️ Recommended hardening for production profiles

```yaml
spec:
  policy:
    mode: strict              # reject latest-URLs, missing checksums, sudo symlinks
    allowSudoSymlinks: false  # keep everything user-scoped
    appsDir: "${HOME}/.apps"
```

Then pin every version, give every download a checksum, commit a lock file, and run
`binstaller apply --locked`.

---

## 📣 Reporting a vulnerability

Please open a [GitHub issue](https://github.com/worxbend/binstaller/issues) for non-sensitive
findings. For anything you believe is sensitive, use GitHub's private vulnerability reporting on
the repository's **Security** tab rather than a public issue.
