package com.ssafy.meeting.service;

import com.ssafy.meeting.config.MinioProperties;
import io.livekit.server.EgressServiceClient;
import io.livekit.server.EncodedOutputs;
import livekit.LivekitEgress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import retrofit2.Response;

/**
 * 녹음 시작 = Egress 시작 (03-recording.md §6-1, 04-egress.md §5-3).
 * 백엔드가 하는 일은 딱 두 줄:
 *   room_started       → startRoomCompositeEgress(audioOnly)   // 전체 믹스
 *   participant_joined → startParticipantEgress(identity, OGG) // 사람별
 * "멈추는 코드"는 없다 — 사람이 나가거나 방이 끝나면 Egress가 자동 종료된다.
 *
 * 파일 목적지는 요청마다 S3Upload(=MinIO 주소)로 함께 넘긴다. (04 §5-3 "output이 곧 설정")
 *
 * SDK: io.livekit:livekit-server:0.8.1. Egress 관련 호출을 이 클래스에 격리했다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EgressService {

    private final EgressServiceClient egressClient;
    private final MinioProperties minio;

    /** 사람별 Participant Egress → meetings/{room}/{identity}/{time}.ogg */
    public String startParticipantEgress(String roomName, int memberId) {
        LivekitEgress.EncodedFileOutput file =
                oggFileOutput("meetings/{room_name}/{publisher_identity}/{time}.ogg");
        EncodedOutputs outputs = new EncodedOutputs(file, null, null, null);
        try {
            Response<LivekitEgress.EgressInfo> res = egressClient
                    .startParticipantEgress(roomName, String.valueOf(memberId), outputs)
                    .execute();
            LivekitEgress.EgressInfo info = requireBody(res, "participant egress");
            log.info("[Egress] participant 시작 room={} member={} egressId={}",
                    roomName, memberId, info.getEgressId());
            return info.getEgressId();
        } catch (Exception e) {
            throw new IllegalStateException("participant egress 시작 실패: " + e.getMessage(), e);
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
                .setS3(minioUpload())
                .build();
    }

    private LivekitEgress.S3Upload minioUpload() {
        return LivekitEgress.S3Upload.newBuilder()
                .setAccessKey(minio.accessKey())
                .setSecret(minio.secretKey())
                .setBucket(minio.bucket())
                .setEndpoint(minio.endpoint())   // http://minio:9000
                .setRegion(minio.region())
                .setForcePathStyle(true)         // MinIO는 path-style 필수
                .build();
    }

    private LivekitEgress.EgressInfo requireBody(Response<LivekitEgress.EgressInfo> res, String what) {
        if (!res.isSuccessful() || res.body() == null) {
            throw new IllegalStateException(what + " 응답 오류: HTTP " + res.code());
        }
        return res.body();
    }
}
