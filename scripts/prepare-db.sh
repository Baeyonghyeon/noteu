#!/usr/bin/env bash
# 테스트 DB 준비: 멤버 8명 비밀번호 통일(1234) + 멤버 9~10 추가 → 총 10명 로그인 가능
# 주의: 앱 재기동 시 ddl-auto create + data.sql 재시드로 초기화되므로 재기동 후엔 재실행 필요
set -euo pipefail
cd "$(dirname "$0")/.."

MYSQL="docker exec noteu-mysql mysql -unoteu -pnoteu noteu"
# 작은따옴표 SQL 안에서는 $ 확장이 없으므로 해시를 그대로 전달 (sed 이스케이프 불필요)
PASSWORD_BCRYPT=$(
  docker run --rm httpd:2.4-alpine htpasswd -bnBC 10 "" 1234 | cut -d: -f2
)

echo "== 멤버 1~10 비밀번호를 1234로 통일 (멤버 9~10이 이미 있으면 덮어쓰기) =="
for i in $(seq 1 10); do
  $MYSQL -e "UPDATE member SET password='${PASSWORD_BCRYPT}' WHERE id=${i};"
done

echo "== 멤버 9~10 추가 (iiii, jjjj) — 재실행 대비 INSERT IGNORE =="
$MYSQL -e "
INSERT IGNORE INTO member (id, username, password, member_name, email, tel, introduction, profile, created_at, modified_at) VALUES
(9, 'iiii', '${PASSWORD_BCRYPT}', 'nameI', 'i@i', '999', '소개합니다9', '/file/profile/default.png', NOW(), NOW()),
(10, 'jjjj', '${PASSWORD_BCRYPT}', 'nameJ', 'j@j', '1010', '소개합니다10', '/file/profile/default.png', NOW(), NOW());
INSERT IGNORE INTO member_role (member_id, role) VALUES (9, 2), (10, 2);
INSERT IGNORE INTO subject_member (created_at, modified_at, member_id, subject_id) VALUES (NOW(), NOW(), 9, 1), (NOW(), NOW(), 10, 1);
"

echo "== 검증 =="
$MYSQL -N -e "SELECT COUNT(*) AS members FROM member;" | awk '{print "member 수: " $1}'
$MYSQL -N -e "SELECT COUNT(*) FROM subject_member WHERE subject_id=1;" | awk '{print "과목1 수강생 수: " $1}'
echo "준비 완료 — 10명 모두 username/password=1234 로그인 가능"
