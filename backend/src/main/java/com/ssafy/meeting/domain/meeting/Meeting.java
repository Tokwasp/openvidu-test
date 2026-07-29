package com.ssafy.meeting.domain.meeting;

import com.ssafy.meeting.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 회의 한 건 = 이 테이블의 행 하나. (01-room-lifecycle.md)
 *  - activeFlag 하나가 열림/닫힘을 다 말한다 (별도 status 컬럼 없음)
 *  - roomName 은 회의마다 새로 발급되는 UUID (녹음 폴더의 자연 키)
 */
@Getter
@Entity
@Table(name = "meeting")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Meeting extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "project_id", nullable = false)
    private int projectId;

    @Column(name = "room_name", nullable = false, unique = true)
    private String roomName;

    @Column(name = "active_flag", nullable = false)
    private boolean activeFlag;      // 원시 boolean. null 없음

    @Column(name = "created_by")
    private int createdBy;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "ended_at")
    private LocalDateTime endedAt;

    private Meeting(int projectId, String roomName, int createdBy) {
        this.projectId = projectId;
        this.roomName = roomName;
        this.createdBy = createdBy;
        this.activeFlag = true;
    }

    /** 참여 API가 "방 없음"으로 판단했을 때만 호출 — INSERT는 여기 한 번뿐. */
    public static Meeting open(int projectId, String roomName, int createdBy) {
        return new Meeting(projectId, roomName, createdBy);
    }

    /** room_started 웹훅 — 첫 참가자 입장 시각을 채운다. */
    public void markStarted(LocalDateTime startedAt) {
        if (this.startedAt == null) {
            this.startedAt = startedAt;
        }
    }

    /** room_finished 웹훅 — 유일한 닫는 경로. 두 번 와도 안전(멱등). */
    public void close(LocalDateTime endedAt) {
        if (!activeFlag) {
            return;
        }
        this.activeFlag = false;
        this.endedAt = endedAt;
    }
}
