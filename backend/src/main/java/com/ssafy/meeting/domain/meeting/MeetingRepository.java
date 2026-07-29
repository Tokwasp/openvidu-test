package com.ssafy.meeting.domain.meeting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, Integer> {

    /** 01 §2: 프로젝트당 열려 있는 회의(있으면 그걸 재사용). */
    @Query("""
            select m from Meeting m
            where m.projectId = :projectId and m.activeFlag = true
            order by m.id asc
            limit 1
            """)
    Optional<Meeting> findOpenMeeting(@Param("projectId") int projectId);

    /** 웹훅은 roomName만 알려준다 → 자연 키로 조회. */
    Optional<Meeting> findByRoomName(String roomName);
}
