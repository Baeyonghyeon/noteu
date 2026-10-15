package com.noteu.noteu.chat.repository;

import com.noteu.noteu.chat.entity.ChatMessage;
import com.noteu.noteu.chat.repository.querydsl.ChatMessageRepositoryCustom;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long>, ChatMessageRepositoryCustom {
    List<ChatMessage> getAllByRoomId(Long roomId);
}
