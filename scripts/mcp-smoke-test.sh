#!/usr/bin/env bash
#
# MCP Server 冒烟测试：用真实子进程走一遍 stdio 协议。
#
# 为什么需要它：MCP 的 stdio 模式有两条容易被忽略的约束，单元测试都覆盖不到——
#   1. stdout 是协议通道，任何日志混进去都会让客户端解析失败。
#      Spring Boot 的启动横幅写在容器启动阶段，因此日志改道必须早于它；
#   2. 服务读到 stdin EOF 就退出。发消息的节奏必须像真实客户端那样
#      "保持连接 → 收齐响应 → 再关闭"，否则响应还没返回进程就结束了。
#
# 因此这里用命名管道（FIFO）保持 stdin 打开，轮询到收齐响应后再关闭。
#
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/code-agent-web/target/code-agent-web-0.1.0-SNAPSHOT.jar"

if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，请先执行: mvn -q -pl code-agent-web -am package -DskipTests"
  exit 1
fi

JAVA_BIN="${JAVA_HOME:-}/bin/java"
if [ ! -x "$JAVA_BIN" ]; then
  JAVA_BIN="java"
fi

WORK="$(mktemp -d)"
OUTPUT="$WORK/stdout.txt"
ERRORS="$WORK/stderr.txt"
FIFO="$WORK/stdin.fifo"
mkfifo "$FIFO"

echo "== 启动 MCP Server（stdio）=="
"$JAVA_BIN" -jar "$JAR" --mcp \
  --code-agent.index.root=examples/demo-project \
  < "$FIFO" > "$OUTPUT" 2> "$ERRORS" &
SERVER_PID=$!

# 打开写端：只要它还开着，服务的 stdin 就不会 EOF
exec 3> "$FIFO"

cat >&3 <<'JSON'
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"smoke-test","version":"1.0"}}}
{"jsonrpc":"2.0","method":"notifications/initialized"}
{"jsonrpc":"2.0","id":2,"method":"tools/list"}
{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"index_status","arguments":{}}}
JSON

# 轮询等待 3 条带 id 的响应（通知没有响应）
EXPECTED=3
for _ in $(seq 1 60); do
  RECEIVED=$(grep -c '^{"jsonrpc"' "$OUTPUT" 2>/dev/null || true)
  if [ "${RECEIVED:-0}" -ge "$EXPECTED" ]; then
    break
  fi
  sleep 0.5
done

# 像真实客户端一样：收完响应再关闭连接
exec 3>&-
wait "$SERVER_PID" 2>/dev/null || true

echo
echo "== 收到的响应 =="
cat "$OUTPUT"
echo

FAILED=0
check() {
  if grep -q "$1" "$OUTPUT"; then
    echo "  ✓ $2"
  else
    echo "  ✗ $2 （未找到: $1）"
    FAILED=1
  fi
}

echo "== 断言 =="
check '"id":1' "收到 initialize 响应"
check '"id":2' "收到 tools/list 响应"
check '"id":3' "收到 tools/call 响应"
check '"name":"code-agent"' "初始化响应里包含服务名"
check '"name":"search_code"' "工具列表里包含 search_code"
check '"name":"impact_analysis"' "工具列表里包含 impact_analysis"
check '索引状态: 切块' "index_status 返回了真实的索引状态"

# 协议通道纯净性：stdout 上只允许出现 JSON-RPC 消息
NON_JSON=$(grep -v '^{"jsonrpc"' "$OUTPUT" | grep -c . || true)
if [ "${NON_JSON:-0}" -eq 0 ]; then
  echo "  ✓ stdout 上只有 JSON-RPC 消息（日志已正确改道 stderr）"
else
  echo "  ✗ stdout 上混入了 $NON_JSON 行非协议内容："
  grep -v '^{"jsonrpc"' "$OUTPUT" | head -5
  FAILED=1
fi

echo
if [ "$FAILED" -ne 0 ]; then
  echo "冒烟测试失败。服务端 stderr 末尾："
  tail -10 "$ERRORS"
  exit 1
fi
echo "MCP Server 冒烟测试通过。"
