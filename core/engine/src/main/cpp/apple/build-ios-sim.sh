#!/usr/bin/env bash
#
# Disposable Milestone-1 build: proves openweights_engine (engine_session.cpp, statically
# linked against llama + llama-common + ggml/Metal) actually links for the iOS Simulator.
# Not a release pipeline -- there is no device or macOS variant here yet, and no XCFramework
# packaging step. See ../../llama.cpp/build-xcframework.sh, which this borrows its Xcode-
# generator invocation style from, for what a real multi-platform packaging script looks
# like once this is worth shipping.
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")"

IOS_MIN_OS_VERSION=16.4
JOBS=$(sysctl -n hw.logicalcpu)

cmake -B build-ios-sim -G Xcode \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_REQUIRED=NO \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGN_IDENTITY="" \
    -DCMAKE_XCODE_ATTRIBUTE_CODE_SIGNING_ALLOWED=NO \
    -DCMAKE_OSX_DEPLOYMENT_TARGET=${IOS_MIN_OS_VERSION} \
    -DIOS=ON \
    -DCMAKE_SYSTEM_NAME=iOS \
    -DCMAKE_OSX_SYSROOT=iphonesimulator \
    -DCMAKE_OSX_ARCHITECTURES=arm64 \
    -DCMAKE_XCODE_ATTRIBUTE_SUPPORTED_PLATFORMS=iphonesimulator \
    -DMTMD_VIDEO=OFF \
    -S .

cmake --build build-ios-sim --config Release -j "${JOBS}" -- -quiet

# One archive, not twelve. CMake's own static-library linking only resolves transitively
# within one Xcode project; an app project consuming this one would otherwise need to
# enumerate llama, ggml, ggml-cpu, ggml-metal, ggml-blas, ggml-base, llama-common,
# llama-common-base, mtmd, cpp-httplib and vendor-hash by hand and keep that list in step
# with whatever llama.cpp links next. `libtool -static` merges them once, here, into the
# one archive an app target actually links against.
rm -f build-ios-sim/libopenweights_engine_combined.a
archives=()
while IFS= read -r -d '' archive; do
    archives+=("${archive}")
done < <(find build-ios-sim -name "*.a" -print0)
libtool -static -o build-ios-sim/libopenweights_engine_combined.a "${archives[@]}"

echo "openweights_engine built for iOS Simulator (arm64):"
echo "  build-ios-sim/libopenweights_engine_combined.a"
