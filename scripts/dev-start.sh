#!/usr/bin/env bash
# scripts/dev-start.sh
# One-shot launcher for orule local development (Linux / macOS)
#
# Usage:
#   ./scripts/dev-start.sh                       # start server + rule-execution-service
#   ./scripts/dev-start.sh server                # only server
#   ./scripts/dev-start.sh rule-execution-service # only rule-execution-service
#   ./scripts/dev-start.sh check                 # dependency check only
#   ./scripts/dev-start.sh stop                  # stop all backend services

set -e

# Colors (auto-disabled when stdout is not a TTY)
if [ -t 1 ]; then
  RED='\033[0;31m'
  GREEN='\033[0;32m'
  YELLOW='\033[1;33m'
  NC='\033[0m'
else
  RED=''; GREEN=''; YELLOW=''; NC=''
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
SERVER_PORT="${ORULE_SERVER_PORT:-8080}"
RUNTIME_PORT="${ORULE_RUNTIME_PORT:-8081}"

log()  { echo -e "${GREEN}[$(date +'%H:%M:%S')]${NC} $1"; }
warn() { echo -e "${YELLOW}[$(date +'%H:%M:%S')]${NC} $1"; }
err()  { echo -e "${RED}[$(date +'%H:%M:%S')] ERROR:${NC} $1"; exit 1; }

check_deps() {
  # Honor explicit ORULE_JAVA_HOME / ORULE_MAVEN_HOME first.
  if [ -n "${ORULE_JAVA_HOME:-}" ] && [ -x "${ORULE_JAVA_HOME}/bin/java" ]; then
    export PATH="${ORULE_JAVA_HOME}/bin:$PATH"
  fi
  if [ -n "${ORULE_MAVEN_HOME:-}" ] && [ -x "${ORULE_MAVEN_HOME}/bin/mvn" ]; then
    export PATH="${ORULE_MAVEN_HOME}/bin:$PATH"
  fi

  # Some Windows JDK installations expose java only via Windows-native PATH;
  # when invoked under WSL/Git-Bash, the path may be truncated at the first space.
  # We extend PATH with a few common locations before lookup.
  local extra_paths=(
    "/mnt/c/Program Files/Java/jdk-21/bin"
    "/mnt/c/Program Files/Java/jdk-17/bin"
    "/mnt/c/ProgramData/Oracle/Java/javapath"
    "/mnt/d/Program Files/Java/jdk-21/bin"
  )
  for p in "${extra_paths[@]}"; do
    if [ -x "$p/java" ]; then
      export PATH="$p:$PATH"
      break
    fi
  done

  # Maven is shipped with IntelliJ in this dev environment
  local mvn_extra="/mnt/d/Program Files/JetBrains/IntelliJ IDEA 2025.3.2/plugins/maven/lib/maven3/bin"
  if [ -x "$mvn_extra/mvn" ] && ! command -v mvn >/dev/null 2>&1; then
    export PATH="$mvn_extra:$PATH"
  fi

  command -v java >/dev/null 2>&1 || err "java not found, please install JDK 21+ (or set ORULE_JAVA_HOME)"
  command -v mvn  >/dev/null 2>&1 || err "mvn not found, please install Maven 3.9+ (or set ORULE_MAVEN_HOME)"

  local java_ver
  java_ver=$(java -version 2>&1 | head -n1 | awk -F '"' '{print $2}' | awk -F '.' '{print $1}')
  [ "$java_ver" -ge 21 ] || err "JDK 21+ required, current=$java_ver"

  log "[OK] dependency check passed (java=$java_ver)"
}

prepare_dirs() {
  local data_dir="${ORULE_DATA_DIR:-$HOME/orule/data}"
  mkdir -p "$data_dir/db" "$data_dir/artifacts" "$data_dir/logs"
  echo "$data_dir"
}

# Wait for HTTP health check
# usage: wait_for_health URL TIMEOUT_SECONDS
wait_for_health() {
  local url=$1
  local timeout=${2:-60}
  local i
  for ((i=1; i<=timeout/2; i++)); do
    if curl -s --max-time 2 "$url" >/dev/null 2>&1; then
      log "[OK] health check passed: $url"
      return 0
    fi
    sleep 2
  done
  err "health check timeout: $url (waited ${timeout}s)"
}

start_module() {
  local name=$1
  local port=$2
  local log_file=$3
  local module_dir="${4:-$ROOT_DIR/packages/orule-${name}}"
  log "starting orule-${name} on port ${port}..."
  (
    cd "$module_dir" && \
    mvn -q -DskipTests spring-boot:run
  ) > "$log_file" 2>&1 &
  echo $!
}

cmd_check() {
  check_deps
  log "current modules:"
  ls -1 "$ROOT_DIR/packages"
}

cmd_stop() {
  log "stopping all spring-boot:run processes..."
  # Use pgrep with explicit signal to avoid hanging on some shells.
  local pids
  pids=$(pgrep -f 'spring-boot:run' 2>/dev/null || true)
  if [ -n "$pids" ]; then
    # shellcheck disable=SC2086
    kill $pids 2>/dev/null || true
    sleep 1
    pids=$(pgrep -f 'spring-boot:run' 2>/dev/null || true)
    if [ -n "$pids" ]; then
      # shellcheck disable=SC2086
      kill -9 $pids 2>/dev/null || true
    fi
  fi
  log "[OK] stopped"
}

cmd_server() {
  check_deps
  local data_dir
  data_dir=$(prepare_dirs)
  local pid
  pid=$(start_module server "$SERVER_PORT" "$data_dir/logs/server.log")
  wait_for_health "http://localhost:${SERVER_PORT}/actuator/health" 60
  echo "$pid"
}

cmd_runtime() {
  check_deps
  local data_dir
  data_dir=$(prepare_dirs)
  local pid
  pid=$(start_module rule-execution-service "$RUNTIME_PORT" "$data_dir/logs/rule-execution-service.log")
  wait_for_health "http://localhost:${RUNTIME_PORT}/actuator/health" 60
  echo "$pid"
}

cmd_start_all() {
  check_deps
  local data_dir
  data_dir=$(prepare_dirs)

  local server_pid
  server_pid=$(start_module server "$SERVER_PORT" "$data_dir/logs/server.log")
  wait_for_health "http://localhost:${SERVER_PORT}/actuator/health" 60

  local runtime_pid
  runtime_pid=$(start_module rule-execution-service "$RUNTIME_PORT" "$data_dir/logs/rule-execution-service.log")
  wait_for_health "http://localhost:${RUNTIME_PORT}/actuator/health" 60

  echo ""
  log "[OK] all services started!"
  echo ""
  echo "  orule-server:                    http://localhost:${SERVER_PORT}"
  echo "  orule-rule-execution-service:    http://localhost:${RUNTIME_PORT}"
  echo ""
  echo "PIDs: server=$server_pid, runtime=$runtime_pid"
  echo "Logs: $data_dir/logs/"
  echo ""
  echo "Press Ctrl+C to stop all services..."

  # Graceful exit
  trap "log 'stopping all services...'; kill $server_pid $runtime_pid 2>/dev/null; exit 0" INT TERM
  wait
}

cmd_help() {
  cat <<'EOF'
orule one-shot launcher

Usage:
  $0 [command]

Commands:
  start                       start server + rule-execution-service (default)
  server                      only server
  rule-execution-service      only rule-execution-service (was: runtime)
  check                       dependency check
  stop                        stop all spring-boot:run processes
  help                        show this help

Environment variables:
  ORULE_SERVER_PORT     server port (default 8080)
  ORULE_RUNTIME_PORT    rule-execution-service port (default 8081)
  ORULE_DATA_DIR        data dir (default $HOME/orule/data)
EOF
}

main() {
  local cmd=${1:-start}
  case "$cmd" in
    start)                       cmd_start_all ;;
    server)                      cmd_server ;;
    rule-execution-service)      cmd_runtime ;;
    check)                       cmd_check ;;
    stop)                        cmd_stop ;;
    help|-h|--help)              cmd_help ;;
    *) err "unknown command: $cmd (use 'help' to see usage)" ;;
  esac
}

main "$@"
