// load-gen/sse.mjs — 사용자 N명이 SSE 연결 유지 (현재 구현 시뮬레이션)
// 브라우저 EventSource 동작 재현: 서버 타임아웃(30s)으로 스트림이 끊기면 자동 재연결
// fetch 스트리밍으로 SSE 프레임(event:/data: 라인)을 수동 파싱
// 사용법: node sse.mjs [지속초=60]
import { readFileSync } from 'node:fs';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const DURATION_SEC = Number(process.argv[2] ?? 60);
const sessions = JSON.parse(readFileSync(new URL('./sessions.json', import.meta.url), 'utf8'));

const endAt = Date.now() + DURATION_SEC * 1000;
let events = 0;
let connects = 0;
let reconnects = 0;
let errors = 0;

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function sseLoop(session) {
  while (Date.now() < endAt) {
    // 종료 시각이 되면 진행 중인 fetch를 강제로 중단 (서버는 30s 타임아웃 전에 스트림을 닫지 않음)
    const controller = new AbortController();
    const abortTimer = setTimeout(() => controller.abort(), Math.max(0, endAt - Date.now()) + 2000);
    try {
      const res = await fetch(`${BASE}/sse`, {
        headers: { cookie: session.cookie, accept: 'text/event-stream' },
        signal: controller.signal,
      });
      if (!res.ok || !res.body) {
        errors++;
        await sleep(1000);
        continue;
      }
      connects++;
      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let buf = '';
      while (true) {
        const { done, value } = await reader.read();
        if (done) {
          break;   // 서버가 스트림 닫음(30s 타임아웃) → 재연결
        }
        buf += decoder.decode(value, { stream: true });
        let idx;
        while ((idx = buf.indexOf('\n\n')) !== -1) {
          const frame = buf.slice(0, idx);
          buf = buf.slice(idx + 2);
          for (const line of frame.split('\n')) {
            if (line.startsWith('event:')) events++;
          }
        }
      }
      if (Date.now() < endAt) reconnects++;
    } catch (e) {
      if (e.name === 'AbortError') break;   // 측정 시간 종료
      errors++;
      await sleep(1000);   // 서버 미기동 등 → 과부하 방지 대기
    } finally {
      clearTimeout(abortTimer);
    }
  }
}

await Promise.all(sessions.map(sseLoop));
console.log(JSON.stringify({
  scenario: 'sse',
  users: sessions.length,
  durationSec: DURATION_SEC,
  initialConnects: connects,
  reconnects,
  eventsReceived: events,
  errors,
}));
