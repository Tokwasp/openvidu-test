package com.ssafy.meeting.api.dto;

/**
 * 01 §3: 참여 API가 매번 "지금 이 프로젝트의 회의방 이름"과 토큰을 함께 내려준다.
 */
public record JoinResponse(
        Integer meetingId,
        String roomName,
        String token,
        String livekitUrl,
        boolean created
) {
}
