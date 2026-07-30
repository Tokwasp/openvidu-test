package com.ssafy.meeting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.List;

/**
 * CORS + 로그인 회원 ArgumentResolver 등록.
 *
 * <p>세션 쿠키를 프론트가 보내야 하므로 CORS 는 {@code allowCredentials(true)} 가 필요하다.
 * 같은 오리진(nginx 가 /api 프록시)이면 사실상 불필요하지만, 로컬 vite 개발 서버용으로 열어둔다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(true);   // 세션 쿠키 전달 허용
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(new LoginMemberArgumentResolver());
    }
}
