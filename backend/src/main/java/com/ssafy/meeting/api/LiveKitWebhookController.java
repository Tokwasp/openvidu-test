package com.ssafy.meeting.api;

import com.ssafy.meeting.service.WebhookService;
import io.livekit.server.WebhookReceiver;
import livekit.LivekitWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * LiveKit(OpenVidu3) 웹훅 수신 (04 §5-1).
 *  - Authorization 헤더의 JWT로 서명을 검증한다 (WebhookReceiver).
 *  - 빨리 200을 돌려준다. 무거운 처리는 WebhookService → 워커로 넘어간다.
 *  - OpenVidu/LiveKit 서버 설정에 이 주소를 등록해야 이벤트가 온다.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LiveKitWebhookController {

    private final WebhookReceiver webhookReceiver;
    private final WebhookService webhookService;

    // LiveKit 은 웹훅을 Content-Type: application/webhook+json 으로 보낸다.
    // application/json 으로 제한하면 415 로 거부되어 방 생명주기·녹음이 동작하지 않는다.
    @PostMapping(value = "/api/livekit/webhook", consumes = MediaType.ALL_VALUE)
    public ResponseEntity<Void> receive(@RequestBody String body,
                                        @RequestHeader(value = "Authorization", required = false) String authHeader) {
        try {
            LivekitWebhook.WebhookEvent event = webhookReceiver.receive(body, authHeader);
            webhookService.handle(event);
        } catch (Exception e) {
            // 서명 실패 등은 로그만 남기고 200 — LiveKit 재시도 폭주를 막는다
            log.warn("[Webhook] 처리 오류(무시): {}", e.getMessage());
        }
        return ResponseEntity.ok().build();
    }
}
