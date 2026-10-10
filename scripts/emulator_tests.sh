#!/usr/bin/env bash
# Runs the instrumented tests on the emulator. Retries once, because emulator boot/install
# hiccups are common on CI; a real failure fails twice. Failures are shown as annotations.
set -u
cd "$(dirname "$0")/.."
for try in 1 2; do
  if gradle --no-daemon --console=plain :app:connectedDebugAndroidTest > emulator.log 2>&1; then
    tail -5 emulator.log
    echo "emulator tests passed (try $try)"
    exit 0
  fi
  echo "::warning title=Emulator tests failed (try $try)::see the log below"
  tail -60 emulator.log
done
python3 scripts/report_test_failures.py || true
grep -E "FAILED|Exception|AssertionError|error:|INSTALL_FAILED|Process crashed" emulator.log | tail -12 \
  | while IFS= read -r l; do echo "::error title=Emulator::${l:0:300}"; done
exit 1
