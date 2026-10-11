#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
RABBIT_HOME="${RABBIT_HOME:-$(brew --prefix rabbitmq)}"
RABBIT_SBIN="$RABBIT_HOME/sbin"
RUNTIME_DIR="${RABBIT_RUNTIME_DIR:-$PROJECT_DIR/fault-test/runtime/rabbitmq}"
NODE_NAME="${RABBITMQ_NODENAME:-hmdp_isolated@localhost}"
PID_FILE="$RUNTIME_DIR/rabbitmq.pid"
CONFIG_FILE="$PROJECT_DIR/fault-test/rabbitmq/rabbitmq"

mkdir -p "$RUNTIME_DIR/mnesia" "$RUNTIME_DIR/log"

export RABBITMQ_NODENAME="$NODE_NAME"
export RABBITMQ_NODE_PORT="${RABBITMQ_NODE_PORT:-5673}"
export RABBITMQ_DIST_PORT="${RABBITMQ_DIST_PORT:-25673}"
export RABBITMQ_MNESIA_BASE="$RUNTIME_DIR/mnesia"
export RABBITMQ_LOG_BASE="$RUNTIME_DIR/log"
export RABBITMQ_PID_FILE="$PID_FILE"
export RABBITMQ_CONFIG_FILE="$CONFIG_FILE"

diagnostics() { "$RABBIT_SBIN/rabbitmq-diagnostics" -q -n "$NODE_NAME" "$@"; }

validated_pid() {
  [[ -f "$PID_FILE" ]] || return 1
  local pid command_line listener_pid
  pid="$(tr -d '[:space:]' <"$PID_FILE")"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  listener_pid="$(lsof -nP -tiTCP:"$RABBITMQ_NODE_PORT" -sTCP:LISTEN 2>/dev/null || true)"
  [[ -n "$command_line" && "$command_line" == *"beam.smp"* && "$listener_pid" == "$pid" ]] || return 1
  printf '%s\n' "$pid"
}

start_node() {
  if diagnostics ping >/dev/null 2>&1; then
    echo "RabbitMQ 隔离节点已运行：$NODE_NAME port=$RABBITMQ_NODE_PORT"
    return 0
  fi
  "$RABBIT_SBIN/rabbitmq-server" -detached
  local deadline=$((SECONDS + 60))
  until diagnostics ping >/dev/null 2>&1; do
    if (( SECONDS >= deadline )); then
      echo "RabbitMQ 隔离节点启动超时；日志：$RUNTIME_DIR/log" >&2
      return 1
    fi
    sleep 1
  done
  echo "RabbitMQ 隔离节点已启动：$NODE_NAME port=$RABBITMQ_NODE_PORT data=$RUNTIME_DIR/mnesia"
}

case "${1:-status}" in
  start) start_node ;;
  status)
    diagnostics ping
    "$RABBIT_SBIN/rabbitmqctl" -q -n "$NODE_NAME" status | sed -n '1,25p'
    ;;
  stop)
    if diagnostics ping >/dev/null 2>&1; then "$RABBIT_SBIN/rabbitmqctl" -q -n "$NODE_NAME" stop; fi
    ;;
  kill)
    pid="$(validated_pid)" || { echo "拒绝 kill：PID 无效或不属于隔离节点 $NODE_NAME" >&2; exit 1; }
    kill -9 "$pid"
    echo "已 kill -9 隔离 RabbitMQ PID=${pid}；数据目录保持不变"
    ;;
  cli)
    shift
    exec "$RABBIT_SBIN/rabbitmqctl" -n "$NODE_NAME" "$@"
    ;;
  *) echo "用法：$0 {start|status|stop|kill|cli ...}" >&2; exit 2 ;;
esac
