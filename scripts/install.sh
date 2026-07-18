#!/bin/sh
# Install the binstaller CLI from GitHub Releases.
#
# Usage:
#   curl --proto '=https' --tlsv1.2 -sSfL \
#     https://github.com/worxbend/binstaller/releases/latest/download/install.sh | sh
#
# Environment variables:
#   BINSTALLER_VERSION      Release tag to install, e.g. v0.3.0 (default: latest)
#   BINSTALLER_INSTALL_DIR  Directory to install the binary into (default: $HOME/.local/bin)

set -eu

REPO="worxbend/binstaller"
VERSION="${BINSTALLER_VERSION:-latest}"
INSTALL_DIR="${BINSTALLER_INSTALL_DIR:-${HOME}/.local/bin}"

info() {
  printf '%s\n' "$*"
}

die() {
  printf 'error: %s\n' "$*" >&2
  exit 1
}

require() {
  command -v "$1" >/dev/null 2>&1 || die "required command not found: $1"
}

require curl
require tar
require uname
require mktemp

os="$(uname -s)"
case "${os}" in
  Linux) ;;
  *) die "unsupported OS: ${os} (only Linux is supported)" ;;
esac

arch="$(uname -m)"
case "${arch}" in
  x86_64 | amd64) arch=amd64 ;;
  aarch64 | arm64) arch=arm64 ;;
  *) die "unsupported architecture: ${arch}" ;;
esac

target="linux-${arch}"

if [ "${VERSION}" = "latest" ]; then
  info "resolving latest release version"
  VERSION="$(
    curl --proto '=https' --tlsv1.2 -fsSL "https://api.github.com/repos/${REPO}/releases/latest" \
      | grep '"tag_name"' \
      | head -n 1 \
      | sed 's/.*"tag_name": *"//; s/".*//'
  )"
  [ -n "${VERSION}" ] || die "failed to resolve the latest release version"
fi

archive="binstaller-${VERSION}-${target}.tar.gz"
base_url="https://github.com/${REPO}/releases/download/${VERSION}"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "${tmp_dir}"' EXIT INT TERM

info "downloading ${base_url}/${archive}"
curl --proto '=https' --tlsv1.2 -fsSL "${base_url}/${archive}" -o "${tmp_dir}/${archive}"
curl --proto '=https' --tlsv1.2 -fsSL "${base_url}/${archive}.sha256" -o "${tmp_dir}/${archive}.sha256"

cd "${tmp_dir}"

info "verifying checksum"
if command -v sha256sum >/dev/null 2>&1; then
  sha256sum -c "${archive}.sha256" || die "checksum verification failed"
elif command -v shasum >/dev/null 2>&1; then
  shasum -a 256 -c "${archive}.sha256" || die "checksum verification failed"
else
  die "neither sha256sum nor shasum is available to verify the download"
fi

tar -xzf "${archive}"

binary_path="binstaller-${VERSION}-${target}/binstaller"
[ -f "${binary_path}" ] || die "extracted archive is missing the binstaller binary"

mkdir -p "${INSTALL_DIR}"
cp "${binary_path}" "${INSTALL_DIR}/binstaller"
chmod +x "${INSTALL_DIR}/binstaller"

info "installed binstaller ${VERSION} to ${INSTALL_DIR}/binstaller"

path_line="export PATH=\"${INSTALL_DIR}:\$PATH\""

add_to_rc() {
  rc_file="$1"
  if [ -f "${rc_file}" ] && grep -qF "${INSTALL_DIR}" "${rc_file}"; then
    return 0
  fi
  printf '\n# Added by the binstaller install script\n%s\n' "${path_line}" >> "${rc_file}"
  info "updated PATH in ${rc_file}"
}

add_to_rc "${HOME}/.bashrc"
add_to_rc "${HOME}/.zshrc"

info ""
info "binstaller ${VERSION} is installed. Restart your shell, or run:"
info "  source ${HOME}/.bashrc   # bash"
info "  source ${HOME}/.zshrc    # zsh"
