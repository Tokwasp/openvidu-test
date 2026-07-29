package com.ssafy.meeting.service;

import com.ssafy.meeting.api.dto.JoinResponse;
import com.ssafy.meeting.config.LiveKitProperties;
import com.ssafy.meeting.domain.meeting.Meeting;
import com.ssafy.meeting.domain.meeting.MeetingRepository;
import io.livekit.server.RoomServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * 참여/종료 흐름 (01-room-lifecycle.md, 02-access-token.md).
 *  - join: 열린 회의 있으면 재사용, 없으면 createRoom + INSERT. 토큰은 매번 새로 서명.
 *  - end : deleteRoom만 요청. DB는 건드리지 않는다 → room_finished 웹훅이 닫는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingRepository meetingRepository;
    private final RoomServiceClient roomServiceClient;
    private final TokenService tokenService;
    private final LiveKitProperties liveKit;

    @Transactional
    public JoinResponse join(int projectId, int memberId, String memberName) {
        Meeting meeting = meetingRepository.findOpenMeeting(projectId)
                .map(existing -> {
                    // 있음 → 아무것도 만들지 않고 아무것도 UPDATE 하지 않는다. 토큰만 새로 서명.
                    log.info("[Join] 기존 회의 재사용 meetingId={} room={}",
                            existing.getId(), existing.getRoomName());
                    return existing;
                })
                .orElseGet(() -> openNewMeeting(projectId, memberId));

        String token = tokenService.issue(memberId, memberName, meeting.getRoomName());
        boolean created = meeting.getStartedAt() == null && meeting.getEndedAt() == null;

        return new JoinResponse(
                meeting.getId(),
                meeting.getRoomName(),
                token,
                liveKit.wsUrl(),
                created);
    }

    private Meeting openNewMeeting(int projectId, int createdBy) {
        String roomName = UUID.randomUUID().toString();   // 회의마다 새 UUID (녹음 폴더의 자연 키)
        createRoom(roomName);
        Meeting meeting = meetingRepository.save(Meeting.open(projectId, roomName, createdBy));
        log.info("[Join] 새 회의 생성 meetingId={} room={}", meeting.getId(), roomName);
        return meeting;
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

    /** "회의 종료" 버튼 — deleteRoom만. DB는 room_finished 웹훅이 닫는다 (01 §5·§6). */
    @Transactional(readOnly = true)
    public void end(int meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new IllegalArgumentException("회의를 찾을 수 없습니다: " + meetingId));
        try {
            roomServiceClient.deleteRoom(meeting.getRoomName()).execute();
            log.info("[End] deleteRoom 요청 room={} (webhook이 DB를 닫음)", meeting.getRoomName());
        } catch (Exception e) {
            throw new IllegalStateException("deleteRoom 실패: " + e.getMessage(), e);
        }
    }
}
