// load-gen/polling.mjs — 사용자 N명이 REST로 방 목록을 주기 폴링 (폴링 방식 시뮬레이션)
// SSE가 대체한 것과 동일한 정보(= 채팅방 목록+마지막 메시지)를 "폴링이었다면" 가져오는 요청 패턴
// 사용법: node polling.mjs [간격ms=1000] [지속초=60]
import { readFileSync } from 'node:fs';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const INTERVAL_MS = Number(process.argv[2] ?? 1000);
const DURATION_SEC = Number(process.argv[3] ?? 60);
const sessions = JSON.parse(readFileSync(new URL('./sessions.json', import.meta.url), 'utf8'));

let polls = 0;
let errors = 0;
const startedAt = Date.now();

async function poll(session) {
  try {
    const res = await fetch(`${BASE}/subjects/1/chats/rooms`, {
      headers: { cookie: session.cookie },
    });
    if (res.ok) polls++;
    else {
      errors++;
      console.error(`poll error: ${session.username} status=${res.status}`);
    }
  } catch (e) {
    errors++;
  }
}

const timers = sessions.map((s) => setInterval(() => poll(s), INTERVAL_MS));

await new Promise((r) => setTimeout(r, DURATION_SEC * 1000));
timers.forEach(clearInterval);

const elapsed = (Date.now() - startedAt) / 1000;
console.log(JSON.stringify({
  scenario: 'polling',
  users: sessions.length,
  intervalMs: INTERVAL_MS,
  durationSec: elapsed.toFixed(1),
  requests: polls,
  errors,
  reqPerSec: (polls / elapsed).toFixed(2),
}));
