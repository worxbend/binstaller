# ❓ FAQ

---

### 🤔 Is this a package manager?

No, and it won't become one. There is no dependency graph, no repository index, no uninstall
verb, no post-install hooks. `binstaller` downloads binary tool distributions you named yourself,
verifies them, and puts them where you said.

If you want a package manager, use your package manager. `binstaller` is for the tools it doesn't
carry — the ones you currently install with a hand-written `curl | tar` block.

---

### 🆚 How is it different from just writing a shell script?

A shell script does the same downloads. It just doesn't:

- print a complete dry-run plan before touching anything
- verify SHA-256 and refuse to continue on mismatch
- confine archive members to a staging root
- re-validate every redirect hop against an SSRF guard
- record resolved versions, URLs, sizes and digests to a lock file
- resume from where the last run failed
- fail closed on non-interactive sudo

You can add all of that to a shell script. That's roughly what this is.

---

### 📜 Why are installer scripts rejected?

Because `curl … | bash` moves the trust boundary from "a manifest I can review" to "whatever the
vendor ships today." Once shell execution is in the manifest contract, so are inherited
environment, quoting bugs, cleanup semantics, timeouts and silent sudo.

Direct binary and archive downloads cover the overwhelming majority of CLI tool distributions,
and they are checkable.

---

### 🐧 Which platforms are supported?

The CLI ships as native images for **Linux** and **macOS**, on **amd64** and **arm64**.

Profiles can target either OS using `when:` clauses:

```yaml
when:
  os:
    family: linux
  architecture: amd64
```

Entries whose `when` clause doesn't match the host are skipped, so a single profile can carry
both platforms. Windows is not supported.

---

### ☕ Do I need a JVM?

No. Releases are GraalVM native images. You only need a JDK 21+ if you build from source, and
GraalVM 21 only if you build native images locally.

---

### 📁 Where do tools get installed?

Under `spec.policy.appsDir` — commonly `${HOME}/.apps` — with one directory per tool. Resolved
install directories must stay under `appsDir`. Nothing is installed system-wide unless you
explicitly declare a `sudo: true` symlink and set `policy.allowSudoSymlinks: true`.

---

### 🛣️ Does it modify my PATH?

No. Neither the CLI nor the install script touches your shell configuration by default. The
install script prints the `export PATH=...` line for you; set `BINSTALLER_UPDATE_PATH=1` if you
want it appended to `~/.bashrc` and `~/.zshrc`.

---

### 🗑️ How do I uninstall a tool?

Delete its directory under `appsDir` and remove the entry from the profile. There is no
`uninstall` command — install state is a directory, not a database.

---

### 🔄 How do I upgrade a tool?

Run `binstaller versions` to see what moved upstream, bump the version in `spec.versions`, update
or re-discover the checksum, then `binstaller apply`. The staged install replaces the previous one
atomically.

---

### 📌 Should I use `dynamic: latest-url`?

For a personal workstation, it's convenient. For anything reproducible, no — a dynamic source can
never be locked to a specific artifact, and `policy.mode: strict` rejects it by default. Pin the
version and let `binstaller versions` tell you when to bump.

---

### 🔐 What if upstream doesn't publish checksums?

Three options, in descending order of preference:

1. Use `checksum.discover` if they publish a `SHA256SUMS` file.
2. Download the artifact once, verify it however you can, and pin the digest yourself.
3. Leave it missing and stay in `developer` mode — the plan will flag it on every run.

---

### 🤖 Can I run it in CI?

Yes, and it's designed for it: script-friendly plain output when stdout isn't a terminal,
predictable exit codes, and `plan --locked` as a drift check that installs nothing.

```yaml
- run: binstaller plan --locked --lock-file binstaller.lock.json
- run: binstaller apply --locked --lock-file binstaller.lock.json
```

Non-interactive runs fail closed on sudo rather than hanging on a prompt.

---

### ⚡ Do downloads run in parallel?

Yes — concurrent downloads render as a redrawn progress block with one row per tool. Installs are
still ordered and each tool's result is committed to state as it completes.

---

### 🧊 What exactly does a lock file pin?

Resolved version, initial URL, final URL, the full redirect chain, size in bytes, the SHA-256
digest and its provenance (`configured` vs `discovered`), plus the manifest fingerprint. See
🧊 [Lock Files & Reproducibility](Lock-Files-and-Reproducibility).

---

### 🔁 Is apply idempotent?

Re-applying a compatible profile skips tools already recorded as complete in state. With
`--reset-state`, everything is downloaded and installed again, replacing the previous install
directory atomically.

---

### 🧩 Can one profile cover a whole team?

That's the intended use: commit `config.yaml` and `binstaller.lock.json` together, and have
everyone run `binstaller apply --locked`. Anyone whose environment would resolve to something
different gets a refusal instead of a silently different toolchain.

---

### 🛠️ Can I contribute?

Yes — see 🛠️ [Development](Development). Bug reports with a minimal profile that reproduces the
problem are especially welcome.
