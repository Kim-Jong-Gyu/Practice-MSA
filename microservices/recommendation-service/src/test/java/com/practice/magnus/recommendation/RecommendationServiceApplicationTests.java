package com.practice.magnus.recommendation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static com.practice.magnus.api.event.Event.Type.CREATE;
import static com.practice.magnus.api.event.Event.Type.DELETE;

import java.util.function.Consumer;
import com.practice.magnus.api.core.recommendation.Recommendation;
import com.practice.magnus.api.event.Event;
import com.practice.magnus.recommendation.persistence.RecommendationRepository;
import com.practice.magnus.util.exceptions.InvalidInputException;
import com.practice.magnus.util.test.MongoDbTestBase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("Recommendation 서비스 통합 테스트")
@SpringBootTest(
        webEnvironment = RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "spring.cloud.config.enabled=false"})
@AutoConfigureWebTestClient
class RecommendationServiceApplicationTests extends MongoDbTestBase {

    @Autowired
    private WebTestClient client;

    @Autowired
    private RecommendationRepository repository;

    @Autowired
    @Qualifier("messageProcessor")
    private Consumer<Event<Integer, Recommendation>> messageProcessor;

    @BeforeEach
    void setupDb() {
        repository.deleteAll().block();
    }

    @Test
    @DisplayName("CREATE 이벤트 3건을 받으면 추천 3건이 저장되고 API로 조회할 수 있다")
    void createEvents_thenRecommendationsCanBeFetched() {
        int productId = 1;

        sendCreateRecommendationEvent(productId, 1);
        sendCreateRecommendationEvent(productId, 2);
        sendCreateRecommendationEvent(productId, 3);

        assertThat(repository.findByProductId(productId).count().block()).isEqualTo(3);

        getAndVerifyRecommendationsByProductId(productId, OK)
                .jsonPath("$.length()").isEqualTo(3)
                .jsonPath("$[2].productId").isEqualTo(productId)
                .jsonPath("$[2].recommendationId").isEqualTo(3);
    }

    @Test
    @DisplayName("같은 productId와 recommendationId로 CREATE 이벤트를 두 번 받으면 InvalidInputException이 발생한다")
    void duplicateCreateEvent_thenThrowsInvalidInput() {
        int productId = 1;
        int recommendationId = 1;

        sendCreateRecommendationEvent(productId, recommendationId);

        assertThat(repository.count().block()).isEqualTo(1);

        assertThatThrownBy(() -> sendCreateRecommendationEvent(productId, recommendationId))
                .isInstanceOf(InvalidInputException.class)
                .hasMessage("Duplicate key, Product Id: 1, Recommendation Id:1");

        assertThat(repository.count().block()).isEqualTo(1);
    }

    @Test
    @DisplayName("DELETE 이벤트를 받으면 추천이 삭제되고, 없는 추천을 삭제해도 에러가 나지 않는다")
    void deleteEvent_thenRecommendationsRemoved() {
        int productId = 1;
        int recommendationId = 1;

        sendCreateRecommendationEvent(productId, recommendationId);
        assertThat(repository.findByProductId(productId).count().block()).isEqualTo(1);

        sendDeleteRecommendationEvent(productId);
        assertThat(repository.findByProductId(productId).count().block()).isZero();

        sendDeleteRecommendationEvent(productId);   // 멱등성 확인: 두 번째 삭제도 예외 없이 통과해야 함
    }

    @Test
    @DisplayName("productId 파라미터가 없으면 400 Bad Request를 반환한다")
    void getRecommendations_withoutProductId_thenBadRequest() {
        getAndVerifyRecommendationsByProductId("", BAD_REQUEST)
                .jsonPath("$.path").isEqualTo("/recommendation");
    }

    @Test
    @DisplayName("productId가 숫자가 아니면 400 Bad Request를 반환한다")
    void getRecommendations_withNonNumericProductId_thenBadRequest() {
        getAndVerifyRecommendationsByProductId("?productId=no-integer", BAD_REQUEST)
                .jsonPath("$.path").isEqualTo("/recommendation");
    }

    @Test
    @DisplayName("추천이 없는 productId면 빈 배열을 반환한다")
    void getRecommendations_withUnknownProductId_thenEmptyList() {
        getAndVerifyRecommendationsByProductId("?productId=113", OK)
                .jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    @DisplayName("productId가 음수면 422 Unprocessable Entity를 반환한다")
    void getRecommendations_withNegativeProductId_thenUnprocessableEntity() {
        int productIdInvalid = -1;

        getAndVerifyRecommendationsByProductId("?productId=" + productIdInvalid, UNPROCESSABLE_CONTENT)
                .jsonPath("$.path").isEqualTo("/recommendation")
                .jsonPath("$.message").isEqualTo("Invalid productId: " + productIdInvalid);
    }

    private WebTestClient.BodyContentSpec getAndVerifyRecommendationsByProductId(int productId, HttpStatus expectedStatus) {
        return getAndVerifyRecommendationsByProductId("?productId=" + productId, expectedStatus);
    }

    private WebTestClient.BodyContentSpec getAndVerifyRecommendationsByProductId(String productIdQuery, HttpStatus expectedStatus) {
        return client.get()
                .uri("/recommendation" + productIdQuery)
                .accept(APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectHeader().contentType(APPLICATION_JSON)
                .expectBody();
    }

    private void sendCreateRecommendationEvent(int productId, int recommendationId) {
        Recommendation recommendation = new Recommendation(productId, recommendationId,
                "Author " + recommendationId, recommendationId, "Content " + recommendationId, "SA");
        Event<Integer, Recommendation> event = new Event<>(CREATE, productId, recommendation);
        messageProcessor.accept(event);
    }

    private void sendDeleteRecommendationEvent(int productId) {
        Event<Integer, Recommendation> event = new Event<>(DELETE, productId, null);
        messageProcessor.accept(event);
    }
}