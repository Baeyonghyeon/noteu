package com.noteu.noteu.chat.service;

import com.noteu.noteu.chat.dto.ChatRoomResponse;
import com.noteu.noteu.chat.dto.response.ChatMessageResponseDto;
import com.noteu.noteu.chat.dto.response.ChatRoomInfoResponseDto;
import com.noteu.noteu.chat.dto.response.ChatRoomResponseDto;
import com.noteu.noteu.member.dto.response.MemberResponseDto;

import java.util.List;

public interface RestChatService {

    ChatRoomInfoResponseDto findAllById(Long friendId, Long loginId);

    /** 성능 비교용 "1쿼리안" — findAllById(2쿼리안, 사실상 3쿼리)와 동일한 응답을 자기 조인 단일 쿼리로 조회 */
    ChatRoomInfoResponseDto findAllByIdSingleQuery(Long subjectId, Long loginId);

    /** "v2" — 별칭 분리 + ON 절 상관 서브쿼리 단일 쿼리. participants가 실제로 채워지는 버전 */
    ChatRoomInfoResponseDto findAllByIdV2(Long subjectId, Long loginId);

    //채팅방 생성
    List<ChatMessageResponseDto> findPastChat(Long roomId);

    ChatRoomResponse createRoom(Long subjectId, Long friendId, Long loginMemberId);
    //이전 채팅 보임

    List<MemberResponseDto> findAllSubjectsBySubjectId(Long subjectId, Long loginId);
}
