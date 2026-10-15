#!/usr/bin/env bash
# 채팅 데이터 시드: member 1(aaaa) 기준 방 N개(상대 = member 2..10 순환), 방당 메시지 M개
# 주의: chat_* 테이블을 초기화하므로 data.sql 기본 시드 방도 지워짐 (방 수 통제 목적)
# 사용법: ./scripts/seed-chat-data.sh [방수=100] [방당메시지=10]
set -euo pipefail
cd "$(dirname "$0")/.."

ROOMS="${1:-100}"
MSGS="${2:-10}"
MYSQL="docker exec -i noteu-mysql mysql -unoteu -pnoteu noteu"

{
  # FK 순서 고려 삭제: 메시지 → 참가자 → 방
  echo "DELETE FROM chat_message;"
  echo "DELETE FROM chat_participant;"
  echo "DELETE FROM chat_room;"

  for ((r = 1; r <= ROOMS; r++)); do
    friend=$(( (r % 9) + 2 ))   # member 2..10 순환

    echo "INSERT INTO chat_room (created_at, modified_at, subject_id) VALUES (NOW() - INTERVAL ${ROOMS} DAY + INTERVAL ${r} MINUTE, NOW() - INTERVAL ${ROOMS} DAY + INTERVAL ${r} MINUTE, 1);"
    echo "SET @rid := LAST_INSERT_ID();"   # 방금 넣은 방 id 캡처 (단일 세션이므로 유효)
    echo "INSERT INTO chat_participant (created_at, modified_at, chat_room_id, member_id) VALUES (NOW(), NOW(), @rid, 1), (NOW(), NOW(), @rid, ${friend});"

    for ((m = 1; m <= MSGS; m++)); do
      if (( m % 2 == 0 )); then sender="${friend}"; sender_name="friend"; else sender="1"; sender_name="aaaa"; fi
      echo "INSERT INTO chat_message (created_at, modified_at, room_id, sender_id, sender_name, message) VALUES (NOW() - INTERVAL ${MSGS} MINUTE + INTERVAL ${m} MINUTE + INTERVAL ${r} SECOND, NOW(), @rid, ${sender}, '${sender_name}', CONCAT('seed r${r}-m${m}'));"
    done
  done
} | $MYSQL

echo "시드 완료: 방 ${ROOMS}개 × 방당 메시지 ${MSGS}개 (member 1 기준)"