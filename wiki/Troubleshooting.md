# 🩺 Troubleshooting

Error messages, decoded. Each row is what `binstaller` actually prints, what it means, and the fix.

> 💡 First move for almost anything: run `binstaller plan` (writes nothing) and add `--verbose`.
> The plan header alone answers most "why did it do that?" questions.

Every apply-time failure prints a summary line followed by indented detail pairs,
ending with a `suggestion:` line that names what to check or change — read that
line first.

---

## 📄 Manifest won't load

| Message | Meaning | Fix |
|---|---|---|
| `installer scripts are not supported; use direct binary or archive download` | The profile has an `installer:` block. | Convert it to a `download:` + optional `archive:` block. Script execution is intentionally unsupported. |
| `duplicate tool name 'X'` | Two `spec.plan` entries share a name. | Rename one. Names are the selection key for `--only` / `--skip`. |
| `tool 'X' references unknown version 'Y'` | `spec.versionRef` has no matching `spec.versions` entry. | Add the version, or fix the reference. |
| `spec.plan[0].spec: required map is missing` | Fields were placed directly on the plan entry instead of under `spec:`. | Nest `versionRef`, `installDir`, `download`, `executables` under `spec:`. |
| `spec.plan[0].download: unknown field 'download'` | Same cause as above — unknown keys are rejected rather than ignored. | Move it under `spec:`. |

> 🧭 Decoding reports **every** problem at once, not just the first. Read the whole block —
> the "unknown field" and "required … is missing" lines usually describe the same mistake twice.

---

## 🧱 Strict-mode refusals

Strict failures have stable, greppable codes:

```text
strict-policy[<code>]: <reason>; suggestion[<code>]: <suggestion>
```

| Situation | Fix |
|---|---|
| Dynamic `latest-url` version, or a download URL containing `/latest` | Pin the version, or set `policy.allowDynamicLatestUrls: true` after review. |
| Missing `download.checksum` | Add a checksum, use `checksum.discover`, or set `policy.allowMissingChecksums: true`. |
| `sudo: true` symlink | Drop it, or set `policy.allowSudoSymlinks: true`. |

Prefer fixing the profile over adding the opt-in. The opt-ins exist so exceptions are **explicit
and reviewable**, not so they become the default.

---

## 🌐 Network and URL problems

| Symptom | Cause | Fix |
|---|---|---|
| URL rejected before any request | Not HTTPS, or no host. | Use a full `https://` URL. |
| Host rejected | It names or resolves to loopback, link-local, private, site-local, multicast or a cloud-metadata endpoint. | Use a public host. This guard also applies to **every redirect hop**. |
| `http-text resolver failed: …` | The `resolver.url` fetch failed. | Check the URL by hand: `curl -sSfL <url>`. |
| `version 'X' did not resolve to a concrete value` | A resolver returned nothing usable. | Verify the endpoint returns a bare version string. |

---

## 🔐 Checksum failures

```text
checksum: sha256 expected <a>, got <b>
```

Something changed. In order of likelihood:

1. **The upstream artifact was re-published** under the same version — common with `latest` URLs.
2. **Your pinned checksum is stale** after a version bump.
3. **The download was truncated or corrupted.**
4. Something is genuinely wrong. Don't paste in the new digest reflexively.

The error deliberately tells you to *verify the downloaded artifact before updating the manifest
checksum*. Do that. Then update the value, or switch to `checksum.discover` so the digest comes
from the upstream `SHA256SUMS` file.

> ⚠️ A checksum must be exactly 64 hexadecimal characters. Anything else is rejected at load time.

---

## 🗜️ Archive and path errors

| Message | Meaning |
|---|---|
| `archive extraction: …` | Extraction failed — usually a `from:` path that doesn't exist in the archive. |
| `… must not contain traversal segments` | A `to:` path contains `..`. |
| `… must not be drive-prefixed` / `must not contain backslashes` | Windows-style path in a `to:` target. |
| `… must not contain control characters` | Non-printable characters in a resolved path. |
| `verify executable: missing <path>` | The declared executable isn't there after extraction. |

**Debugging a `from:` mismatch** — list the archive's real member names:

```bash
tar -tzf downloaded.tar.gz | head -30     # tar.gz
unzip -l downloaded.zip | head -30        # zip
```

Then match `from:` exactly, including any leading directory such as
`nvim-linux-x86_64/bin/nvim`.

`verify executable: missing` almost always means your `to:` target and your `executables[].path`
disagree. They must point at the same relative path inside `installDir`.

---

## 💾 State and resume

| Message | Fix |
|---|---|
| `state file … does not match this manifest: expected profile 'A' with fingerprint …` | You edited the manifest or renamed the profile. Re-run with `--reset-state`. |
| `state file … uses schema version N, expected M` | State from an older binstaller. Re-run with `--reset-state`. |
| `absolute state paths are not allowed` | `stateFile` must be a bare filename. |
| `state path must be a filename in the current working directory` | Remove directory components from `stateFile`. |
| `state filename must not be empty` | Set a real filename, or drop the field. |

Tools that already succeeded are skipped on a compatible re-run — that's the feature, not a bug.
Use `--reset-state` when you want a clean run.

---

## 🧑‍💼 Sudo symlinks

| Message | Fix |
|---|---|
| `sudo symlinks are not allowed by policy.allowSudoSymlinks` | Set `policy.allowSudoSymlinks: true`, or remove `sudo: true`. |
| `sudo credentials unavailable for <link> -> <target>` | The run is non-interactive and there's no cached sudo credential. Run `sudo -v` first, or run interactively — binstaller **fails closed** rather than guessing. |
| `sudo credentials canceled for <link> -> <target>` | You cancelled the prompt. That symlink fails; `continueOnError` decides whether the rest proceeds. |

---

## 🧊 Locked apply refusals

All rendered as `locked apply refused by <path>: <reason>`.

| Reason | Fix |
|---|---|
| `lock file … is missing` | Run `binstaller lock`, or drop `--locked`. |
| `manifest fingerprint changed: …` | You edited the profile. Re-run `binstaller lock` and commit both files together. |
| `tool 'X' version changed: lock has 'A', resolved 'B'` | Upstream moved. Re-lock deliberately. |
| `tool 'X' has no locked sha256 digest` | Add a checksum to that tool, then re-lock. |
| `missing lock entry for tool 'X'` | The tool was added after locking. Re-lock. |

---

## 🖥️ Output looks wrong

| Symptom | Explanation |
|---|---|
| No colours, no progress bars | Output isn't a terminal. Plain line-oriented text is intentional so piping stays safe. |
| Progress bars overwrite each other | Concurrent downloads share a redrawn progress block. It settles once downloads finish. |
| A download label shows an opaque ID instead of a filename | The label comes from the **final** URL, and some CDNs use signed, opaque asset paths. The install itself is unaffected. |

---

## 🐛 Still stuck?

Collect this before filing an issue:

```bash
binstaller --version
binstaller plan --config your.yaml --verbose 2>&1 | head -60
```

Then open a [GitHub issue](https://github.com/worxbend/binstaller/issues/new) with the profile
snippet (redact anything private), the command, and the full output.
