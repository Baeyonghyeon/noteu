package com.noteu.noteu.chat.controller;

import com.noteu.noteu.chat.dto.ChatRoomResponse;
import com.noteu.noteu.chat.dto.response.ChatMessageResponseDto;
import com.noteu.noteu.chat.dto.response.ChatRoomInfoResponseDto;
import com.noteu.noteu.chat.dto.response.ChatRoomResponseDto;
import com.noteu.noteu.chat.service.RestChatService;
import com.noteu.noteu.member.dto.MemberInfo;
import com.noteu.noteu.member.dto.response.MemberResponseDto;
import com.noteu.noteu.sse.SseEmitterService;
import com.noteu.noteu.sse.dto.SseNewChatDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.Map;

@Slf4j
@Controller
@RequiredArgsConstructor
@RequestMapping("subjects/{subject-id}/chats")
public class ChatRoomController {

    private final RestChatService restChatService;
    private final SseEmitterService sseEmitterService;

    @GetMapping
    public String room(@PathVariable("subject-id") Long subjectId,
                       @AuthenticationPrincipal MemberInfo memberInfo,
                       Model model) {
        model.addAttribute("subjectId", subjectId);
        model.addAttribute("memberId", memberInfo.getId());
        model.addAttribute("memberName", memberInfo.getMemberName());
        model.addAttribute("profile", memberInfo.getProfile());

        return "layout/chat/chat";
    }

    // 모든 채팅방 목록 반환
    @GetMapping("/rooms")
    @ResponseBody
    public ChatRoomInfoResponseDto getAllRoom(@PathVariable("subject-id") Long subjectId,
                                              @AuthenticationPrincipal MemberInfo memberInfo) {

        return restChatService.findAllById(subjectId, memberInfo.getId());
    }

    // 성능 비교용: 자기 조인 단일 쿼리("1쿼리안") — /rooms(2쿼리안)와 나란히 두고 측정용
    @GetMapping("/rooms/single")
    @ResponseBody
    public ChatRoomInfoResponseDto getAllRoomSingleQuery(@PathVariable("subject-id") Long subjectId,
                                              @AuthenticationPrincipal MemberInfo memberInfo) {

        return restChatService.findAllByIdSingleQuery(subjectId, memberInfo.getId());
    }

    // 성능 비교용: "v2" — 별칭 분리 + ON 절 상관 서브쿼리 단일 쿼리
    @GetMapping("/rooms/v2")
    @ResponseBody
    public ChatRoomInfoResponseDto getAllRoomV2(@PathVariable("subject-id") Long subjectId,
                                                @AuthenticationPrincipal MemberInfo memberInfo) {

        return restChatService.findAllByIdV2(subjectId, memberInfo.getId());
    }

    // 같은반 친구들 목록 반환
    @GetMapping("/friends")
    @ResponseBody
    public List<MemberResponseDto> getAllFriend(@PathVariable("subject-id") Long subjectId,
                                                @AuthenticationPrincipal MemberInfo memberInfo) {
        List<MemberResponseDto> allSubjectsBySubjectId = restChatService.findAllSubjectsBySubjectId(subjectId, memberInfo.getId());
        log.info("멤버 정보들을 다 가져옵니다. {}", allSubjectsBySubjectId);
        return allSubjectsBySubjectId;
    }

    @PostMapping("/rooms")
    @ResponseBody
    public ResponseEntity<ChatRoomResponse> createRoom(@RequestBody Map<String, Long> requestBody,
                                                       @AuthenticationPrincipal MemberInfo memberInfo,
                                                       @PathVariable("subject-id") Long subjectId) {
        Long friendId = requestBody.get("friendId");
        ChatRoomResponse chatRoomResponseDto = restChatService.createRoom(subjectId, friendId, memberInfo.getId());

        if (chatRoomResponseDto instanceof ChatRoomResponseDto) {
            Long roomId = ((ChatRoomResponseDto) chatRoomResponseDto).getId();
            SseNewChatDto sseNewChatDto = SseNewChatDto.builder()
                    .memberId(friendId)
                    .friendId(memberInfo.getId())
                    .subjectId(subjectId)
                    .roomId(roomId)
                    .build();
            log.info("sseNewChatDto 생성 확인 : {}", sseNewChatDto);
            sseEmitterService.newChatRoom(sseNewChatDto);
        }

        return ResponseEntity
                .created(URI.create("/subjects/" + subjectId + "/chats"))
                .body(chatRoomResponseDto);
    }

    // 채팅방 입장 화면
    @GetMapping("/rooms/enter/{roomId}")
    public String roomDetail(Model model, @PathVariable Long roomId,
                             @AuthenticationPrincipal MemberInfo memberInfo) {

        model.addAttribute("roomId", roomId);

        return "fragments/content/chat/roomdetail";
    }

    @ResponseBody
    @GetMapping("/rooms/api")
    public List<ChatMessageResponseDto> pastChat(@RequestParam Long roomId) {
        List<ChatMessageResponseDto> chatMessageResponseDtos = restChatService.findPastChat(roomId);
        log.info("이전 채팅 불러오기 : {}", chatMessageResponseDtos);

        return chatMessageResponseDtos;
    }
}
