#!/usr/bin/env bash
# Downloads an official Ventoy release and copies the three boot images IGNYX needs
# into app/src/main/assets/ventoy/. Ventoy is GPLv3: https://github.com/ventoy/Ventoy
#
#   scripts/fetch-ventoy.sh            # latest release
#   VENTOY_VERSION=1.1.05 scripts/fetch-ventoy.sh
set -euo pipefail

cd "$(dirname "$0")/.."
DEST=app/src/main/assets/ventoy
AUTH=()
if [[ -n "${GITHUB_TOKEN:-}" ]]; then AUTH=(-H "Authorization: Bearer ${GITHUB_TOKEN}"); fi

VER="${VENTOY_VERSION:-}"
if [[ -z "$VER" ]]; then
  VER=$(curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 "${AUTH[@]}" https://api.github.com/repos/ventoy/Ventoy/releases/latest \
        | python3 -c 'import sys,json; print(json.load(sys.stdin)["tag_name"].lstrip("v"))')
fi
echo "Ventoy version: $VER"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
curl -fsSL --retry 5 --retry-all-errors --retry-delay 5 -o "$TMP/ventoy.tar.gz" \
  "https://github.com/ventoy/Ventoy/releases/download/v${VER}/ventoy-${VER}-linux.tar.gz"
tar -xzf "$TMP/ventoy.tar.gz" -C "$TMP"
SRC="$TMP/ventoy-${VER}"

mkdir -p "$DEST"
cp "$SRC/boot/boot.img"              "$DEST/boot.img"
cp "$SRC/boot/core.img.xz"           "$DEST/core.img.xz"
cp "$SRC/ventoy/ventoy.disk.img.xz"  "$DEST/ventoy.disk.img.xz"
echo "$VER" > "$DEST/VERSION"

# Sanity checks
[[ $(stat -c %s "$DEST/boot.img") -eq 512 ]] || { echo "unexpected boot.img size"; exit 1; }
CORE=$(xz -dc "$DEST/core.img.xz" | wc -c)
EFI=$(xz -dc "$DEST/ventoy.disk.img.xz" | wc -c)
echo "core.img: $CORE bytes, ventoy.disk.img: $EFI bytes"
(( CORE <= 2047 * 512 )) || { echo "core.img too large"; exit 1; }
(( EFI == 32 * 1024 * 1024 )) || { echo "ventoy.disk.img is not 32 MiB"; exit 1; }
ls -l "$DEST"
