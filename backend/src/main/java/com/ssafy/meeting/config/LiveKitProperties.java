package com.ssafy.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * LiveKit(OpenVidu3) 접속 설정. application.yml 의 livekit.* 를 바인딩한다.
 */
@ConfigurationProperties(prefix = "livekit")
public record LiveKitProperties(
        String wsUrl,
        String host,
        String apiKey,
        String apiSecret,
        long tokenTtlMs,        // 밀리초. AccessToken.setTtl 이 ms 단위를 받는다.
        int emptyTimeoutSec,
        int departureTimeoutSec
) {
}
