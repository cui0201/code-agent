#!/usr/bin/env bash
#
# 下载交叉编码器重排模型（bge-reranker-base 量化版）。
#
# 模型体积约 280 MB，因此不放进代码仓库，需要时用本脚本获取。
# 默认从 hf-mirror.com 下载（国内可达）；如需官方源：
#   HF_ENDPOINT=https://huggingface.co ./scripts/download-reranker-model.sh
#
set -euo pipefail

MODEL_DIR="${1:-$HOME/.codeagent/models/bge-reranker-base}"
ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
REPO="Xenova/bge-reranker-base"

echo "下载源: $ENDPOINT"
echo "目标目录: $MODEL_DIR"
mkdir -p "$MODEL_DIR"

download() {
  local remote_path="$1"
  local local_name="$2"
  local target="$MODEL_DIR/$local_name"
  if [ -s "$target" ]; then
    echo "已存在，跳过: $local_name"
    return
  fi
  echo "下载 $local_name ..."
  curl -L --retry 3 -C - -o "$target" "$ENDPOINT/$REPO/resolve/main/$remote_path"
}

# 量化版在精度损失可控的前提下把体积压到 1/4，CPU 推理速度也更快
download "onnx/model_quantized.onnx" "model_quantized.onnx"
download "tokenizer.json" "tokenizer.json"

echo
echo "完成。启用重排："
echo "  export RE_RANK_MODEL=$MODEL_DIR/model_quantized.onnx"
echo "  java -jar code-agent-web/target/code-agent-web-*.jar \\"
echo "    --ask '下单接口对订单金额做了哪些校验？' \\"
echo "    --code-agent.rerank.model-path=$MODEL_DIR/model_quantized.onnx \\"
echo "    --code-agent.rerank.tokenizer-path=$MODEL_DIR/tokenizer.json"
