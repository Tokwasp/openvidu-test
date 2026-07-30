package com.ssafy.meeting.service;

import com.ssafy.meeting.config.S3Properties;
import io.livekit.server.EgressServiceClient;
import livekit.LivekitEgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import retrofit2.Response;

/**
 * 녹음 시작 = Egress 시작 (03-recording.md §6-1, 04-egress.md §5-3).
 * 백엔드가 하는 일은 딱 두 줄:
 *   room_started       → startRoomCompositeEgress(audioOnly)   // 전체 믹스
 *   track_published    → startTrackEgress(audio track SID, OGG) // 사람별 마이크 1트랙
 * "멈추는 코드"는 없다 — 사람이 나가거나 방이 끝나면 Egress가 자동 종료된다.
 *
 * 파일 목적지는 요청마다 S3Upload(=우리 AWS S3)로 함께 넘긴다. (04 §5-3 "output이 곧 설정")
 *
 * SDK: io.livekit:livekit-server:0.8.1. Egress 관련 호출을 이 클래스에 격리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EgressService {

    private final EgressServiceClient egressClient;
    private final S3Properties s3;

    /**
     * 사람별 오디오 Track Egress(마이크 1트랙만 직접 저장) → meetings/{room}/{memberId}/{time}.ogg
     *
     * <p>왜 Participant Egress 가 아니라 Track Egress 인가:
     * Participant Egress 는 그 사람의 <b>오디오+비디오를 함께</b> 녹화하려 하는데, 출력이
     * OGG(오디오 전용 컨테이너)라 비디오 트랙을 담을 코덱이 없어 LiveKit 이
     * "no supported codec is compatible with all outputs" 로 <b>400</b> 을 냈다.
     * 마이크 한 트랙만 저장하려면 오디오 트랙 SID 를 지정하는 Track Egress 를 쓴다.
     * Track Egress 는 무변환(passthrough) — OPUS 를 그대로 OGG 로 떨어뜨려 코덱 충돌이 없다.
     */
    public String startAudioTrackEgress(String roomName, int memberId, String trackId) {
        LivekitEgress.DirectFileOutput file = LivekitEgress.DirectFileOutput.newBuilder()
                .setFilepath("meetings/{room_name}/" + memberId + "/{time}.ogg")
                .setS3(s3Upload())
                .build();
        try {
            Response<LivekitEgress.EgressInfo> res = egressClient
                    .startTrackEgress(roomName, file, trackId)
                    .execute();
            LivekitEgress.EgressInfo info = requireBody(res, "track egress");
            log.info("[Egress] track(오디오) 시작 room={} member={} track={} egressId={}",
                    roomName, memberId, trackId, info.getEgressId());
            return info.getEgressId();
        } catch (Exception e) {
            throw new IllegalStateException("track egress 시작 실패: " + e.getMessage(), e);
        }
    }

    /** 전체 믹스 RoomComposite Egress(audioOnly) → meetings/{room}/mixed/{time}.ogg */
    public String startMixedEgress(String roomName) {
        LivekitEgress.EncodedFileOutput file =
                oggFileOutput("meetings/{room_name}/mixed/{time}.ogg");
        try {
            Response<LivekitEgress.EgressInfo> res = egressClient
                    .startRoomCompositeEgress(
                            roomName,
                            file,
                            "",                                       // layout 비움(오디오 전용 최적화 경로)
                            (LivekitEgress.EncodingOptionsPreset) null,
                            (LivekitEgress.EncodingOptions) null,
                            true,                                     // audioOnly
                            false)                                    // videoOnly
                    .execute();
            LivekitEgress.EgressInfo info = requireBody(res, "room composite egress");
            log.info("[Egress] mixed 시작 room={} egressId={}", roomName, info.getEgressId());
            return info.getEgressId();
        } catch (Exception e) {
            throw new IllegalStateException("mixed egress 시작 실패: " + e.getMessage(), e);
        }
    }

    private LivekitEgress.EncodedFileOutput oggFileOutput(String filepath) {
        return LivekitEgress.EncodedFileOutput.newBuilder()
                .setFileType(LivekitEgress.EncodedFileType.OGG) // 오디오 전용 컨테이너
                .setFilepath(filepath)
                .setS3(s3Upload())
                .build();
    }

    /**
     * AWS S3 업로드. MinIO 와 달리 endpoint 를 비우면 AWS 기본 엔드포인트를 쓰고,
     * forcePathStyle 도 끈다(AWS 는 virtual-hosted 스타일).
     */
    private LivekitEgress.S3Upload s3Upload() {
        return LivekitEgress.S3Upload.newBuilder()
                .setAccessKey(s3.accessKey())
                .setSecret(s3.secretKey())
                .setBucket(s3.bucket())
                .setRegion(s3.region())
                .build();
    }

    private LivekitEgress.EgressInfo requireBody(Response<LivekitEgress.EgressInfo> res, String what) {
        if (!res.isSuccessful() || res.body() == null) {
            throw new IllegalStateException(what + " 응답 오류: HTTP " + res.code());
        }
        return res.body();
    }
}
