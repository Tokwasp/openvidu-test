package com.ssafy.meeting.common;

/**
 * 06 문서 예시 응답 형태({ status, message, data })에 맞춘 공통 래퍼.
 */
public record ApiResponse<T>(int status, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "성공", data);
    }
}
