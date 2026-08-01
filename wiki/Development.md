# 🛠️ Development

Scala 3 · Mill · GraalVM. No sbt, Maven, Gradle, npm or Make — use the checked-in `./mill`
launcher for everything.

---

## 📋 Requirements

| Need | Version |
|---|---|
| JDK | 21+ |
| GraalVM | 21, **only** for building native images locally |
| Mill | Pinned by `.mill-version`; the `./mill` launcher fetches it |

---

## 🏗️ Module graph

```text
app ──▶ cli ──▶ core ──▶ config
```

The graph is acyclic and `core` never imports CLI code.

| Module | Owns |
|---|---|
| `config` | YAML reading (SnakeYAML Engine), typed decoding, enum validation, rejecting installer scripts, duplicate names, unknown `versionRef`s, SHA-256 shape, sudo gating. |
| `core` | Variable and version resolution, HTTPS validation, `--only`/`--skip` selection, bounded downloads, checksum verification, archive extraction, staging and replacement, symlinks, apply state, typed installer events. |
| `cli` | Picocli parsing, exit codes, script-friendly output, coloured apply progress, command routing. |
| `app` | Process entry and exit-code propagation. Nothing else. |

---

## ✅ Checks

Focused:

```bash
./mill config.test
./mill core.test
./mill cli.test
```

Broad:

```bash
./mill __.compile
./mill __.test
./mill mill.scalalib.scalafmt/checkFormatAll
git diff --check
```

Repair formatting:

```bash
./mill mill.scalalib.scalafmt/reformatAll
```

> ⚠️ Don't pipe Mill output through `grep`, `head`, `tail` or `/dev/null` — it hides diagnostics
> you'll want for the follow-up fix. Stream it to a file instead:
>
> ```bash
> LOGFILE="/tmp/binstaller-check-$(date +%s).log"
> ./mill __.test 2>&1 | tee "$LOGFILE"
> ```

---

## ▶️ Running from source

```bash
./mill app.run --help
./mill app.run plan     --config config.example.yaml
./mill app.run apply    --config config.example.yaml
./mill app.run versions --config config.example.yaml
./mill app.run lock     --config config.example.yaml --output /tmp/binstaller.lock.json
```

---

## 📦 Native image

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
```

Native options live in `build.mill`: `--no-fallback`, `-O2`,
`--initialize-at-build-time=scala`. Picocli reflection configuration is generated as part of the
build.

---

## 🧪 Test patterns

Tests use [utest](https://github.com/com-lihaoyi/utest) and **never** hit the network. Downloads
and text fetches are injected:

- A fake `HttpTextClient` returns pinned resolver values for `http-text` versions, and routes
  GitHub latest-release metadata used by `versions`.
- A fake `BinaryDownloadClient` returns bytes or a typed `BinaryDownloadError`.
- Progress tests override `download(url, observer)` and emit started / advanced / finished events
  before returning bytes.

Tests receive the repository root through `-Dbinstaller.repoRoot`, set by the build, so fixtures
can locate checked-in files such as `config.example.yaml`.

When adding behaviour, prefer a test at the lowest module that owns the rule — decoding rules in
`config`, resolution and safety rules in `core`, output and exit codes in `cli`.

---

## 📡 Event contract

`core` emits renderer-agnostic events; the CLI is one consumer among possible others.

```text
ResolvingStarted · PlanReady · ToolStarted · ToolPhaseChanged · DownloadProgress
LogLine · ToolResult · ToolSkipped · Summary
```

Phases: `Resolving`, `Planning`, `LoadingState`, `Downloading`, `VerifyingChecksum`, `Staging`,
`ApplyingModes`, `ReplacingInstall`, `VerifyingExecutables`, `CreatingSymlinks`, `SavingState`.

If you add a phase or event, update
[`docs/architecture.md`](https://github.com/worxbend/binstaller/blob/main/docs/architecture.md)
in the same change.

---

## 🚢 Releasing

Releases are GraalVM native binaries built by `.github/workflows/release.yml`, triggered by a
`v*` tag or a manual `workflow_dispatch` with a tag input.

Per target (`linux-amd64`, `linux-arm64`, `macos-amd64`, `macos-arm64`) the workflow:

1. Checks out and sets up GraalVM 21 with `native-image`
2. Runs `./mill __.test`
3. Builds `./mill app.nativeImage`
4. Packages `binstaller-<version>-<target>.tar.gz`
5. Smokes native `--help`, `plan` and `versions`
6. Writes a per-file `.sha256`

The publish job then re-verifies checksums, signs every artifact with keyless Sigstore
(`cosign sign-blob` using the workflow's GitHub OIDC identity), and publishes the release assets
including `config.example.<version>.yaml` and `install.sh`.

All GitHub Actions are pinned to commit SHAs; Mill is pinned via `.mill-version`.

Full detail: [`docs/release.md`](https://github.com/worxbend/binstaller/blob/main/docs/release.md).

---

## 🌐 Website and wiki

| Surface | Source | Published by |
|---|---|---|
| 🌐 [Website](https://worxbend.github.io/binstaller/) | [`site/`](https://github.com/worxbend/binstaller/tree/main/site) + [`assets/`](https://github.com/worxbend/binstaller/tree/main/assets) | `.github/workflows/pages.yml` |
| 📚 This wiki | [`wiki/`](https://github.com/worxbend/binstaller/tree/main/wiki) | `.github/workflows/wiki.yml` |

Terminal screenshots in `assets/` are SVG renderings of **real** command output — regenerate them
from a real run rather than hand-editing, so the docs can't drift from the tool.

Edit wiki pages in `wiki/` and open a pull request. Direct edits in the GitHub wiki UI are
overwritten on the next sync.

---

## 🤝 Contributing

1. Open an issue first for anything that changes the manifest contract or the CLI surface — the
   narrow scope is a feature, and scope changes deserve a discussion.
2. Keep `core` free of CLI concerns.
3. Add tests at the module that owns the rule.
4. Run `./mill __.test` and `./mill mill.scalalib.scalafmt/checkFormatAll` before pushing.
5. Update the relevant `docs/` page in the same change.
