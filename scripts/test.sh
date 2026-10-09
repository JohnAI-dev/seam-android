#!/usr/bin/env bash
# Fast test entry point used by CI and by the Grok agent: unit tests (including the
# protocol vectors shared with seam-desktop), a full build, and Android lint.
# CI additionally launches the app on an emulator (see .github/workflows/ci.yml).
set -euo pipefail
cd "$(dirname "$0")/.."
CURRENT="setup"
trap 'echo "::error title=Test failed::Step \"$CURRENT\" failed"' ERR
CURRENT="unit tests"; echo "== $CURRENT"
gradle --no-daemon --console=plain :app:testDebugUnitTest
CURRENT="build"; echo "== $CURRENT"
gradle --no-daemon --console=plain :app:assembleDebug
CURRENT="lint"; echo "== $CURRENT"
gradle --no-daemon --console=plain :app:lintDebug
