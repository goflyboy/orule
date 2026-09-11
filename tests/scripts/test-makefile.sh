#!/usr/bin/env bash
# tests/scripts/test-makefile.sh
# RFC-0013 Makefile automated tests

set -e

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
MAKEFILE="$ROOT/scripts/Makefile"
fail=0

# 1. file exists
if [ -f "$MAKEFILE" ]; then
  echo "  PASS: Makefile exists"
else
  echo "  FAIL: Makefile missing"
  exit 1
fi

# 2. required phony targets
for t in build server runtime start stop clean test help; do
  if grep -E "^\.PHONY:.*\b$t\b" "$MAKEFILE" >/dev/null; then
    echo "  PASS: .PHONY has $t"
  else
    echo "  FAIL: .PHONY missing $t"
    fail=$((fail+1))
  fi
done

# 3. required command patterns
for pattern in "mvn -B -DskipTests clean install" "mvn -B test" "./scripts/dev-start.sh server" "./scripts/dev-start.sh runtime" "./scripts/dev-start.sh stop"; do
  if grep -F "$pattern" "$MAKEFILE" >/dev/null; then
    echo "  PASS: command present: $pattern"
  else
    echo "  FAIL: command missing: $pattern"
    fail=$((fail+1))
  fi
done

echo ""
if [ "$fail" -eq 0 ]; then
  echo "All RFC-0013 Makefile tests passed"
  exit 0
else
  echo "$fail failure(s)"
  exit 1
fi
