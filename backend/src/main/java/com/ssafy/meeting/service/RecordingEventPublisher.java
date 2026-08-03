package com.ssafy.meeting.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ssafy.meeting.config.SqsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 녹음 후처리 메시지를 SQS 로 발행한다. 메시지 내용은 단체·개인이 같고(RecordingEvent),
 * kind 에 따라 큐만 나눈다: MIXED(단체)는 단체 큐, PARTICIPANT(개인)는 개인 큐.
 * 대상 큐 URL 이 비어 있으면 조용히 생략(로컬/개발). 발행 실패는 로그만 — 웹훅은 200 을 유지해야 한다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingEventPublisher {

    /** RecordingEvent.kind 값 — 개인(참가자별) 녹음. */
    private static final String KIND_PARTICIPANT = "PARTICIPANT";

    private final SqsClient sqsClient;
    private final SqsProperties sqsProps;
    private final ObjectMapper objectMapper;

    public void publish(RecordingEvent event) {
        String url = resolveQueueUrl(event);
        if (url == null || url.isBlank()) {
            log.debug("[SQS] 대상 큐 미설정 → 발행 생략 room={} kind={}", event.roomName(), event.kind());
            return;
        }
        try {
            String body = objectMapper.writeValueAsString(event);
            sqsClient.sendMessage(b -> b.queueUrl(url).messageBody(body));
            log.info("[SQS] 발행 room={} project={} member={} kind={} key={}",
                    event.roomName(), event.projectId(), event.memberId(), event.kind(), event.objectKey());
        } catch (Exception e) {
            log.warn("[SQS] 발행 실패(무시): {}", e.getMessage());
        }
    }

    /**
     * kind 로 대상 큐를 고른다. 개인(PARTICIPANT)은 개인 큐로,
     * 나머지(단체 믹스)는 단체 큐로 보낸다. 개인 큐가 비어 있으면 단체 큐로 폴백한다(하위 호환).
     */
    private String resolveQueueUrl(RecordingEvent event) {
        if (KIND_PARTICIPANT.equals(event.kind())) {
            String personalUrl = sqsProps.personalQueueUrl();
            if (personalUrl != null && !personalUrl.isBlank()) {
                return personalUrl;
            }
        }
        return sqsProps.queueUrl();
    }
}
