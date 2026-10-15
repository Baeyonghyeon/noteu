package com.noteu.noteu.chat.repository.querydsl.impl;

import com.noteu.noteu.chat.entity.ChatMessage;
import com.noteu.noteu.chat.entity.QChatMessage;
import com.noteu.noteu.chat.repository.querydsl.ChatMessageRepositoryCustom;
import com.querydsl.jpa.JPAExpressions;
import org.springframework.data.jpa.repository.support.QuerydslRepositorySupport;

import java.util.List;

public class ChatMessageRepositoryCustomImpl extends QuerydslRepositorySupport implements ChatMessageRepositoryCustom {

    public ChatMessageRepositoryCustomImpl() {
        super(ChatMessage.class);
    }

    @Override
    public List<ChatMessage> findLastMessagesByRoomIds(List<Long> roomIds) {
        QChatMessage chatMessage = QChatMessage.chatMessage;
        QChatMessage m2 = new QChatMessage("m2"); // 서브쿼리용 별칭

        return from(chatMessage)
                .where(chatMessage.id.in(
                        JPAExpressions
                                .select(m2.id.max())          // 방별 최대 id = 방별 마지막 메시지
                                .from(m2)
                                .where(m2.roomId.in(roomIds))
                                .groupBy(m2.roomId)))
                .fetch();
    }
}
