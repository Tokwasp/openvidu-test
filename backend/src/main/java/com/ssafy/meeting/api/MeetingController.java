package com.ssafy.meeting.api;

import com.ssafy.meeting.api.dto.JoinResponse;
import com.ssafy.meeting.common.ApiResponse;
import com.ssafy.meeting.config.Login;
import com.ssafy.meeting.service.MeetingService;
import com.ssafy.projectree.domain.member.LoginMember;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingService meetingService;

    /** 참여: 세션에서 회원 식별 → 열린 방 재사용/생성 + 토큰 발급 (01, 02, 02 §7). */
    @PostMapping("/api/projects/{projectId}/meetings/join")
    public ApiResponse<JoinResponse> join(@PathVariable int projectId,
                                          @Login LoginMember member) {
        JoinResponse response = meetingService.join(projectId, member.getId(), member.getName());
        return ApiResponse.ok(response);
    }

    /** 회의 종료 버튼: 방장만 종료 가능. deleteRoom만, Redis 는 room_finished 웹훅이 닫는다 (01 §5). */
    @DeleteMapping("/api/meetings/{roomName}")
    public ApiResponse<Void> end(@PathVariable String roomName,
                                 @Login LoginMember member) {
        meetingService.end(roomName, member.getId());
        return ApiResponse.ok(null);
    }
}
