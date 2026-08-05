package com.ssafy.meeting.service;

import com.ssafy.meeting.api.dto.JoinResponse;
import com.ssafy.meeting.common.exception.CustomException;
import com.ssafy.meeting.common.exception.MeetingErrorCode;
import com.ssafy.meeting.config.LiveKitProperties;
import io.livekit.server.RoomServiceClient;
import livekit.LivekitModels;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 참여/종료 흐름 (01-room-lifecycle.md, 02-access-token.md).
 * 방 상태의 주인은 Redis(RoomRegistry). MySQL 은 쓰지 않는다.
 *  - join: 프로젝트에 열린 방 있으면 재사용, 없으면 createRoom + Redis 저장. 토큰은 매번 새로 서명.
 *  - end : deleteRoom만 요청. Redis 는 건드리지 않는다 → room_finished 웹훅이 닫는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private final RoomRegistry roomRegistry;
    private final RoomServiceClient roomServiceClient;
    private final TokenService tokenService;
    private final LiveKitProperties liveKit;

    public JoinResponse join(int projectId, int memberId, String memberName) {
        String existing = roomRegistry.findRoom(projectId).orElse(null);

        String roomName;
        boolean created;
        int creatorId;
        if (existing != null) {
            // 있음 → 아무것도 만들지 않는다. 토큰만 새로 서명.
            roomName = existing;
            created = false;
            // 토큰 identity = memberId 라, 같은 회원이 또 들어오면 LiveKit 이 먼저 있던 참가자를
            // 강제로 내보낸다(중복 identity). 그 전에 join 을 막아 안내한다.
            if (isAlreadyParticipant(roomName, memberId)) {
                throw new CustomException(MeetingErrorCode.ALREADY_JOINED);
            }
            // 방을 처음 연 회원 — Redis 에 저장돼 있다. 유실 시엔 요청자를 대신 쓴다(가용성 우선).
            creatorId = roomRegistry.findCreator(projectId).orElse(memberId);
            log.info("[Join] 기존 회의 재사용 project={} room={}", projectId, roomName);
        } else {
            roomName = openNewRoom(projectId, memberId);
            created = true;
            creatorId = memberId;   // 방을 새로 연 사람이 곧 creator
        }

        String token = tokenService.issue(memberId, memberName, roomName);
        return new JoinResponse(roomName, token, liveKit.wsUrl(), created, creatorId);
    }

    /**
     * 그 방에 이미 memberId(=토큰 identity)인 참가자가 접속해 있는지.
     * 조회 자체가 실패하면 막지 않는다(가용성 우선 — 조회 오류로 입장을 못 하게 하지 않는다).
     */
    private boolean isAlreadyParticipant(String roomName, int memberId) {
        String identity = String.valueOf(memberId);
        try {
            List<LivekitModels.ParticipantInfo> participants =
                    roomServiceClient.listParticipants(roomName).execute().body();
            if (participants == null) {
                return false;
            }
            return participants.stream().anyMatch(p -> identity.equals(p.getIdentity()));
        } catch (Exception e) {
            log.warn("[Join] 참가자 조회 실패(무시하고 진행) room={}: {}", roomName, e.getMessage());
            return false;
        }
    }

    /**
     * Redis 에 먼저 등록한 뒤 Room 을 만든다(순서 중요).
     * createRoom 은 즉시 room_started 웹훅을 쏘는데, 그 웹훅이 전체 mixed Egress 를
     * 시작하려고 isKnownRoom 을 본다. 방을 나중에 등록하면 웹훅이 먼저 도착해
     * "알 수 없는 room" 으로 걸러져 <b>mixed Egress 가 아예 시작되지 않는다</b>.
     * createRoom 이 실패하면 못 들어가는 방이 Redis 에 남지 않도록 등록을 되돌린다.
     * 동시 첫 입장으로 방이 둘 생기는 것은 이 규모에서 방어하지 않는다(01 §2).
     */
    private String openNewRoom(int projectId, int creatorId) {
        String roomName = UUID.randomUUID().toString();   // 회의마다 새 UUID (녹음 폴더의 자연 키)
        roomRegistry.open(projectId, roomName, creatorId);
        try {
            createRoom(roomName);
        } catch (RuntimeException e) {
            roomRegistry.closeByRoom(roomName);           // 방 생성 실패 → Redis 등록 롤백
            throw e;
        }
        log.info("[Join] 새 회의 생성 project={} room={}", projectId, roomName);
        return roomName;
    }

    /**
     * empty_timeout / departure_timeout 을 방 생성 시 명시한다 (01 §4: 120초 / 20초).
     * createRoom(name, emptyTimeout, maxParticipants, nodeId, metadata,
     *            minPlayoutDelay, maxPlayoutDelay, syncStreams, departureTimeout)
     */
    private void createRoom(String roomName) {
        try {
            roomServiceClient.createRoom(
                    roomName,
                    liveKit.emptyTimeoutSec(),   // emptyTimeout
                    null,                        // maxParticipants
                    null,                        // nodeId
                    null,                        // metadata
                    null,                        // minPlayoutDelay
                    null,                        // maxPlayoutDelay
                    null,                        // syncStreams
                    liveKit.departureTimeoutSec()// departureTimeout
            ).execute();
        } catch (Exception e) {
            throw new IllegalStateException("createRoom 실패: " + e.getMessage(), e);
        }
    }

    /** "회의 종료" 버튼 — deleteRoom만. Redis 는 room_finished 웹훅이 닫는다 (01 §5·§6). */
    public void end(String roomName) {
        try {
            roomServiceClient.deleteRoom(roomName).execute();
            log.info("[End] deleteRoom 요청 room={} (webhook이 Redis를 닫음)", roomName);
        } catch (Exception e) {
            throw new IllegalStateException("deleteRoom 실패: " + e.getMessage(), e);
        }
    }
}
