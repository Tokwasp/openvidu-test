package com.ssafy.meeting.domain.stat;

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

/**
 * 회의 × 사람당 한 행 (06 §4-3). 음성 파일을 지운 뒤 남는 "숫자" 전부.
 */
@Getter
@Entity
@Table(name = "meeting_speech_stat",
        uniqueConstraints = @UniqueConstraint(columnNames = {"meeting_id", "member_id"}))
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingSpeechStat extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "meeting_id", nullable = false)
    private int meetingId;

    @Column(name = "member_id", nullable = false)
    private int memberId;

    // ── 발언 시간 지표 (기능 A) ──
    @Column(name = "speaking_ms", nullable = false)
    private long speakingMs;         // 공백 제거 후 실제 발화 시간

    @Column(name = "segment_count", nullable = false)
    private int segmentCount;

    @Column(name = "avg_segment_ms", nullable = false)
    private int avgSegmentMs;

    @Column(name = "longest_silence_ms", nullable = false)
    private int longestSilenceMs;

    // ── 음향 지표 (기능 B, 근사치) ──
    @Column(name = "avg_volume")
    private double avgVolume;

    @Column(name = "pitch_var")
    private double pitchVar;

    @Column(name = "speech_rate")
    private double speechRate;

    @Column(name = "tone_jitter")
    private double toneJitter;

    private MeetingSpeechStat(int meetingId, int memberId) {
        this.meetingId = meetingId;
        this.memberId = memberId;
    }

    public static MeetingSpeechStat of(int meetingId, int memberId, SpeechMetrics m) {
        MeetingSpeechStat stat = new MeetingSpeechStat(meetingId, memberId);
        stat.apply(m);
        return stat;
    }

    /** 재입장으로 녹음이 여러 개면 사람 단위로 합산해 한 행에 누적한다 (06 §4-3). */
    public void accumulate(SpeechMetrics m) {
        this.speakingMs += m.speakingMs();
        this.segmentCount += m.segmentCount();
        this.avgSegmentMs = this.segmentCount == 0 ? 0 : (int) (this.speakingMs / this.segmentCount);
        this.longestSilenceMs = Math.max(this.longestSilenceMs, m.longestSilenceMs());
        // 음향 지표는 최신 패스 값으로 갱신(데모 단순화)
        this.avgVolume = m.avgVolume();
        this.pitchVar = m.pitchVar();
        this.speechRate = m.speechRate();
        this.toneJitter = m.toneJitter();
    }

    private void apply(SpeechMetrics m) {
        this.speakingMs = m.speakingMs();
        this.segmentCount = m.segmentCount();
        this.avgSegmentMs = m.avgSegmentMs();
        this.longestSilenceMs = m.longestSilenceMs();
        this.avgVolume = m.avgVolume();
        this.pitchVar = m.pitchVar();
        this.speechRate = m.speechRate();
        this.toneJitter = m.toneJitter();
    }
}
