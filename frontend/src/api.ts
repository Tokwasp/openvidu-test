// 백엔드 호출 래퍼. nginx(도커) 또는 vite 프록시(로컬)가 /api 를 백엔드로 넘긴다.

export interface JoinResponse {
  roomName: string;
  token: string;
  livekitUrl: string;
  created: boolean;
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

// 회의 종료(방장만) — roomName 으로 deleteRoom. Redis 는 room_finished 웹훅이 닫는다.
// memberId 는 join 과 동일하게 dev-auth-bypass(세션 없을 때) 용. 운영은 세션에서 회원을 읽으므로 무시된다.
// 방장이 아니면 백엔드가 403 을 준다.
export function endMeeting(roomName: string, memberId: number) {
  const q = new URLSearchParams({ memberId: String(memberId) }).toString();
  return fetch(`/api/meetings/${roomName}?${q}`, { method: 'DELETE', credentials: 'include' }).then((r) =>
    unwrap<void>(r),
  );
}
