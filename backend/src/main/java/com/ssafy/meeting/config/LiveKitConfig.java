package com.ssafy.meeting.config;

import io.livekit.server.EgressServiceClient;
import io.livekit.server.RoomServiceClient;
import io.livekit.server.WebhookReceiver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LiveKit 서버와 대화하는 클라이언트 3종.
 *  - RoomServiceClient  : createRoom / deleteRoom
 *  - EgressServiceClient : 녹음(Egress) 시작
 *  - WebhookReceiver     : LiveKit이 보내는 웹훅 서명 검증
 */
@Configuration
public class LiveKitConfig {

    @Bean
    public RoomServiceClient roomServiceClient(LiveKitProperties props) {
        return RoomServiceClient.create(props.host(), props.apiKey(), props.apiSecret());
    }

    @Bean
    public EgressServiceClient egressServiceClient(LiveKitProperties props) {
        return EgressServiceClient.create(props.host(), props.apiKey(), props.apiSecret());
    }

    @Bean
    public WebhookReceiver webhookReceiver(LiveKitProperties props) {
        return new WebhookReceiver(props.apiKey(), props.apiSecret());
    }
}
