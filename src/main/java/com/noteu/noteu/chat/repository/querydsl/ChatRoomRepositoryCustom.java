package com.noteu.noteu.chat.repository.querydsl;

import com.noteu.noteu.chat.entity.ChatParticipant;
import com.noteu.noteu.chat.entity.ChatRoom;
import com.querydsl.core.Tuple;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepositoryCustom {

    List<ChatRoom> findAllBySubjectId(Long subjectId, Long loginId);

    /** 성능 비교용 "1쿼리안" — 자기 조인(greatest-n-per-group) + fetch join. row = (ChatRoom, ChatMessage) */
    List<Tuple> findAllWithLastMessageSingleQuery(Long subjectId, Long loginId);

    /**
     * "v2" — 별칭 분리(참가자 컬렉션 온전히 fetch) + ON 절 상관 서브쿼리(방당 최신 메시지 1건)로 단일 쿼리.
     * 자기 조인(M²) 없이 1쿼리를 달성하고, findAllBySubjectId의 participants 빈 배열 버그도 해소.
     * row = (ChatRoom, ChatMessage) — 메시지 없는 방은 ChatMessage가 null
     */
    List<Tuple> findAllBySubjectIdV2(Long subjectId, Long loginId);

    Optional<ChatRoom> existsChatRoom(Long subjectId, Long friendId, Long loginId);
}
