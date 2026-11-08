#!/usr/bin/env bash
set -euo pipefail

recipe_dir="$(cd "$(dirname "$0")" && pwd)"
repo_dir="$(cd "$recipe_dir/../.." && pwd)"
sdk_dir="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
ndk_dir="${ANDROID_NDK_HOME:-$sdk_dir/ndk/30.0.16248370}"
toolchain_dir="$ndk_dir/toolchains/llvm/prebuilt/linux-x86_64/bin"
if [[ ! -x "$toolchain_dir/llvm-ar" ]]; then
    echo 'Install the pinned Android NDK on the CI build host first.' >&2
    exit 1
fi

selection="${1:-universal}"
case "$selection" in
    arm64-v8a|armeabi-v7a|x86|x86_64) abis=("$selection") ;;
    *)
        case "${selection##*-}" in
            universal) abis=(arm64-v8a x86_64) ;;
            arm64) abis=(arm64-v8a) ;;
            armeabi) abis=(armeabi-v7a) ;;
            x86) abis=(x86) ;;
            x86_64) abis=(x86_64) ;;
            all) abis=(arm64-v8a armeabi-v7a x86 x86_64) ;;
            *) echo 'Unsupported Android ABI selection' >&2; exit 1 ;;
        esac
        ;;
esac

bash "$recipe_dir/prepare.sh"
for abi in "${abis[@]}"; do
    case "$abi" in
        arm64-v8a) triple=aarch64-linux-android; compiler=aarch64-linux-android24-clang ;;
        armeabi-v7a) triple=armv7-linux-androideabi; compiler=armv7a-linux-androideabi24-clang ;;
        x86) triple=i686-linux-android; compiler=i686-linux-android24-clang ;;
        x86_64) triple=x86_64-linux-android; compiler=x86_64-linux-android24-clang ;;
    esac
    rustup target add --toolchain 1.97.1 "$triple"
    target_env="${triple//-/_}"
    linker_env="CARGO_TARGET_${target_env^^}_LINKER"
    env "$linker_env=$toolchain_dir/$compiler" \
        "CC_$target_env=$toolchain_dir/$compiler" "AR_$target_env=$toolchain_dir/llvm-ar" \
        "RUSTFLAGS=${RUSTFLAGS:-} -C link-arg=-Wl,-z,max-page-size=16384" \
        cargo +1.97.1 build --manifest-path "$recipe_dir/Cargo.toml" --release --locked --target "$triple"
    library="$recipe_dir/target/$triple/release/libnatune_librespot.so"
    output_dir="$repo_dir/app/build/generated/librespot/jniLibs/$abi"
    mkdir -p "$output_dir"
    cp "$library" "$output_dir/libnatune_librespot.so"
    "$toolchain_dir/llvm-readelf" -l "$library"
    wc -c "$library"
done
