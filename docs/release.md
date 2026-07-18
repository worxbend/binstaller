# Release Guide

Date: 2026-07-18

Releases are GraalVM native binaries produced by
`.github/workflows/release.yml`, built for Linux amd64/arm64 and macOS
amd64/arm64.

## Artifacts

For each target the release workflow publishes a versioned tarball plus a
per-file SHA-256 checksum and a keyless Sigstore bundle:

- `binstaller-<version>-<target>.tar.gz`
- `binstaller-<version>-<target>.tar.gz.sha256`
- `binstaller-<version>-<target>.tar.gz.sigstore.json`
- `config.example.<version>.yaml` (with matching `.sha256` and `.sigstore.json`)
- `install.sh`

Targets are `linux-amd64`, `linux-arm64`, `macos-amd64`, and `macos-arm64`.
The workflow is triggered by `v*` tags or manual `workflow_dispatch` with a
tag input.

## Workflow Summary

The GitHub Actions build matrix, per target:

1. Checks out the repository.
2. Sets up GraalVM 21 with `native-image`.
3. Runs `java -version` and `native-image --version`.
4. Runs `./mill __.test`.
5. Builds `./mill app.nativeImage`.
6. Copies the native executable into a `binstaller-<version>-<target>` tarball.
7. Smokes native `--help`, `plan`, and `versions`.
8. Writes a per-file `.sha256` checksum.
9. Uploads the tarball as a build artifact.

The publish job then:

1. Downloads all target artifacts.
2. Re-verifies the checksums.
3. Signs every artifact with keyless Sigstore (`cosign sign-blob`, using the
   workflow's GitHub OIDC identity via `id-token: write`), producing a
   `.sigstore.json` bundle per artifact.
4. Publishes the GitHub Release assets.

All GitHub Actions are pinned to commit SHAs, and Mill is pinned via
`.mill-version`.

## Pre-Release Checks

Before tagging, run:

```bash
./mill config.test
./mill core.test
./mill cli.test
./mill __.compile
./mill __.test
./mill app.run --help
./mill app.run plan --config config.example.yaml
./mill app.run versions --config config.example.yaml
./mill app.run lock --config config.example.yaml --output /tmp/binstaller.lock.json
./mill mill.scalalib.scalafmt/checkFormatAll
git diff --check
```

## Native Smoke Checks

When `native-image` is available locally:

```bash
GRAALVM_HOME=/path/to/graalvm ./mill app.nativeImage
native_path="$(find out/app/nativeImage.dest -maxdepth 1 -type f -name native-executable -print -quit)"
"$native_path" --help
"$native_path" plan --config config.example.yaml
"$native_path" versions --config config.example.yaml
"$native_path" lock --config config.example.yaml --output /tmp/binstaller.lock.json
```

If local native image is blocked, record `command -v native-image` and
`java -version` output, then rely on the GitHub workflow for the native build.

## Release Smoke After Publish

After a release is published:

```bash
# Simplest path: the install script verifies the checksum and, when cosign is
# present, the keyless Sigstore signature before installing.
curl --proto '=https' --tlsv1.2 -sSfL \
  https://github.com/worxbend/binstaller/releases/latest/download/install.sh | sh

# Or verify a single target tarball manually:
target="linux-amd64"
base="https://github.com/worxbend/binstaller/releases/latest/download"
mkdir -p dist && cd dist
version="$(basename "$(curl -fsSL -o /dev/null -w '%{url_effective}' \
  https://github.com/worxbend/binstaller/releases/latest)")"
archive="binstaller-${version}-${target}.tar.gz"
curl -fsSL -o "${archive}" "${base}/${archive}"
curl -fsSL -o "${archive}.sha256" "${base}/${archive}.sha256"
sha256sum --check "${archive}.sha256"
tar -xzf "${archive}"
"binstaller-${version}-${target}/binstaller" --help
```

Do not run apply against a real profile during release smoke unless the profile
uses an isolated temporary `appsDir`, a disposable current-directory state
filename, and the install side effects are intentional.

## Rollback Notes

If a release is bad:

- Stop any rollout automation that downloads `releases/latest`.
- Delete or mark the GitHub Release as prerelease if the tag should no longer
  be promoted.
- Publish a fixed patch tag rather than mutating a release that users may have
  checksummed.
- Keep the bad artifact checksum in incident notes for user verification.
- If the issue is manifest compatibility rather than binary behavior, document
  the required manifest change and link to the fixed docs.

Runtime install rollback is handled per tool by the staged replacement layer:
if replacing an existing install fails after the old install was moved aside,
the filesystem layer attempts to restore the previous install and reports any
rollback failure. Apply state may still record terminal failures, so retry with
`--reset-state` only after confirming the manifest and install directories are
safe.
