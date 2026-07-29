package com.ssafy.meeting.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * 04/06 문서: egress_ended 웹훅은 빨리 200을 돌려주고,
 * 무거운 VAD 처리는 "별도 워커/큐"에서 돌린다. 여기서는 그 큐를 스레드풀로 단순화한다.
 */
@EnableAsync
@Configuration
public class AsyncConfig {

    @Bean(name = "recordingExecutor")
    public Executor recordingExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("vad-worker-");
        executor.initialize();
        return executor;
    }
}
