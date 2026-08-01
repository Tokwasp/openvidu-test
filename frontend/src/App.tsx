import { useCallback, useEffect, useRef, useState } from 'react';
import {
  createLocalTracks,
  LocalAudioTrack,
  LocalVideoTrack,
  Participant,
  RemoteTrack,
  RemoteTrackPublication,
  Room,
  RoomEvent,
  Track,
} from 'livekit-client';
import * as api from './api';
import type { JoinResponse } from './api';

type Phase = 'lobby' | 'prejoin' | 'room' | 'ended';

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
  const [needSound, setNeedSound] = useState(false);  // 브라우저 자동재생 차단 시 클릭 유도
  const [showLog, setShowLog] = useState(false);
  const [log, setLog] = useState<string[]>([]);
  const [elapsed, setElapsed] = useState(0);

  // ── 프리조인(기기 설정) 상태 ─────────────────
  const [mics, setMics] = useState<MediaDeviceInfo[]>([]);
  const [cams, setCams] = useState<MediaDeviceInfo[]>([]);
  const [selectedMicId, setSelectedMicId] = useState('');
  const [selectedCamId, setSelectedCamId] = useState('');
  const [prejoinStatus, setPrejoinStatus] = useState('');
  const [prejoinErr, setPrejoinErr] = useState(false);
  const [previewTick, setPreviewTick] = useState(0);  // 미리보기 재부착 트리거

  const roomRef = useRef<Room | null>(null);
  const videoGridRef = useRef<HTMLDivElement | null>(null);
  const startedAtRef = useRef<number>(0);
  // 프리조인에서 미리 잡아둔 로컬 트랙. 참여 시 그대로 publish 해 카메라를 두 번 잡지 않는다.
  const preMicTrackRef = useRef<LocalAudioTrack | null>(null);
  const preCamTrackRef = useRef<LocalVideoTrack | null>(null);
  const previewRef = useRef<HTMLDivElement | null>(null);

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

  // 원격 오디오는 반드시 DOM에 attach 해야 실제로 소리가 난다(구독만으로는 안 들림).
  // 중요: 기존 참가자의 트랙은 connect() 도중, 즉 아직 phase='lobby'(회의 UI 미마운트)
  // 상태에서 subscribe 된다. 그래서 React가 렌더한 컨테이너에 의존하지 말고, 렌더 시점과
  // 무관하게 항상 존재하는 document.body 에 직접(숨겨서) 붙인다.
  const attachAudio = (track: RemoteTrack, id: string) => {
    if (track.kind !== Track.Kind.Audio) return;
    if (document.querySelector(`audio[data-aid="${id}"]`)) return;
    const el = track.attach() as HTMLAudioElement;
    el.dataset.aid = id;
    el.autoplay = true;
    el.style.display = 'none';
    document.body.appendChild(el);
  };

  const wireEvents = (room: Room) => {
    const resync = () => syncParticipants(room);
    room
      .on(RoomEvent.TrackSubscribed, (track: RemoteTrack, _pub: RemoteTrackPublication, p: Participant) => {
        if (track.kind === Track.Kind.Video) attachVideo(track, `${p.identity}-${track.sid}`, p.name || p.identity);
        else if (track.kind === Track.Kind.Audio) attachAudio(track, `${p.identity}-${track.sid}`);
        // 진단용: 오디오가 구독되는데도 안 들리면 자동재생/미디어 전송(use_external_ip) 쪽 문제.
        pushLog(`구독: ${p.name || p.identity} ${track.kind} (재생가능=${room.canPlaybackAudio})`);
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
      // 자동재생이 막히면 canPlaybackAudio=false 로 바뀐다 → 클릭 배너를 띄워 startAudio 유도.
      .on(RoomEvent.AudioPlaybackStatusChanged, () => {
        const blocked = !room.canPlaybackAudio;
        setNeedSound(blocked);
        if (blocked) pushLog('브라우저가 소리 재생을 막음 — "소리 켜기" 클릭 필요');
      })
      .on(RoomEvent.Disconnected, () => pushLog('연결 종료됨'));
  };

  // 자동재생 차단 해제: 반드시 사용자 클릭 핸들러 안에서 동기적으로 startAudio 를 호출해야 먹힌다.
  const enableSound = async () => {
    const room = roomRef.current;
    if (!room) return;
    try {
      await room.startAudio();
      setNeedSound(!room.canPlaybackAudio);
      if (room.canPlaybackAudio) pushLog('소리 재생 허용됨');
    } catch (e) {
      pushLog('소리 켜기 실패: ' + (e as Error).message);
    }
  };

  // ── 프리조인(기기 설정) ────────────────────────
  // 로비 → 프리조인: 권한을 받고 기기 목록·미리보기를 준비한다. 서버 호출/Room 인스턴스는 아직 없다.
  const openPrejoin = async () => {
    setPrejoinErr(false);
    setPrejoinStatus('카메라·마이크 준비 중…');
    setMicOn(true);
    setCamOn(true);
    setPhase('prejoin');
    try {
      // 1) 권한 + 트랙 확보 (서버 호출 0번, Room 불필요)
      const tracks = await createLocalTracks({ audio: true, video: true });
      for (const t of tracks) {
        if (t.kind === Track.Kind.Audio) preMicTrackRef.current = t as LocalAudioTrack;
        else if (t.kind === Track.Kind.Video) preCamTrackRef.current = t as LocalVideoTrack;
      }
      // 2) 기기 목록은 권한 승인 후 조회해야 label(기기 이름)이 채워진다.
      const camList = await Room.getLocalDevices('videoinput');
      const micList = await Room.getLocalDevices('audioinput');
      setCams(camList);
      setMics(micList);
      setSelectedMicId(preMicTrackRef.current?.mediaStreamTrack.getSettings().deviceId ?? micList[0]?.deviceId ?? '');
      setSelectedCamId(preCamTrackRef.current?.mediaStreamTrack.getSettings().deviceId ?? camList[0]?.deviceId ?? '');
      setPrejoinStatus('');
      setPreviewTick((n) => n + 1);  // 미리보기 부착
    } catch (e) {
      setPrejoinErr(true);
      setPrejoinStatus('카메라/마이크 권한이 필요합니다: ' + (e as Error).message);
    }
  };

  // 미리보기 부착: 카메라 grab 은 하지 않고 이미 잡아둔 트랙을 DOM 에 붙이기만 한다(멱등).
  useEffect(() => {
    if (phase !== 'prejoin') return;
    const box = previewRef.current;
    if (!box) return;
    box.innerHTML = '';
    const track = preCamTrackRef.current;
    if (camOn && track) {
      const el = track.attach() as HTMLVideoElement;
      el.style.cssText = 'width:100%;height:100%;object-fit:cover;transform:scaleX(-1)';
      box.appendChild(el);
    }
  }, [phase, camOn, previewTick]);

  const togglePreMic = () => {
    const t = preMicTrackRef.current;
    if (!t) return;
    const next = !micOn;
    if (next) t.unmute(); else t.mute();
    setMicOn(next);
  };

  const togglePreCam = async () => {
    const next = !camOn;
    if (next) {
      try {
        const [t] = await createLocalTracks({ video: selectedCamId ? { deviceId: selectedCamId } : true });
        preCamTrackRef.current = t as LocalVideoTrack;
      } catch (e) {
        alert('카메라를 켤 수 없습니다: ' + (e as Error).message);
        return;
      }
    } else {
      preCamTrackRef.current?.stop();  // 트랙 stop → 카메라 표시등 꺼짐
      preCamTrackRef.current = null;
    }
    setCamOn(next);
    setPreviewTick((n) => n + 1);
  };

  const changeMic = async (deviceId: string) => {
    setSelectedMicId(deviceId);
    const old = preMicTrackRef.current;
    try {
      const [t] = await createLocalTracks({ audio: { deviceId } });
      if (!micOn) (t as LocalAudioTrack).mute();
      preMicTrackRef.current = t as LocalAudioTrack;
      old?.stop();
    } catch (e) {
      alert('마이크 변경 실패: ' + (e as Error).message);
    }
  };

  const changeCam = async (deviceId: string) => {
    setSelectedCamId(deviceId);
    if (!camOn) return;
    const old = preCamTrackRef.current;
    try {
      const [t] = await createLocalTracks({ video: { deviceId } });
      preCamTrackRef.current = t as LocalVideoTrack;
      old?.stop();
      setPreviewTick((n) => n + 1);
    } catch (e) {
      alert('카메라 변경 실패: ' + (e as Error).message);
    }
  };

  const stopPrejoinTracks = () => {
    preMicTrackRef.current?.stop();
    preMicTrackRef.current = null;
    preCamTrackRef.current?.stop();
    preCamTrackRef.current = null;
    if (previewRef.current) previewRef.current.innerHTML = '';
  };

  const cancelPrejoin = () => {
    stopPrejoinTracks();
    setMics([]);
    setCams([]);
    setPrejoinStatus('');
    setPrejoinErr(false);
    setPhase('lobby');
  };

  // ── 참여: 프리조인에서 만든 트랙을 그대로 publish ─────────────
  const handleParticipate = async () => {
    if (joining) return;
    setJoining(true);
    try {
      const info = await api.join(projectId, memberId, memberName);  // 여기서 처음 서버 호출
      setJoinInfo(info);
      pushLog(`참여: room=${info.roomName.slice(0, 8)}… (${info.created ? '새 회의 생성' : '기존 회의 입장'})`);

      // adaptiveStream/dynacast 는 "보이는 요소만 구독/송출"이라 소규모 회의에서
      // 늦게 들어온 사람이 상대 카메라를 못 보는 문제를 만든다 → 끈다(항상 구독/송출).
      // captureDefaults 로 회의 중 재활성화(setCam/MicEnabled) 때도 선택한 기기를 쓰게 한다.
      const room = new Room({
        adaptiveStream: false,
        dynacast: false,
        audioCaptureDefaults: selectedMicId ? { deviceId: selectedMicId } : undefined,
        videoCaptureDefaults: selectedCamId ? { deviceId: selectedCamId } : undefined,
      });
      roomRef.current = room;
      wireEvents(room);
      await room.connect(info.livekitUrl, info.token);
      // 자동재생 차단 대비: 먼저 조용히 시도(프리조인에서 마이크 권한을 이미 받아 성공률이 높다).
      try { await room.startAudio(); } catch { /* 아래 배너로 처리 */ }
      setNeedSound(!room.canPlaybackAudio);

      // 프리조인 트랙을 재사용해 publish (setMic/CamEnabled 를 부르지 않아 이중 캡처를 막는다).
      const micTrack = preMicTrackRef.current;
      const camTrack = preCamTrackRef.current;
      // 마이크(오디오)는 개인 녹음(track_published→개인 Egress→S3→SQS)의 트리거이므로 항상 publish 한다.
      // 음소거로 참여해도 publication 은 생겨 녹음은 시작되고(무음 기록), 소리 흐름도 그대로 유지된다.
      if (micTrack) await room.localParticipant.publishTrack(micTrack, { source: Track.Source.Microphone });
      if (camTrack && camOn) await room.localParticipant.publishTrack(camTrack, { source: Track.Source.Camera });
      // 소유권을 Room 으로 넘겼으니 ref 를 비운다 → 프리조인 정리가 트랙을 stop 하지 않아 카메라가 끊김 없이 이어진다.
      preMicTrackRef.current = null;
      preCamTrackRef.current = null;

      syncParticipants(room);
      setPhase('room');
    } catch (e) {
      alert('참여 실패: ' + (e as Error).message);
    } finally {
      setJoining(false);
    }
  };

  // 회의방 진입 시 그리드(room 페이즈에만 마운트됨)에 이미 확보된 비디오들을 붙인다.
  useEffect(() => {
    if (phase !== 'room') return;
    const room = roomRef.current;
    if (!room) return;

    // 1) 프리조인에서 켜둔 로컬 카메라
    if (camOn) {
      const pub = room.localParticipant.getTrackPublication(Track.Source.Camera);
      if (pub?.track) attachVideo(pub.track, 'local-cam', '나');
    }

    // 2) 나중에 입장한 경우, 기존 참가자의 비디오가 늦게 들어온 쪽에서 안 보이는 문제.
    //    - connect() 도중(그리드 미마운트)에 구독된 트랙은 TrackSubscribed 가 그리드를 못 찾고 지나간다.
    //    - 아직 구독조차 안 된 트랙은 track 이 null 이라 붙일 것도 없다.
    //    그래서 (a) 원격 비디오를 명시적으로 구독 요청하고, (b) 이미 붙은 track 은 그리드에 다시 붙인다.
    //    미디어가 늦게 도착하는 경우를 대비해 잠깐 재시도한다. attachVideo 는 멱등이라 중복되지 않는다.
    const attachRemoteVideos = () => {
      for (const p of room.remoteParticipants.values()) {
        for (const pub of p.trackPublications.values()) {
          if (pub.kind !== Track.Kind.Video) continue;
          if (!pub.isSubscribed) {
            (pub as RemoteTrackPublication).setSubscribed(true);  // 안전망: 강제 구독
          }
          if (pub.track) {
            attachVideo(pub.track, `${p.identity}-${pub.track.sid}`, p.name || p.identity);
          }
        }
      }
    };

    attachRemoteVideos();
    const retry = setInterval(attachRemoteVideos, 600);
    const stopRetry = setTimeout(() => clearInterval(retry), 4000);
    return () => {
      clearInterval(retry);
      clearTimeout(stopRetry);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [phase]);

  // ── 컨트롤 ────────────────────────────────────
  const toggleMic = async () => {
    const room = roomRef.current;
    if (!room) return;
    const next = !micOn;
    // 이미 발행된 마이크 트랙을 mute/unmute 만 한다(publication 은 유지 → 개인 녹음이 끊기지 않는다).
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
    stopPrejoinTracks();  // 혹시 남은 프리조인 트랙 정리(참여 성공 시엔 이미 비어 있음)
    if (videoGridRef.current) videoGridRef.current.innerHTML = '';
    document.querySelectorAll('audio[data-aid]').forEach((el) => el.remove());  // body에 붙인 원격 오디오 정리
    setParticipants([]);
    setSpeakers([]);
    setHasVideo(false);
    setNeedSound(false);
    setScreenOn(false);
    setPhase('ended');
  };

  const handleLeave = () => cleanup();

  // 회의 종료는 방장만 가능하다. 서버가 거부(403)하면 방을 닫지 않고 사용자에게 알린다.
  // 성공했을 때만 로컬 정리 → "방장 아닌 사람이 눌러도 방이 닫히는" 착시를 없앤다.
  const handleEndMeeting = async () => {
    if (!joinInfo) {
      cleanup();
      return;
    }
    try {
      await api.endMeeting(joinInfo.roomName, memberId);
    } catch (e) {
      const message = (e as Error).message;
      pushLog('종료 요청 거부됨: ' + message);
      alert(message || '회의 방장만 종료할 수 있습니다. 나가려면 "나가기"를 눌러 주세요.');
      return;
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

          <button className="btnPrimary" onClick={openPrejoin}>
            기기 설정하고 참여
          </button>

          <div className="recNotice">🔴 이 회의는 음성이 녹음됩니다.</div>
        </div>
        <p className="foot">회원 ID·이름은 로그인 세션이 없을 때만 쓰입니다.</p>
      </div>
    );
  }

  if (phase === 'prejoin') {
    return (
      <div className="app lobbyBg">
        <div className="lobbyCard prejoinCard">
          <div className="brand"><span className="dot" /> 참여 전 기기 설정</div>
          <h1>카메라·마이크 확인</h1>
          <p className="sub">참여하기 전에 사용할 기기를 고르고 켜고 끌 수 있어요.</p>

          <div className="preview" ref={previewRef}>
            {!camOn && <span className="off">📷 카메라 꺼짐</span>}
          </div>

          {prejoinStatus && <div className={`preStatus ${prejoinErr ? 'err' : ''}`}>{prejoinStatus}</div>}

          <div className="toggleRow">
            <button className={`toggleBtn ${micOn ? '' : 'off'}`} onClick={togglePreMic} disabled={!preMicTrackRef.current}>
              {micOn ? '🎙 마이크 켜짐' : '🔇 마이크 꺼짐'}
            </button>
            <button className={`toggleBtn ${camOn ? '' : 'off'}`} onClick={togglePreCam}>
              {camOn ? '📷 카메라 켜짐' : '📷 카메라 꺼짐'}
            </button>
          </div>

          <div className="deviceRow">
            <label>마이크
              <select value={selectedMicId} onChange={(e) => changeMic(e.target.value)}>
                {mics.length === 0 && <option value="">기기를 찾는 중…</option>}
                {mics.map((d) => (
                  <option key={d.deviceId} value={d.deviceId}>{d.label || '마이크'}</option>
                ))}
              </select>
            </label>
            <label>카메라
              <select value={selectedCamId} onChange={(e) => changeCam(e.target.value)}>
                {cams.length === 0 && <option value="">기기를 찾는 중…</option>}
                {cams.map((d) => (
                  <option key={d.deviceId} value={d.deviceId}>{d.label || '카메라'}</option>
                ))}
              </select>
            </label>
          </div>

          <div className="btnRow">
            <button className="btnGhost" onClick={cancelPrejoin} disabled={joining}>뒤로</button>
            <button className="btnPrimary" onClick={handleParticipate} disabled={joining || prejoinErr}>
              {joining ? '연결 중…' : '지금 참여'}
            </button>
          </div>

          <div className="recNotice">🔴 이 회의는 음성이 녹음됩니다.</div>
        </div>
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

      {needSound && (
        <button className="soundBanner" onClick={enableSound}>
          🔊 브라우저가 소리를 막았어요 — 눌러서 상대 목소리 듣기
        </button>
      )}

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
