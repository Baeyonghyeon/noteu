// load-gen/rooms.mjs — 방 목록 API 응답시간 측정 (N+1 전/후, 2쿼리안/1쿼리안 비교용)
// 단일 세션 순차 호출: 동시성 효과를 배제하고 요청당 비용(쿼리 수 → 응답시간)만 측정
// 사용법: node rooms.mjs [username=aaaa] [요청수=200] [warmup=20] [경로=/subjects/1/chats/rooms]
//   2쿼리안(기존): node rooms.mjs aaaa 200 20 /subjects/1/chats/rooms
//   1쿼리안(신규): node rooms.mjs aaaa 200 20 /subjects/1/chats/rooms/single
import { readFileSync } from 'node:fs';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const USERNAME = process.argv[2] ?? 'aaaa';
const REQUESTS = Number(process.argv[3] ?? 200);
const WARMUP = Number(process.argv[4] ?? 20);
const PATH = process.argv[5] ?? '/subjects/1/chats/rooms';

const sessions = JSON.parse(readFileSync(new URL('./sessions.json', import.meta.url), 'utf8'));
const session = sessions.find((s) => s.username === USERNAME);
if (!session) {
  console.error(`sessions.json에 '${USERNAME}' 없음 — login.mjs 먼저 실행`);
  process.exit(1);
}

const times = [];
let errors = 0;

for (let i = 0; i < WARMUP + REQUESTS; i++) {
  const t0 = performance.now();
  try {
    const res = await fetch(`${BASE}${PATH}`, {
      headers: { cookie: session.cookie },
    });
    await res.text(); // 응답 본문 소비 (커넥션 재사용 위해)
    if (!res.ok) { errors++; continue; }
    if (i >= WARMUP) times.push(performance.now() - t0);
  } catch {
    errors++;
  }
}

const sorted = [...times].sort((a, b) => a - b);
const q = (p) => (sorted.length
  ? sorted[Math.min(sorted.length - 1, Math.floor(sorted.length * p))].toFixed(1)
  : 'n/a');
const avg = times.length ? times.reduce((a, b) => a + b, 0) / times.length : 0;

console.log(JSON.stringify({
  user: USERNAME,
  path: PATH,
  requests: times.length,
  errors,
  avgMs: avg.toFixed(1),
  p50: q(0.5),
  p95: q(0.95),
  maxMs: sorted.length ? sorted.at(-1).toFixed(1) : 'n/a',
}, null, 2));
