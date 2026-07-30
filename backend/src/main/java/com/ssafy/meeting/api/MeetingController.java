package com.ssafy.meeting.api;

import com.ssafy.meeting.api.dto.CoachingResponse;
import com.ssafy.meeting.api.dto.JoinResponse;
import com.ssafy.meeting.api.dto.SpeechStatsResponse;
import com.ssafy.meeting.common.ApiResponse;
import com.ssafy.meeting.config.Login;
import com.ssafy.meeting.service.AiCoachingService;
import com.ssafy.meeting.service.MeetingService;
import com.ssafy.meeting.service.MemberDirectory;
import com.ssafy.meeting.service.SpeechStatsService;
import com.ssafy.projectree.domain.member.LoginMember;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;
    private final SpeechStatsService speechStatsService;
    private final AiCoachingService aiCoachingService;
    private final MemberDirectory memberDirectory;

    /** 참여: 세션에서 회원 식별 → 열린 회의 재사용/생성 + 토큰 발급 (01, 02, 02 §7). */
    @PostMapping("/api/projects/{projectId}/meetings/join")
    public ApiResponse<JoinResponse> join(@PathVariable int projectId,
                                          @Login LoginMember member) {
        memberDirectory.remember(member.getId(), member.getName());
        JoinResponse response = meetingService.join(projectId, member.getId(), member.getName());
        return ApiResponse.ok(response);
    }

    /** 회의 종료 버튼: deleteRoom만. DB는 room_finished 웹훅이 닫는다 (01 §5). */
    @DeleteMapping("/api/meetings/{meetingId}")
    public ApiResponse<Void> end(@PathVariable int meetingId) {
        meetingService.end(meetingId);
        return ApiResponse.ok(null);
    }

    /** 발언 시간 그래프 (06 §5). */
    @GetMapping("/api/meetings/{meetingId}/speech-stats")
    public ApiResponse<SpeechStatsResponse> speechStats(@PathVariable int meetingId) {
        return ApiResponse.ok(speechStatsService.of(meetingId));
    }

    /** AI 말하기 코칭 (06 §6) — 실제 LLM 호출은 생략, 파이프라인만. */
    @GetMapping("/api/meetings/{meetingId}/coaching")
    public ApiResponse<CoachingResponse> coaching(@PathVariable int meetingId,
                                                  @RequestParam int memberId) {
        String advice = aiCoachingService.coachingFor(memberId);
        return ApiResponse.ok(new CoachingResponse(memberId, advice));
    }
}
