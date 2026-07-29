package com.ssafy.meeting.domain.recording;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MeetingRecordingRepository extends JpaRepository<MeetingRecording, Integer> {

    Optional<MeetingRecording> findByEgressId(String egressId);

    boolean existsByEgressId(String egressId);
}
