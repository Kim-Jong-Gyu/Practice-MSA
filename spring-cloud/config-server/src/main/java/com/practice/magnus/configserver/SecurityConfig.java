package com.practice.magnus.configserver;

import static org.springframework.security.config.Customizer.withDefaults;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // Disable CSRF to allow POST to /encrypt and /decrypt endpoints
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(auth -> auth
                // 쿠버네티스 probe는 인증 정보 없이 호출하므로 actuator는 열어 둔다
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated())
            .httpBasic(withDefaults());
        return http.build();
    }
}
