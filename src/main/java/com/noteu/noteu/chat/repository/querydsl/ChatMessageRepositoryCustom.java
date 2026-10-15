package com.noteu.noteu.chat.repository.querydsl;

import com.noteu.noteu.chat.entity.ChatMessage;

import java.util.List;

public interface ChatMessageRepositoryCustom {
    /** 방 목록의 "방당 최신 메시지"를 일괄 조회 — N+1 제거용 */
    List<ChatMessage> findLastMessagesByRoomIds(List<Long> roomIds);
}
