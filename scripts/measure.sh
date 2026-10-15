#!/usr/bin/env bash
# 폴링 vs SSE A/B 성능 측정
# 각 시나리오 N초: MySQL Com_select 델타(쿼리/초) + docker stats CPU 샘플링(2초 간격)
# 사용법: ./scripts/measure.sh [지속초=60] [폴링간격ms=1000]
set -euo pipefail
cd "$(dirname "$0")/.."

WINDOW="${1:-60}"
POLLING_INTERVAL_MS="${2:-1000}"
APP=noteu-app
MYSQL=noteu-mysql
BASE_URL="${BASE_URL:-http://localhost:8080}"

mysql_q() { docker exec "$MYSQL" mysql -unoteu -pnoteu noteu -N -e "$1" 2>/dev/null; }

# docker stats MemUsage "123.4MiB / 2GiB" → MiB 숫자로
mem_to_mib() {
  local v
  v=$(echo "$1" | awk '{print $1}')
  case "$v" in
    *GiB) echo "${v%GiB}" | awk '{printf "%.1f", $1 * 1024}' ;;
    *MiB) echo "${v%MiB}" | awk '{printf "%.1f", $1}' ;;
    *KiB) echo "${v%KiB}" | awk '{printf "%.1f", $1 / 1024}' ;;
    *B)   echo "${v%B}"   | awk '{printf "%.3f", $1 / 1048576}' ;;
  esac
}

run_scenario() {
  local name="$1"
  shift
  echo ""
  echo "=== 시나리오: $name (${WINDOW}s) ==="

  # FLUSH STATUS 권한이 없으므로 누적 카운터 델타 방식 사용
  local select_start
  select_start=$(mysql_q "SHOW GLOBAL STATUS LIKE 'Com_select'" | awk '{print $2}')

  # 부하 프로세스 기동 (없으면 sleep: baseline)
  if [ "$#" -gt 0 ]; then
    "$@" &
    local load_pid=$!
  fi

  # CPU/메모리 샘플링: 2초 간격
  local max=0
  local total=0
  local n=0
  local mem_total=0
  local mem_max=0
  for ((t = 0; t < WINDOW; t += 2)); do
    local stats
    stats=$(docker stats --no-stream --format '{{.CPUPerc}}|{{.MemUsage}}' "$APP")
    local cpu="${stats%%|*}"
    cpu=$(echo "$cpu" | tr -d '%')
    local mem
    mem=$(mem_to_mib "${stats#*|}")
    total=$(awk "BEGIN{print $total + $cpu}")
    mem_total=$(awk "BEGIN{print $mem_total + $mem}")
    n=$((n + 1))
    max=$(awk "BEGIN{print ($cpu > $max) ? $cpu : $max}")
    mem_max=$(awk "BEGIN{print ($mem > $mem_max) ? $mem : $mem_max}")
    sleep 2
  done

  if [ -n "${load_pid:-}" ]; then wait "$load_pid" || true; fi

  local select_end
  select_end=$(mysql_q "SHOW GLOBAL STATUS LIKE 'Com_select'" | awk '{print $2}')
  local selects=$((select_end - select_start))
  local avg
  avg=$(awk -v t="$total" -v c="$n" 'BEGIN{printf "%.2f", t / c}')
  local mem_avg
  mem_avg=$(awk -v t="$mem_total" -v c="$n" 'BEGIN{printf "%.1f", t / c}')
  local per_sec
  per_sec=$(awk -v s="$selects" -v w="$WINDOW" 'BEGIN{printf "%.2f", s / w}')

  echo "  Com_select 델타       : $selects (start=$select_start end=$select_end)"
  echo "  DB 조회 요청/초      : $per_sec"
  echo "  CPU 평균             : ${avg}%   (최대 ${max}%)"
  echo "  메모리 평균          : ${mem_avg} MiB   (최대 ${mem_max} MiB)"
}

# 사전 확인
if ! docker ps --format '{{.Names}}' | grep -q "^${APP}$"; then
  echo "앱 컨테이너가 실행 중이어야 합니다. (docker compose up -d)"
  exit 1
fi
if [ ! -f load-gen/sessions.json ]; then
  echo "세션이 없습니다. 먼저: node load-gen/login.mjs"
  exit 1
fi

run_scenario "A. 폴링 (10명 × ${POLLING_INTERVAL_MS}ms 간격)" node load-gen/polling.mjs "$POLLING_INTERVAL_MS" "$WINDOW"
run_scenario "B. SSE (10명 유휴 연결)" node load-gen/sse.mjs "$WINDOW"
run_scenario "C. 무부하 baseline" sleep "$WINDOW"

echo ""
echo "=== 요약 (이력서 수치 교정용) ==="
echo "  * A(폴링)과 B(SSE)를 '동일 환경(로컬 docker, 1코어)'에서 비교"
echo "  * 절대 CPU%는 배포 서버와 다를 수 있으므로 감소율(상대값)로 표기 권장"
