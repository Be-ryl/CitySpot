#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
JMX_FILE="$PROJECT_DIR/load-test/seckill.jmx"
TOKEN_FILE="$PROJECT_DIR/load-test/token.csv"
SUMMARIZER="$SCRIPT_DIR/summarize-jmeter.py"
BACKEND="${BACKEND:-stream}"
ROUND_COUNT=10
TOTAL_SAMPLES=1000
RAMP_UP=1
VOUCHER_ID="${VOUCHER_ID:-10}"
INITIAL_STOCK="${INITIAL_STOCK:-100}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_DATABASE="${MYSQL_DATABASE:-hmdp}"
MYSQL_USER="${MYSQL_USER:-root}"
REDIS_HOST="${REDIS_HOST:-127.0.0.1}"
REDIS_PORT="${REDIS_PORT:-6379}"
STREAM_KEY="stream.orders"
STREAM_GROUP="g1"
RABBIT_NODE="${RABBITMQ_NODENAME:-hmdp_isolated@localhost}"
RABBIT_SBIN="${RABBIT_SBIN:-$(brew --prefix rabbitmq)/sbin}"
RABBIT_QUEUE="voucher.order.queue"
RABBIT_DLQ="voucher.order.dlq"
WAIT_TIMEOUT_SECONDS="${WAIT_TIMEOUT_SECONDS:-120}"

case "$BACKEND" in
  stream)
    BASE_URL="${BASE_URL:-http://127.0.0.1:8081}"
    API_PATH="${API_PATH:-/voucher-order/seckill}"
    ;;
  rabbit)
    BASE_URL="${BASE_URL:-http://127.0.0.1:8083}"
    API_PATH="${API_PATH:-/voucher-order/seckill/mq}"
    ;;
  *) echo "BACKEND 只能是 stream 或 rabbit" >&2; exit 2 ;;
esac

JMETER_PROTOCOL="${BASE_URL%%://*}"
url_without_scheme="${BASE_URL#*://}"
JMETER_HOST="${url_without_scheme%%:*}"
JMETER_PORT="${url_without_scheme##*:}"

if [[ -f "$PROJECT_DIR/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env.local"
  set +a
fi
[[ -n "${MYSQL_PASSWORD:-}" ]] || { echo "缺少 MYSQL_PASSWORD" >&2; exit 1; }

for command_name in curl jq mysql python3 redis-cli; do
  command -v "$command_name" >/dev/null 2>&1 || { echo "缺少命令：$command_name" >&2; exit 1; }
done

if [[ -n "${JMETER_BIN:-}" && -x "$JMETER_BIN" ]]; then :
elif command -v jmeter >/dev/null 2>&1; then JMETER_BIN="$(command -v jmeter)"
elif [[ -x "$PROJECT_DIR/../apache-jmeter-5.6.3/bin/jmeter" ]]; then JMETER_BIN="$PROJECT_DIR/../apache-jmeter-5.6.3/bin/jmeter"
else echo "没有找到 JMeter，请设置 JMETER_BIN" >&2; exit 1
fi

RUN_ID="$(date '+%Y%m%d-%H%M%S')"
RESULTS_DIR="${RESULTS_DIR:-$PROJECT_DIR/load-test/results/${BACKEND}-${RUN_ID}}"
mkdir -p "$RESULTS_DIR"
EVENT_LOG="$RESULTS_DIR/events.log"
touch "$EVENT_LOG"

REDIS_COMMAND=(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT")
[[ -z "${REDIS_PASSWORD:-}" ]] || REDIS_COMMAND+=(--no-auth-warning -a "$REDIS_PASSWORD")

log_event() {
  local level="$1"; shift
  printf '%s %s %s\n' "$(date '+%Y-%m-%d %H:%M:%S')" "$level" "$*" | tee -a "$EVENT_LOG"
}

fail_round() {
  local scene="$1" round="$2"; shift 2
  local failure_dir="$RESULTS_DIR/scene-$scene/round-$(printf '%02d' "$round")"
  mkdir -p "$failure_dir"
  printf '%s\n' "$*" >"$failure_dir/failure.txt"
  log_event ERROR "场景 $scene 第 $round 轮终止：$*"
  return 1
}

mysql_query() {
  MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names \
    -h "$MYSQL_HOST" -P "$MYSQL_PORT" -u "$MYSQL_USER" "$MYSQL_DATABASE" -e "$1" 2>/dev/null
}
redis_raw() { "${REDIS_COMMAND[@]}" --raw "$@"; }
rabbit_cli() { "$RABBIT_SBIN/rabbitmqctl" -q -n "$RABBIT_NODE" "$@"; }

queue_stats() {
  local queue="$1"
  rabbit_cli list_queues name messages_ready messages_unacknowledged consumers 2>/dev/null \
    | awk -v q="$queue" '$1 == q {print $2 " " $3 " " $4; found=1} END {if (!found) exit 1}'
}

stream_group_exists() {
  "${REDIS_COMMAND[@]}" --json XINFO GROUPS "$STREAM_KEY" 2>/dev/null \
    | jq -e --arg group "$STREAM_GROUP" 'map(select(.name == $group)) | length == 1' >/dev/null
}

rabbit_topology_ok() {
  "$RABBIT_SBIN/rabbitmq-diagnostics" -q -n "$RABBIT_NODE" ping >/dev/null 2>&1 || return 1
  rabbit_cli list_exchanges name type durable | awk '$1=="voucher.order.exchange" && $2=="direct" && $3=="true"{m=1} $1=="voucher.order.dlx" && $2=="direct" && $3=="true"{d=1} END{exit !(m&&d)}' || return 1
  rabbit_cli list_queues name durable consumers | awk '$1=="voucher.order.queue" && $2=="true" && $3>=1{m=1} $1=="voucher.order.dlq" && $2=="true"{d=1} END{exit !(m&&d)}' || return 1
  rabbit_cli list_bindings source_name destination_name routing_key | awk '$1=="voucher.order.exchange" && $2=="voucher.order.queue" && $3=="voucher.order.create"{m=1} $1=="voucher.order.dlx" && $2=="voucher.order.dlq" && $3=="voucher.order.dead"{d=1} END{exit !(m&&d)}'
}

preflight() {
  local scene="$1" round="$2" health
  health="$(curl --silent --show-error --max-time 5 "$BASE_URL/actuator/health" 2>/dev/null)" || { fail_round "$scene" "$round" "健康接口无法访问"; return 1; }
  jq -e '.status == "UP"' >/dev/null 2>&1 <<<"$health" || { fail_round "$scene" "$round" "健康接口未返回 UP：$health"; return 1; }
  [[ "$(redis_raw PING 2>/dev/null || true)" == "PONG" ]] || { fail_round "$scene" "$round" "Redis PING 失败"; return 1; }
  [[ "$(mysql_query 'SELECT 1;' 2>/dev/null || true)" == "1" ]] || { fail_round "$scene" "$round" "MySQL 连通失败"; return 1; }
  if [[ "$BACKEND" == "stream" ]]; then
    stream_group_exists || { fail_round "$scene" "$round" "Stream/消费组不存在"; return 1; }
  else
    rabbit_topology_ok || { fail_round "$scene" "$round" "RabbitMQ 节点、交换机、队列、绑定或消费者检查失败"; return 1; }
  fi
  [[ "$(mysql_query "SELECT COUNT(*) FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID AND begin_time <= NOW() AND end_time >= NOW();")" == "1" ]] \
    || { fail_round "$scene" "$round" "秒杀券无效或不在有效期"; return 1; }
}

reset_data() {
  if [[ "$BACKEND" == "rabbit" ]]; then
    rabbit_cli purge_queue "$RABBIT_QUEUE" >/dev/null
    rabbit_cli purge_queue "$RABBIT_DLQ" >/dev/null
  fi
  mysql_query "START TRANSACTION; DELETE FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID; UPDATE tb_seckill_voucher SET stock=$INITIAL_STOCK WHERE voucher_id=$VOUCHER_ID; COMMIT;"
  if [[ "$BACKEND" == "stream" ]]; then
    local reset_lua="redis.call('DEL',KEYS[1],KEYS[2],KEYS[3]); redis.call('SET',KEYS[1],ARGV[1]); return redis.call('XGROUP','CREATE',KEYS[3],ARGV[2],'0','MKSTREAM')"
    redis_raw EVAL "$reset_lua" 3 "seckill:stock:$VOUCHER_ID" "seckill:order:$VOUCHER_ID" "$STREAM_KEY" "$INITIAL_STOCK" "$STREAM_GROUP" >/dev/null
  else
    redis_raw DEL "seckill:stock:$VOUCHER_ID" "seckill:order:$VOUCHER_ID" >/dev/null
    redis_raw SET "seckill:stock:$VOUCHER_ID" "$INITIAL_STOCK" >/dev/null
  fi
  [[ "$(mysql_query "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID;")" == "$INITIAL_STOCK" \
    && "$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")" == "0" \
    && "$(redis_raw GET "seckill:stock:$VOUCHER_ID")" == "$INITIAL_STOCK" ]]
}

check_tokens() {
  local required="$1" tokens=() token valid_count
  [[ -f "$TOKEN_FILE" && "$(head -n 1 "$TOKEN_FILE")" == "token" ]] || return 1
  while IFS= read -r token; do
    [[ -z "$token" ]] || tokens+=("$token")
    [[ "${#tokens[@]}" -lt "$required" ]] || break
  done < <(tail -n +2 "$TOKEN_FILE")
  [[ "${#tokens[@]}" -eq "$required" ]] || return 1
  valid_count="$(redis_raw EVAL "local n=0; for _,t in ipairs(ARGV) do local k='login:token:'..t; if redis.call('EXISTS',k)==1 and redis.call('TTL',k)>0 then n=n+1 end end; return n" 0 "${tokens[@]}")"
  [[ "$valid_count" -eq "$required" ]]
}

wait_for_drain() {
  local deadline=$((SECONDS + WAIT_TIMEOUT_SECONDS)) ready unacked consumers xlen pending
  while (( SECONDS < deadline )); do
    if [[ "$BACKEND" == "stream" ]]; then
      xlen="$(redis_raw XLEN "$STREAM_KEY")"
      pending="$("${REDIS_COMMAND[@]}" --json XPENDING "$STREAM_KEY" "$STREAM_GROUP" | jq '.[0]')"
      [[ "$xlen" == "0" && "$pending" == "0" ]] && return 0
    else
      read -r ready unacked consumers < <(queue_stats "$RABBIT_QUEUE")
      [[ "$ready" == "0" && "$unacked" == "0" ]] && return 0
    fi
    sleep 0.1
  done
  return 1
}

smoke_test() {
  local round_dir="$1" token response_file http_code deadline
  token="$(sed -n '2p' "$TOKEN_FILE")"
  response_file="$round_dir/smoke-response.json"
  http_code="$(curl --silent --show-error --max-time 10 -o "$response_file" -w '%{http_code}' -X POST -H "authorization: $token" "$BASE_URL$API_PATH/$VOUCHER_ID")"
  [[ "$http_code" == "200" ]] && jq -e '.success == true and .data != null' "$response_file" >/dev/null || return 1
  deadline=$((SECONDS + 15))
  while (( SECONDS < deadline )); do
    if [[ "$(mysql_query "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")" == "1" ]] && wait_for_drain; then return 0; fi
    sleep 0.1
  done
  return 1
}

monitor_backlog() {
  local output="$1" ready unacked consumers dlq_ready ignored
  printf 'timestamp_ms,xlen,pending,dlq\n' >"$output"
  while true; do
    if [[ "$BACKEND" == "stream" ]]; then
      ready="$(redis_raw XLEN "$STREAM_KEY" 2>/dev/null || echo 0)"
      unacked="$("${REDIS_COMMAND[@]}" --json XPENDING "$STREAM_KEY" "$STREAM_GROUP" 2>/dev/null | jq '.[0]' 2>/dev/null || echo 0)"
      dlq_ready=0
    else
      read -r ready unacked consumers < <(queue_stats "$RABBIT_QUEUE" 2>/dev/null || echo '0 0 0')
      read -r dlq_ready ignored consumers < <(queue_stats "$RABBIT_DLQ" 2>/dev/null || echo '0 0 0')
    fi
    printf '%s,%s,%s,%s\n' "$(($(date '+%s') * 1000))" "$ready" "$unacked" "$dlq_ready" >>"$output"
    sleep 0.05
  done
}

run_round() {
  local scene="$1" threads="$2" round="$3"
  local requests_per_thread=$((TOTAL_SAMPLES / threads))
  local round_dir="$RESULTS_DIR/scene-$scene/round-$(printf '%02d' "$round")"
  mkdir -p "$round_dir"
  log_event INFO "$BACKEND 场景 $scene 第 $round/$ROUND_COUNT 轮：前置检查"
  preflight "$scene" "$round" || return 1
  reset_data || { fail_round "$scene" "$round" "数据重置校验失败"; return 1; }
  check_tokens "$TOTAL_SAMPLES" || { fail_round "$scene" "$round" "Token 不足或登录态失效"; return 1; }
  smoke_test "$round_dir" || { fail_round "$scene" "$round" "冒烟失败"; return 1; }
  reset_data || { fail_round "$scene" "$round" "冒烟后基线恢复失败"; return 1; }

  monitor_backlog "$round_dir/backlog.csv" &
  local monitor_pid=$! jmeter_status=0
  HEAP="-Xms1g -Xmx1g -XX:MaxMetaspaceSize=256m" JVM_ARGS="-Xss256k" \
  "$JMETER_BIN" -n -t "$JMX_FILE" -l "$round_dir/result.jtl" -j "$round_dir/jmeter.log" -e -o "$round_dir/html-report" \
    -Jthreads="$threads" -JrequestsPerThread="$requests_per_thread" -Jrampup="$RAMP_UP" \
    -Jhost="$JMETER_HOST" -Jport="$JMETER_PORT" -Jprotocol="$JMETER_PROTOCOL" -JapiPath="$API_PATH" \
    -JvoucherId="$VOUCHER_ID" -JtokenFile="$TOKEN_FILE" -Jsample_variables="business_success,token_index" \
    -Jjmeter.save.saveservice.output_format=csv -Jjmeter.save.saveservice.print_field_names=true \
    >"$round_dir/jmeter-console.log" 2>&1 || jmeter_status=$?
  kill "$monitor_pid" 2>/dev/null || true
  wait "$monitor_pid" 2>/dev/null || true
  [[ "$jmeter_status" -eq 0 && -s "$round_dir/result.jtl" ]] || { fail_round "$scene" "$round" "JMeter 失败，退出码=$jmeter_status"; return 1; }
  wait_for_drain || { fail_round "$scene" "$round" "消费排空超时，现场已保留"; return 1; }

  local db_stats db_orders db_distinct db_stock duplicate_users redis_stock ready=0 unacked=0 consumers=0 dlq=0 ignored
  db_stats="$(mysql_query "SELECT COUNT(*), COUNT(DISTINCT user_id), (SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID), (SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID GROUP BY user_id HAVING COUNT(*) > 1) duplicates) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;")"
  IFS=$'\t' read -r db_orders db_distinct db_stock duplicate_users <<<"$db_stats"
  redis_stock="$(redis_raw GET "seckill:stock:$VOUCHER_ID")"
  if [[ "$BACKEND" == "stream" ]]; then
    ready="$(redis_raw XLEN "$STREAM_KEY")"
    unacked="$("${REDIS_COMMAND[@]}" --json XPENDING "$STREAM_KEY" "$STREAM_GROUP" | jq '.[0]')"
  else
    read -r ready unacked consumers < <(queue_stats "$RABBIT_QUEUE")
    read -r dlq ignored consumers < <(queue_stats "$RABBIT_DLQ")
  fi

  python3 "$SUMMARIZER" round --backend "$BACKEND" --jtl "$round_dir/result.jtl" --backlog "$round_dir/backlog.csv" \
    --output "$round_dir/summary.json" --scene "$scene" --threads "$threads" --round "$round" --initial-stock "$INITIAL_STOCK" \
    --db-orders "$db_orders" --db-distinct-users "$db_distinct" --db-stock "$db_stock" --redis-stock "$redis_stock" \
    --duplicate-users "$duplicate_users" --stream-xlen "$ready" --stream-pending "$unacked" --dlq "$dlq" >"$round_dir/summary-line.json"

  [[ "$(jq -r '.samples' "$round_dir/summary.json")" == "$TOTAL_SAMPLES" ]] || { fail_round "$scene" "$round" "样本数不是 $TOTAL_SAMPLES"; return 1; }
  [[ "$(jq -r '.business_pass' "$round_dir/summary.json")" == "true" ]] || { fail_round "$scene" "$round" "业务校验失败，现场已保留"; return 1; }
  log_event INFO "$BACKEND 场景 $scene 第 $round 轮完成：$(jq -r '.business_validation' "$round_dir/summary.json")"
}

if [[ ! -f "$TOKEN_FILE" ]] || [[ "$(($(wc -l <"$TOKEN_FILE") - 1))" -lt "$TOTAL_SAMPLES" ]]; then
  "$SCRIPT_DIR/prepare-jmeter-tokens.sh" "$TOTAL_SAMPLES" >>"$EVENT_LOG" 2>&1
fi
log_event INFO "$BACKEND 压测开始：A 10轮后 B 10轮；结果=$RESULTS_DIR"
for round in $(seq 1 "$ROUND_COUNT"); do run_round A 1000 "$round" || exit 1; done
reset_data
for round in $(seq 1 "$ROUND_COUNT"); do run_round B 100 "$round" || exit 1; done
python3 "$SUMMARIZER" report --backend "$BACKEND" --results-dir "$RESULTS_DIR" >/dev/null
log_event INFO "全部完成：$RESULTS_DIR/benchmark-report.md"
printf '%s\n' "$RESULTS_DIR"
