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
  return fetch(`/api/projects/${projectId}/meetings/join`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ memberId, memberName }),
  }).then((r) => unwrap<JoinResponse>(r));
}

export function endMeeting(meetingId: number) {
  return fetch(`/api/meetings/${meetingId}`, { method: 'DELETE' }).then((r) => unwrap<void>(r));
}

export function speechStats(meetingId: number) {
  return fetch(`/api/meetings/${meetingId}/speech-stats`).then((r) => unwrap<SpeechStats>(r));
}

export function coaching(meetingId: number, memberId: number) {
  return fetch(`/api/meetings/${meetingId}/coaching?memberId=${memberId}`).then((r) =>
    unwrap<{ memberId: number; advice: string }>(r),
  );
}
