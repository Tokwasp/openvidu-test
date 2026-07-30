package com.ssafy.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.meeting.config.SqsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 녹음 후처리 메시지를 SQS 로 발행한다.
 * queue-url 이 비어 있으면 조용히 생략(로컬/개발). 발행 실패는 로그만 — 웹훅은 200 을 유지해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingEventPublisher {

    private final SqsClient sqsClient;
    private final SqsProperties sqsProps;
    private final ObjectMapper objectMapper;

    public void publish(RecordingEvent event) {
        String url = sqsProps.queueUrl();
        if (url == null || url.isBlank()) {
            log.debug("[SQS] queue-url 미설정 → 발행 생략 room={}", event.roomName());
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(event);
            sqsClient.sendMessage(b -> b.queueUrl(url).messageBody(body));
            log.info("[SQS] 발행 room={} member={} kind={} key={}",
                    event.roomName(), event.memberId(), event.kind(), event.objectKey());
        } catch (Exception e) {
            log.warn("[SQS] 발행 실패(무시): {}", e.getMessage());
        }
    }
}
