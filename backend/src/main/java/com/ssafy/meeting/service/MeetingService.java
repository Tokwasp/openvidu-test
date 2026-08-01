package com.ssafy.meeting.service;

import com.ssafy.meeting.api.dto.JoinResponse;
import com.ssafy.meeting.config.LiveKitProperties;
import io.livekit.server.RoomServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
        if (existing != null) {
            // 있음 → 아무것도 만들지 않는다. 토큰만 새로 서명.
            roomName = existing;
            created = false;
            log.info("[Join] 기존 회의 재사용 project={} room={}", projectId, roomName);
        } else {
            roomName = openNewRoom(projectId);
            created = true;
        }

        String token = tokenService.issue(memberId, memberName, roomName);
        return new JoinResponse(roomName, token, liveKit.wsUrl(), created);
    }

    /**
     * Redis 에 먼저 등록한 뒤 Room 을 만든다(순서 중요).
     * createRoom 은 즉시 room_started 웹훅을 쏘는데, 그 웹훅이 전체 mixed Egress 를
     * 시작하려고 isKnownRoom 을 본다. 방을 나중에 등록하면 웹훅이 먼저 도착해
     * "알 수 없는 room" 으로 걸러져 <b>mixed Egress 가 아예 시작되지 않는다</b>.
     * createRoom 이 실패하면 못 들어가는 방이 Redis 에 남지 않도록 등록을 되돌린다.
     * 동시 첫 입장으로 방이 둘 생기는 것은 이 규모에서 방어하지 않는다(01 §2).
     */
    private String openNewRoom(int projectId) {
        String roomName = UUID.randomUUID().toString();   // 회의마다 새 UUID (녹음 폴더의 자연 키)
        roomRegistry.open(projectId, roomName);
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
