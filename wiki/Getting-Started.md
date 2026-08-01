# 🚀 Getting Started

From nothing installed to a verified toolchain in about five minutes.

---

## 1️⃣ Install the binary

```bash
curl --proto '=https' --tlsv1.2 -sSfL \
  https://github.com/worxbend/binstaller/releases/latest/download/install.sh | sh
```

The script:

1. Downloads the release tarball for your platform.
2. Verifies its SHA-256 checksum against the published `.sha256` file.
3. Additionally verifies the keyless Sigstore signature **when `cosign` is installed**.
4. Installs `binstaller` into `~/.local/bin`.

| Environment variable | Effect |
|---|---|
| `BINSTALLER_INSTALL_DIR` | Install somewhere other than `~/.local/bin`. |
| `BINSTALLER_VERSION` | Pin a release tag, e.g. `v0.2.0`. Default: latest. |
| `BINSTALLER_UPDATE_PATH=1` | Append the install directory to existing `~/.bashrc` and `~/.zshrc`. |

> ⚠️ By default your shell configuration is **not** modified. The script prints the
> `export PATH=...` line for you to run or add yourself.

**Prefer to do it by hand?** Grab the tarball, the `.sha256` file, and check it yourself:

```bash
V=v0.2.0
curl -sSfLO https://github.com/worxbend/binstaller/releases/download/$V/binstaller-$V-linux-amd64.tar.gz
curl -sSfLO https://github.com/worxbend/binstaller/releases/download/$V/binstaller-$V-linux-amd64.tar.gz.sha256
sha256sum -c binstaller-$V-linux-amd64.tar.gz.sha256
tar -xzf binstaller-$V-linux-amd64.tar.gz
```

Verify it works:

```bash
binstaller --version
binstaller --help
```

---

## 2️⃣ Get a profile

The fastest start is the checked-in example, which covers direct binaries, all three archive
types, dynamic version endpoints, HTTP version resolvers and symlinks:

```bash
curl -sSfLO https://raw.githubusercontent.com/worxbend/binstaller/main/config.example.yaml
cp config.example.yaml config.yaml
```

Or write a minimal one yourself — this is a complete, working profile:

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile

metadata:
  name: my-tools

spec:
  policy:
    mode: developer
    appsDir: "${HOME}/.apps"
    allowSudoSymlinks: false
    stateFile: my-tools.state.json

  versions:
    lazygit: 0.61.0

  plan:
    - name: lazygit
      kind: binary-tool
      description: Terminal UI for git.
      when:
        os:
          family: linux
        architecture: amd64
      spec:
        versionRef: lazygit
        installDir: "${appsDir}/lazygit"
        download:
          url: "https://github.com/jesseduffield/lazygit/releases/download/v${version}/lazygit_${version}_Linux_x86_64.tar.gz"
          filename: lazygit.tar.gz
          archive:
            type: tar.gz
            extract:
              files:
                - from: lazygit
                  to: bin/lazygit
        executables:
          - path: bin/lazygit
```

---

## 3️⃣ Read the plan

```bash
binstaller plan
```

`plan` writes **nothing**. It prints, per tool:

- the resolved destination directory
- the resolved version, and whether it is concrete or dynamic
- the exact download URL and the file it lands in
- the checksum status — configured, discovered, or **missing**
- the archive type and every extract mapping
- every executable that will be verified
- every symlink, with `[local]` or `[sudo risk]` on each

The header also tells you the total tool count, the apps directory, the policy mode, the state
file, whether a lock file is required, and whether any sudo commands are involved.

> 💡 Read the `sudo risk:` line before your first apply. If it says `YES`, the plan lists exactly
> which privileged `ln -sfn` commands will run.

---

## 4️⃣ Apply

```bash
binstaller apply
```

Per tool, apply resolves, downloads (with a live progress bar per concurrent download), verifies
the checksum, stages into a private directory, applies modes, replaces the previous install,
verifies executables, creates symlinks, and saves state.

The run finishes with a summary:

```text
✨ Summary
  ✅ installed: 3
  ❌ failed: 0
  ⏭ skipped: 0
  🚦 exit code: 0
  🎉 apply completed successfully
```

---

## 5️⃣ Put the tools on your PATH

`binstaller` installs under `appsDir` and does not edit your shell configuration. Add the
`bin` directories you care about, for example:

```bash
export PATH="$HOME/.apps/lazygit/bin:$PATH"
```

Or declare local symlinks into a single directory you already have on `PATH`:

```yaml
symlinks:
  - path: bin/lzg
    target: bin/lazygit
```

---

## 6️⃣ Keep it honest over time

```bash
binstaller versions     # what is pinned vs. the newest upstream release
binstaller plan         # re-read before every apply
binstaller lock         # freeze the resolved toolchain
```

---

## ⏭️ Next

- 🍳 [Manifest Cookbook](Manifest-Cookbook) — recipes for real tools
- 🧊 [Lock Files & Reproducibility](Lock-Files-and-Reproducibility) — pin it for a team
- 🔐 [Security Model](Security-Model) — what is trusted and what is not
