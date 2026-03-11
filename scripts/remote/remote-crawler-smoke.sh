#!/usr/bin/env bash
set -euo pipefail

if ! command -v jq >/dev/null 2>&1; then
  echo "jq is required (sudo apt install jq)" >&2
  exit 1
fi

BASE_URL="${BASE_URL:-https://localhost}"
API_BASE="${API_BASE:-$BASE_URL/api/remote-crawl}"
USERNAME="${USERNAME:-admin}"
PASSWORD="${PASSWORD:-admin}"
HOST_NAME="${HOST_NAME:-$(hostname -s | tr '[:upper:]' '[:lower:]')}"
CRAWL_CONFIG_ID="${CRAWL_CONFIG_ID:-}"
QUEUE_BATCH_SIZE="${QUEUE_BATCH_SIZE:-10}"
EXPECTED_TOTAL="${EXPECTED_TOTAL:-50}"
CURL_INSECURE="${CURL_INSECURE:-false}"

if [[ -z "$CRAWL_CONFIG_ID" ]]; then
  cat >&2 <<'EOF'
CRAWL_CONFIG_ID is required.

Example:
  CRAWL_CONFIG_ID=123 scripts/remote/remote-crawler-smoke.sh "/home/sean/Documents" "/home/sean/Downloads"
EOF
  exit 1
fi

if [[ "$#" -gt 0 ]]; then
  FOLDERS=("$@")
else
  FOLDERS=("/home/${USER}/Documents" "/home/${USER}/Downloads")
fi

echo "BASE_URL=$BASE_URL"
echo "API_BASE=$API_BASE"
echo "HOST_NAME=$HOST_NAME"
echo "CRAWL_CONFIG_ID=$CRAWL_CONFIG_ID"
echo "FOLDERS=${FOLDERS[*]}"

curl_json() {
  local method="$1"
  local url="$2"
  local payload="${3:-}"
  local -a curl_args=(-sS)
  if [[ "${CURL_INSECURE,,}" == "true" ]]; then
    curl_args+=(-k)
  fi
  if [[ -n "$payload" ]]; then
    curl "${curl_args[@]}" -u "${USERNAME}:${PASSWORD}" \
      -H "Content-Type: application/json" \
      -X "$method" "$url" \
      --data "$payload"
  else
    curl "${curl_args[@]}" -u "${USERNAME}:${PASSWORD}" \
      -H "Content-Type: application/json" \
      -X "$method" "$url"
  fi
}

echo "1) Start remote session"
START_REQ=$(jq -n \
  --arg host "$HOST_NAME" \
  --argjson crawlConfigId "$CRAWL_CONFIG_ID" \
  --argjson expectedTotal "$EXPECTED_TOTAL" \
  '{host:$host, crawlConfigId:$crawlConfigId, expectedTotal:$expectedTotal}')
START_RESP=$(curl_json POST "$API_BASE/session/start" "$START_REQ")
echo "$START_RESP" | jq .
SESSION_ID=$(echo "$START_RESP" | jq -r '.sessionId')
if [[ -z "$SESSION_ID" || "$SESSION_ID" == "null" ]]; then
  echo "Failed to start session" >&2
  exit 1
fi

echo "2) Enqueue folder batch"
ENQUEUE_REQ=$(jq -n \
  --arg host "$HOST_NAME" \
  --argjson crawlConfigId "$CRAWL_CONFIG_ID" \
  --argjson sessionId "$SESSION_ID" \
  --argjson folders "$(printf '%s\n' "${FOLDERS[@]}" | jq -R '{path: .}' | jq -s '.')" \
  '{host:$host, crawlConfigId:$crawlConfigId, sessionId:$sessionId, folders:$folders, defaultPriority:0}')
ENQUEUE_RESP=$(curl_json POST "$API_BASE/session/tasks/enqueue-folders" "$ENQUEUE_REQ")
echo "$ENQUEUE_RESP" | jq .

echo "3) Claim next tasks"
CLAIM_REQ=$(jq -n \
  --arg host "$HOST_NAME" \
  --argjson crawlConfigId "$CRAWL_CONFIG_ID" \
  --argjson sessionId "$SESSION_ID" \
  --argjson maxTasks "$QUEUE_BATCH_SIZE" \
  '{host:$host, crawlConfigId:$crawlConfigId, sessionId:$sessionId, maxTasks:$maxTasks, reclaimAfterMinutes:10}')
CLAIM_RESP=$(curl_json POST "$API_BASE/session/tasks/next" "$CLAIM_REQ")
echo "$CLAIM_RESP" | jq .

CLAIM_TOKEN=$(echo "$CLAIM_RESP" | jq -r '.claimToken')
TASK_COUNT=$(echo "$CLAIM_RESP" | jq -r '.tasks | length')

if [[ "$TASK_COUNT" -gt 0 && "$CLAIM_TOKEN" != "null" ]]; then
  echo "4) Ingest synthetic file metadata/content for claimed tasks"
  INGEST_FILES=$(echo "$CLAIM_RESP" | jq -c '[.tasks[] | {
    path: (.folderPath + "/README-remote-smoke.txt"),
    analysisStatus: "ANALYZE",
    bodyText: ("Remote smoke test content for task " + (.taskId|tostring)),
    contentType: "text/plain"
  }]')
  INGEST_REQ=$(jq -n \
    --arg host "$HOST_NAME" \
    --argjson crawlConfigId "$CRAWL_CONFIG_ID" \
    --argjson sessionId "$SESSION_ID" \
    --argjson folders '[]' \
    --argjson files "$INGEST_FILES" \
    '{host:$host, crawlConfigId:$crawlConfigId, sessionId:$sessionId, folders:$folders, files:$files}')
  INGEST_RESP=$(curl_json POST "$API_BASE/session/ingest" "$INGEST_REQ")
  echo "$INGEST_RESP" | jq .

  echo "5) Ack claimed tasks as COMPLETED"
  ACK_RESULTS=$(echo "$CLAIM_RESP" | jq '[.tasks[] | {taskId: .taskId, outcome: "COMPLETED"}]')
  ACK_REQ=$(jq -n \
    --arg host "$HOST_NAME" \
    --argjson crawlConfigId "$CRAWL_CONFIG_ID" \
    --argjson sessionId "$SESSION_ID" \
    --arg claimToken "$CLAIM_TOKEN" \
    --argjson results "$ACK_RESULTS" \
    '{host:$host, crawlConfigId:$crawlConfigId, sessionId:$sessionId, claimToken:$claimToken, results:$results}')
  ACK_RESP=$(curl_json POST "$API_BASE/session/tasks/ack" "$ACK_REQ")
  echo "$ACK_RESP" | jq .
else
  echo "No tasks claimed; skipping ingest/ack"
fi

echo "6) Queue status"
STATUS_REQ=$(jq -n \
  --arg host "$HOST_NAME" \
  --argjson crawlConfigId "$CRAWL_CONFIG_ID" \
  --argjson sessionId "$SESSION_ID" \
  '{host:$host, crawlConfigId:$crawlConfigId, sessionId:$sessionId}')
STATUS_RESP=$(curl_json POST "$API_BASE/session/tasks/status" "$STATUS_REQ")
echo "$STATUS_RESP" | jq .

echo "7) Complete session"
COMPLETE_REQ=$(jq -n \
  --arg host "$HOST_NAME" \
  --argjson crawlConfigId "$CRAWL_CONFIG_ID" \
  --argjson sessionId "$SESSION_ID" \
  '{host:$host, crawlConfigId:$crawlConfigId, sessionId:$sessionId, runStatus:"COMPLETED", finalStep:"Smoke flow complete"}')
COMPLETE_RESP=$(curl_json POST "$API_BASE/session/complete" "$COMPLETE_REQ")
echo "$COMPLETE_RESP" | jq .

echo "Done. sessionId=$SESSION_ID"
