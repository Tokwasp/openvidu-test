package com.ssafy.meeting.api.dto;

/**
 * 참여 API 응답. 회의 식별자는 roomName(UUID) 으로 통일한다(meetingId 제거).
 * 01 §3: 매번 "지금 이 프로젝트의 회의방 이름"과 토큰을 함께 내려준다.
 * creatorId 는 그 방을 처음 연 회원 id — 방을 새로 만들면 요청자 자신, 기존 방이면 Redis 에 저장된 값.
 */
public record JoinResponse(
        String roomName,
        String token,
        String livekitUrl,
        boolean created,
        int creatorId
) {
}
