package com.ssafy.meeting.service;

import com.ssafy.meeting.config.MinioProperties;
import io.minio.GetObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;

/**
 * 워커가 MinIO(스크래치)에서 녹음 파일을 읽고, 처리 후 삭제한다 (06 §3).
 * MinIO는 서버 디스크라 안 지우면 쌓여서 녹음이 멈춘다 → 반드시 삭제.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MinioStorage {

    private final MinioClient minioClient;
    private final MinioProperties props;

    /** 파일 크기(bytes). 데모 VAD가 발언 시간을 근사하는 재료로 쓴다. */
    public long size(String objectKey) {
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
            return stat.size();
        } catch (Exception e) {
            log.warn("[MinIO] stat 실패 {}: {}", objectKey, e.getMessage());
            return 0L;
        }
    }

    /** 실제 신호처리라면 이 스트림을 디코딩해 VAD를 돌린다. 데모에선 존재 확인 용도. */
    public InputStream open(String objectKey) throws Exception {
        return minioClient.getObject(
                GetObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
    }

    public void delete(String objectKey) {
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder().bucket(props.bucket()).object(objectKey).build());
            log.info("[MinIO] 처리 완료 후 삭제: {}", objectKey);
        } catch (Exception e) {
            log.warn("[MinIO] 삭제 실패 {}: {}", objectKey, e.getMessage());
        }
    }
}
