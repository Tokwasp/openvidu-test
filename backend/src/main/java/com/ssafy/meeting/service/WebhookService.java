package com.ssafy.meeting.service;

import com.ssafy.meeting.domain.meeting.Meeting;
import com.ssafy.meeting.domain.meeting.MeetingRepository;
import com.ssafy.meeting.domain.recording.MeetingRecording;
import com.ssafy.meeting.domain.recording.MeetingRecordingRepository;
import livekit.LivekitEgress;
import livekit.LivekitModels;
import livekit.LivekitWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * LiveKit 웹훅 → 우리 도메인 반응 (01 §5, 03 §6, 06 §3).
 *   room_started       → started_at 채우고 전체 믹스 Egress 시작
 *   participant_joined → 사람별 Egress 시작 + (egressId→memberId) 매핑 저장
 *   room_finished      → meeting.close()  (유일한 닫는 경로, 멱등)
 *   egress_ended       → 녹음 로그 채우고 VAD 워커에 넘김
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final MeetingRepository meetingRepository;
    private final MeetingRecordingRepository recordingRepository;
    private final EgressService egressService;
    private final RecordingWorker recordingWorker;

    @Transactional
    public void handle(LivekitWebhook.WebhookEvent event) {
        String type = event.getEvent();
        log.info("[Webhook] event={}", type);
        switch (type) {
            case "room_started"       -> onRoomStarted(event.getRoom());
            case "participant_joined" -> onParticipantJoined(event.getRoom(), event.getParticipant());
            case "room_finished"      -> onRoomFinished(event.getRoom());
            case "egress_ended", "egress_updated" -> onEgressEnded(event.getEgressInfo());
            default -> log.debug("[Webhook] 처리 안 함: {}", type);
        }
    }

    private void onRoomStarted(LivekitModels.Room room) {
        meetingByRoom(room.getName()).ifPresent(meeting -> {
            meeting.markStarted(LocalDateTime.now());
            // 전체 믹스(RoomComposite, audioOnly) 시작 — 첫 참가자일 때 한 번
            String egressId = egressService.startMixedEgress(meeting.getRoomName());
            saveMappingIfAbsent(MeetingRecording.mixed(egressId, meeting.getId()));
        });
    }

    private void onParticipantJoined(LivekitModels.Room room, LivekitModels.ParticipantInfo participant) {
        Integer memberId = parseMemberId(participant.getIdentity());
        if (memberId == null) {
            log.debug("[Webhook] 숫자 아닌 identity 무시: {}", participant.getIdentity());
            return;
        }
        meetingByRoom(room.getName()).ifPresent(meeting -> {
            String egressId = egressService.startParticipantEgress(meeting.getRoomName(), memberId);
            saveMappingIfAbsent(MeetingRecording.participant(egressId, meeting.getId(), memberId));
        });
    }

    private void onRoomFinished(LivekitModels.Room room) {
        meetingByRoom(room.getName())
                .ifPresent(meeting -> meeting.close(LocalDateTime.now()));
    }

    private void onEgressEnded(LivekitEgress.EgressInfo info) {
        if (info == null) {
            return;
        }
        String egressId = info.getEgressId();
        MeetingRecording rec = recordingRepository.findByEgressId(egressId).orElse(null);
        if (rec == null) {
            log.warn("[Webhook] egress_ended 매핑 없음 egressId={}", egressId);
            return;
        }
        if ("COMPLETE".equals(rec.getStatus()) || "PROCESSED".equals(rec.getStatus())) {
            return; // 멱등: 이미 받은 egress_ended
        }

        boolean ok = info.getStatus() == LivekitEgress.EgressStatus.EGRESS_COMPLETE;
        if (!ok || info.getFileResultsCount() == 0) {
            rec.fail();
            log.warn("[Webhook] egress 실패 egressId={} status={}", egressId, info.getStatus());
            return;
        }

        LivekitEgress.FileInfo file = info.getFileResults(0);
        rec.complete(file.getFilename(), LocalDateTime.now());
        log.info("[Webhook] egress_ended → 녹음 위치 확보 key={} → 워커에 넘김", file.getFilename());

        // 무거운 VAD 처리는 별도 워커로 (웹훅은 빨리 200 반환)
        recordingWorker.process(egressId);
    }

    private Optional<Meeting> meetingByRoom(String roomName) {
        Optional<Meeting> meeting = meetingRepository.findByRoomName(roomName);
        if (meeting.isEmpty()) {
            log.warn("[Webhook] 알 수 없는 room={}", roomName);
        }
        return meeting;
    }

    private void saveMappingIfAbsent(MeetingRecording rec) {
        if (!recordingRepository.existsByEgressId(rec.getEgressId())) {
            recordingRepository.save(rec);
        }
    }

    private Integer parseMemberId(String identity) {
        try {
            return Integer.parseInt(identity);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
