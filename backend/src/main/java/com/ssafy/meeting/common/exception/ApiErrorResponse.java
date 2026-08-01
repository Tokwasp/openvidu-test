package com.ssafy.meeting.common.exception;

/**
 * 에러 응답 본문. 성공 응답({@code ApiResponse})과 짝을 이루는 실패 포맷.
 */
public record ApiErrorResponse(int status, String message) {

    public static ApiErrorResponse of(ErrorCode errorCode) {
        return new ApiErrorResponse(errorCode.getStatus().value(), errorCode.getMessage());
    }
}
