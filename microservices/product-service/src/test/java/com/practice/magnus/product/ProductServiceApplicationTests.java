package com.practice.magnus.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static com.practice.magnus.api.event.Event.Type.CREATE;
import static com.practice.magnus.api.event.Event.Type.DELETE;

import java.util.function.Consumer;
import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.api.event.Event;
import com.practice.magnus.product.persistence.ProductRepository;
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

@DisplayName("Product 서비스 통합 테스트")
@SpringBootTest(
        webEnvironment = RANDOM_PORT,
        properties = {"eureka.client.enabled=false", "spring.cloud.config.enabled=false"})
@AutoConfigureWebTestClient
class ProductServiceApplicationTests extends MongoDbTestBase {

    @Autowired
    private WebTestClient client;

    @Autowired
    private ProductRepository repository;

    @Autowired
    @Qualifier("messageProcessor")
    private Consumer<Event<Integer, Product>> messageProcessor;

    @BeforeEach
    void setupDb() {
        repository.deleteAll().block();
    }

    @Test
    @DisplayName("CREATE 이벤트를 받으면 상품이 저장되고 API로 조회할 수 있다")
    void createEvent_thenProductCanBeFetched() {
        int productId = 1;

        assertThat(repository.findByProductId(productId).block()).isNull();
        assertThat(repository.count().block()).isZero();

        sendCreateProductEvent(productId);

        assertThat(repository.findByProductId(productId).block()).isNotNull();
        assertThat(repository.count().block()).isEqualTo(1);

        getAndVerifyProduct(productId, OK)
            .jsonPath("$.productId").isEqualTo(productId);
    }

    @Test
    @DisplayName("같은 productId로 CREATE 이벤트를 두 번 받으면 InvalidInputException이 발생한다")
    void duplicateCreateEvent_thenThrowsInvalidInput() {
        int productId = 1;

        assertThat(repository.findByProductId(productId).block()).isNull();

        sendCreateProductEvent(productId);

        assertThat(repository.findByProductId(productId).block()).isNotNull();

        assertThatThrownBy(() -> sendCreateProductEvent(productId))
            .isInstanceOf(InvalidInputException.class)
            .hasMessage("Duplicate key, Product Id: " + productId);
    }

    @Test
    @DisplayName("DELETE 이벤트를 받으면 상품이 삭제되고, 없는 상품을 삭제해도 에러가 나지 않는다")
    void deleteEvent_thenProductRemoved() {
        int productId = 1;

        sendCreateProductEvent(productId);
        assertThat(repository.findByProductId(productId).block()).isNotNull();

        sendDeleteProductEvent(productId);
        assertThat(repository.findByProductId(productId).block()).isNull();

        sendDeleteProductEvent(productId);   // 멱등성 확인: 두 번째 삭제도 예외 없이 통과해야 함
    }

    @Test
    @DisplayName("productId가 숫자가 아니면 400 Bad Request를 반환한다")
    void getProduct_withNonNumericId_thenBadRequest() {
        getAndVerifyProduct("/no-integer", BAD_REQUEST)
            .jsonPath("$.path").isEqualTo("/product/no-integer")
            .jsonPath("$.message").isEqualTo("Type mismatch.");
    }

    @Test
    @DisplayName("존재하지 않는 productId면 404 Not Found를 반환한다")
    void getProduct_withUnknownId_thenNotFound() {
        int productIdNotFound = 13;

        getAndVerifyProduct(productIdNotFound, NOT_FOUND)
            .jsonPath("$.path").isEqualTo("/product/" + productIdNotFound)
            .jsonPath("$.message").isEqualTo("No product found for productId: " + productIdNotFound);
    }

    @Test
    @DisplayName("productId가 음수면 422 Unprocessable Entity를 반환한다")
    void getProduct_withNegativeId_thenUnprocessableEntity() {
        int productIdInvalid = -1;

        getAndVerifyProduct(productIdInvalid, UNPROCESSABLE_CONTENT)
            .jsonPath("$.path").isEqualTo("/product/" + productIdInvalid)
            .jsonPath("$.message").isEqualTo("Invalid productId: " + productIdInvalid);
    }

    private WebTestClient.BodyContentSpec getAndVerifyProduct(int productId, HttpStatus expectedStatus) {
        return getAndVerifyProduct("/" + productId, expectedStatus);
    }

    private WebTestClient.BodyContentSpec getAndVerifyProduct(String productIdPath, HttpStatus expectedStatus) {
        return client.get()
            .uri("/product" + productIdPath)
            .accept(APPLICATION_JSON)
            .exchange()
            .expectStatus().isEqualTo(expectedStatus)
            .expectHeader().contentType(APPLICATION_JSON)
            .expectBody();
    }

    private void sendCreateProductEvent(int productId) {
        Product product = new Product(productId, "Name " + productId, productId, "SA");
        Event<Integer, Product> event = new Event<>(CREATE, productId, product);
        messageProcessor.accept(event);
    }

    private void sendDeleteProductEvent(int productId) {
        Event<Integer, Product> event = new Event<>(DELETE, productId, null);
        messageProcessor.accept(event);
    }
}