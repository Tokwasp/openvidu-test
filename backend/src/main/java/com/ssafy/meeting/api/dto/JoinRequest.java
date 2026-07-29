package com.ssafy.meeting.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * 데모용 참여 요청. 실제 앱에선 memberId/이름은 세션(Redis)에서 온다(02 §7).
 * 여기서는 로그인 없이 흐름을 보기 위해 바디로 받는다.
 */
public record JoinRequest(
        @NotNull Integer memberId,
        String memberName
) {
    public String nameOrDefault() {
        return (memberName == null || memberName.isBlank()) ? "회원" + memberId : memberName;
    }
}
