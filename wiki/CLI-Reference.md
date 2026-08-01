# 🎛️ CLI Reference

Four commands. Predictable exit codes. No hidden side effects.

```text
Usage: binstaller [-hV] [--reset-state] [--verbose] [--config=FILE]
                  [--state=FILE] COMMAND
```

---

## 📋 Commands

| Command | Purpose | Writes files |
|---|---|---|
| 🔍 `plan` | Render the resolved install plan. | No |
| 🚀 `apply` | Download, verify, stage, install, symlink, save state. | Yes |
| 🆙 `versions` | Print package versions and available GitHub release updates. | No |
| 🧊 `lock` | Resolve and write a JSON lock file. | Lock file only |

---

## ⚙️ Shared options

| Flag | Meaning |
|---|---|
| `--config FILE` | Path to the YAML profile. Defaults to `config.yaml` in the current directory. |
| `--state FILE` | Override the profile state file used by `apply`. |
| `--reset-state` | Ignore saved execution state and start fresh. |
| `--verbose` | Show additional command diagnostics. |
| `-h`, `--help` | Show help and exit `0`. |
| `-V`, `--version` | Print version information and exit `0`. |

### Selection

Available on `plan`, `apply` and `lock`. Both flags are repeatable.

| Flag | Meaning |
|---|---|
| `--only TOOL` | Include only the named tool. |
| `--skip TOOL` | Omit the named tool. |

`--only` is applied **first**, `--skip` **second**, and manifest order is always preserved.
Unknown tool names are a selection error (exit `1`), not a silent no-op.

```bash
binstaller plan  --only yazi --only zig
binstaller apply --skip neovim
```

---

## 🔍 `plan`

Resolves everything and prints it. Touches no files, though version and checksum resolution may
use the network (`http-text` resolvers, `sha256sum` discovery, and GitHub latest-release lookups).

```bash
binstaller plan
binstaller plan --config profiles/workstation.yaml
binstaller plan --only kubectl
binstaller plan --locked --lock-file binstaller.lock.json
```

| Flag | Meaning |
|---|---|
| `--locked` | Require a compatible JSON lock file before rendering. |
| `--lock-file FILE` | Path to the lock file used by `--locked`. |

Read the header first:

```text
binstaller plan
tools: 15
apps dir: /home/you/.apps (not created)
policy mode: developer
state file: developer-binaries.state.json (not created)
lock file: not required
filesystem: no changes will be made (version and checksum resolution may use the network)
sudo risk: YES - 5 sudo symlink command(s) require elevated privileges
```

---

## 🚀 `apply`

```bash
binstaller apply
binstaller apply --only lazygit
binstaller apply --reset-state
binstaller apply --locked --lock-file binstaller.lock.json
```

| Flag | Meaning |
|---|---|
| `--locked` | Require a compatible JSON lock file before applying. |
| `--lock-file FILE` | Path to the lock file used by `--locked`. |

**Per-tool phases:** `Resolving` → `Planning` → `LoadingState` → `Downloading` →
`VerifyingChecksum` → `Staging` → `ApplyingModes` → `ReplacingInstall` →
`VerifyingExecutables` → `CreatingSymlinks` → `SavingState`.

**Output style:** colour, in-place progress bars and a concurrent progress block when a terminal
is attached; plain, line-oriented, script-friendly text when it is not. Piping to a file or
another process is safe.

**Failure behaviour:** by default the run stops at the first failed tool. Set
`spec.policy.continueOnError: true` to keep going — the run still exits non-zero if anything
failed.

---

## 🆙 `versions`

```bash
binstaller versions
```

Prints a three-column table: package, resolved version, and a newer version when one exists.

```text
package              version             newer version
lazygit              0.61.0              v0.63.1
kubectl              v1.36.3             -
minikube             dynamic latest-url  -
kustomize            v5.8.1              ?
```

| Column value | Meaning |
|---|---|
| `-` | No newer release detected. |
| `?` | The upstream latest release could not be determined. |
| `dynamic latest-url` | The version is intentionally resolved by the download endpoint itself. |

Update detection works for downloads that look like GitHub release URLs; the repository's latest
release tag is queried and compared.

---

## 🧊 `lock`

```bash
binstaller lock
binstaller lock --output /tmp/binstaller.lock.json
binstaller lock --only helm --only kubectl
```

| Flag | Meaning |
|---|---|
| `--output FILE` | Lock file to write. Default: `binstaller.lock.json`. |

Resolves versions, URLs and digests and writes them as JSON. Installs nothing.
See 🧊 [Lock Files & Reproducibility](Lock-Files-and-Reproducibility).

---

## 🚦 Exit codes

| Code | Meaning |
|---|---|
| `0` | Completed successfully — including `--help` and `plan`. |
| `1` | Manifest loading or resolution failed, selection was invalid, apply failed, or state persistence failed. |
| `2` | Command-line usage error. |

Scripting example:

```bash
if ! binstaller plan --only kubectl >/dev/null; then
  echo "profile does not resolve" >&2
  exit 1
fi
binstaller apply --only kubectl
```

---

## 🧑‍💻 Running from source

```bash
./mill app.run --help
./mill app.run plan    --config config.example.yaml
./mill app.run apply   --config config.example.yaml
./mill app.run versions --config config.example.yaml
./mill app.run lock    --config config.example.yaml --output /tmp/binstaller.lock.json
```
