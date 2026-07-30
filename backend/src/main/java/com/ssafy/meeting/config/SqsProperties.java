package com.ssafy.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 녹음 후처리 큐. egress_ended 웹훅에서 이 큐로 메시지를 넣고,
 * 워커/Lambda 가 꺼내 VAD → 발화시간 → RDS 로 이어간다.
 * 자격증명·리전은 S3 와 동일(같은 IAM 사용자 키)이라 S3Properties 를 재사용한다.
 * queue-url 이 비어 있으면 발행을 생략한다(로컬/개발).
 */
@ConfigurationProperties(prefix = "aws.sqs")
public record SqsProperties(
        String queueUrl
) {
}
