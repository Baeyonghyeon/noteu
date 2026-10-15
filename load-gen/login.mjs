// load-gen/login.mjs — 테스트 사용자 10명 로그인 → sessions.json 생성
// 사용법: node login.mjs [비밀번호]   (기본 1234)
import { writeFileSync } from 'node:fs';

const BASE = process.env.BASE_URL ?? 'http://localhost:8080';
const PASSWORD = process.argv[2] ?? '1234';
const USERS = ['aaaa', 'bbbb', 'cccc', 'dddd', 'eeee', 'ffff', 'gggg', 'hhhh', 'iiii', 'jjjj'];

async function tryLogin(username, loginPath) {
  const res = await fetch(`${BASE}${loginPath}`, {
    method: 'POST',
    redirect: 'manual',
    headers: { 'content-type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ username, password: PASSWORD }),
  });
  const cookies = res.headers.getSetCookie ? res.headers.getSetCookie() : [res.headers.get('set-cookie') ?? ''];
  const cookie = (cookies.find((c) => c.startsWith('JSESSIONID=')) ?? '').split(';')[0];
  const location = res.headers.get('location') ?? '';
  const success = location.includes('/subjects');   // 성공 시 /subjects로 302 (절대/상대 URL 모두)
  return { cookie, success, status: res.status, location };
}

const sessions = [];
for (const username of USERS) {
  let r = await tryLogin(username, '/login');
  if (!r.success) r = await tryLogin(username, '/auth/login');  // 폼이 POST /auth/login인 앱 대비
  if (!r.success || !r.cookie) {
    console.error(`로그인 실패: ${username} (status=${r.status}, location=${r.location})`);
    process.exit(1);
  }
  sessions.push({ username, cookie: r.cookie });
  console.log(`login OK: ${username}`);
}

writeFileSync(new URL('./sessions.json', import.meta.url), JSON.stringify(sessions, null, 2));
console.log(`sessions.json 작성 완료 (${sessions.length}명)`);
