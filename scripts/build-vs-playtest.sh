#!/usr/bin/env bash
set -euo pipefail

project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
read_property() {
    awk -F= -v key="$1" '$1 == key {gsub(/\r/, "", $2); print $2}' "${project_dir}/gradle.properties"
}
work_dir="${SKYRIDERS_STACK_BUILD_DIR:-$(mktemp -d "${TMPDIR:-/tmp}/skyriders-stack.XXXXXX")}"
mkdir -p "$work_dir"
work_dir="$(cd "$work_dir" && pwd)"
printf 'Building pinned local VS stack in %s\n' "$work_dir"

checkout() {
    local name="$1" source="$2" revision="$3" patch="$4"
    test -n "$revision"
    test ! -e "$work_dir/$name"
    git init -q "$work_dir/$name"
    git -C "$work_dir/$name" fetch --depth=1 "$source" "$revision"
    git -C "$work_dir/$name" checkout --detach FETCH_HEAD
    test "$(git -C "$work_dir/$name" rev-parse HEAD)" = "$revision"
    git -C "$work_dir/$name" apply --ignore-space-change "$project_dir/patches/$patch"
}

checkout native "${SKYRIDERS_KRUNCH_SOURCE:-https://github.com/StewStrong/KrunchX.git}" "$(read_property krunch_source_revision)" krunch-shape-queries.patch
git -C "$work_dir/native" -c url.https://github.com/.insteadOf=git@github.com: submodule update --init --recursive
checkout api "${SKYRIDERS_PHYSICS_API_SOURCE:-https://github.com/ValkyrienSkies/Valkyrien-Skies-Physics-API.git}" "$(read_property physics_api_source_revision)" physics-api-shape-queries.patch
checkout bridge "${SKYRIDERS_KRUNCH_API_SOURCE:-https://github.com/ValkyrienSkies/Valkyrien-Skies-Krunch-Physics-API.git}" "$(read_property krunch_api_source_revision)" krunch-api-shape-queries.patch
checkout core "${SKYRIDERS_CORE_SOURCE:-https://github.com/ValkyrienSkies/vs-core.git}" "$(read_property vs_core_source_revision)" vs-core-shape-queries.patch
checkout vs "${SKYRIDERS_VS_SOURCE:-https://github.com/ValkyrienSkies/Valkyrien-Skies-2.git}" "$(read_property vs2_source_revision)" vs-playtest-server-hooks.patch

link_flags=()
case "$(uname -s)" in
    Linux) link_flags=(-DCMAKE_EXE_LINKER_FLAGS=-fuse-ld=lld -DCMAKE_SHARED_LINKER_FLAGS=-fuse-ld=lld); native_ext=so ;;
    Darwin) native_ext=dylib ;;
    *) printf 'Use Build-LocalStack.ps1 on Windows.\n' >&2; exit 1 ;;
esac
case "$(uname -m)" in
    x86_64) native_arch=64 ;;
    aarch64|arm64) native_arch=arm64 ;;
    *) printf 'Unsupported native architecture.\n' >&2; exit 1 ;;
esac
cmake -S "$work_dir/native" -B "$work_dir/native/build" -G Ninja \
    -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=clang -DCMAKE_CXX_COMPILER=clang++ \
    -DPBD_NO_DEMOS=TRUE -DKRUNCH_ENABLE_SLOW_TESTS=ON "${link_flags[@]}"
cmake --build "$work_dir/native/build" --target KrunchJni KrunchBenchmarkTests --parallel 4
KRUNCH_AVBD_PACKED_NATIVE_BENCH=1 "$work_dir/native/build/bin/KrunchBenchmarkTests"
mkdir -p "$work_dir/natives"
cp "$work_dir/native/build/lib/libKrunchJni.$native_ext" "$work_dir/natives/libKrunchJni$native_arch.$native_ext"

gradle() { local name="$1"; shift; (cd "$work_dir/$name" && bash ./gradlew "$@" --max-workers=4 --no-daemon); }
api_version="$(read_property physics_api_version)"
bridge_version="$(read_property krunch_version)"
core_version="$(read_property vs_core_version)"
vs_version="$(read_property vs2_version)"
gradle api publishToMavenLocal "-PCustomReleaseVersion=$api_version"
gradle bridge test publishToMavenLocal "-Pvs_phys_api_version=$api_version" \
    "-PCustomReleaseVersion=$bridge_version" "-PLocalKrunchNativeDirectory=$work_dir/natives"
gradle core publishToMavenLocal "-Pkrunch_version=$bridge_version" "-PCustomReleaseVersion=$core_version"
gradle vs :forge:publishToMavenLocal "-PCustomVersion=${vs_version#*+}" \
    "-Pvs_core_version=$core_version" "-Pkrunch_version=$bridge_version" "-Pvs_phys_api_version=$api_version"
printf 'Built VS %s from pinned playtest %s\n' "$vs_version" "$(read_property vs2_source_revision)"
