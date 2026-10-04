#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
USER_COUNT="${1:-1000}"

if ! [[ "$USER_COUNT" =~ ^[1-9][0-9]*$ ]]; then
  echo "用户数量必须是正整数，例如：$0 1000" >&2
  exit 1
fi

if [[ -f "$PROJECT_DIR/.env.local" ]]; then
  set -a
  # shellcheck disable=SC1091
  source "$PROJECT_DIR/.env.local"
  set +a
fi

if [[ -z "${MYSQL_PASSWORD:-}" ]]; then
  echo "缺少 MYSQL_PASSWORD。请在项目根目录 .env.local 中设置。" >&2
  exit 1
fi

DEFAULT_JAVA_HOME="/opt/homebrew/opt/openjdk@11/libexec/openjdk.jdk/Contents/Home"
if [[ -x "$DEFAULT_JAVA_HOME/bin/java" ]]; then
  export JAVA_HOME="$DEFAULT_JAVA_HOME"
fi

if [[ -z "${JAVA_HOME:-}" || ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "没有找到 JDK 11。请设置 JAVA_HOME 后重试。" >&2
  exit 1
fi

JAVA_VERSION="$($JAVA_HOME/bin/java -version 2>&1 | head -n 1)"
if [[ "$JAVA_VERSION" != *'"11.'* ]]; then
  echo "必须使用 JDK 11，当前版本：$JAVA_VERSION" >&2
  exit 1
fi

export PATH="$JAVA_HOME/bin:$PATH"

cd "$PROJECT_DIR"
mvn \
  -Dtest=com.hmdp.loadtest.LoadTestTokenGenerator \
  -Dloadtest.user-count="$USER_COUNT" \
  -Dloadtest.output-dir="$PROJECT_DIR/load-test" \
  -Ddebug=false \
  -Dspring.main.banner-mode=off \
  -Dlogging.level.root=WARN \
  -Dlogging.level.org.springframework=WARN \
  -Dlogging.level.com.hmdp=INFO \
  test

echo
echo "生成文件："
wc -l "$PROJECT_DIR/load-test/tokens.txt" "$PROJECT_DIR/load-test/token-users.csv"
echo
echo "核对结果："
sed -n '1,20p' "$PROJECT_DIR/load-test/token-verification.txt"
