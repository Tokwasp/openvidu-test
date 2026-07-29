import { useCallback, useRef, useState } from 'react';
import {
  LocalTrackPublication,
  Participant,
  RemoteTrack,
  RemoteTrackPublication,
  Room,
  RoomEvent,
  Track,
} from 'livekit-client';
import * as api from './api';
import type { JoinResponse, SpeechStats } from './api';

type Phase = 'lobby' | 'room' | 'stats';

export default function App() {
  const [phase, setPhase] = useState<Phase>('lobby');

  // 로비 입력값
  const [projectId, setProjectId] = useState(5);
  const [memberId, setMemberId] = useState(7);
  const [memberName, setMemberName] = useState('김경현');

  // 세션 상태
  const [joinInfo, setJoinInfo] = useState<JoinResponse | null>(null);
  const [micOn, setMicOn] = useState(true);
  const [camOn, setCamOn] = useState(false);
  const [screenOn, setScreenOn] = useState(false);
  const [speakers, setSpeakers] = useState<string[]>([]);
  const [log, setLog] = useState<string[]>([]);

  // 결과
  const [stats, setStats] = useState<SpeechStats | null>(null);
  const [advice, setAdvice] = useState<string>('');

  const roomRef = useRef<Room | null>(null);
  const localMediaRef = useRef<HTMLDivElement | null>(null);
  const remoteMediaRef = useRef<HTMLDivElement | null>(null);

  const pushLog = useCallback((line: string) => {
    setLog((prev) => [`${new Date().toLocaleTimeString()}  ${line}`, ...prev].slice(0, 40));
  }, []);

  // ── 참여 ──────────────────────────────────────
  const handleJoin = async () => {
    try {
      const info = await api.join(projectId, memberId, memberName);
      setJoinInfo(info);
      pushLog(`참여 응답: room=${info.roomName.slice(0, 8)}… created=${info.created}`);

      const room = new Room({ adaptiveStream: true, dynacast: true });
      roomRef.current = room;
      wireEvents(room);

      await room.connect(info.livekitUrl, info.token);
      pushLog(`LiveKit 연결됨 (${info.livekitUrl})`);

      await room.localParticipant.setMicrophoneEnabled(true);
      setMicOn(true);
      setPhase('room');
    } catch (e) {
      alert('참여 실패: ' + (e as Error).message);
    }
  };

  const wireEvents = (room: Room) => {
    room
      .on(RoomEvent.TrackSubscribed, (track: RemoteTrack, _pub: RemoteTrackPublication, p: Participant) => {
        pushLog(`구독: ${p.identity} 의 ${track.kind}`);
        attach(track, remoteMediaRef.current, `${p.identity}-${track.sid}`);
      })
      .on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack) => {
        track.detach().forEach((el) => el.remove());
      })
      .on(RoomEvent.LocalTrackPublished, (pub: LocalTrackPublication) => {
        if (pub.track && pub.track.kind === Track.Kind.Video) {
          attach(pub.track, localMediaRef.current, `local-${pub.trackSid}`);
        }
      })
      .on(RoomEvent.ActiveSpeakersChanged, (list: Participant[]) => {
        setSpeakers(list.map((p) => p.identity));
      })
      .on(RoomEvent.ParticipantConnected, (p) => pushLog(`입장: ${p.identity}`))
      .on(RoomEvent.ParticipantDisconnected, (p) => pushLog(`퇴장: ${p.identity}`))
      .on(RoomEvent.Disconnected, () => pushLog('연결 종료됨'));
  };

  const attach = (track: Track | RemoteTrack, container: HTMLElement | null, id: string) => {
    if (!container) return;
    const el = track.attach();
    el.id = id;
    if (track.kind === Track.Kind.Video) {
      (el as HTMLVideoElement).style.width = '240px';
      (el as HTMLVideoElement).style.borderRadius = '8px';
      (el as HTMLVideoElement).style.background = '#000';
    }
    container.appendChild(el);
  };

  // ── 컨트롤 ────────────────────────────────────
  const toggleMic = async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !micOn;
    await room.localParticipant.setMicrophoneEnabled(next);
    setMicOn(next);
    pushLog(`마이크 ${next ? '켜짐' : '음소거'} (Participant Egress 는 파일 안 나뉨)`);
  };

  const toggleCam = async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !camOn;
    await room.localParticipant.setCameraEnabled(next);
    setCamOn(next);
    pushLog(`카메라 ${next ? '켜짐' : '꺼짐'}`);
  };

  const toggleScreen = async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !screenOn;
    await room.localParticipant.setScreenShareEnabled(next);
    setScreenOn(next);
    pushLog(`화면공유 ${next ? '시작' : '중지'}`);
  };

  // 나가기: 내 연결만 끊는다(다른 사람은 계속). 마지막 사람이 나가면 timeout 후 room_finished.
  const handleLeave = async () => {
    await roomRef.current?.disconnect();
    roomRef.current = null;
    await loadStats();
  };

  // 회의 종료: deleteRoom → 모두 튕김 → room_finished → egress_ended → MinIO 확인 지점
  const handleEndMeeting = async () => {
    if (!joinInfo) return;
    try {
      await api.endMeeting(joinInfo.meetingId);
      pushLog('회의 종료 요청(deleteRoom). 잠시 후 녹음이 MinIO 로 업로드됩니다.');
    } catch (e) {
      pushLog('종료 요청 오류: ' + (e as Error).message);
    }
    await roomRef.current?.disconnect();
    roomRef.current = null;
    await loadStats();
  };

  const loadStats = async () => {
    if (!joinInfo) return;
    setPhase('stats');
    try {
      const [s, c] = await Promise.all([
        api.speechStats(joinInfo.meetingId),
        api.coaching(joinInfo.meetingId, memberId),
      ]);
      setStats(s);
      setAdvice(c.advice);
    } catch (e) {
      pushLog('통계 조회 오류: ' + (e as Error).message);
    }
  };

  const refreshStats = () => loadStats();

  // ── 렌더 ──────────────────────────────────────
  if (phase === 'lobby') {
    return (
      <div className="wrap">
        <h1>OpenVidu3 회의 데모</h1>
        <p className="muted">참여 → 화면공유/음소거/마이크 토글 → 종료 → MinIO 녹음 &amp; 발언시간 확인</p>
        <div className="card">
          <label>projectId <input type="number" value={projectId} onChange={(e) => setProjectId(+e.target.value)} /></label>
          <label>memberId <input type="number" value={memberId} onChange={(e) => setMemberId(+e.target.value)} /></label>
          <label>이름 <input value={memberName} onChange={(e) => setMemberName(e.target.value)} /></label>
          <button className="primary" onClick={handleJoin}>회의 참여</button>
        </div>
        <p className="muted small">
          팁: 다른 탭/브라우저에서 memberId·이름을 다르게 넣어 같은 projectId 로 참여하면 같은 방에 여러 명이 들어갑니다.
        </p>
      </div>
    );
  }

  if (phase === 'room') {
    return (
      <div className="wrap">
        <h2>회의 중 · room {joinInfo?.roomName.slice(0, 8)}…</h2>
        <div className="controls">
          <button className={micOn ? 'on' : 'off'} onClick={toggleMic}>{micOn ? '🎙 마이크 ON' : '🔇 음소거'}</button>
          <button className={camOn ? 'on' : 'off'} onClick={toggleCam}>{camOn ? '📷 카메라 ON' : '📷 카메라 OFF'}</button>
          <button className={screenOn ? 'on' : 'off'} onClick={toggleScreen}>{screenOn ? '🖥 화면공유 중' : '🖥 화면공유'}</button>
          <button className="ghost" onClick={handleLeave}>나가기</button>
          <button className="danger" onClick={handleEndMeeting}>회의 종료</button>
        </div>

        <div className="speakers">발언 중: {speakers.length ? speakers.join(', ') : '—'}</div>

        <div className="videos">
          <div>
            <h4>내 화면(카메라/공유)</h4>
            <div ref={localMediaRef} className="mediaGrid" />
          </div>
          <div>
            <h4>상대 화면</h4>
            <div ref={remoteMediaRef} className="mediaGrid" />
          </div>
        </div>

        <h4>이벤트 로그</h4>
        <pre className="log">{log.join('\n')}</pre>
      </div>
    );
  }

  // stats
  return (
    <div className="wrap">
      <h2>회의 결과 · meeting #{joinInfo?.meetingId}</h2>
      <p className="muted">
        음성 파일은 MinIO 에서 VAD 처리 후 삭제됩니다. 남는 건 숫자(발언시간)뿐 —{' '}
        <a href="http://localhost:9001" target="_blank" rel="noreferrer">MinIO 콘솔</a> 에서 파일이 쌓였다 사라지는 걸 확인하세요.
      </p>
      <button className="ghost" onClick={refreshStats}>새로고침 (워커 처리 후 다시 조회)</button>

      {stats && (
        <div className="card">
          <div>총 발언시간 {(stats.totalSpeakingMs / 1000).toFixed(1)}초 · 참여 균형 {stats.balanceScore}점</div>
          {stats.participants.length === 0 && <p className="muted">아직 통계가 없습니다. 잠시 후 새로고침하세요.</p>}
          {stats.participants.map((p) => (
            <div key={p.memberId} className="bar">
              <span className="barLabel">{p.memberName}</span>
              <span className="barTrack"><span className="barFill" style={{ width: `${p.sharePct}%` }} /></span>
              <span className="barVal">{(p.speakingMs / 1000).toFixed(1)}s · {p.sharePct}%</span>
            </div>
          ))}
        </div>
      )}

      {advice && (
        <div className="card">
          <h4>AI 말하기 코칭 (LLM 호출은 데모에서 생략)</h4>
          <p>{advice}</p>
        </div>
      )}

      <button className="primary" onClick={() => setPhase('lobby')}>로비로</button>
    </div>
  );
}
