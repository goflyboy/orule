#!/usr/bin/env bash
# tests/scripts/test-dev-start.sh
# RFC-0013 automated tests for dev-start.sh
#
# Coverage:
#   1. bash syntax check
#   2. help command output
#   3. unknown command -> non-zero exit
#   4. --help via -h/--help
#   5. required keywords present

set -e

SCRIPT="$(cd "$(dirname "$0")/../.." && pwd)/scripts/dev-start.sh"
fail=0

assert_pass() {
  echo "  PASS: $1"
}

assert_fail() {
  echo "  FAIL: $1"
  fail=$((fail+1))
}

# 1. bash syntax check
echo "[1] bash -n syntax check"
if bash -n "$SCRIPT"; then
  assert_pass "bash -n passed"
else
  assert_fail "bash -n failed"
fi

# 2. help command
echo "[2] help command"
out=$("$SCRIPT" help 2>&1)
if echo "$out" | grep -qi "usage"; then
  assert_pass "help output contains 'Usage'"
else
  assert_fail "help output missing 'Usage'"
fi

if echo "$out" | grep -qi "command"; then
  assert_pass "help output contains 'command'"
else
  assert_fail "help output missing 'command'"
fi

# 3. unknown command
echo "[3] unknown command -> non-zero exit"
set +e
"$SCRIPT" bogus-cmd >/dev/null 2>&1
rc=$?
set -e
if [ "$rc" -ne 0 ]; then
  assert_pass "unknown command exits non-zero (rc=$rc)"
else
  assert_fail "unknown command should exit non-zero (rc=$rc)"
fi

# 4. --help / -h variants
echo "[4] --help / -h variants"
for flag in --help -h help; do
  set +e
  out=$("$SCRIPT" "$flag" 2>&1)
  rc=$?
  set -e
  if [ "$rc" -eq 0 ] && echo "$out" | grep -qi "usage"; then
    assert_pass "flag '$flag' shows help"
  else
    assert_fail "flag '$flag' should show help (rc=$rc)"
  fi
done

# 5. required keywords
echo "[5] required keywords present"
for kw in "spring-boot:run" "actuator/health" "8080" "8081" "ORULE_DATA_DIR" "ORULE_SERVER_PORT" "check_deps"; do
  if grep -q "$kw" "$SCRIPT"; then
    assert_pass "keyword present: $kw"
  else
    assert_fail "keyword missing: $kw"
  fi
done

# 6. stop command idempotent
echo "[6] stop command is idempotent (no error if nothing to stop)"
set +e
"$SCRIPT" stop >/dev/null 2>&1
rc=$?
set -e
if [ "$rc" -eq 0 ]; then
  assert_pass "stop exits 0"
else
  assert_fail "stop should exit 0 (rc=$rc)"
fi

echo ""
if [ "$fail" -eq 0 ]; then
  echo "All RFC-0013 dev-start.sh tests passed"
  exit 0
else
  echo "$fail failure(s)"
  exit 1
fi
