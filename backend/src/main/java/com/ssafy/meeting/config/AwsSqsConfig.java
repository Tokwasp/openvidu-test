package com.ssafy.meeting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * SQS 클라이언트. 자격증명·리전은 S3 와 같은 IAM 사용자 키를 재사용한다.
 * (빌린 EC2 라 인스턴스 역할을 못 붙여 정적 키를 쓴다.)
 */
@Configuration
public class AwsSqsConfig {

    @Bean
    public SqsClient sqsClient(S3Properties s3) {
        return SqsClient.builder()
                .region(Region.of(s3.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.accessKey(), s3.secretKey())))
                .build();
    }
}
