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

    ROOM_NOT_FOUND(HttpStatus.NOT_FOUND, "종료할 회의를 찾을 수 없습니다."),
    NOT_ROOM_OWNER(HttpStatus.FORBIDDEN, "회의 방장만 종료할 수 있습니다.");

    private final HttpStatus status;
    private final String message;
}
