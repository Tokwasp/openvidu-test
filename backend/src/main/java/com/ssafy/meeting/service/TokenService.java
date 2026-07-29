package com.ssafy.meeting.service;

import com.ssafy.meeting.config.LiveKitProperties;
import io.livekit.server.AccessToken;
import io.livekit.server.RoomJoin;
import io.livekit.server.RoomName;
import org.springframework.stereotype.Service;

/**
 * 접근 토큰 발급 (02-access-token.md).
 *  - 저장하지 않는다. 요청마다 새로 서명해 내려주고 잊는다.
 *  - identity = memberId (녹음 폴더 이름이 되므로 절대 안 바뀌는 값)
 *  - 유효기간은 "입장까지"만. 연결 후에는 만료돼도 회의 유지.
 *  - 서명은 서버 메모리에서 HMAC 한 번. 네트워크도 DB도 안 탄다.
 */
@Service
public class TokenService {

    private final LiveKitProperties props;

    public TokenService(LiveKitProperties props) {
        this.props = props;
    }

    public String issue(int memberId, String memberName, String roomName) {
        AccessToken token = new AccessToken(props.apiKey(), props.apiSecret());
        token.setIdentity(String.valueOf(memberId));   // "7"
        token.setName(memberName);
        token.setTtl(props.tokenTtlMs());               // ms 단위 (SDK 기본 6시간 → 10분으로)
        token.addGrants(new RoomJoin(true), new RoomName(roomName));
        return token.toJwt();
    }
}
