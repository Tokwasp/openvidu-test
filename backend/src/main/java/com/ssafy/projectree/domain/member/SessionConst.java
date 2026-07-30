package com.ssafy.projectree.domain.member;

/**
 * 세션 키 상수. 모놀리식(projectree)과 동일해야 한다.
 * 모놀리식: session.setAttribute(SESSION_LOGIN_MEMBER, LoginMember.from(member))
 */
public class SessionConst {

    public static final String SESSION_LOGIN_MEMBER = "loginMember";

    private SessionConst() {
    }
}
