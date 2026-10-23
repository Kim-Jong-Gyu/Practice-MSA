package com.practice.magnus.authorizationserver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Authorization Server 테스트")
// 테스트에는 RabbitMQ가 없어서 rabbit health가 DOWN(503)이 되므로 끈다 — 이 테스트는 "인증 없이 접근 가능한지"만 본다
@SpringBootTest(properties = {"spring.cloud.config.enabled=false", "management.health.rabbit.enabled=false"})
@AutoConfigureMockMvc
class AuthorizationServerApplicationTests {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("writer 클라이언트는 product:read, product:write 권한의 토큰을 받는다")
    void writerGetsReadAndWriteToken() throws Exception {
        JsonNode token = requestToken("writer", "product:read product:write")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andReturn().getResponse().getContentAsString()
            .transform(MAPPER::readTree);

        assertThat(token.path("access_token").asString()).isNotBlank();
        assertThat(token.path("scope").asString()).contains("product:read", "product:write");
    }

    @Test
    @DisplayName("reader 클라이언트는 product:read 권한의 토큰을 받는다")
    void readerGetsReadToken() throws Exception {
        requestToken("reader", "product:read")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.scope").value("product:read"));
    }

    @Test
    @DisplayName("reader 클라이언트가 product:write를 요청하면 거부된다")
    void readerCannotGetWriteScope() throws Exception {
        requestToken("reader", "product:write")
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("invalid_scope"));
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401을 반환한다")
    void wrongSecretIsRejected() throws Exception {
        mvc.perform(post("/oauth2/token")
                .with(httpBasic("writer", "wrong"))
                .param("grant_type", "client_credentials")
                .param("scope", "product:read"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("공개 키(JWK Set)를 인증 없이 조회할 수 있다")
    void jwkSetIsPublic() throws Exception {
        mvc.perform(get("/oauth2/jwks"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.keys[0].kid").value("auth-server-key"))
            .andExpect(jsonPath("$.keys[0].kty").value("RSA"));
    }

    @Test
    @DisplayName("OpenID 설정 정보에 토큰과 JWK 엔드포인트가 나온다")
    void openidConfigurationIsPublished() throws Exception {
        mvc.perform(get("/.well-known/openid-configuration"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token_endpoint").exists())
            .andExpect(jsonPath("$.jwks_uri").exists());
    }

    @Test
    @DisplayName("actuator health는 인증 없이 접근할 수 있다")
    void actuatorIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
            .andExpect(status().isOk());
    }

    private ResultActions requestToken(String clientId, String scope) throws Exception {
        return mvc.perform(post("/oauth2/token")
            .with(httpBasic(clientId, "secret-" + clientId))
            .param("grant_type", "client_credentials")
            .param("scope", scope));
    }
}
