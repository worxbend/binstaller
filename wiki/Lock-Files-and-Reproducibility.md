# 🧊 Lock Files & Reproducibility

A profile says *what* you want. A lock file records *exactly what that resolved to* — versions,
URLs, redirect chains, sizes and digests — so a later apply either reproduces it or refuses.

---

## 🔨 Writing a lock file

```bash
binstaller lock
binstaller lock --lock-file /tmp/binstaller.lock.json
binstaller lock --only helm --only kubectl
```

Default output path: `binstaller.lock.json`. Nothing is installed.

```text
wrote lock file: /path/to/binstaller.lock.json
profile: demo
manifest fingerprint: 93554ed0c1409e233aeeb2bc3ea68cbd0310469c92d32cbdd780aa7271e55452
tools: 2
checksums: configured 2, discovered 0, inspected 0, missing 0
```

That last line is the one to read in CI. `missing 0` is the goal.

---

## 📄 What's inside

```json
{
  "schemaVersion": 1,
  "profileName": "demo",
  "manifestFingerprint": "93554ed0c140…e55452",
  "tools": [
    {
      "name": "dotbot",
      "resolvedVersion": "v0.3.0",
      "versionProvenance": null,
      "downloadProvenance": {
        "initialUrl": "https://github.com/worxbend/dotbot-go/releases/download/v0.3.0/dotbot-linux-amd64.tar.gz",
        "finalUrl": "https://release-assets.githubusercontent.com/…",
        "redirects": [
          { "from": "https://github.com/…", "to": "https://release-assets.githubusercontent.com/…", "statusCode": 302 }
        ]
      },
      "sizeBytes": 2148359,
      "checksum": {
        "algorithm": "sha256",
        "value": "45d49e064d8684926fed97ad051c6ecebbf796a3c709edaa7a4a166b2978633d",
        "source": "configured"
      },
      "dynamicSource": false
    }
  ]
}
```

| Field | Why it's there |
|---|---|
| `manifestFingerprint` | Ties the lock to the exact manifest content it was generated from. |
| `downloadProvenance.redirects` | Records every hop, so a changed CDN path is visible in review. |
| `sizeBytes` | Cross-checked against upstream metadata at locked-apply time. |
| `checksum.source` | Provenance of the digest itself: `"configured"` (pinned in the manifest), `"inspected"` (observed while writing the lock), or a `discovered` object carrying the discovery `url`, `file` and its own `provenance`. Absent when no checksum is known. |
| `dynamicSource` | Marks tools whose version is intentionally resolved by a latest-URL. |

> ⚠️ `finalUrl` values for GitHub release assets contain **signed, expiring** query parameters.
> They are recorded as provenance, not as a fetch target you should reuse by hand.

---

## 🔒 Applying under a lock

```bash
binstaller apply --locked --lock-file binstaller.lock.json
binstaller plan  --locked          # same gate, without installing
```

The gate refuses the run — **before anything downloads** — when any of these is true:

| Check | Refusal message |
|---|---|
| Lock file absent | `lock file <path> is missing; run \`binstaller lock --config <file>\` first or omit --locked` |
| Schema mismatch | `expected schema version 1, found N` |
| Different profile | `expected profile 'X', found 'Y'` |
| Manifest edited | `manifest fingerprint changed: …; rerun \`binstaller lock --config <file>\`` |
| Duplicate lock entry | `duplicate lock entry for tool 'X'` |
| Tool not in lock | `missing lock entry for tool 'X'` |
| Digest absent from lock | `tool 'X' has no locked sha256 digest; regenerate the lock file` |
| Version drifted | `tool 'X' version changed: lock has 'A', resolved 'B'` |
| Provenance incomplete | `tool 'X' has incomplete download provenance` |

All refusals are rendered as `locked apply refused by <lock path>: <reason>`.

---

## 🔄 A workflow that holds up

```bash
# once, when you change the profile
binstaller lock
git add config.yaml binstaller.lock.json
git commit -m "pin toolchain"

# everywhere else, forever after
binstaller apply --locked
```

Editing `config.yaml` changes the manifest fingerprint, so `--locked` will refuse until you
re-run `binstaller lock`. That's the point: the lock file and the profile move together, in the
same commit, visible in the same diff.

---

## 🤖 In CI

```yaml
- name: Verify the toolchain still resolves to the locked state
  run: binstaller plan --locked --lock-file binstaller.lock.json
```

`plan --locked` runs the entire gate without installing anything — a cheap drift alarm on a
schedule.

---

## 💡 Getting the most out of it

- 🧱 Pair lock files with `policy.mode: strict` so unpinned versions can't get in to begin with.
- 📌 Prefer pinned `versions:` entries over `dynamic.latest-url` — a dynamic source can only ever
  be locked as "this was dynamic", not as a specific artifact.
- 🔐 Give every tool a checksum (configured or discovered). A tool with no digest is refused under
  `--locked` anyway.
- 🆙 Run `binstaller versions` on a schedule to see upstream drift, then bump and re-lock
  deliberately.
