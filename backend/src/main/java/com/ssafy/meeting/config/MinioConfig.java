package com.ssafy.meeting.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Slf4j
@Configuration
public class MinioConfig {

    /**
     * 워커가 녹음 파일을 읽고 삭제할 때 쓰는 MinIO 클라이언트.
     * (Egress가 파일을 쓰는 목적지 자격증명은 EgressService 에서 요청마다 함께 넘긴다.)
     * 시작 시 버킷이 없으면 만들어 둔다 — 06 문서의 "스크래치 버킷".
     */
    @Bean
    public MinioClient minioClient(MinioProperties props) {
        MinioClient client = MinioClient.builder()
                .endpoint(props.endpoint())
                .credentials(props.accessKey(), props.secretKey())
                .build();
        ensureBucket(client, props.bucket());
        return client;
    }

    private void ensureBucket(MinioClient client, String bucket) {
        try {
            boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if (!exists) {
                client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                log.info("[MinIO] 스크래치 버킷 생성: {}", bucket);
            }
        } catch (Exception e) {
            // MinIO가 아직 안 떴을 수 있음 — 첫 녹음 전에만 있으면 되므로 경고만 남긴다
            log.warn("[MinIO] 버킷 확인 실패(무시하고 계속): {}", e.getMessage());
        }
    }
}
