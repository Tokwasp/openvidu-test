package com.ssafy.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 녹음 파일이 올라갈 우리 AWS S3 버킷. (MinIO 대신)
 * Egress 가 요청마다 이 자격증명으로 S3 에 직접 업로드한다(04 §5-3, docs §8-1).
 * 백엔드는 파일을 중계하지 않는다.
 */
@ConfigurationProperties(prefix = "aws.s3")
public record S3Properties(
        String bucket,
        String region,
        String accessKey,
        String secretKey
) {
}
