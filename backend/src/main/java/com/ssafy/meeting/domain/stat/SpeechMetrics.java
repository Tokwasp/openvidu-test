package com.ssafy.meeting.domain.stat;

/**
 * VAD/DSP 처리 결과로 나오는 "숫자 묶음" (06 §4).
 * 데모에서는 VadAnalyzer 가 파일 크기 기반으로 근사치를 만들어 채운다(실제 신호처리 대신).
 */
public record SpeechMetrics(
        long speakingMs,
        int segmentCount,
        int avgSegmentMs,
        int longestSilenceMs,
        double avgVolume,
        double pitchVar,
        double speechRate,
        double toneJitter
) {
}
