#!/usr/bin/env bash
set -euo pipefail
recipe_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$recipe_dir/../.." && pwd)"
host="${1:?desktop host required}"
case "$host" in
    linux-x64) triple=x86_64-unknown-linux-gnu; library=libnatune_librespot.so ;;
    linux-arm64) triple=aarch64-unknown-linux-gnu; library=libnatune_librespot.so ;;
    windows-x64) triple=x86_64-pc-windows-msvc; library=natune_librespot.dll ;;
    *) echo 'No Librespot recipe for this desktop host' >&2; exit 1 ;;
esac
bash "$recipe_dir/prepare.sh"
rustup target add --toolchain 1.97.1 "$triple"
cargo +1.97.1 build --manifest-path "$recipe_dir/Cargo.toml" --release --locked --target "$triple"
output_dir="$repo_dir/desktopApp/build/generated/librespot/$host/native"
mkdir -p "$output_dir"
cp "$recipe_dir/target/$triple/release/$library" "$output_dir/$library"
python "$recipe_dir/desktop-artifact.py" record "$host"
