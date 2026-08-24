# 🍳 Manifest Cookbook

Copy-paste recipes for real tools. The authoritative field list lives in
[`docs/manifest-reference.md`](https://github.com/worxbend/binstaller/blob/main/docs/manifest-reference.md);
this page is the practical companion.

---

## 🧱 Profile skeleton

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile

metadata:
  name: developer-binaries          # participates in state compatibility
  labels:
    role: development

spec:
  policy:
    mode: developer                 # or: strict
    appsDir: "${HOME}/.apps"        # install roots must stay under this
    continueOnError: false
    allowSudoSymlinks: false
    stateFile: developer-binaries.state.json   # cwd filename only

  vars:
    arch: amd64
    linuxArch: x86_64

  versions: {}
  plan: []
```

---

## 📌 Version sources

### Pinned (best)

```yaml
versions:
  lazygit: 0.61.0
  helm: v3.21.2
```

### Resolved from an HTTPS text endpoint

```yaml
versions:
  kubectl:
    resolver:
      type: http-text
      url: https://dl.k8s.io/release/stable.txt
```

The fetched text becomes the concrete `${version}`.

### Dynamic "latest" endpoints

```yaml
versions:
  neovim:
    dynamic:
      type: latest-url
      note: GitHub latest Neovim Linux x86_64 archive endpoint.
```

The URL itself resolves the version at download time, so the reported version stays
`dynamic latest-url`. 🚫 Rejected in `strict` mode unless `policy.allowDynamicLatestUrls: true`.

---

## 📥 Recipe: direct binary

No `archive` block — the downloaded bytes become the executable.

```yaml
plan:
  - name: kubectl
    kind: binary-tool
    description: Kubernetes command-line client.
    when:
      os:
        family: linux
      architecture: amd64
    spec:
      versionRef: kubectl
      installDir: "${appsDir}/kubectl"
      download:
        url: "https://dl.k8s.io/release/${version}/bin/linux/amd64/kubectl"
        filename: kubectl
      executables:
        - path: bin/kubectl
          mode: "0755"
```

---

## 🗜️ Recipe: `tar.gz` with a file mapping

```yaml
- name: helm
  kind: binary-tool
  spec:
    versionRef: helm
    installDir: "${appsDir}/helm"
    download:
      url: "https://get.helm.sh/helm-${version}-linux-amd64.tar.gz"
      filename: helm-linux-amd64.tar.gz
      checksum:
        algorithm: sha256
        value: 0a745198de24545d0055cd8414bc8d2ba10363ef5f5d38369ea1b399671cc083
      archive:
        type: tar.gz
        extract:
          files:
            - from: linux-amd64/helm
              to: bin/helm
    executables:
      - path: bin/helm
```

---

## 📁 Recipe: archive with a directory mapping

When the tool ships a whole tree that must stay together:

```yaml
- name: neovim
  kind: binary-tool
  spec:
    versionRef: neovim
    installDir: "${appsDir}/neovim"
    download:
      url: https://github.com/neovim/neovim/releases/latest/download/nvim-linux-x86_64.tar.gz
      filename: neovim.tar.gz
      archive:
        type: tar.gz
        extract:
          directories:
            - from: nvim-linux-x86_64
              to: "."
    executables:
      - path: bin/nvim
```

---

## 🤐 Recipe: `zip` with several executables

```yaml
- name: yazi
  kind: binary-tool
  spec:
    versionRef: yazi
    installDir: "${appsDir}/yazi"
    download:
      url: "https://github.com/sxyazi/yazi/releases/download/${version}/yazi-${gnuTarget}.zip"
      filename: yazi.zip
      archive:
        type: zip
        extract:
          files:
            - from: "yazi-${gnuTarget}/yazi"
              to: bin/yazi
            - from: "yazi-${gnuTarget}/ya"
              to: bin/ya
    executables:
      - path: bin/yazi
      - path: bin/ya
```

Supported archive types: `zip`, `tar.gz`, `tar.xz`. All are extracted natively in-process —
no `unzip`, `tar` or shell involved. Member names are normalized and confined to a private
staging directory, and only declared mappings are copied into the install directory.

---

## 🔐 Recipe: checksums

### Pinned by you

```yaml
checksum:
  algorithm: sha256
  value: 45d49e064d8684926fed97ad051c6ecebbf796a3c709edaa7a4a166b2978633d
```

Values must be exactly 64 hexadecimal characters. Only `sha256` is supported.

### Discovered from an upstream `SHA256SUMS` file

```yaml
checksum:
  algorithm: sha256
  discover:
    type: sha256sum
    url: "https://example.com/releases/${version}/SHA256SUMS"
    file: "tool-${version}-linux-amd64.tar.gz"      # optional
```

Standard `<sha256>  <file>` lines are parsed. When `file` is omitted, the resolved
`download.filename` is matched. Discovery is data-only — no shell commands are run.

Plan, `versions`, lock output and mismatch diagnostics all label a checksum as
**configured**, **discovered** or **missing**.

---

## 🔗 Recipe: symlinks

### Local (default, no privileges)

```yaml
symlinks:
  - path: bin/lzg
    target: bin/lazygit
```

Local symlinks must stay within the install directory.

### System-wide (requires opt-in)

```yaml
spec:
  policy:
    allowSudoSymlinks: true
...
      symlinks:
        - path: /usr/local/bin/nvim
          target: "${appsDir}/neovim/bin/nvim"
          sudo: true
```

Without `policy.allowSudoSymlinks: true` the profile is **rejected at load time**.
Every privileged link is flagged `[sudo risk]` in the plan and executed as structured argv
equivalent to `sudo ln -sfn <target> <path>`.

---

## 🧭 Recipe: host conditions

```yaml
when:
  os:
    family: linux
  architecture: amd64
```

Entries whose `when` clause doesn't match the host are skipped during resolution. Use it to keep
Linux and macOS entries in one profile.

---

## 🔤 Interpolation

Literal `${name}` only. Shell forms such as `$(cmd)` are **not** executed.

| Available | Source |
|---|---|
| `${HOME}`, `${USER}`, `${LOGNAME}`, `${SHELL}`, `${XDG_*}` | A fixed allowlist of non-secret environment variables. |
| Anything under `spec.vars` | Your manifest. |
| `${appsDir}` | `policy.appsDir`. |
| `${version}` | The resolved `versionRef`. |
| `${installDir}`, `${downloadPath}`, `${tool}` | Per-tool context. |

Arbitrary process environment values are **not** exposed, so a manifest cannot read a secret out
of the environment and into a resolved URL.

---

## 🧱 Strict mode

```yaml
policy:
  mode: strict
```

Rejects, by default:

- `dynamic.latest-url` version sources and download URLs containing `/latest`
- missing `download.checksum` values
- `sudo: true` symlinks
- archive candidate fallback extraction

Reviewed exceptions are explicit:

```yaml
policy:
  mode: strict
  allowDynamicLatestUrls: true
  allowMissingChecksums: true
  allowSudoSymlinks: true
```

---

## 🚫 What will be rejected

| You wrote | What happens |
|---|---|
| `installer:` block | ❌ `installer scripts are not supported; use direct binary or archive download` |
| `http://` URL | ❌ HTTPS is required. |
| Duplicate tool names | ❌ Load-time validation error. |
| `versionRef` with no matching entry | ❌ Load-time validation error. |
| Archive `to:` with `..`, absolute path, or backslash | ❌ Rejected before extraction. |
| Duplicate archive `from:` or `to:` | ❌ Rejected. |
| Checksum that isn't 64 hex characters | ❌ Rejected. |
| `sudo: true` without `allowSudoSymlinks` | ❌ Rejected. |
| Absolute or nested `stateFile` | ❌ Rejected — current-directory filenames only. |
