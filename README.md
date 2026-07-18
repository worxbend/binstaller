# binstaller

[![Release](https://github.com/worxbend/binstaller/actions/workflows/release.yml/badge.svg)](https://github.com/worxbend/binstaller/actions/workflows/release.yml)
![Scala 3](https://img.shields.io/badge/Scala-3.8.2-dc322f?logo=scala&logoColor=white)
![Mill](https://img.shields.io/badge/Mill-1.1.7-5b5bd6)
![GraalVM](https://img.shields.io/badge/GraalVM-native%20ready-f2a900?logo=graalvm&logoColor=111111)
![Linux](https://img.shields.io/badge/Linux-amd64%20binary%20installer-2ea44f?logo=linux&logoColor=white)

`binstaller` is a command-line installer for Linux amd64 binary tool
distributions described by one YAML profile. It resolves versions, previews
what will be downloaded and unpacked, then installs selected tools under a
user-controlled apps directory such as `${HOME}/.apps`.

The supported scope is deliberately narrow: command-line plan/apply workflows,
direct binary downloads, `zip`, `tar.gz`, and `tar.xz` archives, executable
checks, local symlinks, optional sudo symlinks, plan previews, apply
state/resume, CLI progress, and version reporting. It is not a package manager,
dotfiles runner, installer-script host, broad shell-command runner, or multi-OS
workstation provisioner.

## Install

Native Linux amd64 and arm64 builds are published from `v*` tags by GitHub
Actions.

Install with the curl-pipe script, which downloads the release tarball,
verifies its SHA-256 checksum, additionally verifies the keyless Sigstore
signature when `cosign` is installed, and installs `binstaller` to
`~/.local/bin` (set `BINSTALLER_INSTALL_DIR` to override):

```bash
curl --proto '=https' --tlsv1.2 -sSfL \
  https://github.com/worxbend/binstaller/releases/latest/download/install.sh | sh
```

The script does not modify your shell configuration by default; it prints the
`export PATH=...` line to run for the current shell. Set
`BINSTALLER_UPDATE_PATH=1` to have it append the install directory to
`~/.bashrc` and `~/.zshrc`. Pin a specific version with
`BINSTALLER_VERSION=v0.3.0`.

Release artifacts (each `.tar.gz` and the example config ship with a
`.sha256` checksum file):

- `binstaller-<version>-linux-amd64.tar.gz`
- `binstaller-<version>-linux-arm64.tar.gz`
- `binstaller-<version>-macos-amd64.tar.gz`
- `binstaller-<version>-macos-arm64.tar.gz`
- `config.example.<version>.yaml`
- `install.sh`

## Quick Start

Copy the checked-in [config.example.yaml](config.example.yaml) to `config.yaml`
in your working directory, or pass it explicitly with `--config`.

```bash
cp config.example.yaml config.yaml
```

Preview the resolved plan:

```bash
binstaller plan
```

Apply the profile.

```bash
binstaller apply
```

Select or omit tools by name. These flags may be repeated.

```bash
binstaller plan --only yazi
binstaller apply --skip neovim
```

Print a package/version summary table. For tools downloaded from GitHub
Releases, this also checks the repository's latest release tag and prints the
newer version when an update is available.

```bash
binstaller versions
```

Write a reproducible lock file without installing tools:

```bash
binstaller lock --output /tmp/binstaller.lock.json
```

The manifest policy defaults to `mode: developer`, which preserves local
tooling convenience. Production-oriented profiles can set `policy.mode: strict`
to reject dynamic latest URLs, missing checksums, sudo symlinks, and `tar.xz`
fallback extraction unless those risks are explicitly allowed in the manifest.

Source-development equivalents use the checked-in Mill launcher:

```bash
./mill app.run --help
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml
./mill app.run versions --config config.example.yaml
./mill app.run lock --config config.example.yaml --output /tmp/binstaller.lock.json
```

## CLI Surface

Top-level commands:

| Command | Purpose | Writes files |
|---|---|---|
| `plan` | Render the resolved install plan. | No |
| `apply` | Download, stage, install, symlink, and save state. | Yes |
| `versions` | Print package versions and available GitHub release updates. | No |
| `lock` | Resolve and write a JSON lock file. | Lock file only |

Useful shared options:

- `--config FILE`: path to the YAML profile. Defaults to `config.yaml` in the
  current directory.
- `--state FILE`: override the profile state file for apply.
- `--reset-state`: ignore saved execution state and start fresh for apply.
- `--verbose`: show additional command diagnostics.
- `--only TOOL`: include only a named tool for `plan`, `apply`, or `lock`;
  repeatable.
- `--skip TOOL`: omit a named tool for `plan`, `apply`, or `lock`; repeatable.

`apply` also accepts:

- `--locked`: require a compatible JSON lock file before applying.
- `--lock-file FILE`: path to the JSON lock file used by `--locked`.

`lock` also accepts:

- `--output FILE`: path to the JSON lock file to write. The default is
  `binstaller.lock.json`.

Exit codes:

- `0`: command completed successfully, including help and plan commands.
- `1`: manifest loading or resolution failed, selection was invalid, apply
  failed, or state persistence failed.
- `2`: command-line usage error.

## State And Resume

`apply` writes state after each per-tool result. State is tied to the profile
name and manifest fingerprint, so a later apply can skip tools already completed
for the same profile.

The state path comes from `--state` or `spec.policy.stateFile`. Current apply
state paths are current-directory filenames only; absolute, nested, and empty
paths are rejected. `plan` does not touch state.

Use `--reset-state` when you intentionally want to ignore compatible saved
state and retry from the beginning.

## Build From Source

Requirements:

- JDK 21+
- GraalVM 21 only when building native images locally

Common checks:

```bash
./mill config.test
./mill core.test
./mill cli.test
./mill __.compile
./mill __.test
./mill mill.scalalib.scalafmt/checkFormatAll
git diff --check
```

Build a native image locally when GraalVM is installed:

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
```

## Documentation

- [Architecture](docs/architecture.md): module graph, data flow, and event
  contract.
- [Manifest reference](docs/manifest-reference.md): supported profile shape,
  policy fields, versions, downloads, archives, symlinks, and selection.
- [Security model](docs/security.md): trust boundaries, checksums, archive
  safety, sudo policy, state rules, redaction, and known risks.
- [Testing guide](docs/testing.md): project-native checks and test patterns.
- [Release guide](docs/release.md): native artifacts, release workflow, and
  smoke checks.
