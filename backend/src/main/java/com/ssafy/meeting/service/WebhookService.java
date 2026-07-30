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
 *   egress_ended       → 로그만 (파일은 우리 S3 에 있음. 조회/후처리는 S3 이벤트→SQS→Lambda 로)
 *
 * 녹음 메타데이터는 저장하지 않는다 — 파일 경로가 곧 정보다
 * (meetings/{roomName}/{memberId}/{time}.ogg). 조회는 스토리지를 나열해 만든다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final RoomRegistry roomRegistry;
    private final EgressService egressService;

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

    /** 파일은 스토리지에 이미 있다. 지금은 로그만. (조회는 3단계 S3 나열로) */
    private void onEgressEnded(LivekitEgress.EgressInfo info) {
        if (info == null) {
            return;
        }
        if (info.getFileResultsCount() > 0) {
            log.info("[Webhook] egress_ended egressId={} key={}",
                    info.getEgressId(), info.getFileResults(0).getFilename());
        } else {
            log.warn("[Webhook] egress_ended 파일 없음 egressId={} status={}",
                    info.getEgressId(), info.getStatus());
        }
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
