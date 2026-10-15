package com.noteu.noteu.chat.repository.querydsl.impl;

import com.noteu.noteu.chat.entity.*;
import com.noteu.noteu.chat.repository.querydsl.ChatRoomRepositoryCustom;
import com.noteu.noteu.member.entity.QMember;
import com.noteu.noteu.subject.entity.QSubject;
import com.querydsl.core.Tuple;
import com.querydsl.jpa.JPAExpressions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.repository.support.QuerydslRepositorySupport;

import java.util.List;
import java.util.Optional;

@Slf4j
public class ChatRoomRepositoryCustomImpl extends QuerydslRepositorySupport implements ChatRoomRepositoryCustom {

    public ChatRoomRepositoryCustomImpl() {
        super(ChatRoom.class);
    }

    @Override
    public List<ChatRoom> findAllBySubjectId(Long subjectId, Long loginId) {
        QChatRoom chatRoom = QChatRoom.chatRoom;
        QChatParticipant chatParticipant = QChatParticipant.chatParticipant;
        QMember member = QMember.member;
        QSubject subject = QSubject.subject;
        QChatParticipant meCp = new QChatParticipant("meCp"); // 방 선별 전용 별칭

        return from(chatRoom)
                .join(chatRoom.subject, subject).fetchJoin()                // EAGER subject 추가 조회(③)를 쿼리 1에 통합
                .join(chatRoom.participants, chatParticipant).fetchJoin()   // 참가자 lazy load(②) 제거
                .join(chatParticipant.member, member).fetchJoin()           // 참가자의 member 즉시 로딩
                .leftJoin(member.role).fetchJoin()                          // member.role(@ElementCollection EAGER) 추가 조회 제거
                // 필터용 별칭을 fetch용과 분리 — WHERE가 fetch join된 participants 컬렉션을 자르지 않게 한다
                .join(meCp).on(meCp.chatRoom.eq(chatRoom)
                        .and(meCp.member.id.eq(loginId)))
                .where(chatRoom.subject.id.eq(subjectId))
                .select(chatRoom)
                .fetch()
                .stream()
                // fetch join으로 중복된 방 인스턴스(참가자 수 × role 수만큼 반복) 제거.
                .distinct()
                .toList();
    }

    @Override
    public List<Tuple> findAllWithLastMessageSingleQuery(Long subjectId, Long loginId) {
        QChatRoom chatRoom = QChatRoom.chatRoom;
        QChatParticipant chatParticipant = QChatParticipant.chatParticipant;
        QMember member = QMember.member;
        QSubject subject = QSubject.subject;
        QChatMessage chatMessage = QChatMessage.chatMessage;
        QChatMessage newer = new QChatMessage("newer"); // 자기 조인용 별칭 — "나보다 최신 메시지가 있으면 탈락"

        return from(chatRoom)
                .join(chatRoom.subject, subject).fetchJoin()
                .join(chatRoom.participants, chatParticipant).fetchJoin()
                .join(chatParticipant.member, member).fetchJoin()
                .leftJoin(chatMessage).on(chatRoom.id.eq(chatMessage.roomId))
                .leftJoin(newer).on(chatMessage.roomId.eq(newer.roomId)
                        .and(chatMessage.id.lt(newer.id)))
                .where(chatRoom.subject.id.eq(subjectId)
                        .and(member.id.eq(loginId))
                        .and(newer.id.isNull())) // 자기보다 최신인 메시지가 없는 행만 = 방별 최신 메시지
                .orderBy(chatMessage.createdAt.desc())
                .select(chatRoom, chatMessage)
                .fetch();
    }

    @Override
    public List<Tuple> findAllBySubjectIdV2(Long subjectId, Long loginId) {
        QChatRoom chatRoom = QChatRoom.chatRoom;
        QChatParticipant chatParticipant = QChatParticipant.chatParticipant;
        QMember member = QMember.member;
        QSubject subject = QSubject.subject;
        QChatMessage chatMessage = QChatMessage.chatMessage;
        QChatParticipant meCp = new QChatParticipant("meCp");   // 방 선별 전용 별칭
        QChatMessage m2 = new QChatMessage("m2");               // 방별 최신 id 서브쿼리용

        return from(chatRoom)
                .join(chatRoom.subject, subject).fetchJoin()
                .join(chatRoom.participants, chatParticipant).fetchJoin()  // WHERE 필터 없음 → 참가자 전원이 컬렉션에 담김
                .join(chatParticipant.member, member).fetchJoin()
                .leftJoin(member.role).fetchJoin()                         // member.role EAGER 추가 조회 제거
                // 필터용 별칭을 fetch용과 분리: "내가 참가한 방"만 선별하되 컬렉션은 자르지 않는다
                .join(meCp).on(meCp.chatRoom.eq(chatRoom)
                        .and(meCp.member.id.eq(loginId)))
                // 자기 조인(M²) 대신 상관 서브쿼리로 방당 최신 1건만 매칭 → 행이 메시지 수만큼 불어나지 않음
                .leftJoin(chatMessage).on(chatMessage.id.eq(
                        JPAExpressions.select(m2.id.max())
                                .from(m2)
                                .where(m2.roomId.eq(chatRoom.id))))
                .where(chatRoom.subject.id.eq(subjectId))
                .orderBy(chatMessage.createdAt.desc())
                .select(chatRoom, chatMessage)
                .fetch();
    }

    @Override
    public Optional<ChatRoom> existsChatRoom(Long subjectId, Long friendId, Long loginId) {
        QChatRoom chatRoom = QChatRoom.chatRoom;
        QChatParticipant chatParticipant = QChatParticipant.chatParticipant;

        return Optional.ofNullable(
                from(chatRoom)
                        .join(chatRoom.participants, chatParticipant)
                        .where(chatRoom.subject.id.eq(subjectId)
                                .and(chatParticipant.member.id.in(friendId, loginId)))
                        .groupBy(chatRoom.id)
                        .having(chatParticipant.member.id.countDistinct().goe(2L))
                        .fetchOne()
        );
    }
}
