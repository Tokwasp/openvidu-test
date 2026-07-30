import { useCallback, useEffect, useRef, useState } from 'react';
import {
  Participant,
  RemoteTrack,
  RemoteTrackPublication,
  Room,
  RoomEvent,
  Track,
} from 'livekit-client';
import * as api from './api';
import type { JoinResponse } from './api';

type Phase = 'lobby' | 'room' | 'ended';

interface PInfo {
  identity: string;
  name: string;
  isLocal: boolean;
  micOn: boolean;
}

const AVATAR_COLORS = ['#6366f1', '#0ea5e9', '#10b981', '#f59e0b', '#ef4444', '#8b5cf6', '#ec4899'];

function colorFor(identity: string) {
  let h = 0;
  for (const c of identity) h = (h * 31 + c.charCodeAt(0)) % AVATAR_COLORS.length;
  return AVATAR_COLORS[h];
}

function initials(name: string) {
  const t = name.trim();
  return t ? t.slice(0, 2) : '?';
}

export default function App() {
  const [phase, setPhase] = useState<Phase>('lobby');

  // 로비 입력값 (dev-auth-bypass 용 — 운영에선 세션이 회원을 식별)
  const [projectId, setProjectId] = useState(5);
  const [memberId, setMemberId] = useState(7);
  const [memberName, setMemberName] = useState('김경현');
  const [joining, setJoining] = useState(false);

  // 회의 상태
  const [joinInfo, setJoinInfo] = useState<JoinResponse | null>(null);
  const [micOn, setMicOn] = useState(true);
  const [camOn, setCamOn] = useState(false);
  const [screenOn, setScreenOn] = useState(false);
  const [participants, setParticipants] = useState<PInfo[]>([]);
  const [speakers, setSpeakers] = useState<string[]>([]);
  const [hasVideo, setHasVideo] = useState(false);
  const [showLog, setShowLog] = useState(false);
  const [log, setLog] = useState<string[]>([]);
  const [elapsed, setElapsed] = useState(0);

  const roomRef = useRef<Room | null>(null);
  const videoGridRef = useRef<HTMLDivElement | null>(null);
  const startedAtRef = useRef<number>(0);

  const pushLog = useCallback((line: string) => {
    setLog((prev) => [`${new Date().toLocaleTimeString()}  ${line}`, ...prev].slice(0, 60));
  }, []);

  // 경과 시간 타이머
  useEffect(() => {
    if (phase !== 'room') return;
    startedAtRef.current = Date.now();
    const t = setInterval(() => setElapsed(Math.floor((Date.now() - startedAtRef.current) / 1000)), 1000);
    return () => clearInterval(t);
  }, [phase]);

  const syncParticipants = useCallback((room: Room) => {
    const build = (p: Participant, isLocal: boolean): PInfo => {
      const micPub = p.getTrackPublication(Track.Source.Microphone);
      return { identity: p.identity, name: p.name || p.identity, isLocal, micOn: micPub ? !micPub.isMuted : false };
    };
    const list = [
      build(room.localParticipant, true),
      ...[...room.remoteParticipants.values()].map((p) => build(p, false)),
    ];
    setParticipants(list);
  }, []);

  // 그리드에 실제로 붙은 비디오 타일 개수로 표시 여부를 판단(가장 확실).
  const refreshHasVideo = useCallback(() => {
    setHasVideo((videoGridRef.current?.childElementCount ?? 0) > 0);
  }, []);

  const attachVideo = (track: Track | RemoteTrack, id: string, label: string) => {
    const grid = videoGridRef.current;
    if (!grid || track.kind !== Track.Kind.Video) return;
    if (grid.querySelector(`[data-tid="${id}"]`)) return;
    const tile = document.createElement('div');
    tile.className = 'videoTile';
    tile.dataset.tid = id;
    const el = track.attach() as HTMLVideoElement;
    if (id.startsWith('local-')) el.style.transform = 'scaleX(-1)';  // 자기 영상은 거울 반전
    const cap = document.createElement('span');
    cap.className = 'videoName';
    cap.textContent = label;
    tile.appendChild(el);
    tile.appendChild(cap);
    grid.appendChild(tile);
    refreshHasVideo();
  };

  const detachVideo = (id: string) => {
    videoGridRef.current?.querySelector(`[data-tid="${id}"]`)?.remove();
    refreshHasVideo();
  };

  const wireEvents = (room: Room) => {
    const resync = () => syncParticipants(room);
    room
      .on(RoomEvent.TrackSubscribed, (track: RemoteTrack, _pub: RemoteTrackPublication, p: Participant) => {
        if (track.kind === Track.Kind.Video) attachVideo(track, `${p.identity}-${track.sid}`, p.name || p.identity);
        resync();
      })
      .on(RoomEvent.TrackUnsubscribed, (track: RemoteTrack, _pub, p: Participant) => {
        detachVideo(`${p.identity}-${track.sid}`);
        track.detach().forEach((el) => el.remove());
        resync();
      })
      .on(RoomEvent.LocalTrackPublished, () => resync())  // 로컬 비디오는 토글에서 직접 붙인다
      .on(RoomEvent.LocalTrackUnpublished, () => resync())
      .on(RoomEvent.ActiveSpeakersChanged, (list: Participant[]) => setSpeakers(list.map((p) => p.identity)))
      .on(RoomEvent.TrackMuted, resync)
      .on(RoomEvent.TrackUnmuted, resync)
      .on(RoomEvent.ParticipantConnected, (p) => { pushLog(`입장: ${p.name || p.identity}`); resync(); })
      .on(RoomEvent.ParticipantDisconnected, (p) => { pushLog(`퇴장: ${p.name || p.identity}`); resync(); })
      .on(RoomEvent.Disconnected, () => pushLog('연결 종료됨'));
  };

  // ── 참여 ──────────────────────────────────────
  const handleJoin = async () => {
    if (joining) return;
    setJoining(true);
    try {
      const info = await api.join(projectId, memberId, memberName);
      setJoinInfo(info);
      pushLog(`참여: room=${info.roomName.slice(0, 8)}… (${info.created ? '새 회의 생성' : '기존 회의 입장'})`);

      // adaptiveStream/dynacast 는 "보이는 요소만 구독/송출"이라 소규모 회의에서
      // 늦게 들어온 사람이 상대 카메라를 못 보는 문제를 만든다 → 끈다(항상 구독/송출).
      const room = new Room({ adaptiveStream: false, dynacast: false });
      roomRef.current = room;
      wireEvents(room);
      await room.connect(info.livekitUrl, info.token);
      await room.localParticipant.setMicrophoneEnabled(true);
      setMicOn(true);
      syncParticipants(room);
      setPhase('room');
    } catch (e) {
      alert('참여 실패: ' + (e as Error).message);
    } finally {
      setJoining(false);
    }
  };

  // ── 컨트롤 ────────────────────────────────────
  const toggleMic = async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !micOn;
    await room.localParticipant.setMicrophoneEnabled(next);
    setMicOn(next);
    syncParticipants(room);
  };
  const toggleCam = async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !camOn;
    try {
      await room.localParticipant.setCameraEnabled(next);
    } catch (e) {
      alert('카메라를 켤 수 없습니다 (권한 확인): ' + (e as Error).message);
      return;
    }
    setCamOn(next);
    if (next) {
      const pub = room.localParticipant.getTrackPublication(Track.Source.Camera);
      if (pub?.track) attachVideo(pub.track, 'local-cam', '나');
    } else {
      detachVideo('local-cam');
    }
  };
  const toggleScreen = async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !screenOn;
    try {
      await room.localParticipant.setScreenShareEnabled(next);
    } catch (e) {
      alert('화면공유를 시작할 수 없습니다: ' + (e as Error).message);
      return;
    }
    setScreenOn(next);
    if (next) {
      const pub = room.localParticipant.getTrackPublication(Track.Source.ScreenShare);
      if (pub?.track) attachVideo(pub.track, 'local-screen', '내 화면');
    } else {
      detachVideo('local-screen');
    }
  };

  const cleanup = () => {
    roomRef.current?.disconnect();
    roomRef.current = null;
    if (videoGridRef.current) videoGridRef.current.innerHTML = '';
    setParticipants([]);
    setSpeakers([]);
    setHasVideo(false);
    setPhase('ended');
  };

  const handleLeave = () => cleanup();

  const handleEndMeeting = async () => {
    if (joinInfo) {
      try {
        await api.endMeeting(joinInfo.roomName);
      } catch (e) {
        pushLog('종료 요청 오류: ' + (e as Error).message);
      }
    }
    cleanup();
  };

  const backToLobby = () => {
    setJoinInfo(null);
    setLog([]);
    setElapsed(0);
    setPhase('lobby');
  };

  const fmt = (s: number) => `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`;

  // ── 렌더 ──────────────────────────────────────
  if (phase === 'lobby') {
    return (
      <div className="app lobbyBg">
        <div className="lobbyCard">
          <div className="brand"><span className="dot" /> 프로젝트 회의</div>
          <h1>회의에 참여하기</h1>
          <p className="sub">같은 프로젝트의 팀원과 음성으로 연결됩니다.</p>

          <div className="fields">
            <label>프로젝트 ID
              <input type="number" value={projectId} onChange={(e) => setProjectId(+e.target.value)} />
            </label>
            <div className="row2">
              <label>회원 ID
                <input type="number" value={memberId} onChange={(e) => setMemberId(+e.target.value)} />
              </label>
              <label>이름
                <input value={memberName} onChange={(e) => setMemberName(e.target.value)} />
              </label>
            </div>
          </div>

          <button className="btnPrimary" onClick={handleJoin} disabled={joining}>
            {joining ? '연결 중…' : '회의 참여'}
          </button>

          <div className="recNotice">🔴 이 회의는 음성이 녹음됩니다.</div>
        </div>
        <p className="foot">회원 ID·이름은 로그인 세션이 없을 때만 쓰입니다.</p>
      </div>
    );
  }

  if (phase === 'ended') {
    return (
      <div className="app lobbyBg">
        <div className="lobbyCard center">
          <div className="endedIcon">👋</div>
          <h1>회의가 종료되었습니다</h1>
          <p className="sub">참여해 주셔서 감사합니다. 녹음은 잠시 후 저장됩니다.</p>
          <button className="btnPrimary" onClick={backToLobby}>처음으로</button>
        </div>
      </div>
    );
  }

  // room
  const count = participants.length;
  return (
    <div className="app roomBg">
      <header className="topbar">
        <div className="tLeft">
          <span className="recDot" title="녹음 중" />
          <span className="recText">녹음 중</span>
          <span className="sep" />
          <span className="roomName">회의방 · {joinInfo?.roomName.slice(0, 8)}…</span>
        </div>
        <div className="tRight">
          <span className="pill">⏱ {fmt(elapsed)}</span>
          <span className="pill">👥 {count}명</span>
        </div>
      </header>

      <main className="stage">
        {/* 그리드는 항상 마운트 유지(교체하면 붙인 video 요소가 사라진다). 표시만 토글. */}
        <div ref={videoGridRef} className="videoGrid" style={{ display: hasVideo ? 'grid' : 'none' }} />

        <div className={`roster ${hasVideo ? 'compact' : ''}`}>
          {participants.map((p) => {
            const speaking = speakers.includes(p.identity);
            return (
              <div key={p.identity} className={`pTile ${speaking ? 'speaking' : ''}`}>
                <div className="avatar" style={{ background: colorFor(p.identity) }}>
                  {initials(p.name)}
                  {!p.micOn && <span className="muteBadge">🔇</span>}
                </div>
                <div className="pName">{p.name}{p.isLocal && ' (나)'}</div>
              </div>
            );
          })}
        </div>
      </main>

      <footer className="controlbar">
        <button className={`ctrl ${micOn ? '' : 'off'}`} onClick={toggleMic}>
          <span className="ci">{micOn ? '🎙' : '🔇'}</span><span>{micOn ? '마이크' : '음소거됨'}</span>
        </button>
        <button className={`ctrl ${camOn ? 'active' : ''}`} onClick={toggleCam}>
          <span className="ci">📷</span><span>카메라</span>
        </button>
        <button className={`ctrl ${screenOn ? 'active' : ''}`} onClick={toggleScreen}>
          <span className="ci">🖥</span><span>화면공유</span>
        </button>
        <button className="ctrl leave" onClick={handleLeave}>
          <span className="ci">🚪</span><span>나가기</span>
        </button>
        <button className="ctrl end" onClick={handleEndMeeting}>
          <span className="ci">⛔</span><span>회의 종료</span>
        </button>
      </footer>

      <button className="logToggle" onClick={() => setShowLog((v) => !v)}>
        {showLog ? '로그 닫기' : '로그'}
      </button>
      {showLog && <pre className="logPanel">{log.join('\n')}</pre>}
    </div>
  );
}
