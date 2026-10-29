#!/usr/bin/env bash
#
# Codex / IDE 的 stdio MCP 启动器。
#
# 用法：
#   scripts/run-mcp.sh [被索引的 Java 工程根目录]
#
# 不传参数时索引 code-agent 自身。Codex 启动 MCP 子进程时通常没有用户终端里的
# JAVA_HOME，因此脚本会优先寻找 JDK 21，避免误用 x86_64 JDK 导致 ONNX 加载失败。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
JAR="$ROOT/code-agent-web/target/code-agent-web-0.1.0-SNAPSHOT.jar"
PROJECT_ROOT="${1:-$ROOT}"
SNAPSHOT_DIR="${CODE_AGENT_SNAPSHOT_DIR:-$ROOT/.codeagent/index}"

if [ ! -f "$JAR" ]; then
  echo "未找到 $JAR，请先运行: mvn -B -DskipTests package" >&2
  exit 1
fi

JAVA_BIN=""
if [ -n "${CODE_AGENT_JAVA_HOME:-}" ] && [ -x "$CODE_AGENT_JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$CODE_AGENT_JAVA_HOME/bin/java"
elif [ "$(uname -s)" = "Darwin" ] && [ -x /usr/libexec/java_home ]; then
  JAVA_HOME_21="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
  if [ -n "$JAVA_HOME_21" ] && [ -x "$JAVA_HOME_21/bin/java" ]; then
    JAVA_BIN="$JAVA_HOME_21/bin/java"
  fi
elif [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  JAVA_BIN="$JAVA_HOME/bin/java"
fi
if [ -z "$JAVA_BIN" ] && command -v java >/dev/null 2>&1; then
  JAVA_BIN="$(command -v java)"
fi
if [ -z "$JAVA_BIN" ]; then
  echo "未找到 JDK 21；请设置 CODE_AGENT_JAVA_HOME 或 JAVA_HOME" >&2
  exit 1
fi

# stdout 必须只留给 MCP JSON-RPC 协议；所有启动错误都输出到 stderr。
exec "$JAVA_BIN" -jar "$JAR" --mcp \
  "--code-agent.index.root=$PROJECT_ROOT" \
  "--code-agent.index.snapshot-dir=$SNAPSHOT_DIR"
