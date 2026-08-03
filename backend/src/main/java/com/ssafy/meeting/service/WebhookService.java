package com.ssafy.meeting.service;

import livekit.LivekitEgress;
import livekit.LivekitModels;
import livekit.LivekitWebhook;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LiveKit 웹훅 → 우리 도메인 반응 (01 §5, 03 §6).
 *   room_started    → 전체 믹스 Egress 시작 (첫 참가자일 때 한 번)
 *   track_published → 그 참가자의 마이크(오디오) 발행 시 사람별 Egress 시작 (사람당 한 번)
 *   room_finished   → Redis 방 상태 닫기 (유일한 닫는 경로, 멱등)
 *   egress_ended    → 후처리 메시지를 SQS 로 발행 (워커/Lambda 가 VAD→발화시간→RDS)
 *
 * <p>개인 Egress 를 participant_joined 가 아니라 <b>track_published(오디오)</b> 에 거는 이유:
 * 참가자가 마이크 트랙을 발행하기 전에 startParticipantEgress 를 부르면 LiveKit 이 400 을 낸다.
 *
 * <p>녹음 메타데이터는 DB 에 저장하지 않는다 — 파일 경로가 곧 정보다
 * (meetings/{roomName}/{memberId}/{time}.ogg). SQS 메시지에 roomName·memberId·kind 를 담아 보낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookService {

    private final RoomRegistry roomRegistry;
    private final EgressService egressService;
    private final RecordingEventPublisher recordingEventPublisher;

    /** 개인 Egress 를 이미 시작한 (room|memberId) 집합 — 중복 시작 방지. */
    private final Set<String> startedParticipantEgress = ConcurrentHashMap.newKeySet();

    public void handle(LivekitWebhook.WebhookEvent event) {
        String type = event.getEvent();
        log.info("[Webhook] event={}", type);
        switch (type) {
            case "room_started"    -> onRoomStarted(event.getRoom());
            case "track_published" -> onTrackPublished(event.getRoom(), event.getParticipant(), event.getTrack());
            case "room_finished"   -> onRoomFinished(event.getRoom());
            case "egress_ended"    -> onEgressEnded(event.getEgressInfo());
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

    /**
     * 참가자가 마이크(오디오)를 발행하면 그 사람의 개인 Egress 를 시작한다.
     * 오디오 트랙만, 숫자 identity(회원)만, 사람당 한 번만.
     */
    private void onTrackPublished(LivekitModels.Room room,
                                  LivekitModels.ParticipantInfo participant,
                                  LivekitModels.TrackInfo track) {
        if (!isOurRoom(room.getName())) {
            return;
        }
        if (track == null || track.getType() != LivekitModels.TrackType.AUDIO) {
            return; // 마이크(오디오)만 — 카메라/화면공유는 개인 오디오 녹음 대상이 아니다
        }
        Integer memberId = parseMemberId(participant.getIdentity());
        if (memberId == null) {
            log.debug("[Webhook] 숫자 아닌 identity 무시: {}", participant.getIdentity());
            return;
        }
        String key = room.getName() + "|" + memberId;
        if (!startedParticipantEgress.add(key)) {
            return; // 이미 이 회의에서 이 사람 개인 Egress 를 시작함
        }
        try {
            egressService.startAudioTrackEgress(room.getName(), memberId, track.getSid());
        } catch (RuntimeException e) {
            startedParticipantEgress.remove(key); // 실패 → 다음 발행 때 재시도 가능하도록
            log.warn("[Egress] participant 시작 실패(재시도 가능) member={}: {}", memberId, e.getMessage());
        }
    }

    /** 방 종료 → Redis 방 상태 닫기(멱등) + 개인 Egress 시작 표시 정리. */
    private void onRoomFinished(LivekitModels.Room room) {
        roomRegistry.closeByRoom(room.getName());
        startedParticipantEgress.removeIf(k -> k.startsWith(room.getName() + "|"));
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
        // projectId 는 개인·단체 모두 roomName 역인덱스로 조회한다. 역인덱스는 room_finished
        // 후에도 TTL 까지 남으므로, 방이 닫힌 뒤 오는 단체 mixed egress_ended 도 값을 얻는다.
        Integer projectId = roomRegistry.findProjectId(roomName).orElse(null);

        recordingEventPublisher.publish(new RecordingEvent(
                roomName,
                projectId,
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
