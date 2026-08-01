package com.ssafy.meeting.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 비즈니스 예외를 공통 에러 응답으로 변환하는 단일 지점.
 * 서비스가 {@link CustomException} 을 던지면 여기서 상태 코드·메시지로 매핑한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CustomException.class)
    public ResponseEntity<ApiErrorResponse> handleBusinessException(CustomException e) {
        ErrorCode errorCode = e.getErrorCode();
        log.warn("[예외] domain={} message={}", errorCode.getDomain(), errorCode.getMessage());

        return ResponseEntity
                .status(errorCode.getStatus())
                .body(ApiErrorResponse.of(errorCode));
    }
}
