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

echo "openweights_engine built for iOS Simulator (arm64):"
find build-ios-sim -name "libopenweights_engine.a"
