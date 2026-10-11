#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RUNTIME_DIR="$PROJECT_DIR/fault-test/runtime/rabbit-app"
PID_FILE="$RUNTIME_DIR/app.pid"
LOG_FILE="$RUNTIME_DIR/app.log"
JAR="$PROJECT_DIR/target/hm-dianping-0.0.1-SNAPSHOT.jar"
mkdir -p "$RUNTIME_DIR"

if [[ -f "$PROJECT_DIR/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env.local"
  set +a
fi

validated_pid() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid command_line listener_pid
  pid="$(tr -d '[:space:]' <"$PID_FILE")"
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  listener_pid="$(lsof -nP -tiTCP:8083 -sTCP:LISTEN 2>/dev/null || true)"
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$listener_pid" == "$pid" && "$command_line" == *"hm-dianping"* && "$command_line" == *"server.port=8083"* ]] || return 1
  printf '%s\n' "$pid"
}

case "${1:-status}" in
  start)
    if pid="$(validated_pid 2>/dev/null)"; then echo "RabbitMQ 应用已运行 PID=$pid"; exit 0; fi
    [[ -s "$JAR" ]] || { echo "缺少 $JAR" >&2; exit 1; }
    nohup java -jar "$JAR" --server.port=8083 --spring.rabbitmq.port=5673 \
      --management.health.rabbit.enabled=true --hmdp.voucher-order.stream-consumer.enabled=false \
      --hmdp.voucher-order.rabbit.enabled=true --hmdp.voucher-order.rabbit.consumer-enabled=true \
      --hmdp.voucher-order.consumer.processing-delay-ms=0 >"$LOG_FILE" 2>&1 </dev/null &
    echo "$!" >"$PID_FILE"
    deadline=$((SECONDS + 60))
    until curl -fsS http://127.0.0.1:8083/actuator/health 2>/dev/null | jq -e '.status=="UP"' >/dev/null 2>&1; do
      (( SECONDS < deadline )) || { tail -100 "$LOG_FILE" >&2; exit 1; }
      sleep 0.5
    done
    echo "RabbitMQ 应用已启动 PID=$(validated_pid) port=8083"
    ;;
  status)
    pid="$(validated_pid)" || { echo "RabbitMQ 应用未运行"; exit 1; }
    curl -fsS http://127.0.0.1:8083/actuator/health
    echo
    echo "PID=$pid log=$LOG_FILE"
    ;;
  stop)
    pid="$(validated_pid)" || { echo "RabbitMQ 应用未运行"; exit 0; }
    kill "$pid"
    deadline=$((SECONDS + 30))
    while kill -0 "$pid" 2>/dev/null; do (( SECONDS < deadline )) || { echo "应用停止超时" >&2; exit 1; }; sleep 0.2; done
    echo "RabbitMQ 应用已停止 PID=$pid"
    ;;
  *) echo "用法：$0 {start|status|stop}" >&2; exit 2 ;;
esac
