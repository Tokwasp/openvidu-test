package com.ssafy.meeting.service;

import com.ssafy.meeting.domain.recording.MeetingRecording;
import com.ssafy.meeting.domain.recording.MeetingRecordingRepository;
import com.ssafy.meeting.domain.stat.MeetingSpeechStat;
import com.ssafy.meeting.domain.stat.MeetingSpeechStatRepository;
import com.ssafy.meeting.domain.stat.SpeechMetrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * egress_ended → 별도 워커에서 도는 VAD 파이프라인 (06 §3).
 *   1. MinIO에서 .ogg 읽기(크기 확인)
 *   2. VAD로 공백 제거 → 발언 시간/음향 지표 산출 (VadAnalyzer 스텁)
 *   3. 숫자만 DB(meeting_speech_stat)에 저장 (사람 단위 합산)
 *   4. MinIO의 .ogg 삭제  ← 핵심: 안 지우면 디스크가 쌓여 녹음이 멈춘다
 *
 * 웹훅 스레드가 아니라 @Async 스레드풀에서 돈다. egress_id로 멱등 처리.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingWorker {

    private final MeetingRecordingRepository recordingRepository;
    private final MeetingSpeechStatRepository statRepository;
    private final MinioStorage minioStorage;
    private final VadAnalyzer vadAnalyzer;

    @Async("recordingExecutor")
    @Transactional
    public void process(String egressId) {
        MeetingRecording rec = recordingRepository.findByEgressId(egressId).orElse(null);
        if (rec == null) {
            log.warn("[Worker] 알 수 없는 egressId={} (매핑 없음)", egressId);
            return;
        }
        if ("PROCESSED".equals(rec.getStatus())) {
            log.info("[Worker] 이미 처리됨(멱등) egressId={}", egressId);
            return;
        }
        if ("FAILED".equals(rec.getStatus()) || rec.getObjectKey() == null) {
            log.warn("[Worker] 처리 불가 상태 egressId={} status={}", egressId, rec.getStatus());
            return;
        }

        String objectKey = rec.getObjectKey();
        log.info("[Worker] 시작 egressId={} kind={} key={}", egressId, rec.getKind(), objectKey);

        // 1. MinIO에서 파일 확인/읽기
        long size = minioStorage.size(objectKey);
        log.info("[Worker] MinIO 파일 크기 {} bytes", size);

        // 전체 믹스는 발언 시간·코칭에 안 쓰이므로 지표는 안 뽑고 삭제만 한다 (06 §1)
        if (rec.isMixed()) {
            minioStorage.delete(objectKey);
            rec.markProcessed();
            log.info("[Worker] mixed 파일 삭제 완료 egressId={}", egressId);
            return;
        }

        // 2. VAD + DSP (스텁) → 숫자
        SpeechMetrics metrics = vadAnalyzer.analyze(objectKey, size);
        log.info("[Worker] VAD 결과 speakingMs={} segments={}",
                metrics.speakingMs(), metrics.segmentCount());

        // 3. 사람 단위로 합산해 저장 (재입장 시 여러 녹음 → 한 행)
        statRepository.findByMeetingIdAndMemberId(rec.getMeetingId(), rec.getMemberId())
                .ifPresentOrElse(
                        existing -> existing.accumulate(metrics),
                        () -> statRepository.save(
                                MeetingSpeechStat.of(rec.getMeetingId(), rec.getMemberId(), metrics)));

        // 4. 파일 삭제 + 로그행 정리
        minioStorage.delete(objectKey);
        rec.markProcessed();
        log.info("[Worker] 완료 egressId={} member={} → 파일 삭제, 숫자만 남김",
                egressId, rec.getMemberId());
    }
}
