# Legacy Bootstrap Review

Reviewed source:
[`w0rxbend/system-bootstrap/scripts`](https://github.com/w0rxbend/system-bootstrap/tree/main/scripts)
on 2026-06-29.

This document maps the old shell-script bootstrap flow to Initkit config
capabilities. The goal is not to preserve every line as shell, but to identify
which actions deserve typed plan kinds so they can be previewed, selected in
TUI, resumed from state, logged, and tested.

## Summary

Already covered well:

- distro package installs through `apt-packages`, `pacman-packages`,
  `dnf-packages`, `zypper-packages`
- Flatpak app/plugin installs through `flatpak-packages`
- direct binary and archive downloads through `binary-downloads`
- URL-based installer scripts through `shell-scripts`
- Nerd Fonts through `nerd-fonts`
- dotfiles through `dotfiles-apply`
- one-off escape hatches through `commands`

Needs first-class or extended support:

- AUR installs with `paru` or `yay`
- package manager actions beyond install: update, upgrade, swap, group update,
  `pacman -Syu`, `zypper dup`, Packman vendor changes
- repo/key setup for RPM Fusion, Microsoft VS Code, Packman, and `opi codecs`
- `cargo-binstall` or `cargo install` packages
- SDKMAN candidate installs
- root-owned file writes with owner/group/mode
- structured command items for Git clones, Git config, login shell changes,
  user group changes, systemd services, and timedatectl configuration
- richer hardware/file/service conditions

## Script Mapping

| Old script | Key actions | Initkit mapping |
| --- | --- | --- |
| `arch/00-system-update.sh` | `pacman -Syu`, pacman packages, AUR packages, `chsh`, Oh My Zsh installer | extend pacman actions, add `aur-packages`, use structured `commands`, use `shell-scripts` |
| `arch/01-aur-packages.sh` | GPU checks, AUR tools, libvirt service, group membership, Podman, timedatectl | add hardware conditions, `aur-packages`, structured `commands` |
| `arch/02-base-packages.sh` | Hyprland stack, AUR AppImageLauncher, desktop tools | `pacman-packages` plus `aur-packages` |
| `arch/03-hyprland.sh` | Hyprland-specific setup | package kinds plus commands until stable typed actions emerge |
| `fedora/00-system-update.sh` | `dnf check-update`, upgrade, RPM Fusion release RPMs, PipeWire, shell change, Oh My Zsh | add dnf actions and URL package items, source setup, structured `commands`, `shell-scripts` |
| `fedora/01-packages.sh` | package groups, `dnf swap`, codecs, GPU packages, virtualization | add dnf action types: `install`, `groupinstall`, `groupupdate`, `swap` |
| `fedora/02-extras.sh` | Microsoft repo/key, VS Code, Podman, timedatectl | extend source setup, `dnf-packages`, structured `commands` |
| `opensuse/00-system-update.sh` | zypper refresh/update/dup, `opi codecs`, shell change, 1Password repo/package | add zypper actions, command-backed source helpers, structured `commands` |
| `opensuse/01-packages.sh` | Packman vendor change, patterns, GPU packages, many desktop packages | add zypper `dup --from`, pattern/package handling, hardware conditions |
| `opensuse/02-extras.sh` | Microsoft repo/key, VS Code, Podman/Docker compatibility, timedatectl | extend zypper source setup, structured `commands` |
| `opensuse/03-sddm.sh` | write SDDM Sway config, chown, restart SDDM | add `file-writes`, ownership support, structured `commands` |
| `binary-dist.sh` | download direct binaries, zip/tar archives, AppImage, symlinks into user/system paths | extend `binary-downloads` with archive variants and symlink operations |
| `cargo-packages.sh` | install Rust CLI tools through `cargo-binstall` | add `cargo-packages` |
| `cli-tools.sh` | rustup, SDKMAN, nvm, pyenv, Poetry, Starship, pnpm, Juliaup, dotenvx, Miniforge, uv, cargo-binstall | `shell-scripts`, plus `sdkman-packages` for SDKMAN candidates |
| `configurations.sh` | clone tmux TPM, Git config, timedatectl | structured `commands` with `creates`, `sudo`, and item-level conditions |
| `flatpak.sh` | categorized Flatpak apps | existing `flatpak-packages`, one app per command |
| `flatpak-obs-plugins.sh` | OBS Flatpak and plugin refs | existing `flatpak-packages`, one plugin per command |
| `install_golang.sh` | download Go tarball into user path, create workspace | `binary-downloads` archive install plus directory creation |
| `nerd-fonts.p0.sh` | clone upstream Nerd Fonts and run install script per family | replaced by `nerd-fonts` using `worxbend/nerd-font-installer` |
| `oh-my-zsh-plugins.sh` | clone zsh plugins | structured `commands` with `creates` |
| `sdkman-packages.sh` | install SDKMAN and SDK candidates | `shell-scripts` for SDKMAN bootstrap, then `sdkman-packages` |

## Config Improvements

Add these plan kinds:

- `aur-packages`
- `cargo-packages`
- `sdkman-packages`
- `file-writes`

Keep these operations in the existing `commands` kind unless later real-world
profiles prove that a dedicated kind is worth it:

- user groups
- user shell changes
- system service enable/start/restart
- Git repository clone/update
- Git config
- time configuration

Extend these existing areas:

- package managers: support explicit action lists in addition to `install`
- source setup: support key imports, repo files, repo commands, release RPM URLs
- binary downloads: support zip, `tar.gz`, `tar.xz`, AppImage, symlinks, cleanup
- shell scripts: support env, cwd, creates, unattended args, cleanup
- commands: support `cwd`, `env`, `sudo`, `creates`, `unless`,
  `allowedExitCodes`, `confirm`, `timeout`, `retries`, and item-level `when`
- conditions: support files, directories, services, users, groups, and command
  output predicates
- execution state: persist item-level results for all list-based installers

## Important Execution Rule

Every install-like list item must run as its own command. This applies to
package managers, AUR packages, Flatpak apps, SDKMAN candidates, cargo tools,
and Git repositories.

If one item fails, Initkit should still attempt later items in the same plan
entry, then report the entry as a partial failure. Global `continueOnError`
controls whether later top-level plan entries continue.

## Recommended Priority

1. Extend package-manager actions and source setup because they unblock distro
   examples.
2. Add `file-writes` because it replaces risky root-owned heredocs.
3. Strengthen `commands` for services, groups, shell changes, Git clones, Git
   config, and timedatectl.
4. Add `aur-packages`, `cargo-packages`, and `sdkman-packages` because they make
   developer-tool bootstrap pleasant.
5. Expand TUI rendering for dangerous actions, partial failures, and item-level
   resume.
