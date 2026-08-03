package com.ssafy.meeting.config;

import com.ssafy.projectree.domain.member.LoginMember;
import com.ssafy.projectree.domain.member.SessionConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code @Login LoginMember} 파라미터를 Redis 세션에서만 채운다.
 * 모놀리식과 같은 Upstash Redis·같은 SESSION 쿠키·같은 직렬화를 쓰므로,
 * 프론트가 세션 쿠키만 담아 보내면 회원이 식별된다(02 §7).
 *
 * <p>세션이 없으면 무조건 401 — 쿼리파라미터로 회원을 사칭하는 개발용 우회는 없다.
 * 오직 로그인 세션을 가진 회원만 회의에 참여할 수 있다.
 */
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(Login.class)
                && LoginMember.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter,
                                  ModelAndViewContainer mavContainer,
                                  NativeWebRequest webRequest,
                                  WebDataBinderFactory binderFactory) {
        HttpServletRequest request = (HttpServletRequest) webRequest.getNativeRequest();

        LoginMember fromSession = resolveFromSession(request);
        if (fromSession != null) {
            return fromSession;
        }

        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다.");
    }

    private LoginMember resolveFromSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session == null) {
            return null;
        }
        Object member = session.getAttribute(SessionConst.SESSION_LOGIN_MEMBER);
        return (member instanceof LoginMember loginMember) ? loginMember : null;
    }
}
