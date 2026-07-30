package com.ssafy.meeting.config;

import com.ssafy.projectree.domain.member.LoginMember;
import com.ssafy.projectree.domain.member.SessionConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code @Login LoginMember} 파라미터를 Redis 세션에서 채운다.
 * 모놀리식과 같은 Upstash Redis·같은 SESSION 쿠키·같은 직렬화를 쓰므로,
 * 프론트가 세션 쿠키만 담아 보내면 회원이 식별된다(02 §7).
 *
 * <p><b>개발용 우회(devBypass)</b>: {@code meeting.dev-auth-bypass=true} 일 때만,
 * 세션이 없으면 쿼리파라미터 {@code memberId}/{@code memberName} 로 회원을 만들어 준다.
 * 모놀리식 로그인 없이 녹음 파이프라인을 단독 테스트하기 위한 것 —
 * <b>운영에서는 절대 켜지 말 것</b>(아무나 memberId 를 사칭할 수 있다).
 */
@Slf4j
@RequiredArgsConstructor
public class LoginMemberArgumentResolver implements HandlerMethodArgumentResolver {

    private final boolean devBypass;

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

        if (devBypass) {
            LoginMember fromQuery = resolveFromQuery(request);
            if (fromQuery != null) {
                log.warn("[DEV] 세션 우회로 참여 — memberId={} (운영에서 끌 것)", fromQuery.getId());
                return fromQuery;
            }
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

    private LoginMember resolveFromQuery(HttpServletRequest request) {
        String rawId = request.getParameter("memberId");
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            int id = Integer.parseInt(rawId.trim());
            String name = request.getParameter("memberName");
            if (name == null || name.isBlank()) {
                name = "회원" + id;
            }
            return new LoginMember(id, name, "dev@bypass.local");
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
