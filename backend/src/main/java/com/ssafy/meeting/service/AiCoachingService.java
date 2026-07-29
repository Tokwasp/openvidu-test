package com.ssafy.meeting.service;

import com.ssafy.meeting.domain.stat.MeetingSpeechStat;
import com.ssafy.meeting.domain.stat.MeetingSpeechStatRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * AI 말하기 코칭 (06 §6).
 *
 * ⚠️ 요청대로 "실제 LLM 호출은 하지 않는다." 대신 파이프라인을 그대로 보여준다:
 *    최근 회의 통계 조회 → 추세 계산 → 프롬프트 구성 → (여기서 Claude 호출 대신 로그로 출력) → 조언 반환.
 *    실제 구현에서는 buildPrompt() 결과를 Anthropic API 로 넘기고 응답을 그대로 쓰면 된다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiCoachingService {

    private final MeetingSpeechStatRepository statRepository;

    public String coachingFor(int memberId) {
        List<MeetingSpeechStat> recent = statRepository
                .findTop5ByMemberIdOrderByMeetingIdDesc(memberId);

        if (recent.isEmpty()) {
            return "아직 분석할 회의 기록이 없어요. 회의를 한 번 진행하면 발언 추세를 코칭해 드릴게요.";
        }

        String prompt = buildPrompt(memberId, recent);

        // ── 실제 LLM 호출 자리 ────────────────────────────────
        // String advice = anthropicClient.messages(HAIKU, prompt);   // ← 실제 구현
        log.info("[AICoaching] (호출 생략) LLM으로 넘길 프롬프트:\n{}", prompt);
        // ─────────────────────────────────────────────────────

        return stubbedAdvice(recent);
    }

    /** 06 §6-2: 단발 수치보다 "지난 회의 대비 흐름"을 넘긴다. */
    private String buildPrompt(int memberId, List<MeetingSpeechStat> recent) {
        StringBuilder sb = new StringBuilder();
        sb.append("당신은 회의 말하기 코치입니다. 아래는 member ").append(memberId)
                .append(" 의 최근 회의 지표입니다(최신순). ")
                .append("단정적이지 않게 '~해보면 좋겠어요' 톤으로 짧게 조언하세요.\n");
        for (MeetingSpeechStat s : recent) {
            sb.append(String.format(
                    "- meeting %d: 발언 %dms, 구간 %d개, 말빠르기 %.1f, 톤떨림 %.2f, 음량 %.2f%n",
                    s.getMeetingId(), s.getSpeakingMs(), s.getSegmentCount(),
                    s.getSpeechRate(), s.getToneJitter(), s.getAvgVolume()));
        }
        return sb.toString();
    }

    private String stubbedAdvice(List<MeetingSpeechStat> recent) {
        MeetingSpeechStat latest = recent.get(0);
        String pace = latest.getSpeechRate() > 4.0 ? "말이 다소 빠른 편이에요. 핵심 안건에서는 한 박자 천천히"
                : "말 빠르기는 안정적이에요. 지금 페이스를 유지";
        return pace + " 말하면 전달력이 올라가요. 톤은 잘 유지되고 있고, 발언 참여도 꾸준해요 👍"
                + "  (※ 데모: 실제 LLM 호출 없이 생성된 예시 문구)";
    }
}
