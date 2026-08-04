package com.ssafy.meeting.service;

/**
 * egress_ended 시 SQS 로 보내는 녹음 후처리 메시지.
 * 워커/Lambda 가 이걸 받아 S3 의 개인 음성을 읽고 VAD → 발화시간 → RDS 로 이어간다.
 *
 * @param kind      MIXED(전체 믹스) | PARTICIPANT(개인)
 * @param projectId 회의가 속한 프로젝트 id. 개인·단체 모두 roomName 역인덱스(Redis)로 채운다.
 *                  역인덱스가 TTL 마저 지나 사라졌으면 드물게 null 일 수 있다.
 * @param memberId  PARTICIPANT 일 때만 채워짐(MIXED 는 null)
 * @param creatorId 그 회의(채팅방)를 만든 회원 id. MIXED(단체) 일 때만 채워짐(PARTICIPANT 는 null).
 *                  역인덱스로 조회하며, 유예 TTL 마저 지났으면 드물게 null 일 수 있다.
 */
public record RecordingEvent(
        String roomName,
        Integer projectId,
        Integer memberId,
        Integer creatorId,
        String kind,
        String objectKey,
        String egressId,
        String endedAt
) {
}
