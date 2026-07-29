package com.ssafy.meeting.service;

import com.ssafy.meeting.domain.stat.SpeechMetrics;
import org.springframework.stereotype.Component;

/**
 * VAD(공백 제거) + DSP(음향 지표) 처리 자리 (06 §3~4).
 *
 * ⚠️ 데모 스텁: 실제 신호처리(Opus 디코딩 → VAD → 음량/피치 계산)는 하지 않는다.
 *    파일 크기를 재료로 "그럴듯한 숫자"를 결정적으로 만들어 파이프라인만 보여준다.
 *    실제 구현에서는 이 클래스만 실제 오디오 분석기로 교체하면 된다.
 */
@Component
public class VadAnalyzer {

    /**
     * @param objectKey MinIO 키 (예: meetings/{room}/7/2026-...ogg)
     * @param sizeBytes 파일 크기 — 발화 시간 근사에만 사용
     */
    public SpeechMetrics analyze(String objectKey, long sizeBytes) {
        // OGG/Opus ~16kByte/sec 가정으로 녹음 길이를 대충 환산 → 그중 60%를 발화로 본다(공백 제거 흉내)
        long recordedMs = sizeBytes <= 0 ? 30_000 : Math.max(3_000, sizeBytes / 16);
        long speakingMs = (long) (recordedMs * 0.6);

        int seed = Math.abs(objectKey.hashCode());
        int segmentCount = 3 + (seed % 12);                 // 발화 구간 3~14개
        int avgSegmentMs = (int) (speakingMs / segmentCount);
        int longestSilenceMs = 1_000 + (seed % 4_000);

        double avgVolume = 0.4 + (seed % 40) / 100.0;       // 0.40 ~ 0.79
        double pitchVar = 0.1 + (seed % 30) / 100.0;
        double speechRate = 2.5 + (seed % 20) / 10.0;       // 초당 음절 근사
        double toneJitter = 0.02 + (seed % 8) / 100.0;

        return new SpeechMetrics(
                speakingMs, segmentCount, avgSegmentMs, longestSilenceMs,
                avgVolume, pitchVar, speechRate, toneJitter);
    }
}
