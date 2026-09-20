<div align="center">

<img src="https://raw.githubusercontent.com/worxbend/binstaller/main/assets/banner.svg" alt="binstaller" width="760">

**One YAML profile. Every binary. Same result on every machine.**

[🌐 Website](https://worxbend.github.io/binstaller/) · [📦 Releases](https://github.com/worxbend/binstaller/releases) · [🐛 Issues](https://github.com/worxbend/binstaller/issues)

</div>

---

## 👋 Start here

| Page | What you'll find |
|---|---|
| 🚀 [Getting Started](Getting-Started) | Install the binary, write your first profile, run your first apply. |
| 🎛️ [CLI Reference](CLI-Reference) | Every command, every flag, every exit code. |
| 🍳 [Manifest Cookbook](Manifest-Cookbook) | Copy-paste recipes for direct binaries, archives, symlinks and version resolvers. |
| 🧊 [Lock Files & Reproducibility](Lock-Files-and-Reproducibility) | Pin a toolchain and refuse to drift from it. |
| 🔐 [Security Model](Security-Model) | Trust boundaries, checksum policy, SSRF guards, sudo rules. |
| 🩺 [Troubleshooting](Troubleshooting) | Error messages decoded, with the fix for each. |
| ❓ [FAQ](FAQ) | Scope questions, comparisons, and "can it do X?". |
| 🛠️ [Development](Development) | Build from source, run the tests, cut a release. |

---

## ⚡ The 30-second version

```bash
# 1. install the native binary (checksum + Sigstore verified)
curl --proto '=https' --tlsv1.2 -sSfL \
  https://github.com/worxbend/binstaller/releases/latest/download/install.sh | sh

# 2. describe your tools once
cp config.example.yaml config.yaml

# 3. read what would happen — nothing is written
binstaller plan

# 4. do it
binstaller apply
```

---

## 🧭 What binstaller is

A command-line installer for **binary tool distributions** described by a single YAML profile.
It resolves versions, previews what will be downloaded and unpacked, then installs selected tools
under a user-controlled apps directory such as `${HOME}/.apps`.

**Supported scope:**

- ✅ Plan / apply workflows on the command line
- ✅ Direct binary downloads
- ✅ `zip`, `tar.gz` and `tar.xz` archives
- ✅ Executable verification and mode application
- ✅ Local symlinks, plus opt-in sudo symlinks
- ✅ Apply state and resume
- ✅ Lock files
- ✅ Version reporting with upstream update detection

**Deliberately out of scope:**

- ❌ Package management (dependency graphs, removal, repositories)
- ❌ Dotfiles management
- ❌ Running installer scripts — `installer:` blocks are rejected at load time
- ❌ General shell-command execution
- ❌ Multi-OS workstation provisioning

If you need those, `binstaller` is meant to sit *next to* the tool that does them, not replace it.

---

## 🗺️ Where things live

| Thing | Location |
|---|---|
| Example profile | [`config.example.yaml`](https://github.com/worxbend/binstaller/blob/main/config.example.yaml) |
| Architecture notes | [`docs/architecture.md`](https://github.com/worxbend/binstaller/blob/main/docs/architecture.md) |
| Manifest reference | [`docs/manifest-reference.md`](https://github.com/worxbend/binstaller/blob/main/docs/manifest-reference.md) |
| Security model | [`docs/security.md`](https://github.com/worxbend/binstaller/blob/main/docs/security.md) |
| Release guide | [`docs/release.md`](https://github.com/worxbend/binstaller/blob/main/docs/release.md) |

> 📝 **Editing this wiki:** the [`wiki/`](https://github.com/worxbend/binstaller/tree/main/wiki)
> directory in the main repository is the source of truth for this wiki. Pages are synced by CI,
> so edit the files there and open a pull request — direct wiki edits will be overwritten on the
> next sync.
