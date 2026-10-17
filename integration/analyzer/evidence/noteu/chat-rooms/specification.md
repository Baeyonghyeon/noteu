# noteu 채팅방 목록 — 현재 코드에서 확인한 관찰 자료

대상: `GET /subjects/{subject-id}/chats/rooms`

이 문서는 오류 분석 연결을 위한 초기 자료다. 기획자와 개발자가 승인한 제품 명세가 아직 제공되지 않았으며, 아래 구현 관찰을 제품의 기대 정책으로 단정하면 안 된다.

## 현재 구현에서 확인한 사실

- `ChatRoomController.getAllRoom`은 경로의 `subject-id`와 인증 주체의 `MemberInfo.id`를 `RestChatService.findAllById`에 전달한다.
- `ChatRoomRepositoryCustomImpl.findAllBySubjectId`은 해당 과목에서 로그인 회원이 참가한 방을 조회한다.
- `RestChatServiceImpl.findAllById`은 조회된 방과 최근 메시지 정보를 이용해 `ChatRoomInfoResponseDto`를 만든다. 응답 객체에는 `chatRoomResponseDtos`와 `loginId`가 있다.
- 현재 `SecurityConfig`는 HTTP 경로를 `permitAll()`로 열어둔다. 이 컨트롤러에서는 `memberInfo`가 없는 상황을 검사하지 않고 `memberInfo.getId()`를 호출한다. 이는 오류 재현을 설명하는 구현 사실이며, 익명 사용자에 대한 의도된 정책의 근거가 아니다.

관찰한 소스 경로:

- `src/main/java/com/noteu/noteu/chat/controller/ChatRoomController.java`
- `src/main/java/com/noteu/noteu/chat/service/impl/RestChatServiceImpl.java`
- `src/main/java/com/noteu/noteu/chat/repository/querydsl/impl/ChatRoomRepositoryCustomImpl.java`
- `src/main/java/com/noteu/noteu/config/SecurityConfig.java`

## 정책 확인이 필요한 부분

- 비로그인 또는 세션 만료 사용자의 요청을 로그인 화면으로 이동시킬지, JSON 오류로 응답할지, 그리고 HTTP 상태를 무엇으로 할지는 확인되지 않았다.
- 과목이 없거나 해당 과목의 수강생이 아닌 경우의 응답 정책은 확인되지 않았다.
- 저장소 조회 실패 시 사용자가 볼 안내 문구와 재시도 정책은 확인되지 않았다.

이 조건에 관한 BDD 후보를 제안한다면 확인이 필요한 정책을 명시하고, HTTP 상태·문구·화면 이동을 확정된 기대 동작으로 작성하지 않는다. 실제 제품 명세가 준비되면 이 자료를 교체한다.
