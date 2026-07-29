package com.ssafy.meeting.domain.stat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface MeetingSpeechStatRepository extends JpaRepository<MeetingSpeechStat, Integer> {

    List<MeetingSpeechStat> findByMeetingId(int meetingId);

    Optional<MeetingSpeechStat> findByMeetingIdAndMemberId(int meetingId, int memberId);

    /** 6-2: 최근 회의 추세용 — 특정 사람의 최근 통계. */
    List<MeetingSpeechStat> findTop5ByMemberIdOrderByMeetingIdDesc(int memberId);
}
