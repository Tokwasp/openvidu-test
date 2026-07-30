// 백엔드 호출 래퍼. nginx(도커) 또는 vite 프록시(로컬)가 /api 를 백엔드로 넘긴다.

export interface JoinResponse {
  meetingId: number;
  roomName: string;
  token: string;
  livekitUrl: string;
  created: boolean;
}

export interface SpeechStats {
  meetingId: number;
  totalSpeakingMs: number;
  balanceScore: number;
  participants: {
    memberId: number;
    memberName: string;
    speakingMs: number;
    sharePct: number;
  }[];
}

async function unwrap<T>(res: Response): Promise<T> {
  const body = await res.json();
  if (!res.ok) throw new Error(body?.message ?? `HTTP ${res.status}`);
  return body.data as T;
}

export function join(projectId: number, memberId: number, memberName: string) {
  // memberId/name 은 dev-auth-bypass(세션 없을 때) 용 쿼리파라미터. 실제 운영은 세션에서 읽는다.
  // credentials:'include' 로 SESSION 쿠키를 함께 보낸다.
  const q = new URLSearchParams({ memberId: String(memberId), memberName }).toString();
  return fetch(`/api/projects/${projectId}/meetings/join?${q}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    credentials: 'include',
  }).then((r) => unwrap<JoinResponse>(r));
}

// 회의 종료는 roomName 으로 (meetingId 제거됨)
export function endMeeting(roomName: string) {
  return fetch(`/api/meetings/${roomName}`, { method: 'DELETE', credentials: 'include' }).then((r) =>
    unwrap<void>(r),
  );
}

export function speechStats(meetingId: number) {
  return fetch(`/api/meetings/${meetingId}/speech-stats`).then((r) => unwrap<SpeechStats>(r));
}

export function coaching(meetingId: number, memberId: number) {
  return fetch(`/api/meetings/${meetingId}/coaching?memberId=${memberId}`).then((r) =>
    unwrap<{ memberId: number; advice: string }>(r),
  );
}
