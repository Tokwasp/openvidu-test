package com.ssafy.meeting.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 에러 코드 공통 계약. 도메인별 enum 이 구현한다(예: {@link MeetingErrorCode}).
 * 상태 코드와 사용자 노출 메시지를 들고 있고, 도메인 이름은 클래스명에서 유도한다.
 */
public interface ErrorCode {

    HttpStatus getStatus();

    String getMessage();

    default String getDomain() {
        return getClass().getSimpleName().replace("ErrorCode", "").toUpperCase();
    }
}
