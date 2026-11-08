#!/usr/bin/env bash
set -euo pipefail

recipe_dir="$(cd "$(dirname "$0")" && pwd)"
revision="$(cat "$recipe_dir/upstream-revision")"
source_dir="$recipe_dir/upstream"
if [[ ! "$revision" =~ ^[0-9a-f]{40}$ ]]; then
    echo 'Invalid librespot revision' >&2
    exit 1
fi
if [[ ! -d "$source_dir/.git" ]]; then
    mkdir -p "$source_dir"
    git -C "$source_dir" init --quiet
fi
if ! git -C "$source_dir" cat-file -e "$revision^{commit}" 2>/dev/null; then
    git -C "$source_dir" fetch --quiet --depth=1 \
        https://github.com/librespot-org/librespot.git "$revision"
fi
# This ignored directory is owned by this recipe, never a user's checkout or a gitlink.
git -C "$source_dir" checkout --quiet --detach --force "$revision"
git -C "$source_dir" apply --check "$recipe_dir/embedding.patch"
git -C "$source_dir" apply "$recipe_dir/embedding.patch"
cmp "$source_dir/LICENSE" "$recipe_dir/LICENSE.librespot"
