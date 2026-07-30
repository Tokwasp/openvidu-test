package com.ssafy.meeting.service;

/**
 * egress_ended 시 SQS 로 보내는 녹음 후처리 메시지.
 * 워커/Lambda 가 이걸 받아 S3 의 개인 음성을 읽고 VAD → 발화시간 → RDS 로 이어간다.
 *
 * @param kind     MIXED(전체 믹스) | PARTICIPANT(개인)
 * @param memberId PARTICIPANT 일 때만 채워짐(MIXED 는 null)
 */
public record RecordingEvent(
        String roomName,
        Integer memberId,
        String kind,
        String objectKey,
        String egressId,
        String endedAt
) {
}
