# tests/scripts/run-all.sh
# RFC-0013 scripts test unified runner.
# Runs each test file in sequence; on failure, stops immediately.
#
# Usage: bash tests/scripts/run-all.sh

set -e
cd "$(dirname "$0")/../.."

echo "=== test-dev-start.sh (bash) ==="
bash tests/scripts/test-dev-start.sh
echo ""

echo "=== test-makefile.sh (bash) ==="
bash tests/scripts/test-makefile.sh
echo ""

echo "All scripts tests passed (PowerShell suite is invoked separately via powershell -File)"
exit 0
