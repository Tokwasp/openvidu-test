package com.ssafy.meeting.service;

import livekit.LivekitEgress;
import livekit.LivekitModels;
import livekit.LivekitWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LiveKit 웹훅 → 우리 도메인 반응 (01 §5, 03 §6).
 *   room_started       → 전체 믹스 Egress 시작 (첫 참가자일 때 한 번)
 *   participant_joined → 사람별 Egress 시작
 *   room_finished      → Redis 방 상태 닫기 (유일한 닫는 경로, 멱등)
 *   egress_ended       → 후처리 메시지를 SQS 로 발행 (워커/Lambda 가 VAD→발화시간→RDS)
 *
 * 녹음 메타데이터는 DB 에 저장하지 않는다 — 파일 경로가 곧 정보다
 * (meetings/{roomName}/{memberId}/{time}.ogg). SQS 메시지에 roomName·memberId·kind 를 담아 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final RoomRegistry roomRegistry;
    private final EgressService egressService;
    private final RecordingEventPublisher recordingEventPublisher;

    public void handle(LivekitWebhook.WebhookEvent event) {
        String type = event.getEvent();
        log.info("[Webhook] event={}", type);
        switch (type) {
            case "room_started"       -> onRoomStarted(event.getRoom());
            case "participant_joined" -> onParticipantJoined(event.getRoom(), event.getParticipant());
            case "room_finished"      -> onRoomFinished(event.getRoom());
            case "egress_ended"       -> onEgressEnded(event.getEgressInfo());
            default -> log.debug("[Webhook] 처리 안 함: {}", type);
        }
    }

    /** 첫 참가자 입장 시 전체 믹스 Egress 시작. 방을 못 찾으면 우리 방이 아니므로 무시. */
    private void onRoomStarted(LivekitModels.Room room) {
        if (!isOurRoom(room.getName())) {
            return;
        }
        egressService.startMixedEgress(room.getName());
    }

    /** 사람별 Egress 시작. identity(=memberId)가 숫자가 아니면 무시. */
    private void onParticipantJoined(LivekitModels.Room room, LivekitModels.ParticipantInfo participant) {
        if (!isOurRoom(room.getName())) {
            return;
        }
        Integer memberId = parseMemberId(participant.getIdentity());
        if (memberId == null) {
            log.debug("[Webhook] 숫자 아닌 identity 무시: {}", participant.getIdentity());
            return;
        }
        egressService.startParticipantEgress(room.getName(), memberId);
    }

    /** 방 종료 → Redis 방 상태 닫기(멱등). */
    private void onRoomFinished(LivekitModels.Room room) {
        roomRegistry.closeByRoom(room.getName());
    }

    /**
     * 녹음 한 건이 S3 에 올라감 → 후처리 메시지를 SQS 로 발행한다.
     * 실패/파일 없음이면 발행하지 않는다(처리할 파일이 없음).
     */
    private void onEgressEnded(LivekitEgress.EgressInfo info) {
        if (info == null || info.getFileResultsCount() == 0) {
            log.warn("[Webhook] egress_ended 파일 없음 egressId={}",
                    info == null ? "?" : info.getEgressId());
            return;
        }
        String objectKey = info.getFileResults(0).getFilename();
        String roomName = info.getRoomName();
        boolean mixed = isMixed(objectKey);
        Integer memberId = mixed ? null : parseMemberFromKey(objectKey);

        recordingEventPublisher.publish(new RecordingEvent(
                roomName,
                memberId,
                mixed ? "MIXED" : "PARTICIPANT",
                objectKey,
                info.getEgressId(),
                java.time.LocalDateTime.now().toString()));
    }

    /** objectKey = meetings/{roomName}/mixed/... 이면 전체 믹스. */
    private boolean isMixed(String objectKey) {
        String seg = pathSegment(objectKey, 2);
        return "mixed".equals(seg);
    }

    /** meetings/{roomName}/{memberId}/... 에서 memberId 를 뽑는다. */
    private Integer parseMemberFromKey(String objectKey) {
        return parseMemberId(pathSegment(objectKey, 2));
    }

    /** '/' 로 나눈 n번째 세그먼트(없으면 null). */
    private String pathSegment(String objectKey, int index) {
        if (objectKey == null) {
            return null;
        }
        String[] parts = objectKey.split("/");
        return index < parts.length ? parts[index] : null;
    }

    private boolean isOurRoom(String roomName) {
        boolean ours = roomRegistry.isKnownRoom(roomName);
        if (!ours) {
            log.warn("[Webhook] 알 수 없는 room={}", roomName);
        }
        return ours;
    }

    private Integer parseMemberId(String identity) {
        try {
            return Integer.parseInt(identity);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
