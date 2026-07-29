package com.ssafy.meeting.service;

import com.ssafy.meeting.api.dto.SpeechStatsResponse;
import com.ssafy.meeting.domain.stat.MeetingSpeechStat;
import com.ssafy.meeting.domain.stat.MeetingSpeechStatRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 기능 A — 발언 시간 그래프 조회 (06 §5) + 참여 균형 지수(§7).
 */
@Service
@RequiredArgsConstructor
public class SpeechStatsService {

    private final MeetingSpeechStatRepository statRepository;
    private final MemberDirectory memberDirectory;

    @Transactional(readOnly = true)
    public SpeechStatsResponse of(int meetingId) {
        List<MeetingSpeechStat> stats = statRepository.findByMeetingId(meetingId);
        long total = stats.stream().mapToLong(MeetingSpeechStat::getSpeakingMs).sum();

        List<SpeechStatsResponse.Participant> participants = stats.stream()
                .map(s -> new SpeechStatsResponse.Participant(
                        s.getMemberId(),
                        memberDirectory.nameOf(s.getMemberId()),
                        s.getSpeakingMs(),
                        total == 0 ? 0.0 : round1(s.getSpeakingMs() * 100.0 / total)))
                .toList();

        return new SpeechStatsResponse(meetingId, total, balanceScore(stats, total), participants);
    }

    /** 06 §7: 정규화 엔트로피. 완전 균등이면 100, 독점에 가까울수록 낮다. */
    private int balanceScore(List<MeetingSpeechStat> stats, long total) {
        int n = stats.size();
        if (n <= 1 || total == 0) {
            return n <= 1 ? 100 : 0;
        }
        double entropy = 0.0;
        for (MeetingSpeechStat s : stats) {
            double p = s.getSpeakingMs() / (double) total;
            if (p > 0) {
                entropy -= p * Math.log(p);
            }
        }
        return (int) Math.round(entropy / Math.log(n) * 100);
    }

    private double round1(double v) {
        return Math.round(v * 10.0) / 10.0;
    }
}
