package com.ssafy.meeting.api.dto;

import java.util.List;

/** 06 §5 응답 형태. 음성 URL은 없다 — 숫자만. */
public record SpeechStatsResponse(
        int meetingId,
        long totalSpeakingMs,
        int balanceScore,
        List<Participant> participants
) {
    public record Participant(
            int memberId,
            String memberName,
            long speakingMs,
            double sharePct
    ) {
    }
}
