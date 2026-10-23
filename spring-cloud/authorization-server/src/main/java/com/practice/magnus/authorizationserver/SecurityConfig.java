package com.practice.magnus.authorizationserver;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.MediaTypeRequestMatcher;

/**
 * 책의 AuthorizationServerConfiguration(spring-security-oauth2) + UserConfig를 대체한다.
 * 클라이언트와 사용자는 application.yaml에, 보안 규칙과 서명 키는 여기에 둔다.
 */
@Configuration
public class SecurityConfig {

    /**
     * ① 인가 서버 엔드포인트 전용 체인 (/oauth2/token, /oauth2/jwks, /oauth2/authorize, OIDC 등)
     * 책의 @EnableAuthorizationServer가 하던 일
     */
    @Bean
    @Order(1)
    SecurityFilterChain authorizationServerSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .oauth2AuthorizationServer(authorizationServer -> {
                http.securityMatcher(authorizationServer.getEndpointsMatcher());
                authorizationServer.oidc(Customizer.withDefaults());
            })
            .authorizeHttpRequests(authorize -> authorize.anyRequest().authenticated())
            // 브라우저(HTML)로 /oauth2/authorize에 왔는데 로그인이 안 되어 있으면 로그인 화면으로 보냄
            .exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login"),
                new MediaTypeRequestMatcher(MediaType.TEXT_HTML)));

        return http.build();
    }

    /**
     * ② 나머지 요청용 체인 (로그인 화면, actuator)
     * 책의 UserConfig가 하던 일
     */
    @Bean
    @Order(2)
    SecurityFilterChain defaultSecurityFilterChain(HttpSecurity http) throws Exception {
        http
            .authorizeHttpRequests(authorize -> authorize
                .requestMatchers("/actuator/**").permitAll()   // health 체크 (게이트웨이, 쿠버네티스 probe)
                .anyRequest().authenticated())
            .formLogin(Customizer.withDefaults());

        return http.build();
    }

    /**
     * 토큰 서명에 쓰는 RSA 키.
     * 책처럼 고정 키를 써서 서버를 재시작해도 이미 발급한 토큰이 계속 유효하게 한다.
     * (이 빈이 없으면 Spring Boot가 시작할 때마다 새 키를 만든다)
     * 실제 운영에서는 키를 코드에 두지 말고 키 저장소에서 읽고, 주기적으로 교체해야 한다.
     */
    @Bean
    JWKSource<SecurityContext> jwkSource() {
        try {
            String privateExponent = "3851612021791312596791631935569878540203393691253311342052463788814433805390794604753109719790052408607029530149004451377846406736413270923596916756321977922303381344613407820854322190592787335193581632323728135479679928871596911841005827348430783250026013354350760878678723915119966019947072651782000702927096735228356171563532131162414366310012554312756036441054404004920678199077822575051043273088621405687950081861819700809912238863867947415641838115425624808671834312114785499017269379478439158796130804789241476050832773822038351367878951389438751088021113551495469440016698505614123035099067172660197922333993";
            String modulus = "18044398961479537755088511127417480155072543594514852056908450877656126120801808993616738273349107491806340290040410660515399239279742407357192875363433659810851147557504389760192273458065587503508596714389889971758652047927503525007076910925306186421971180013159326306810174367375596043267660331677530921991343349336096643043840224352451615452251387611820750171352353189973315443889352557807329336576421211370350554195530374360110583327093711721857129170040527236951522127488980970085401773781530555922385755722534685479501240842392531455355164896023070459024737908929308707435474197069199421373363801477026083786683";
            String exponent = "65537";

            KeyFactory factory = KeyFactory.getInstance("RSA");
            RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(
                new RSAPublicKeySpec(new BigInteger(modulus), new BigInteger(exponent)));
            RSAPrivateKey privateKey = (RSAPrivateKey) factory.generatePrivate(
                new RSAPrivateKeySpec(new BigInteger(modulus), new BigInteger(privateExponent)));

            RSAKey rsaKey = new RSAKey.Builder(publicKey)
                .privateKey(privateKey)
                .keyID("auth-server-key")   // 토큰 헤더의 kid. 리소스 서버가 이 값으로 공개 키를 고른다
                .build();

            return new ImmutableJWKSet<>(new JWKSet(rsaKey));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create the RSA key", e);
        }
    }
}
