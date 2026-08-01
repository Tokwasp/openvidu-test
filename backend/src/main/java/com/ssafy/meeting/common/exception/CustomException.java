package com.ssafy.meeting.common.exception;

import lombok.Getter;

/**
 * 비즈니스 예외. {@link ErrorCode} 를 담아 던지면
 * {@link GlobalExceptionHandler} 가 한 곳에서 상태 코드·메시지로 변환한다.
 */
@Getter
public class CustomException extends RuntimeException {

    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }
}
