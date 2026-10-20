package com.practice.magnus.eureka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("Eureka 서버 테스트")
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    // 읽기 전용 응답 캐시를 끄면 등록/삭제가 조회 API에 바로 반영된다 (기본은 5초마다 갱신)
    properties = "eureka.server.use-read-only-response-cache=false")
class EurekaServerApplicationTests {

    private static final String TEST_APP = "TEST-SERVICE";
    private static final String TEST_INSTANCE_ID = "test-service-1";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // application.yaml의 app.eureka-username / app.eureka-password
    private static final String USERNAME = "u";
    private static final String PASSWORD = "p";

    @LocalServerPort
    private int port;

    private RestClient client;

    @BeforeEach
    void setUp() {
        client = RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultHeaders(headers -> headers.setBasicAuth(USERNAME, PASSWORD))  // Eureka 서버의 HTTP Basic 인증
            .defaultStatusHandler(status -> true, (request, response) -> { })  // 4xx/5xx도 예외 없이 응답으로 받기
            .build();
    }

    @Test
    @DisplayName("애플리케이션 컨텍스트가 정상적으로 로딩된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("인증 정보 없이 접근하면 401을 반환한다")
    void requestWithoutCredentialsIsRejected() {
        ResponseEntity<String> response = RestClient.builder()
            .baseUrl("http://localhost:" + port)
            .defaultStatusHandler(status -> true, (request, resp) -> { })
            .build()
            .get().uri("/eureka/apps")
            .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("비밀번호가 틀리면 401을 반환한다")
    void requestWithWrongPasswordIsRejected() {
        ResponseEntity<String> response = client.get().uri("/eureka/apps")
            .headers(headers -> headers.setBasicAuth(USERNAME, "wrong"))
            .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Eureka 대시보드 페이지가 열린다")
    void dashboardIsAvailable() {
        ResponseEntity<String> response = client.get().uri("/")
            .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("Eureka");
    }

    @Test
    @DisplayName("서버는 자기 자신을 등록하지 않으므로 처음에는 등록된 서비스가 없다")
    void registryIsEmptyAtStartup() {
        JsonNode applications = getApplications();

        assertThat(applications.path("application").size()).isZero();
    }

    @Test
    @DisplayName("서비스 인스턴스를 등록하면 조회되고, 삭제하면 사라진다")
    void registerAndCancelInstance() {
        // 등록
        ResponseEntity<Void> registered = client.post().uri("/eureka/apps/{app}", TEST_APP)
            .contentType(MediaType.APPLICATION_JSON)
            .body(instanceJson())
            .retrieve().toBodilessEntity();
        assertThat(registered.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        // 조회: 등록한 인스턴스가 UP 상태로 보여야 함
        ResponseEntity<String> found = client.get().uri("/eureka/apps/{app}", TEST_APP)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve().toEntity(String.class);
        assertThat(found.getStatusCode()).isEqualTo(HttpStatus.OK);

        JsonNode instance = MAPPER.readTree(found.getBody()).path("application").path("instance").get(0);
        assertThat(instance.path("instanceId").asString()).isEqualTo(TEST_INSTANCE_ID);
        assertThat(instance.path("status").asString()).isEqualTo("UP");

        // 삭제 (서비스가 정상 종료될 때 보내는 요청과 같음)
        ResponseEntity<Void> cancelled = client.delete()
            .uri("/eureka/apps/{app}/{instanceId}", TEST_APP, TEST_INSTANCE_ID)
            .retrieve().toBodilessEntity();
        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 삭제 후에는 조회되지 않아야 함
        ResponseEntity<String> afterCancel = client.get().uri("/eureka/apps/{app}", TEST_APP)
            .accept(MediaType.APPLICATION_JSON)
            .retrieve().toEntity(String.class);
        assertThat(afterCancel.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Actuator health가 UP이다")
    void healthIsUp() {
        ResponseEntity<String> response = client.get().uri("/actuator/health")
            .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(MAPPER.readTree(response.getBody()).path("status").asString()).isEqualTo("UP");
    }

    private JsonNode getApplications() {
        ResponseEntity<String> response = client.get().uri("/eureka/apps")
            .accept(MediaType.APPLICATION_JSON)
            .retrieve().toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return MAPPER.readTree(response.getBody()).path("applications");
    }

    /** Eureka 클라이언트가 등록할 때 보내는 것과 같은 형식의 최소 인스턴스 정보 */
    private static String instanceJson() {
        return """
            {
              "instance": {
                "instanceId": "%s",
                "app": "%s",
                "hostName": "localhost",
                "ipAddr": "127.0.0.1",
                "status": "UP",
                "port": { "$": 8080, "@enabled": "true" },
                "dataCenterInfo": {
                  "@class": "com.netflix.appinfo.InstanceInfo$DefaultDataCenterInfo",
                  "name": "MyOwn"
                }
              }
            }
            """.formatted(TEST_INSTANCE_ID, TEST_APP);
    }
}
