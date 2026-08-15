#!/usr/bin/env bash
set -euo pipefail

# Cannsheet Mobile End-to-End Verification Runner
# Runs Node.js backend suites, Python benchmarks, and Android JVM unit tests.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${PROJECT_ROOT}"

# Environment configuration
export PATH="/Users/sophiaparis/Library/Application Support/com.raycast.macos/NodeJS/runtime/22.14.0/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-/opt/homebrew/share/android-commandlinetools}"
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17}"

echo "================================================================"
echo "=== [1/3] Running Checked-in Backend JavaScript Test Suites ==="
echo "================================================================"
node tests/backend_analytics_test.js
node tests/backend_contract_test.js
node tests/fake_sheets_batch_update_test.js
node tests/backend_corrections_test.js
node tests/backend_recovery_test.js
node tests/backend_spreadsheet_test.js
node tests/sandbox_performance_fixture_test.js
node tests/sandbox_provisioning_test.js

echo "================================================================"
echo "=== [2/3] Running Python Benchmark & Unit Test Suites       ==="
echo "================================================================"
python3 -m unittest tests/test_backend_sync_benchmark.py

echo "================================================================"
echo "=== [3/3] Running Android Client JVM Unit Tests             ==="
echo "================================================================"
./gradlew --no-daemon testDebugUnitTest

echo "================================================================"
echo "=== [SUCCESS] ALL TEST SUITES PASSED WITH ZERO ERRORS       ==="
echo "================================================================"
