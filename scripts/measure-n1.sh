#!/usr/bin/env bash
# N+1 전/후, 2쿼리안/1쿼리안 측정: 시드 후 rooms.mjs를 R회 반복하며 Com_select 델타로 쿼리/요청 산출
# 사용법: ./scripts/measure-n1.sh [방수=100] [방당메시지=10] [요청수=200] [반복=3] [경로=/subjects/1/chats/rooms]
#   2쿼리안(기존, 실측 3쿼리): ./scripts/measure-n1.sh 100 10 200 3
#   1쿼리안(신규):            ./scripts/measure-n1.sh 100 10 200 3 /subjects/1/chats/rooms/single
set -euo pipefail
cd "$(dirname "$0")/.."

ROOMS="${1:-100}"
MSGS="${2:-10}"
REQUESTS="${3:-200}"
REPEAT="${4:-3}"
ROOM_PATH="${5:-/subjects/1/chats/rooms}"

mysql_q() { docker exec noteu-mysql mysql -unoteu -pnoteu noteu -N -e "$1" 2>/dev/null; }

echo "== 시드: 방 ${ROOMS}개 × 방당 메시지 ${MSGS}개 =="
./scripts/seed-chat-data.sh "$ROOMS" "$MSGS"

for i in $(seq 1 "$REPEAT"); do
  echo ""
  echo "=== 측정 ${i}/${REPEAT} (경로: ${ROOM_PATH}, 요청 ${REQUESTS}회, warmup 20회) ==="

  select_start=$(mysql_q "SHOW GLOBAL STATUS LIKE 'Com_select'" | awk '{print $2}')
  node load-gen/rooms.mjs aaaa "$REQUESTS" 20 "$ROOM_PATH"
  select_end=$(mysql_q "SHOW GLOBAL STATUS LIKE 'Com_select'" | awk '{print $2}')

  # warmup 20회도 델타에 포함되므로 (요청 + warmup)으로 나눈다
  awk "BEGIN{printf \"쿼리/요청: %.1f\n\", ($select_end - $select_start) / ($REQUESTS + 20)}"
done
