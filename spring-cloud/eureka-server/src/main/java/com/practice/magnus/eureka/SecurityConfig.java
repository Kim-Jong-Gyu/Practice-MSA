package com.practice.magnus.eureka;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Eureka 서버에 HTTP Basic 인증을 건다.
 * 서비스들은 defaultZone 주소에 아이디와 비밀번호를 넣어서 등록한다 (http://u:p@eureka:8761/eureka/).
 */
@Configuration
public class SecurityConfig {

    /**
     * 책의 configure(AuthenticationManagerBuilder)를 대체
     * {noop}은 비밀번호를 암호화하지 않고 그대로 비교한다는 뜻 (책의 NoOpPasswordEncoder와 같음, 실습용)
     */
    @Bean
    InMemoryUserDetailsManager userDetailsService(
            @Value("${app.eureka-username}") String username,
            @Value("${app.eureka-password}") String password) {

        UserDetails user = User.withUsername(username)
            .password("{noop}" + password)
            .authorities("USER")
            .build();

        return new InMemoryUserDetailsManager(user);
    }

    /**
     * 책의 configure(HttpSecurity)를 대체
     */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // 서비스들이 POST/PUT/DELETE로 등록, 하트비트, 등록 해제를 하므로 CSRF를 끈다
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }
}
