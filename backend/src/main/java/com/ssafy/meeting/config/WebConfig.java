package com.ssafy.meeting.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 데모용 CORS — 프론트(nginx)와 백엔드가 다른 오리진일 때를 대비.
 * 같은 compose 안에서 nginx가 /api 를 프록시하면 사실상 필요 없지만, 로컬 vite 개발 서버용으로 열어둔다.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
