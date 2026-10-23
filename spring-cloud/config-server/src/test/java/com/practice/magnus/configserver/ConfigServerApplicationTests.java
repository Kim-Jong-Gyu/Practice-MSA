package com.practice.magnus.configserver;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@DisplayName("Config Server 테스트")
@SpringBootTest(properties = {
    "spring.security.user.name=dev-usr",
    "spring.security.user.password=dev-pwd"
})
@AutoConfigureMockMvc
class ConfigServerApplicationTests {

    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("쿠버네티스 probe 경로는 인증 없이 접근할 수 있다")
    void probesArePublic() throws Exception {
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("설정 조회는 인증 없이 401")
    void configRequiresAuthentication() throws Exception {
        mvc.perform(get("/product/docker")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("설정 조회는 HTTP Basic 인증으로 200")
    void configWithAuthentication() throws Exception {
        mvc.perform(get("/product/docker").with(httpBasic("dev-usr", "dev-pwd")))
            .andExpect(status().isOk());
    }
}
