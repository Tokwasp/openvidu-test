package com.ssafy.meeting.service;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 회의방 상태 = Redis (MySQL 대신). 01-room-lifecycle.md 의 "프로젝트당 열린 방 하나"를
 * DB 행이 아니라 Redis 키로 표현한다.
 *
 * <pre>
 *   meeting:project:{projectId} → roomName   (조인 시 이 키로 방 유무 판단)
 *   meeting:room:{roomName}     → projectId   (웹훅은 roomName만 아므로 역인덱스)
 * </pre>
 *
 * 회의는 길어야 2시간이라 TTL 을 안전망으로 둔다(웹훅 유실 시 자동 정리).
 * 정상 종료는 room_finished 웹훅이 키를 지운다.
 */
@Component
@RequiredArgsConstructor
public class RoomRegistry {

    private static final String PROJECT_KEY = "meeting:project:";
    private static final String ROOM_KEY = "meeting:room:";
    private static final String OWNER_SUFFIX = ":owner";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    /** 프로젝트에 열린 방이 있으면 그 roomName. */
    public Optional<String> findRoom(int projectId) {
        return Optional.ofNullable(redis.opsForValue().get(PROJECT_KEY + projectId));
    }

    /** roomName 이 우리(이 서비스가 연) 방인지. 웹훅이 남의 방 이벤트를 거를 때 쓴다. */
    public boolean isKnownRoom(String roomName) {
        return Boolean.TRUE.equals(redis.hasKey(ROOM_KEY + roomName));
    }

    /** 방을 만든 회원(방장) ID. 종료 권한 판단과 방장 퇴장 감지에 쓴다. */
    public Optional<Integer> findOwner(String roomName) {
        String ownerId = redis.opsForValue().get(ownerKey(roomName));
        return ownerId == null ? Optional.empty() : Optional.of(Integer.parseInt(ownerId));
    }

    /** 새 방을 연다 — 정방향/역방향 키와 방장 키를 함께 심는다. */
    public void open(int projectId, String roomName, int ownerId) {
        redis.opsForValue().set(PROJECT_KEY + projectId, roomName, TTL);
        redis.opsForValue().set(ROOM_KEY + roomName, String.valueOf(projectId), TTL);
        redis.opsForValue().set(ownerKey(roomName), String.valueOf(ownerId), TTL);
    }

    /**
     * room_finished 웹훅 처리 — roomName 으로 닫는다(멱등).
     * 프로젝트 키는 "지금도 이 방이 최신일 때만" 지운다(오래된 웹훅이 새 방을 지우지 않게).
     */
    public void closeByRoom(String roomName) {
        String projectId = redis.opsForValue().get(ROOM_KEY + roomName);
        if (projectId != null) {
            String current = redis.opsForValue().get(PROJECT_KEY + projectId);
            if (roomName.equals(current)) {
                redis.delete(PROJECT_KEY + projectId);
            }
        }
        redis.delete(ROOM_KEY + roomName);
        redis.delete(ownerKey(roomName));
    }

    private String ownerKey(String roomName) {
        return ROOM_KEY + roomName + OWNER_SUFFIX;
    }
}
