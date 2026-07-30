package com.ssafy.projectree.domain.member;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;

/**
 * 모놀리식(projectree)이 세션에 저장하는 로그인 회원 정보.
 *
 * <p>⚠️ 이 클래스의 <b>패키지 경로(FQN)와 필드</b>는 모놀리식과 <b>완전히 동일</b>해야 한다.
 * 모놀리식 세션이 JSON default typing(GenericJackson2JsonRedisSerializer)으로 저장되어
 * JSON 안에 {@code "@class":"com.ssafy.projectree.domain.member.LoginMember"} 가 박히기 때문이다.
 * 다른 패키지에 두면 역직렬화 시 클래스를 못 찾는다.
 *
 * <p>필드가 바뀌면(추가/이름변경) 이 파일도 함께 맞춰야 한다.
 */
@Getter
@JsonIgnoreProperties(ignoreUnknown = true)
public class LoginMember {

    private final int id;
    private final String name;
    private final String email;

    @JsonCreator
    public LoginMember(@JsonProperty("id") int id,
                       @JsonProperty("name") String name,
                       @JsonProperty("email") String email) {
        this.id = id;
        this.name = name;
        this.email = email;
    }
}
