#!/usr/bin/env bash
#
# 拒答判官的验收测试（需要 LLM API Key）。
#
# 前置：服务已启动，并且已经建立索引（POST /v1/index）。
# 用法：
#   export LLM_API_KEY=sk-xxx
#   bash scripts/judge-acceptance-test.sh
#
# 它测的是"端到端问答"而不是检索：调用 /v1/ask，看该拒答的是否拒答、
# 该回答的是否带着引用回来。这正是判官改造要影响的指标。
#
set -uo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
GOLDEN="${GOLDEN:-code-agent-eval/src/test/resources/golden/code-agent-self.yml}"

if ! curl -s --max-time 5 "$BASE_URL/v1/health" > /dev/null; then
  # 变量后面紧跟中文必须用 ${}：bash 会把非 ASCII 字符当成变量名的一部分
  echo "服务未就绪：${BASE_URL}（请先启动并 POST /v1/index）"
  exit 1
fi

ask() {
  local question="$1"
  local payload response
  payload=$(jq -nc --arg q "$question" '{question:$q, topK:8}')
  response=$(curl -s --max-time 120 -X POST "$BASE_URL/v1/ask" \
    -H 'Content-Type: application/json' -d "$payload")
  printf '%s\t%s\n' \
    "$(printf '%s' "$response" | jq -r '.refused')" \
    "$(printf '%s' "$response" | jq -r '.citations | length')"
}

# 从 golden set 抽出 id / 是否应拒答 / 问题
parse() {
  awk '
    /^  - id:/             { if (id != "") print id "\t" refusal "\t" q; id=$3; q=""; refusal="false" }
    /^    question:/       { q=$0; sub(/^    question: /, "", q) }
    /expect_refusal: true/ { refusal="true" }
    END                    { if (id != "") print id "\t" refusal "\t" q }
  ' "$1"
}

REFUSE_OK=0; REFUSE_TOTAL=0
EVIDENCE_OK=0; EVIDENCE_TOTAL=0

echo "== 拒答用例 =="
while IFS=$'\t' read -r id expect q; do
  [ "$expect" = "true" ] || continue
  REFUSE_TOTAL=$((REFUSE_TOTAL + 1))
  IFS=$'\t' read -r refused _ <<< "$(ask "$q")"
  if [ "$refused" = "true" ]; then
    REFUSE_OK=$((REFUSE_OK + 1)); mark="✓"
  else
    mark="✗"
  fi
  printf '  %s %-5s refused=%-5s %s\n' "$mark" "$id" "$refused" "$q"
done < <(parse "$GOLDEN")

echo
echo "== 证据用例（抽样前 13 条，避免一次跑太久）=="
count=0
while IFS=$'\t' read -r id expect q; do
  [ "$expect" = "false" ] || continue
  count=$((count + 1)); [ "$count" -gt 13 ] && break
  EVIDENCE_TOTAL=$((EVIDENCE_TOTAL + 1))
  IFS=$'\t' read -r refused citations <<< "$(ask "$q")"
  if [ "$refused" = "false" ] && [ "$citations" -gt 0 ]; then
    EVIDENCE_OK=$((EVIDENCE_OK + 1)); mark="✓"
  else
    mark="✗"
  fi
  printf '  %s %-5s refused=%-5s citations=%-3s %s\n' \
    "$mark" "$id" "$refused" "$citations" "$q"
done < <(parse "$GOLDEN")

echo
echo "== 汇总 =="
echo "拒答准确率:     $REFUSE_OK/$REFUSE_TOTAL"
# 变量后面紧跟中文时必须用 ${} 包起来：bash 会把非 ASCII 字符也当成变量名的一部分，
# 否则会报 "unbound variable"（set -u 下直接中断）
echo "证据类作答率:   ${EVIDENCE_OK}/${EVIDENCE_TOTAL}（有引用即算作答）"
