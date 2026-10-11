#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE="${1:-all}"
RABBIT_CONTROL="$SCRIPT_DIR/rabbitmq-isolated.sh"
RABBIT_SBIN="$(brew --prefix rabbitmq)/sbin"
RABBIT_NODE="hmdp_isolated@localhost"
QUEUE="voucher.order.queue"
DLQ="voucher.order.dlq"
APP_JAR="$PROJECT_DIR/target/hm-dianping-0.0.1-SNAPSHOT.jar"
VOUCHER_ID="${VOUCHER_ID:-10}"
INITIAL_STOCK="${INITIAL_STOCK:-100}"
RUN_ID="$(date '+%Y%m%d-%H%M%S')"
RESULTS_DIR="${RESULTS_DIR:-$PROJECT_DIR/fault-test/results/rabbit-$RUN_ID}"
APP_PID_FILE="$RESULTS_DIR/app-8083.pid"
TOKEN_MAP="$PROJECT_DIR/load-test/token-users.csv"
mkdir -p "$RESULTS_DIR"

if [[ -f "$PROJECT_DIR/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env.local"
  set +a
fi
[[ -n "${MYSQL_PASSWORD:-}" ]] || { echo "缺少 MYSQL_PASSWORD" >&2; exit 1; }
[[ -s "$APP_JAR" ]] || { echo "缺少应用 jar，请先 mvn package" >&2; exit 1; }
for tool in mysql redis-cli curl jq lsof python3; do command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }; done

rabbit_cli() { "$RABBIT_SBIN/rabbitmqctl" -q -n "$RABBIT_NODE" "$@"; }
mysql_query() { MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B -h 127.0.0.1 -P 3306 -u root hmdp -e "$1" 2>/dev/null; }
now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

queue_stats() {
  local queue="$1"
  rabbit_cli list_queues name messages_ready messages_unacknowledged consumers \
    | awk -v q="$queue" '$1==q{print $2 " " $3 " " $4; found=1} END{if(!found) exit 1}'
}

wait_queue() {
  local queue="$1" expected_ready="$2" expected_unacked="$3" deadline=$((SECONDS + 30)) ready unacked consumers
  while (( SECONDS < deadline )); do
    if read -r ready unacked consumers < <(queue_stats "$queue" 2>/dev/null); then
      [[ "$ready" == "$expected_ready" && "$unacked" == "$expected_unacked" ]] && return 0
    fi
    sleep 0.1
  done
  return 1
}

validated_app_pid() {
  [[ -f "$APP_PID_FILE" ]] || return 1
  local pid command_line listener_pid
  pid="$(tr -d '[:space:]' <"$APP_PID_FILE")"
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  listener_pid="$(lsof -nP -tiTCP:8083 -sTCP:LISTEN 2>/dev/null || true)"
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$listener_pid" == "$pid" && "$command_line" == *"hm-dianping"* && "$command_line" == *"server.port=8083"* ]] || return 1
  printf '%s\n' "$pid"
}

start_app() {
  local consumer="$1" delay_ms="$2" log_file="$3"
  java -jar "$APP_JAR" --server.port=8083 --spring.rabbitmq.port=5673 \
    --management.health.rabbit.enabled=true --hmdp.voucher-order.stream-consumer.enabled=false \
    --hmdp.voucher-order.rabbit.enabled=true --hmdp.voucher-order.rabbit.consumer-enabled="$consumer" \
    --hmdp.voucher-order.consumer.processing-delay-ms="$delay_ms" --hmdp.fault-injection.enabled=true \
    >"$log_file" 2>&1 &
  echo "$!" >"$APP_PID_FILE"
  local deadline=$((SECONDS + 60))
  until curl -fsS http://127.0.0.1:8083/actuator/health 2>/dev/null | jq -e '.status=="UP"' >/dev/null 2>&1; do
    (( SECONDS < deadline )) || { tail -100 "$log_file" >&2; return 1; }
    sleep 0.5
  done
  validated_app_pid >/dev/null
}

kill_app() {
  local pid
  pid="$(validated_app_pid)" || { echo "拒绝 kill：8083 应用 PID 校验失败" >&2; return 1; }
  kill -9 "$pid"
  wait "$pid" 2>/dev/null || true
  local deadline=$((SECONDS + 15))
  while kill -0 "$pid" 2>/dev/null; do (( SECONDS < deadline )) || return 1; sleep 0.1; done
  sleep "${APP_RESTART_COOLDOWN_SECONDS:-2}"
}

ensure_tokens() {
  local token valid
  [[ -f "$TOKEN_MAP" ]] || "$SCRIPT_DIR/prepare-jmeter-tokens.sh" 1000 >/dev/null
  token="$(sed -n '2p' "$TOKEN_MAP" | cut -d, -f2)"
  valid="$(redis-cli --raw EXISTS "login:token:$token")"
  [[ "$valid" == 1 ]] || "$SCRIPT_DIR/prepare-jmeter-tokens.sh" 1000 >/dev/null
}

reset_business() {
  rabbit_cli purge_queue "$QUEUE" >/dev/null
  rabbit_cli purge_queue "$DLQ" >/dev/null
  mysql_query "START TRANSACTION; DELETE FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID; UPDATE tb_seckill_voucher SET stock=$INITIAL_STOCK WHERE voucher_id=$VOUCHER_ID; COMMIT;"
  redis-cli --raw DEL "seckill:stock:$VOUCHER_ID" "seckill:order:$VOUCHER_ID" >/dev/null
  redis-cli --raw SET "seckill:stock:$VOUCHER_ID" "$INITIAL_STOCK" >/dev/null
}

publish_order() {
  local row="$1" token
  token="$(sed -n "$((row + 1))p" "$TOKEN_MAP" | cut -d, -f2)"
  curl -fsS -X POST -H "authorization: $token" "http://127.0.0.1:8083/voucher-order/seckill/mq/$VOUCHER_ID"
}

run_a() {
  "$RABBIT_CONTROL" start >/dev/null
  start_app false 0 "$RESULTS_DIR/a-producer.log"
  reset_business
  local csv="$RESULTS_DIR/a-broker-crash.csv"
  printf 'round,order_id,confirm_success,ready_before_kill,ready_after_restart,recovery_ms,preserved\n' >"$csv"
  for round in $(seq 1 30); do
    local response order_id ready_before unacked consumers ready_after started recovery preserved
    response="$(publish_order "$round")"
    order_id="$(jq -r '.data // empty' <<<"$response")"
    [[ "$(jq -r '.success' <<<"$response")" == true && -n "$order_id" ]]
    wait_queue "$QUEUE" "$round" 0
    read -r ready_before unacked consumers < <(queue_stats "$QUEUE")
    started="$(now_ms)"
    "$RABBIT_CONTROL" kill >/dev/null
    "$RABBIT_CONTROL" start >/dev/null
    wait_queue "$QUEUE" "$round" 0
    read -r ready_after unacked consumers < <(queue_stats "$QUEUE")
    recovery=$(( $(now_ms) - started ))
    [[ "$ready_after" == "$round" ]] && preserved=1 || preserved=0
    printf '%s,%s,1,%s,%s,%s,%s\n' "$round" "$order_id" "$ready_before" "$ready_after" "$recovery" "$preserved" >>"$csv"
    echo "Rabbit A $round/30 ready-after=$ready_after"
  done
  kill_app
  start_app true 0 "$RESULTS_DIR/a-consumer.log"
  wait_queue "$QUEUE" 0 0
  local db_orders
  db_orders="$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")"
  printf 'confirmed=30\ndb_orders=%s\nqueue_drained=true\n' "$db_orders" >"$RESULTS_DIR/a-final-consumption.txt"
  [[ "$db_orders" == 30 ]]
  kill_app
}

run_b1() {
  "$RABBIT_CONTROL" start >/dev/null
  start_app false 0 "$RESULTS_DIR/b1-setup.log"
  reset_business
  kill_app
  local csv="$RESULTS_DIR/b1-consumer-crash.csv"
  printf 'round,unacked_before_kill,ready_after_connection_close,db_before_kill,db_after_restart,recovered\n' >"$csv"
  for round in $(seq 1 10); do
    reset_business
    start_app true 5000 "$RESULTS_DIR/b1-$(printf '%02d' "$round")-before.log"
    publish_order "$round" | jq -e '.success==true' >/dev/null
    wait_queue "$QUEUE" 0 1
    local ready unacked unacked_before consumers db_before ready_after db_after recovered
    read -r ready unacked consumers < <(queue_stats "$QUEUE")
    unacked_before="$unacked"
    db_before="$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")"
    kill_app
    wait_queue "$QUEUE" 1 0
    read -r ready_after unacked consumers < <(queue_stats "$QUEUE")
    start_app true 0 "$RESULTS_DIR/b1-$(printf '%02d' "$round")-after.log"
    wait_queue "$QUEUE" 0 0
    db_after="$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")"
    [[ "$db_after" == 1 ]] && recovered=1 || recovered=0
    printf '%s,%s,%s,%s,%s,%s\n' "$round" "$unacked_before" "$ready_after" "$db_before" "$db_after" "$recovered" >>"$csv"
    kill_app
    echo "Rabbit B1 $round/10 requeued=$ready_after db=$db_after"
  done
}

run_b2() {
  "$RABBIT_CONTROL" start >/dev/null
  # 先用无消费者实例声明拓扑并清场，避免上一次失败现场中的 ready/unacked 干扰计数。
  start_app false 0 "$RESULTS_DIR/b2-setup.log"
  reset_business
  kill_app
  start_app true 0 "$RESULTS_DIR/b2-consumer.log"
  local csv="$RESULTS_DIR/b2-poison-dlq.csv"
  printf 'round,order_id,attempts,main_ready,main_unacked,dlq_ready,passed\n' >"$csv"
  for round in $(seq 1 5); do
    local order_id=$((990000000 + round)) response deadline dlq_ready=0 ignored consumers attempts main_ready main_unacked passed
    response="$(curl -fsS -X POST "http://127.0.0.1:8083/internal/fault/rabbit/poison?orderId=$order_id&userId=$round")"
    [[ "$(jq -r '.success' <<<"$response")" == true ]]
    deadline=$((SECONDS + 20))
    while (( SECONDS < deadline )); do
      read -r dlq_ready ignored consumers < <(queue_stats "$DLQ")
      [[ "$dlq_ready" == "$round" ]] && break
      sleep 0.1
    done
    sleep 0.2
    read -r main_ready main_unacked consumers < <(queue_stats "$QUEUE")
    attempts="$(grep -c "开始处理RabbitMQ订单，orderId=$order_id" "$RESULTS_DIR/b2-consumer.log" || true)"
    [[ "$attempts" == 3 && "$main_ready" == 0 && "$main_unacked" == 0 && "$dlq_ready" == "$round" ]] && passed=1 || passed=0
    printf '%s,%s,%s,%s,%s,%s,%s\n' "$round" "$order_id" "$attempts" "$main_ready" "$main_unacked" "$dlq_ready" "$passed" >>"$csv"
    echo "Rabbit B2 $round/5 attempts=$attempts dlq=$dlq_ready"
  done
  kill_app
}

ensure_tokens
case "$MODE" in
  a) run_a ;;
  b1) run_b1 ;;
  b2) run_b2 ;;
  all) run_a; run_b1; run_b2 ;;
  *) echo "用法：$0 {a|b1|b2|all}" >&2; exit 2 ;;
esac

python3 - "$RESULTS_DIR" <<'PY'
import csv, pathlib, sys
root=pathlib.Path(sys.argv[1]); lines=["# RabbitMQ 故障实验记录", "", "隔离单节点持久化实验；不代表多节点高可用。kill -9 是进程崩溃，不等价于断电。", ""]
specs=[("a-broker-crash.csv","A Confirm 后 broker 崩溃恢复","preserved"),("b1-consumer-crash.csv","B1 未 ACK 重投","recovered"),("b2-poison-dlq.csv","B2 有限重试与 DLQ","passed")]
for name,title,field in specs:
    p=root/name
    if not p.exists(): continue
    rows=list(csv.DictReader(p.open())); passed=sum(r[field]=="1" for r in rows)
    lines += [f"## {title}", "", f"通过：{passed}/{len(rows)}；原始记录：`{name}`。", ""]
(root/"rabbit-fault-report.md").write_text("\n".join(lines),encoding="utf-8")
print(root)
PY
