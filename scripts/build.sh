#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
git submodule update --init --depth 1 vendor/stable-diffusion.cpp
git -C vendor/stable-diffusion.cpp submodule update --init --depth 1 ggml
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease --console=plain
