package com.ssafy.meeting.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * 회의 도메인 에러 코드.
 */
@Getter
@RequiredArgsConstructor
public enum MeetingErrorCode implements ErrorCode {

    ALREADY_JOINED(HttpStatus.CONFLICT, "이미 같은 회원이 이 회의에 참여 중입니다.");

    private final HttpStatus status;
    private final String message;
}
