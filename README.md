# binstaller

[![Native Release](https://github.com/worxbend/initkit/actions/workflows/native-release.yml/badge.svg)](https://github.com/worxbend/initkit/actions/workflows/native-release.yml)
![Scala 3](https://img.shields.io/badge/Scala-3.8.2-dc322f?logo=scala&logoColor=white)
![Mill](https://img.shields.io/badge/Mill-1.1.7-5b5bd6)
![GraalVM](https://img.shields.io/badge/GraalVM-native%20ready-f2a900?logo=graalvm&logoColor=111111)
![Linux](https://img.shields.io/badge/Linux-amd64%20binary%20installer-2ea44f?logo=linux&logoColor=white)

`binstaller` installs Linux amd64 binary tool distributions from one YAML
profile. It resolves versions, previews what will be downloaded and unpacked,
then installs selected tools under a user-controlled apps directory such as
`${HOME}/.apps`.

The supported scope is deliberately narrow: direct binary downloads, `zip`,
`tar.gz`, and `tar.xz` archives, executable checks, local symlinks, optional
sudo symlinks, dry-run previews, apply state/resume, CLI progress, explicit TUI
entrypoints, and version reporting. It is not a package manager, dotfiles
runner, installer-script host, broad shell-command runner, or multi-OS
workstation provisioner.

## Install

Native Linux amd64 builds are published from `v*` tags by GitHub Actions.

```bash
curl -L -o binstaller \
  https://github.com/worxbend/initkit/releases/latest/download/binstaller-linux-amd64
chmod +x binstaller
sudo mv binstaller /usr/local/bin/binstaller
```

Release artifacts:

- `binstaller-linux-amd64`
- `binstaller-linux-amd64.tar.gz`
- `SHA256SUMS`

## Quick Start

Use the checked-in [config.example.yaml](config.example.yaml) as the reference
profile. It uses:

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile
```

Preview the resolved plan:

```bash
binstaller plan --config config.example.yaml
```

Render the apply operations without changing files:

```bash
binstaller apply --config config.example.yaml --dry-run
```

Apply the profile. `--yes` is required when the profile has
`requireConfirmation: true`.

```bash
binstaller apply --config config.example.yaml --yes
```

Select or omit tools by name. These flags may be repeated.

```bash
binstaller plan --config config.example.yaml --only yazi
binstaller apply --config config.example.yaml --skip neovim --dry-run
```

Inspect pinned, resolved, and dynamic version sources:

```bash
binstaller versions --config config.example.yaml
```

Source-development equivalents use the checked-in Mill launcher:

```bash
./mill app.run --help
./mill app.run plan --config config.example.yaml
./mill app.run apply --config config.example.yaml --dry-run
./mill app.run versions --config config.example.yaml
```

## CLI Surface

Top-level commands:

- `plan`: render the binary installer plan without changing files.
- `apply`: run the installer, or render operations with `--dry-run`.
- `versions`: resolve and print manifest version sources.

Useful shared options:

- `--config FILE`: path to the YAML profile; required for `plan`, `apply`, and
  `versions`.
- `--state FILE`: override the profile state file.
- `--reset-state`: ignore saved execution state and start fresh.
- `--verbose`: show additional command diagnostics.
- `--only TOOL`: include only a named tool for `plan` or `apply`; repeatable.
- `--skip TOOL`: omit a named tool for `plan` or `apply`; repeatable.

`apply` also accepts:

- `--dry-run`: render concrete apply operations without downloads, install
  writes, symlink writes, or state writes.
- `--yes`: confirm non-dry-run apply actions, including sudo symlinks.

Exit codes:

- `0`: command completed successfully, including help and dry-run commands.
- `1`: manifest loading or resolution failed, selection was invalid,
  confirmation was missing, apply failed, or state persistence failed.
- `2`: command-line usage error, including a missing required `--config`.

## State And Resume

Non-dry-run `apply` writes state after each terminal tool result. State is tied
to the profile name and manifest fingerprint, so a later apply can skip tools
already completed for the same profile.

The state path comes from `--state` or `spec.policy.stateFile`. Current apply
state paths are current-directory filenames only; absolute, nested, and empty
paths are rejected. `plan` and `apply --dry-run` do not touch state.

Use `--reset-state` when you intentionally want to ignore compatible saved
state and retry from the beginning.

## TUI

The TUI is explicit and optional. Default command output remains
script-friendly.

```bash
binstaller plan --config config.example.yaml --tui
binstaller apply --config config.example.yaml --dry-run --tui
binstaller apply --config config.example.yaml --tui --yes
```

`plan --tui` opens the planning view. `apply --tui` opens the execution view.
In non-interactive shells, the TUI renders a static fallback frame instead of
entering raw mode or the alternate screen.

## Build From Source

Requirements:

- JDK 21+
- GraalVM 21 only when building native images locally

Common checks:

```bash
./mill config.test
./mill core.test
./mill cli.test
./mill tui.test
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
- [TUI guide](docs/tui-guide.md): planning/execution views, keybindings, and
  terminal troubleshooting.
- [TUI smoke workflow](docs/tui-smoke.md): manual terminal smoke steps.
- [Testing guide](docs/testing.md): project-native checks and test patterns.
- [Release guide](docs/release.md): native artifacts, release workflow, and
  smoke checks.
- [Post-TUI readiness review](docs/post-tui-readiness-review.md): hardening
  findings, implemented fixes, and documented deferrals.
