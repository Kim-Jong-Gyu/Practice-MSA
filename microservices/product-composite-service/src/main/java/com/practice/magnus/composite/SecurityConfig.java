package com.practice.magnus.composite;

import static org.springframework.http.HttpMethod.DELETE;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.web.server.SecurityWebFilterChain;

/**
 * product-composite를 OAuth2 리소스 서버로 만든다.
 * 인가 서버가 발급한 JWT의 scope으로 읽기/쓰기 권한을 나눈다.
 *
 * 토큰의 scope 클레임 "product:read"는 Spring Security에서 "SCOPE_product:read" 권한이 된다.
 *  - reader 클라이언트 토큰: SCOPE_product:read                       → 조회만 가능
 *  - writer 클라이언트 토큰: SCOPE_product:read, SCOPE_product:write  → 조회, 생성, 삭제 가능
 */
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .authorizeExchange(exchanges -> exchanges
                // 인증 없이 열어 두는 경로
                .pathMatchers("/actuator/**").permitAll()
                .pathMatchers("/openapi/**", "/webjars/**").permitAll()

                // 쓰기: 생성, 삭제
                .pathMatchers(POST, "/product-composite/**").hasAuthority("SCOPE_product:write")
                .pathMatchers(DELETE, "/product-composite/**").hasAuthority("SCOPE_product:write")

                // 읽기: 조회
                .pathMatchers(GET, "/product-composite/**").hasAuthority("SCOPE_product:read")

                .anyExchange().authenticated())

            // JWT 토큰을 받는 API 서버라 세션/쿠키를 쓰지 않으므로 CSRF 보호가 필요 없다
            .csrf(ServerHttpSecurity.CsrfSpec::disable)

            // Authorization: Bearer <JWT> 헤더를 읽고, 인가 서버의 공개 키(jwk-set-uri)로 서명을 검증
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        return http.build();
    }
}
