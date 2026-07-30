package com.ssafy.meeting.config;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.session.data.redis.config.annotation.web.http.EnableRedisHttpSession;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;

/**
 * 모놀리식(projectree)과 세션을 공유하기 위한 설정.
 *
 * <p>핵심 3가지가 모놀리식과 일치해야 세션이 읽힌다:
 * <ol>
 *   <li>같은 Upstash Redis (application.yml 의 spring.data.redis)</li>
 *   <li>같은 쿠키 이름 {@code SESSION} + 같은 도메인</li>
 *   <li>같은 직렬화 ({@code springSessionDefaultRedisSerializer}) — JSON default typing</li>
 * </ol>
 *
 * <p>⚠️ 아래 {@code springSessionDefaultRedisSerializer} 는 호환 가능한 <b>기본 구현</b>이다.
 * 만약 세션 역직렬화가 실패하면(로그에 InvalidTypeIdException/역직렬화 오류),
 * <b>모놀리식의 {@code springSessionDefaultRedisSerializer} + {@code sessionTypeValidator()} 빈을
 * 그대로 복사</b>해 오는 것이 가장 확실하다. 직렬화는 양쪽이 글자 그대로 같아야 안전하다.
 */
@Configuration
@EnableRedisHttpSession   // 네임스페이스 기본값 "spring:session" — 모놀리식이 커스텀했다면 여기 맞춘다
public class SessionConfig {

    /** 쿠키 이름을 모놀리식과 동일하게 SESSION 으로 고정. */
    @Bean
    public CookieSerializer cookieSerializer() {
        DefaultCookieSerializer serializer = new DefaultCookieSerializer();
        serializer.setCookieName("SESSION");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setSameSite("Lax");
        serializer.setCookiePath("/");
        // 프론트/모놀리식/회의 서비스가 서로 다른 서브도메인이면 아래 주석을 풀어 상위 도메인으로:
        // serializer.setDomainName("p.ssafy.io");
        return serializer;
    }

    /**
     * Spring Session 이 세션 속성을 직렬화할 때 쓰는 직렬화기.
     * 빈 이름이 반드시 {@code springSessionDefaultRedisSerializer} 여야 Spring Session 이 집어간다.
     */
    @Bean
    public RedisSerializer<Object> springSessionDefaultRedisSerializer() {
        PolymorphicTypeValidator typeValidator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.ssafy.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("org.springframework.session.")
                .allowIfSubType("org.springframework.security.")
                .build();

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY);
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // GenericJackson2JsonRedisSerializer 와 동일한 포맷: 타입 정보를 "@class" 프로퍼티로 기록
        mapper.activateDefaultTyping(typeValidator,
                ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return new GenericJackson2JsonRedisSerializer(mapper);
    }
}
