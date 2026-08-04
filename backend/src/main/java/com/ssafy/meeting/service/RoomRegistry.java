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
 *   meeting:project:{projectId} → Hash{room: roomName, creator: memberId}
 *                                            (조인 시 room 필드로 방 유무 판단, creator 는 방을 연 회원)
 *   meeting:room:{roomName}     → Hash{project: projectId, creator: memberId}
 *                                            (웹훅은 roomName만 아므로 역인덱스. creator 는 순방향 키가
 *                                             지워진 뒤 오는 단체 mixed egress_ended 후처리용)
 * </pre>
 *
 * 회의는 길어야 2시간이라 TTL 을 안전망으로 둔다(웹훅 유실 시 자동 정리).
 * 정상 종료는 room_finished 웹훅이 순방향 키(PROJECT_KEY)를 지운다. 역인덱스(ROOM_KEY)는
 * 바로 지우지 않고 짧은 유예 TTL 로 낮춰, 뒤늦게 오는 단체 egress_ended 가 projectId 를
 * 되찾은 뒤 스스로 소멸하게 한다.
 */
@Component
@RequiredArgsConstructor
public class RoomRegistry {

    private static final String PROJECT_KEY = "meeting:project:";
    private static final String ROOM_KEY = "meeting:room:";
    private static final String FIELD_ROOM = "room";
    private static final String FIELD_PROJECT = "project";
    private static final String FIELD_CREATOR = "creator";
    private static final Duration TTL = Duration.ofHours(2);
    /** 방 종료 후 역인덱스를 남겨두는 유예 시간 — 뒤늦게 오는 단체 egress_ended 가 projectId 를 얻을 창. */
    private static final Duration REVERSE_INDEX_GRACE = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;

    /** 프로젝트에 열린 방이 있으면 그 roomName. */
    public Optional<String> findRoom(int projectId) {
        return Optional.ofNullable(redis.<String, String>opsForHash().get(PROJECT_KEY + projectId, FIELD_ROOM));
    }

    /** 그 프로젝트의 열린 방을 만든(처음 연) 회원 id. 방이 없으면 비어 있음. */
    public Optional<Integer> findCreator(int projectId) {
        return Optional.ofNullable(redis.<String, String>opsForHash().get(PROJECT_KEY + projectId, FIELD_CREATOR))
                .map(Integer::valueOf);
    }

    /** roomName 이 우리(이 서비스가 연) 방인지. 웹훅이 남의 방 이벤트를 거를 때 쓴다. */
    public boolean isKnownRoom(String roomName) {
        return Boolean.TRUE.equals(redis.hasKey(ROOM_KEY + roomName));
    }

    /**
     * roomName 으로 projectId 를 되찾는다(역인덱스). 개인·단체 egress_ended 모두에서 쓴다.
     * 역인덱스는 room_finished 후에도 유예 TTL 동안 남으므로, 방이 닫힌 뒤 도착하는 단체
     * mixed egress_ended 도 projectId 를 얻는다. 유예마저 지났으면 비어 있음.
     */
    public Optional<Integer> findProjectId(String roomName) {
        return Optional.ofNullable(redis.<String, String>opsForHash().get(ROOM_KEY + roomName, FIELD_PROJECT))
                .map(Integer::valueOf);
    }

    /**
     * roomName 으로 방을 연 회원 id 를 되찾는다(역인덱스). 단체 mixed egress_ended 후처리에서 쓴다.
     * 순방향 키는 room_finished 때 지워지지만 역인덱스는 유예 TTL 동안 남으므로, 방이 닫힌 뒤
     * 오는 단체 mixed egress_ended 도 creator 를 얻는다. 유예마저 지났으면 비어 있음.
     */
    public Optional<Integer> findCreatorByRoom(String roomName) {
        return Optional.ofNullable(redis.<String, String>opsForHash().get(ROOM_KEY + roomName, FIELD_CREATOR))
                .map(Integer::valueOf);
    }

    /** 새 방을 연다 — 순방향 키(roomName·creator)와 역방향 키(projectId·creator)를 함께 심는다. */
    public void open(int projectId, String roomName, int creatorId) {
        String creator = String.valueOf(creatorId);

        String projectKey = PROJECT_KEY + projectId;
        redis.opsForHash().put(projectKey, FIELD_ROOM, roomName);
        redis.opsForHash().put(projectKey, FIELD_CREATOR, creator);
        redis.expire(projectKey, TTL);

        // 역인덱스에도 creator 를 둔다 — 방이 닫힌 뒤 오는 단체 mixed egress_ended 는 이미 지워진
        // 순방향 키를 못 보므로, 그 후처리에서 creator 를 얻으려면 여기 살아있어야 한다.
        String roomKey = ROOM_KEY + roomName;
        redis.opsForHash().put(roomKey, FIELD_PROJECT, String.valueOf(projectId));
        redis.opsForHash().put(roomKey, FIELD_CREATOR, creator);
        redis.expire(roomKey, TTL);
    }

    /**
     * room_finished 웹훅 처리 — roomName 으로 닫는다(멱등).
     * 프로젝트 키는 "지금도 이 방이 최신일 때만" 지운다(오래된 웹훅이 새 방을 지우지 않게).
     *
     * <p>역인덱스(ROOM_KEY)는 여기서 바로 지우지 않고 짧은 유예 TTL 로 낮춘다. 단체 mixed
     * Egress 는 방이 끝나야 종료돼 mixed egress_ended 가 room_finished 보다 몇 초 늦게 오는데,
     * 그때 findProjectId 로 projectId 를 되찾으려면 역인덱스가 살아있어야 하기 때문이다.
     * 유예가 지나면 스스로 소멸한다. 이 키는 roomName(UUID) 기준이라 같은 projectId 로의
     * 재입장을 막지 않는다(재입장 판단은 위에서 지운 순방향 PROJECT_KEY 로 한다).
     */
    public void closeByRoom(String roomName) {
        String projectId = redis.<String, String>opsForHash().get(ROOM_KEY + roomName, FIELD_PROJECT);
        if (projectId != null) {
            String current = redis.<String, String>opsForHash().get(PROJECT_KEY + projectId, FIELD_ROOM);
            if (roomName.equals(current)) {
                redis.delete(PROJECT_KEY + projectId);
            }
        }
        redis.expire(ROOM_KEY + roomName, REVERSE_INDEX_GRACE);
    }
}
