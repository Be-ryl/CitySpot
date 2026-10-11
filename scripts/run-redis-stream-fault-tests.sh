#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
MODE="${1:-all}"
MASTER_PORT=6380
REPLICA_PORT=6381
STREAM_KEY="fault.stream.orders"
GROUP="fault-g1"
CONSUMER="fault-c1"
VOUCHER_ID="${VOUCHER_ID:-10}"
INITIAL_STOCK="${INITIAL_STOCK:-100}"
RUN_ID="$(date '+%Y%m%d-%H%M%S')"
RESULTS_DIR="${RESULTS_DIR:-$PROJECT_DIR/fault-test/results/redis-$RUN_ID}"
RUNTIME_ROOT="$RESULTS_DIR/runtime"
APP_JAR="$PROJECT_DIR/target/hm-dianping-0.0.1-SNAPSHOT.jar"
APP_PID_FILE="$RUNTIME_ROOT/app-8082.pid"
JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"

mkdir -p "$RUNTIME_ROOT"
if [[ -f "$PROJECT_DIR/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env.local"
  set +a
fi
[[ -n "${MYSQL_PASSWORD:-}" ]] || { echo "缺少 MYSQL_PASSWORD" >&2; exit 1; }
for tool in redis-server redis-cli mysql curl jq lsof python3; do command -v "$tool" >/dev/null || { echo "缺少命令：$tool" >&2; exit 1; }; done

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }
master() { redis-cli -p "$MASTER_PORT" --raw "$@"; }
replica() { redis-cli -p "$REPLICA_PORT" --raw "$@"; }
mysql_query() { MYSQL_PWD="$MYSQL_PASSWORD" mysql -N -B -h 127.0.0.1 -P 3306 -u root hmdp -e "$1" 2>/dev/null; }

wait_redis() {
  local port="$1" deadline=$((SECONDS + 20))
  until [[ "$(redis-cli -p "$port" --raw PING 2>/dev/null || true)" == "PONG" ]]; do
    (( SECONDS < deadline )) || return 1
    sleep 0.1
  done
}

redis_pid() {
  local pid_file="$1" port="$2" pid command_line
  [[ -f "$pid_file" ]] || return 1
  pid="$(tr -d '[:space:]' <"$pid_file")"
  [[ "$pid" =~ ^[1-9][0-9]*$ ]] || return 1
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  [[ "$command_line" == *"redis-server"* && "$command_line" == *":$port"* ]] || return 1
  printf '%s\n' "$pid"
}

start_redis() {
  local port="$1" dir="$2" pid_file="$3" replica_port="${4:-}"
  mkdir -p "$dir"
  local args=(--port "$port" --bind 127.0.0.1 --protected-mode yes --daemonize yes
    --dir "$dir" --appendonly yes --appendfsync everysec --save "" --pidfile "$pid_file" --logfile "$dir/redis.log")
  [[ -z "$replica_port" ]] || args+=(--replicaof 127.0.0.1 "$replica_port")
  redis-server "${args[@]}"
  wait_redis "$port"
  [[ "$(redis-cli -p "$port" --raw CONFIG GET appendonly | tail -1)" == "yes" ]]
  [[ "$(redis-cli -p "$port" --raw CONFIG GET appendfsync | tail -1)" == "everysec" ]]
}

stop_redis() {
  local port="$1"
  redis-cli -p "$port" SHUTDOWN NOSAVE >/dev/null 2>&1 || true
  local deadline=$((SECONDS + 10))
  while lsof -nP -iTCP:"$port" -sTCP:LISTEN >/dev/null 2>&1; do
    (( SECONDS < deadline )) || return 1
    sleep 0.1
  done
}

kill_redis() {
  local pid_file="$1" port="$2" pid
  pid="$(redis_pid "$pid_file" "$port")" || { echo "拒绝 kill：$port 不是受控隔离 Redis" >&2; return 1; }
  kill -9 "$pid"
  local deadline=$((SECONDS + 10))
  while kill -0 "$pid" 2>/dev/null; do (( SECONDS < deadline )) || return 1; sleep 0.05; done
}

wait_replication_caught_up() {
  local deadline=$((SECONDS + 20)) master_offset replica_offset status
  while (( SECONDS < deadline )); do
    status="$(replica INFO replication | tr -d '\r' | awk -F: '$1=="master_link_status"{print $2}')"
    master_offset="$(master INFO replication | tr -d '\r' | awk -F: '$1=="master_repl_offset"{print $2}')"
    replica_offset="$(replica INFO replication | tr -d '\r' | awk -F: '$1=="slave_repl_offset"{print $2}')"
    [[ "$status" == "up" && -n "$master_offset" && "$master_offset" == "$replica_offset" ]] && return 0
    sleep 0.1
  done
  return 1
}

contains_trial() {
  local port="$1" message_id="$2" trial_id="$3"
  redis-cli -p "$port" --raw XRANGE "$STREAM_KEY" "$message_id" "$message_id" 2>/dev/null | grep -Fq "$trial_id"
}

run_a1() {
  local csv="$RESULTS_DIR/a1-default-network.csv"
  printf 'round,trial_id,message_id,replica_before_restart,master_after_restart,recovery_ms,lost_on_both\n' >"$csv"
  for round in $(seq 1 30); do
    local trial_dir="$RUNTIME_ROOT/a1-$(printf '%02d' "$round")"
    local master_dir="$trial_dir/master" replica_dir="$trial_dir/replica"
    local master_pid="$master_dir/redis.pid" replica_pid="$replica_dir/redis.pid" trial_id="a1-$RUN_ID-$round" message_id started replica_has master_has recovery lost
    start_redis "$MASTER_PORT" "$master_dir" "$master_pid"
    start_redis "$REPLICA_PORT" "$replica_dir" "$replica_pid" "$MASTER_PORT"
    wait_replication_caught_up
    master DEL "$STREAM_KEY" >/dev/null
    wait_replication_caught_up
    message_id="$(master XADD "$STREAM_KEY" '*' trial "$trial_id")"
    started="$(now_ms)"
    kill_redis "$master_pid" "$MASTER_PORT"
    contains_trial "$REPLICA_PORT" "$message_id" "$trial_id" && replica_has=1 || replica_has=0
    start_redis "$MASTER_PORT" "$master_dir" "$master_pid"
    contains_trial "$MASTER_PORT" "$message_id" "$trial_id" && master_has=1 || master_has=0
    recovery=$(( $(now_ms) - started ))
    [[ "$replica_has" == 0 && "$master_has" == 0 ]] && lost=1 || lost=0
    printf '%s,%s,%s,%s,%s,%s,%s\n' "$round" "$trial_id" "$message_id" "$replica_has" "$master_has" "$recovery" "$lost" >>"$csv"
    stop_redis "$REPLICA_PORT"; stop_redis "$MASTER_PORT"
    echo "A1 $round/30 replica=$replica_has recovered-master=$master_has"
  done
}

run_a2() {
  local csv="$RESULTS_DIR/a2-replication-paused.csv"
  printf 'round,trial_id,message_id,replica_after_promotion,master_aof_recovered,missing_on_promoted_replica\n' >"$csv"
  for round in $(seq 1 10); do
    local trial_dir="$RUNTIME_ROOT/a2-$(printf '%02d' "$round")"
    local master_dir="$trial_dir/master" replica_dir="$trial_dir/replica"
    local master_pid="$master_dir/redis.pid" replica_pid="$replica_dir/redis.pid" trial_id="a2-$RUN_ID-$round" message_id replica_has master_has missing
    start_redis "$MASTER_PORT" "$master_dir" "$master_pid"
    start_redis "$REPLICA_PORT" "$replica_dir" "$replica_pid" "$MASTER_PORT"
    wait_replication_caught_up
    replica CLIENT PAUSE 3000 ALL >/dev/null
    message_id="$(master XADD "$STREAM_KEY" '*' trial "$trial_id")"
    kill_redis "$master_pid" "$MASTER_PORT"
    sleep 3.2
    replica REPLICAOF NO ONE >/dev/null
    contains_trial "$REPLICA_PORT" "$message_id" "$trial_id" && replica_has=1 || replica_has=0
    start_redis "$MASTER_PORT" "$master_dir" "$master_pid"
    contains_trial "$MASTER_PORT" "$message_id" "$trial_id" && master_has=1 || master_has=0
    [[ "$replica_has" == 0 ]] && missing=1 || missing=0
    printf '%s,%s,%s,%s,%s,%s\n' "$round" "$trial_id" "$message_id" "$replica_has" "$master_has" "$missing" >>"$csv"
    stop_redis "$REPLICA_PORT"; stop_redis "$MASTER_PORT"
    echo "A2 $round/10 promoted-replica=$replica_has master-aof=$master_has"
  done
}

validated_app_pid() {
  [[ -f "$APP_PID_FILE" ]] || return 1
  local pid command_line listener_pid
  pid="$(tr -d '[:space:]' <"$APP_PID_FILE")"
  command_line="$(ps -p "$pid" -o command= 2>/dev/null || true)"
  listener_pid="$(lsof -nP -tiTCP:8082 -sTCP:LISTEN 2>/dev/null || true)"
  [[ "$pid" =~ ^[1-9][0-9]*$ && "$listener_pid" == "$pid" && "$command_line" == *"hm-dianping"* && "$command_line" == *"server.port=8082"* ]] || return 1
  printf '%s\n' "$pid"
}

start_app() {
  local log_file="$1" delay_ms="${2:-5000}"
  "$JAVA_BIN" -jar "$APP_JAR" --server.port=8082 --spring.redis.port="$MASTER_PORT" \
    --hmdp.voucher-order.stream-consumer.enabled=true --hmdp.voucher-order.rabbit.enabled=false \
    --hmdp.voucher-order.consumer.processing-delay-ms="$delay_ms" >"$log_file" 2>&1 &
  echo "$!" >"$APP_PID_FILE"
  local deadline=$((SECONDS + 60))
  until curl -fsS http://127.0.0.1:8082/actuator/health 2>/dev/null | jq -e '.status=="UP"' >/dev/null 2>&1; do
    (( SECONDS < deadline )) || return 1
    sleep 0.5
  done
  validated_app_pid >/dev/null
}

kill_app() {
  local pid
  pid="$(validated_app_pid)" || { echo "拒绝 kill：8082 应用 PID 校验失败" >&2; return 1; }
  kill -9 "$pid"
  wait "$pid" 2>/dev/null || true
  local deadline=$((SECONDS + 15))
  while kill -0 "$pid" 2>/dev/null; do (( SECONDS < deadline )) || return 1; sleep 0.1; done
}

copy_test_login() {
  local user_id token
  IFS=, read -r user_id token < <(sed -n '2p' "$PROJECT_DIR/load-test/token-users.csv")
  master HSET "login:token:$token" id "$user_id" nickName fault-user >/dev/null
  master EXPIRE "login:token:$token" 3600 >/dev/null
  printf '%s,%s\n' "$user_id" "$token"
}

reset_business() {
  mysql_query "START TRANSACTION; DELETE FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID; UPDATE tb_seckill_voucher SET stock=$INITIAL_STOCK WHERE voucher_id=$VOUCHER_ID; COMMIT;"
  master DEL "seckill:stock:$VOUCHER_ID" "seckill:order:$VOUCHER_ID" stream.orders >/dev/null
  master SET "seckill:stock:$VOUCHER_ID" "$INITIAL_STOCK" >/dev/null
  master XGROUP CREATE stream.orders g1 0 MKSTREAM >/dev/null
}

wait_pending() {
  local expected="$1" deadline=$((SECONDS + 20)) count
  while (( SECONDS < deadline )); do
    count="$(redis-cli -p "$MASTER_PORT" --json XPENDING stream.orders g1 2>/dev/null | jq '.[0]' 2>/dev/null || echo 0)"
    [[ "$count" == "$expected" ]] && return 0
    sleep 0.1
  done
  return 1
}

run_b() {
  [[ -s "$APP_JAR" ]] || { echo "请先执行 mvn package" >&2; return 1; }
  [[ -f "$PROJECT_DIR/load-test/token-users.csv" ]] || { echo "缺少 token-users.csv" >&2; return 1; }
  local base="$RUNTIME_ROOT/b"
  local master_dir="$base/master" replica_dir="$base/replica"
  local master_pid="$master_dir/redis.pid" replica_pid="$replica_dir/redis.pid"
  start_redis "$MASTER_PORT" "$master_dir" "$master_pid"
  start_redis "$REPLICA_PORT" "$replica_dir" "$replica_pid" "$MASTER_PORT"
  wait_replication_caught_up
  local login user_id token
  login="$(copy_test_login)"; user_id="${login%%,*}"; token="${login#*,}"
  local csv="$RESULTS_DIR/b-consumer-crash.csv"
  printf 'round,pending_before_kill,db_before_kill,redis_stock_before_kill,pending_after_restart,db_after_restart,poison_pending,poison_attempts,poison_db_orders\n' >"$csv"
  for round in $(seq 1 10); do
    reset_business
    local log_before="$RESULTS_DIR/b-round-$(printf '%02d' "$round")-before.log" log_after="$RESULTS_DIR/b-round-$(printf '%02d' "$round")-after.log"
    start_app "$log_before" 5000
    curl -fsS -X POST -H "authorization: $token" "http://127.0.0.1:8082/voucher-order/seckill/$VOUCHER_ID" | jq -e '.success==true' >/dev/null
    wait_pending 1
    local pending_before db_before redis_before pending_after db_after poison_pending poison_attempts poison_db poison_id
    pending_before="$(redis-cli -p "$MASTER_PORT" --json XPENDING stream.orders g1 | jq '.[0]')"
    db_before="$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")"
    redis_before="$(master GET "seckill:stock:$VOUCHER_ID")"
    kill_app
    start_app "$log_after" 0
    wait_pending 0
    local deadline=$((SECONDS + 20)); db_after=0
    while (( SECONDS < deadline )); do db_after="$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")"; [[ "$db_after" == 1 ]] && break; sleep 0.1; done
    poison_id="$(master XADD stream.orders '*' id "$((900000 + round))" userId "$user_id" voucherId 999999999)"
    wait_pending 1
    # 等过至少一个 5 秒 Pending 扫描周期，证明毒消息会重复失败而非仅停留一次。
    sleep 7
    poison_pending="$(redis-cli -p "$MASTER_PORT" --json XPENDING stream.orders g1 | jq '.[0]')"
    poison_attempts="$(grep -c "orderId=$((900000 + round)).*voucherId=999999999" "$log_after" || true)"
    poison_db="$(mysql_query 'SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=999999999;')"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s\n' "$round" "$pending_before" "$db_before" "$redis_before" 0 "$db_after" "$poison_pending" "$poison_attempts" "$poison_db" >>"$csv"
    kill_app
    echo "B $round/10 pending-before=$pending_before recovered-orders=$db_after poison-pending=$poison_pending attempts=$poison_attempts"
  done
  stop_redis "$REPLICA_PORT"; stop_redis "$MASTER_PORT"
}

case "$MODE" in
  a1) run_a1 ;;
  a2) run_a2 ;;
  b) run_b ;;
  all) run_a1; run_a2; run_b ;;
  *) echo "用法：$0 {a1|a2|b|all}" >&2; exit 2 ;;
esac

python3 - "$RESULTS_DIR" <<'PY'
import csv, pathlib, sys
root = pathlib.Path(sys.argv[1])
lines = ["# Redis Stream 故障实验记录", "", "说明：kill -9 是进程崩溃模拟，不等价于真实断电；AOF everysec 的断电窗口需与本实验边界分开理解。", ""]
for filename, title in [("a1-default-network.csv", "A1 默认健康网络"), ("a2-replication-paused.csv", "A2 复制链路暂停对照"), ("b-consumer-crash.csv", "B 消费者崩溃与毒消息")]:
    path = root / filename
    if not path.exists(): continue
    rows = list(csv.DictReader(path.open()))
    lines += [f"## {title}", "", f"轮数：{len(rows)}。原始记录：`{filename}`。", ""]
    if filename.startswith("a1"):
        lost = sum(int(r["lost_on_both"]) for r in rows)
        lines += [f"主 AOF 与从节点均缺失：{lost}/{len(rows)}。默认网络观察值不得外推为断电保证。", ""]
    elif filename.startswith("a2"):
        lost = sum(int(r["missing_on_promoted_replica"]) for r in rows)
        lines += [f"提升后的从节点缺失已确认消息：{lost}/{len(rows)}。该数据只属于受控复制中断对照。", ""]
    else:
        recovered = sum(r["db_after_restart"] == "1" and r["pending_after_restart"] == "0" for r in rows)
        poison = sum(r["poison_pending"] == "1" for r in rows)
        repeated = sum(int(r.get("poison_attempts", 0)) >= 2 for r in rows)
        lines += [f"未 ACK 消息重启恢复：{recovered}/{len(rows)}；毒消息持续 Pending：{poison}/{len(rows)}，观察到重复失败：{repeated}/{len(rows)}。", ""]
(root / "redis-fault-report.md").write_text("\n".join(lines), encoding="utf-8")
print(root)
PY
