package com.ssafy.meeting.domain.recording;

import com.ssafy.meeting.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 가벼운 "처리 로그" (06 §4-3).
 *  - egress를 시작할 때 (egressId → memberId) 매핑 행을 먼저 만들어 둔다 (03 §6-2)
 *  - egress_ended가 오면 objectKey/status/startedAt 을 채운다
 *  - objectKey 는 파일을 읽어 삭제하기 전까지만 쓰는 임시값. 재생용 컬럼은 두지 않는다.
 */
@Getter
@Entity
@Table(name = "meeting_recording",
        uniqueConstraints = @UniqueConstraint(columnNames = "egress_id"))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingRecording extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "egress_id", nullable = false)
    private String egressId;        // UNIQUE — 웹훅이 두 번 와도 중복 저장 방지

    @Column(name = "meeting_id", nullable = false)
    private int meetingId;

    @Column(name = "member_id", nullable = false)
    private int memberId;           // -1 이면 전체 믹스(RoomComposite)

    @Column(name = "kind", nullable = false)
    private String kind;            // PARTICIPANT | MIXED

    @Column(name = "object_key")
    private String objectKey;       // MinIO 키(삭제 전까지 임시)

    @Column(name = "status", nullable = false)
    private String status;          // STARTED | COMPLETE | FAILED | PROCESSED

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    public static final int MIXED_MEMBER = -1;

    private MeetingRecording(String egressId, int meetingId, int memberId, String kind) {
        this.egressId = egressId;
        this.meetingId = meetingId;
        this.memberId = memberId;
        this.kind = kind;
        this.status = "STARTED";
    }

    public static MeetingRecording participant(String egressId, int meetingId, int memberId) {
        return new MeetingRecording(egressId, meetingId, memberId, "PARTICIPANT");
    }

    public static MeetingRecording mixed(String egressId, int meetingId) {
        return new MeetingRecording(egressId, meetingId, MIXED_MEMBER, "MIXED");
    }

    /** egress_ended 수신 시 파일 위치와 상태를 채운다. */
    public void complete(String objectKey, LocalDateTime startedAt) {
        this.objectKey = objectKey;
        this.startedAt = startedAt;
        this.status = "COMPLETE";
    }

    public void fail() {
        this.status = "FAILED";
    }

    /** 워커가 VAD 처리 + 파일 삭제까지 끝낸 상태. */
    public void markProcessed() {
        this.status = "PROCESSED";
        this.objectKey = null;      // 파일을 지웠으므로 키는 의미 없음
    }

    public boolean isMixed() {
        return memberId == MIXED_MEMBER;
    }
}
