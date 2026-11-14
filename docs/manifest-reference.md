# Manifest Reference

Date: 2026-06-30

Supported identity:

```yaml
apiVersion: binstaller.io/v1alpha1
kind: BinaryDistributionProfile
```

The manifest is a single profile with metadata, policy, variables, version
sources, and an ordered `spec.plan`.

## Top-Level Shape

```yaml
metadata:
  name: developer-binaries
  labels:
    role: development
  annotations:
    binstaller.io/description: Example profile.

spec:
  policy:
    mode: developer
    appsDir: "${HOME}/.apps"
    continueOnError: false
    allowSudoSymlinks: true
    stateFile: developer-binaries.state.json
  vars:
    arch: amd64
  versions:
    kubectl:
      resolver:
        type: http-text
        url: https://dl.k8s.io/release/stable.txt
  plan: []
```

`metadata.name` participates in state compatibility. The ordered `plan` order is
the default render and apply order.

## YAML Dialect

The loader reads one YAML 1.2 document with the Core schema:

- `null`, `Null`, `NULL`, `~` and an empty value are null. A null value is not
  the same as a missing key: `stateFile: ~` is rejected with
  `value must be a string`.
- `true` and `false`, also as `True`/`TRUE` and `False`/`FALSE`, are booleans.
  `yes`, `no`, `on` and `off` are plain text.
- An unquoted number is a number, and a number is rejected where text is
  expected. Quote versions and modes: `"1.20"`, `"0755"`. Unquoted `0755` is
  the number 755.

Anchors and aliases are supported. Merge keys are not: `<<` is an ordinary
key, so `<<: *defaults` merges nothing and fails validation as an unknown or
wrong-typed field, and an explicit `!!merge` tag is a parse error. Duplicate keys and a second document in the
same file are parse errors. A file larger than 8 MiB is rejected before it is
parsed, and so is a document longer than 2,097,152 characters (code points) or
a line longer than 65,536 characters.

## Policy

- `mode`: optional policy profile. Defaults to `developer`. Supported values
  are `developer` and `strict`.
- `appsDir`: root directory that resolved install directories must stay under.
- `continueOnError`: when `true`, apply continues after a failed tool and still
  exits nonzero if any tool failed.
- `allowSudoSymlinks`: must be `true` before any plan entry may declare
  `sudo: true` symlinks.
- `allowDynamicLatestUrls`: in `strict` mode, set to `true` to permit
  `dynamic.latest-url` version sources and download URLs containing `/latest`.
  `developer` mode allows them by default; an explicit `false` rejects them in
  either mode.
- `allowMissingChecksums`: in `strict` mode, set to `true` to permit plan
  entries with no `download.checksum`. `developer` mode allows them by default;
  an explicit `false` rejects them in either mode.
- `stateFile`: optional current-directory filename used by apply resume.

`spec.policy` accepts only these keys: `mode`, `continueOnError`, `appsDir`,
`allowSudoSymlinks`, `allowDynamicLatestUrls`, `allowMissingChecksums`, and
`stateFile`. Any other key is a validation error reported as
`spec.policy.<key>: unknown field '<key>'`. There are no keys that are accepted
but ignored for compatibility.

Developer mode preserves the historical behavior for local tooling profiles:
dynamic latest URLs and missing checksums are allowed by default. Sudo symlinks
still require `allowSudoSymlinks: true`.

Strict mode rejects production-sensitive risks by default:

- `dynamic.latest-url` version sources and download URLs containing `/latest`.
- Missing `download.checksum` values.
- `sudo: true` symlinks unless `allowSudoSymlinks: true`.

Strict profiles can opt into reviewed exceptions with explicit booleans:

```yaml
policy:
  mode: strict
  allowDynamicLatestUrls: true
  allowMissingChecksums: true
  allowSudoSymlinks: true
```

State files are not written by `plan`. Apply rejects absolute, nested, or empty
state paths.

## Variables And Versions

Interpolation supports literal `${name}` references. Shell forms such as
`$(cmd)` are not executed.

Available variables include a fixed allowlist of non-secret runtime environment
variables (`HOME`, `USER`, `LOGNAME`, `SHELL`, and the `XDG_*` base directories),
manifest `spec.vars`, policy-derived `appsDir`, tool-local `version`,
`installDir`, and `tool`. Arbitrary process environment values are not
exposed to interpolation, so a manifest cannot read secrets out of the
environment into a resolved URL.

Variable names use letters, digits, and `_`. A `${...}` that is still present
after interpolation is an error, never literal text: that covers a name with
other characters, such as `${linux-arch}`, and a `spec.vars` entry that
references another entry whose own value is a template. A `spec.vars` entry may
reference a runtime variable (`root: "${HOME}/opt"`), but a second entry cannot
build on it (`apps: "${root}/apps"`).

`appsDir` and `installDir` must not start with `~`; nothing expands it. Use
`${HOME}`. A `sudo: true` symlink `path` must be absolute.

Version sources:

```yaml
versions:
  yazi: v26.5.6

  kubectl:
    resolver:
      type: http-text
      url: https://dl.k8s.io/release/stable.txt

  neovim:
    dynamic:
      type: latest-url
      note: GitHub latest Neovim Linux archive endpoint.
```

- A string value is a pinned version.
- `resolver.type: http-text` fetches HTTPS text and uses it as the concrete
  version.
- `dynamic.type: latest-url` intentionally keeps the version displayed as
  `dynamic latest-url`.
- `versions` detects GitHub release download URLs such as
  `https://github.com/jj-vcs/jj/releases/download/v${version}/...`, queries the
  repository's latest release tag, and prints the newer version in the package
  summary table when an update is available.

## Plan Entries

Each `spec.plan` item accepts these keys:

- `name` (required): the tool's identity. It is what `--only` and `--skip`
  select, and it keys the rows of the state file and the lock file. It must not
  be empty, `.` or `..`, must not start with `-`, and must not contain path
  separators, control characters, whitespace or commas. `--only` and `--skip`
  split their values on whitespace and commas and read a value that starts
  with `-` as an option, so such a name could never be selected.
- `kind` (required): `binary-tool`.
- `description`: optional text.
- `when`: optional host selector.
- `spec` (required): `versionRef`, `installDir`, `download` and `executables`
  are required. `createDirectories` and `symlinks` are optional.

State and lock files are read with the same name rule. A file written while a
tool had a space or a comma in its name, or a name starting with `-`, no
longer loads: rename the tool, run `apply --reset-state`, and write the lock
file again with `binstaller lock`.

`when` limits an entry to matching hosts. An entry that does not match the host
is left out of the plan. `--skip` accepts its name; `--only` reports it as
excluded for this host.

```yaml
when:
  os:
    family: linux
  architecture: amd64
```

- `os.family`: exactly one of `linux`, `darwin` (also `macos`, `mac`, `osx`)
  or `windows`. Case and surrounding spaces are ignored. A value naming two
  families, such as `linux, darwin`, or a longer name such as `Mac OS X`, is
  rejected.
- `architecture`: `amd64` (also `x86_64`, `x64`), `arm64` (also `aarch64`) or
  `"386"` (also `x86`, `i386`, `i486`, `i586`, `i686`). Case is ignored. Quote
  `"386"`; unquoted it is a number.

Any other value is a validation error: it could match no host, and the tool
would be missing from every plan.

`createDirectories` lists directories, relative to `installDir`, that are
created in the install before the downloaded files are placed:

```yaml
spec:
  createDirectories:
    - share/completions
```

## Direct Binary

A direct binary has no `archive` block. The downloaded bytes are written to the
first executable path under the install directory, mode is applied, executables
are verified, then the staged install replaces the previous install.

```yaml
plan:
  - name: kubectl
    kind: binary-tool
    description: Kubernetes command-line client.
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

`mode` applies to declared executables only. The install directory and the
directories inside it get the mode a plain `mkdir` would give them, so the umask
of the account running `apply` decides who else can use the tool.

## Archives

Supported archive types are `zip`, `tar.gz`, and `tar.xz`.

All three types use native, in-process extraction. Each archive member name is
normalized and confined to a staging directory before any bytes are written, and
only declared mapped members are copied into the install directory. Extraction
also enforces an aggregate expanded-byte budget to bound decompression bombs.

File mapping example:

```yaml
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

Directory mapping example:

```yaml
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

Archive `from` paths identify archive members. `to` paths are relative install
targets. Absolute paths, traversal, backslashes, control characters, duplicate
sources, and duplicate targets are rejected.

A directory mapping may use `.` on either side. `from: "."` is the archive root:
it maps every member, which is how an archive with no top-level directory is
installed whole. `to: "."` is the install directory. A file mapping names a file
on both sides, so `.` as its `from` or `to` is a validation error.

## Checksums

Only SHA-256 is supported:

```yaml
checksum:
  algorithm: sha256
  value: 45d49e064d8684926fed97ad051c6ecebbf796a3c709edaa7a4a166b2978633d
```

Configured SHA-256 values must be exactly 64 hexadecimal characters. Apply
verifies checksums before staging replacement. Missing checksums remain allowed
in developer mode, but plan surfaces mark them as risk. Strict mode rejects
missing checksums unless `policy.allowMissingChecksums: true` is set.

Profiles can also opt into checksum discovery from an upstream-published
`sha256sum` file:

```yaml
checksum:
  algorithm: sha256
  discover:
    type: sha256sum
    url: "https://example.invalid/releases/${version}/SHA256SUMS"
    file: "tool-${version}-linux-amd64.tar.gz"
```

`discover.type: sha256sum` fetches the HTTPS text file through the built-in text
client and parses standard `<sha256> <file>` lines. `file` is optional; when it
is omitted, the resolved `download.filename` is matched. Discovery is data-only:
it does not run shell commands. Plan, `versions`, lock output, and checksum
mismatch diagnostics label checksums as configured, discovered, or missing.

## Symlinks

Local symlinks stay within the install directory:

```yaml
symlinks:
  - path: bin/lzg
    target: bin/lazygit
```

Sudo symlinks require `policy.allowSudoSymlinks: true`:

```yaml
symlinks:
  - path: /usr/local/bin/nvim
    target: "${appsDir}/neovim/bin/nvim"
    sudo: true
```

Apply executes privileged symlinks as structured argv equivalent to
`sudo ln -sfn <target> <path>`.

## Selection

Selection is a command option, not a manifest field.

```bash
binstaller plan --only yazi
binstaller apply --skip neovim
binstaller apply --only yazi,lazygit
binstaller apply --only yazi lazygit --skip lazygit
```

`--only` and `--skip` each accept a comma- or space-separated list of tool
names, and either flag may also be repeated; all values from every occurrence
are combined. `--only` is applied first, `--skip` is applied second, and
manifest order is preserved. Unknown names are selection errors.

The whole manifest is still loaded and validated when it is read, so an
error found at load time, such as an unknown `versionRef`, fails the run even
for a deselected entry.

Selection runs before resolution. A deselected entry is not resolved: its
version source and checksum discovery are not fetched, and its resolution
errors cannot fail the run. The strict-policy checks cover only the selected
entries. Run `plan` without `--only` or `--skip` to resolve the whole manifest.

Install directories are still compared. A deselected entry's `installDir` is
worked out without a network request: `${version}` takes the pinned value
when the version source is pinned, and a stand-in otherwise. A selected tool
whose `installDir` equals, sits inside, or contains the `installDir` of a
deselected tool fails the run, because installing it would replace the other
tool. A deselected `installDir` that cannot be worked out is left out of the
comparison, and an overlap between two deselected tools is not reported.

Names are matched against every `spec.plan` entry, including entries that a
`when` selector excludes on this host. `--skip` accepts such a name, so one
command works on every platform. `--only` rejects it with
`tool '<name>' is excluded for this host (<os>/<architecture>) by its when selector`.

## Unsupported Fields

Installer script blocks are not supported:

```yaml
installer:
  shell: bash
```

The loader rejects installer blocks with a validation error. Use a direct binary
or archive-backed download instead.
