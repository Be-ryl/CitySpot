#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIRECTORY="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
AI_SERVICE_DIRECTORY="$(cd "${SCRIPT_DIRECTORY}/.." && pwd)"
PYTHON_EXECUTABLE="${AI_SERVICE_DIRECTORY}/.venv/bin/python"
HF_EXECUTABLE="${AI_SERVICE_DIRECTORY}/.venv/bin/hf"
MODEL_ID="BAAI/bge-small-zh-v1.5"
MODEL_REVISION="7999e1d3359715c523056ef9478215996d62a620"
MODEL_DIRECTORY="${AI_SERVICE_DIRECTORY}/models/bge-small-zh-v1.5"
KNOWLEDGE_DIRECTORY="${AI_SERVICE_DIRECTORY}/knowledge/merchant_policy"
INDEX_DIRECTORY="${AI_SERVICE_DIRECTORY}/artifacts/indexes/merchant-policy-dense"
CASES_PATH="${AI_SERVICE_DIRECTORY}/evaluation/cases/merchant_policy_cases.jsonl"
REPORT_PATH="${AI_SERVICE_DIRECTORY}/evaluation/results/merchant_policy_comparison.json"

if [[ ! -x "${PYTHON_EXECUTABLE}" ]]; then
  python3 -m venv "${AI_SERVICE_DIRECTORY}/.venv"
fi

"${PYTHON_EXECUTABLE}" -m pip install --quiet -r "${AI_SERVICE_DIRECTORY}/requirements.txt"

if [[ ! -x "${HF_EXECUTABLE}" ]]; then
  echo "hf command is unavailable after dependency installation" >&2
  exit 1
fi

"${HF_EXECUTABLE}" download "${MODEL_ID}" \
  --revision "${MODEL_REVISION}" \
  --local-dir "${MODEL_DIRECTORY}" \
  --exclude "pytorch_model.bin"

cd "${AI_SERVICE_DIRECTORY}"

HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  "${PYTHON_EXECUTABLE}" -m app.rag.dense_index build \
  --knowledge-dir "${KNOWLEDGE_DIRECTORY}" \
  --index-dir "${INDEX_DIRECTORY}" \
  --model "${MODEL_DIRECTORY}" \
  --max-chunk-chars 600

HF_HUB_OFFLINE=1 TRANSFORMERS_OFFLINE=1 \
  "${PYTHON_EXECUTABLE}" -m app.evaluation.retrieval_eval \
  --retrieval-mode all \
  --knowledge-dir "${KNOWLEDGE_DIRECTORY}" \
  --cases "${CASES_PATH}" \
  --dense-index-dir "${INDEX_DIRECTORY}" \
  --embedding-model "${MODEL_DIRECTORY}" \
  --top-k 3 \
  --candidate-top-k 20 \
  --rrf-k 60 \
  --warmup-query-count 5 \
  --latency-runs 5 \
  --report "${REPORT_PATH}"

echo "RAG comparison report: ${REPORT_PATH}"
