package com.noteu.noteu.chat.service.impl;

import com.noteu.noteu.chat.converter.ChatConverter;
import com.noteu.noteu.chat.dto.ChatRoomResponse;
import com.noteu.noteu.chat.dto.response.ChatMessageResponseDto;
import com.noteu.noteu.chat.dto.response.ChatRoomInfoResponseDto;
import com.noteu.noteu.chat.dto.response.ChatRoomResponseDto;
import com.noteu.noteu.chat.entity.ChatMessage;
import com.noteu.noteu.chat.entity.ChatParticipant;
import com.noteu.noteu.chat.entity.ChatRoom;
import com.noteu.noteu.chat.repository.ChatMessageRepository;
import com.noteu.noteu.chat.repository.ChatParticipantRepository;
import com.noteu.noteu.chat.repository.ChatRoomRepository;
import com.noteu.noteu.chat.service.RestChatService;
import com.noteu.noteu.member.dto.response.MemberResponseDto;
import com.noteu.noteu.member.entity.Member;
import com.noteu.noteu.member.repository.MemberRepository;
import com.noteu.noteu.subject.entity.Subject;
import com.noteu.noteu.subject.repository.SubjectMemberRepository;
import com.noteu.noteu.subject.repository.SubjectRepository;
import com.querydsl.core.Tuple;
import jakarta.persistence.EntityExistsException;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
@Transactional
public class RestChatServiceImpl implements RestChatService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final ChatParticipantRepository chatParticipantRepository;
    private final SubjectRepository subjectRepository;
    private final SubjectMemberRepository subjectMemberRepository;
    private final MemberRepository memberRepository;
    private final ChatConverter converter;

    @Transactional(readOnly = true)
    @Override
    public ChatRoomInfoResponseDto findAllById(Long subjectId, Long loginId) {
        List<ChatRoom> chatRooms = chatRoomRepository.findAllBySubjectId(subjectId, loginId);   // 쿼리 1 (fetch join 포함)

        List<Long> roomIds = chatRooms.stream().map(ChatRoom::getId).toList();
        Map<Long, ChatMessage> lastMessageByRoomId = roomIds.isEmpty()
                ? Map.of()
                : chatMessageRepository.findLastMessagesByRoomIds(roomIds).stream()             // 쿼리 2 (일괄)
                        .collect(Collectors.toMap(ChatMessage::getRoomId, Function.identity()));

        // 기존 SQL의 ORDER BY chat_message.created_at DESC를 대체.
        // 정렬용 chat_message 조인을 쿼리 1에서 빼면 행 수가 (참가자 × 메시지)로 불어나지 않고,
        // MySQL 3065(ORDER BY 컬럼이 SELECT 목록에 없음)도 원천적으로 사라진다.
        Comparator<ChatRoom> byLastMessageDesc = Comparator.comparing(
                (ChatRoom room) -> Optional.ofNullable(lastMessageByRoomId.get(room.getId()))
                        .map(ChatMessage::getCreatedAt)
                        .orElse(null),
                Comparator.nullsLast(Comparator.reverseOrder()));   // 메시지 없는 방은 뒤로 (MySQL DESC와 동일)

        List<ChatRoomResponseDto> chatRoomResponseDtos = chatRooms.stream()
                .sorted(byLastMessageDesc)
                .map(chatRoom -> converter.chatRoomEntityToChatRoomDto(
                        chatRoom, loginId,
                        lastMessageByRoomId.getOrDefault(chatRoom.getId(),
                                new ChatMessage(null, null, null, ""))))   // 빈 방: 기존과 동일한 fallback
                .toList();

        return ChatRoomInfoResponseDto.builder()
                .chatRoomResponseDtos(chatRoomResponseDtos)
                .loginId(loginId)
                .build();
    }

    @Transactional(readOnly = true)
    @Override
    public ChatRoomInfoResponseDto findAllByIdSingleQuery(Long subjectId, Long loginId) {
        List<Tuple> rows = chatRoomRepository.findAllWithLastMessageSingleQuery(subjectId, loginId); // 쿼리 1 (자기 조인 + fetch join, "1쿼리안")

        Map<Long, ChatRoom> roomById = new LinkedHashMap<>();
        Map<Long, ChatMessage> lastMessageByRoomId = new HashMap<>();
        for (Tuple row : rows) {
            ChatRoom room = row.get(0, ChatRoom.class);
            ChatMessage message = row.get(1, ChatMessage.class);
            roomById.putIfAbsent(room.getId(), room);
            if (message != null) {
                lastMessageByRoomId.putIfAbsent(room.getId(), message);
            }
        }

        List<ChatRoomResponseDto> chatRoomResponseDtos = roomById.values().stream()
                .map(chatRoom -> converter.chatRoomEntityToChatRoomDto(
                        chatRoom, loginId,
                        lastMessageByRoomId.getOrDefault(chatRoom.getId(),
                                new ChatMessage(null, null, null, ""))))
                .toList();

        return ChatRoomInfoResponseDto.builder()
                .chatRoomResponseDtos(chatRoomResponseDtos)
                .loginId(loginId)
                .build();
    }

    @Transactional(readOnly = true)
    @Override
    public ChatRoomInfoResponseDto findAllByIdV2(Long subjectId, Long loginId) {
        List<Tuple> rows = chatRoomRepository.findAllBySubjectIdV2(subjectId, loginId);   // 쿼리 1개로 끝 ("v2")

        // 참가자 수만큼 방이 중복 등장 → 방 id 기준으로 접는다 (메시지는 방당 최대 1건이라 첫 값이 곧 최신)
        Map<Long, ChatRoom> roomById = new LinkedHashMap<>();
        Map<Long, ChatMessage> lastMessageByRoomId = new HashMap<>();
        for (Tuple row : rows) {
            ChatRoom room = row.get(0, ChatRoom.class);
            ChatMessage message = row.get(1, ChatMessage.class);
            roomById.putIfAbsent(room.getId(), room);
            if (message != null) {
                lastMessageByRoomId.putIfAbsent(room.getId(), message);
            }
        }

        List<ChatRoomResponseDto> chatRoomResponseDtos = roomById.values().stream()
                .map(chatRoom -> converter.chatRoomEntityToChatRoomDto(
                        chatRoom, loginId,
                        lastMessageByRoomId.getOrDefault(chatRoom.getId(),
                                new ChatMessage(null, null, null, ""))))
                .toList();

        return ChatRoomInfoResponseDto.builder()
                .chatRoomResponseDtos(chatRoomResponseDtos)
                .loginId(loginId)
                .build();
    }

    @Transactional(readOnly = true)
    @Override
    public List<MemberResponseDto> findAllSubjectsBySubjectId(Long subjectId, Long loginId) {
        return subjectMemberRepository.findAllSubjectsBySubjectId(subjectId).stream()
                .map(converter::memberEntityToMemberResponseDto)
                .filter(m -> !Objects.equals(m.getId(), loginId))
                .toList();
    }

    @Transactional(readOnly = true)
    @Override
    public List<ChatMessageResponseDto> findPastChat(Long roomId) {
        return chatMessageRepository.getAllByRoomId(roomId).stream()
                .map(converter::chatMessageEntityToChatMessageResponsedto)
                .toList();
    }

//    public void updateLastStay(Long roomId, Long loginId) {
//        ChatParticipant chatParticipant = chatParticipantRepository.findByChatRoomIdAndMemberId(roomId, loginId)
//                .orElseThrow(EntityNotFoundException::new);
//
//        chatParticipant.updateLastStay();
//    }

    @Override
    public ChatRoomResponse createRoom(Long subjectId, Long friendId, Long loginId) {
        return chatRoomRepository.existsChatRoom(subjectId, friendId, loginId)
                .map(chatRoom -> converter.chatRoomEntityToChatRoomExistResponseDto(chatRoom, friendId, loginId))
                .orElseGet(() -> {
                    Subject subject = subjectRepository.getReferenceById(subjectId);
                    ChatRoom chatRoom = chatRoomRepository.save(new ChatRoom(subject));
                    enterRoom(chatRoom, friendId, loginId);
                    return converter.chatRoomEntityToChatRoomDto(chatRoom, loginId, new ChatMessage(null, null, null, ""));
                });
    }

    // 생성된 방에 멤버 넣기
    private void enterRoom(ChatRoom chatRoom, Long friendId, Long loginId) {
        Member friend = memberRepository.getReferenceById(friendId);
        Member loginMember = memberRepository.getReferenceById(loginId);
        ChatParticipant save1 = chatParticipantRepository.save(new ChatParticipant(chatRoom, friend));
        ChatParticipant save2 = chatParticipantRepository.save(new ChatParticipant(chatRoom, loginMember));

        chatRoom.getParticipants().add(save1);
        chatRoom.getParticipants().add(save2);
        log.info("채팅방에 유저 담기 성공");
    }

}
