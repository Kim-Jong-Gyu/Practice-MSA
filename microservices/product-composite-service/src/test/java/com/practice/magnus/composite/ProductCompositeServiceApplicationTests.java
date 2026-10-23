package com.practice.magnus.composite;

import static java.util.Collections.singletonList;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.http.HttpStatus.*;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.api.core.recommendation.Recommendation;
import com.practice.magnus.api.core.review.Review;
import com.practice.magnus.composite.services.ProductCompositeIntegration;
import com.practice.magnus.util.exceptions.InvalidInputException;
import com.practice.magnus.util.exceptions.NotFoundException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@DisplayName("Product Composite API 테스트")
@SpringBootTest(
        webEnvironment = MOCK,
        properties = {"spring.cloud.config.enabled=false"})
@AutoConfigureWebTestClient
class ProductCompositeServiceApplicationTests {

    private static final int PRODUCT_ID_OK = 1;
    private static final int PRODUCT_ID_NOT_FOUND = 2;
    private static final int PRODUCT_ID_INVALID = 3;
    // README/test-em-all.bash가 "fallback 캐시에도 없음"으로 고정해 둔 productId
    private static final int PRODUCT_ID_NOT_FOUND_IN_FALLBACK_CACHE = 13;

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private ProductCompositeIntegration compositeIntegration;

    // 인가 서버 없이 테스트하려고, scope이 담긴 가짜 JWT를 요청에 붙인다 (서명 검증을 건너뜀)
    private static final SimpleGrantedAuthority READ = new SimpleGrantedAuthority("SCOPE_product:read");
    private static final SimpleGrantedAuthority WRITE = new SimpleGrantedAuthority("SCOPE_product:write");

    @BeforeEach
    void setUp() {
        // 기본값: 추천과 리뷰는 빈 결과 (Mock의 기본 반환값 null이 Mono.zip에 들어가지 않도록)
        // 아래의 PRODUCT_ID_OK 전용 설정이 이 기본값보다 우선한다
        when(compositeIntegration.getRecommendations(anyInt())).thenReturn(Flux.empty());
        when(compositeIntegration.getReviews(anyInt())).thenReturn(Flux.empty());

        when(compositeIntegration.getProduct(eq(PRODUCT_ID_OK), anyInt(), anyInt()))
                .thenReturn(Mono.just(new Product(PRODUCT_ID_OK, "name", 1, "mock-address")));

        when(compositeIntegration.getRecommendations(PRODUCT_ID_OK))
                .thenReturn(Flux.fromIterable(singletonList(
                        new Recommendation(PRODUCT_ID_OK, 1, "author", 1, "content", "mock address"))));

        when(compositeIntegration.getReviews(PRODUCT_ID_OK))
                .thenReturn(Flux.fromIterable(singletonList(
                        new Review(PRODUCT_ID_OK, 1, "author", "subject", "content", "mock address"))));

        when(compositeIntegration.getProduct(eq(PRODUCT_ID_NOT_FOUND), anyInt(), anyInt()))
                .thenReturn(Mono.error(new NotFoundException("NOT FOUND: " + PRODUCT_ID_NOT_FOUND)));

        when(compositeIntegration.getProduct(eq(PRODUCT_ID_INVALID), anyInt(), anyInt()))
                .thenReturn(Mono.error(new InvalidInputException("INVALID: " + PRODUCT_ID_INVALID)));
    }

    @Test
    @DisplayName("애플리케이션 컨텍스트가 정상적으로 로딩된다")
    void contextLoads() {
    }

    @Test
    @DisplayName("상품을 조회하면 추천과 리뷰가 포함된 composite 응답을 반환한다")
    void getProductById() {
        getAndVerifyProduct(PRODUCT_ID_OK, OK)
                .jsonPath("$.productId").isEqualTo(PRODUCT_ID_OK)
                .jsonPath("$.recommendations.length()").isEqualTo(1)
                .jsonPath("$.reviews.length()").isEqualTo(1);
    }

    @Test
    @DisplayName("상품이 없으면 404 Not Found를 반환한다")
    void getProductNotFound() {
        getAndVerifyProduct(PRODUCT_ID_NOT_FOUND, NOT_FOUND)
                .jsonPath("$.path").isEqualTo("/product-composite/" + PRODUCT_ID_NOT_FOUND)
                .jsonPath("$.message").isEqualTo("NOT FOUND: " + PRODUCT_ID_NOT_FOUND);
    }

    @Test
    @DisplayName("잘못된 입력이면 422 Unprocessable Content를 반환한다")
    void getProductInvalidInput() {
        getAndVerifyProduct(PRODUCT_ID_INVALID, UNPROCESSABLE_CONTENT)
                .jsonPath("$.path").isEqualTo("/product-composite/" + PRODUCT_ID_INVALID)
                .jsonPath("$.message").isEqualTo("INVALID: " + PRODUCT_ID_INVALID);
    }

    @Test
    @DisplayName("서킷 브레이커가 OPEN 상태(CallNotPermittedException)면 fallback 상품을 반환한다")
    void getProductCircuitBreakerOpenReturnsFallback() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("product");
        when(compositeIntegration.getProduct(eq(PRODUCT_ID_OK), anyInt(), anyInt()))
                .thenReturn(Mono.error(CallNotPermittedException.createCallNotPermittedException(circuitBreaker)));

        getAndVerifyProduct(PRODUCT_ID_OK, OK)
                .jsonPath("$.productId").isEqualTo(PRODUCT_ID_OK)
                .jsonPath("$.name").isEqualTo("Fallback product" + PRODUCT_ID_OK);
    }

    @Test
    @DisplayName("서킷 브레이커가 OPEN 상태이고 fallback 캐시에도 없는 상품이면 404 Not Found를 반환한다")
    void getProductCircuitBreakerOpenAndNotInFallbackCache() {
        CircuitBreaker circuitBreaker = CircuitBreaker.ofDefaults("product");
        when(compositeIntegration.getProduct(eq(PRODUCT_ID_NOT_FOUND_IN_FALLBACK_CACHE), anyInt(), anyInt()))
                .thenReturn(Mono.error(CallNotPermittedException.createCallNotPermittedException(circuitBreaker)));

        getAndVerifyProduct(PRODUCT_ID_NOT_FOUND_IN_FALLBACK_CACHE, NOT_FOUND)
                .jsonPath("$.message").isEqualTo(
                        "Product Id: " + PRODUCT_ID_NOT_FOUND_IN_FALLBACK_CACHE + " not found in fallback cache!");
    }

    @Test
    @DisplayName("토큰 없이 조회하면 401 Unauthorized를 반환한다")
    void getProductWithoutToken() {
        client.get()
                .uri("/product-composite/" + PRODUCT_ID_OK)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @DisplayName("product:read 권한이 없는 토큰으로 조회하면 403 Forbidden을 반환한다")
    void getProductWithoutReadScope() {
        client.mutateWith(mockJwt())   // scope 없는 토큰
                .get()
                .uri("/product-composite/" + PRODUCT_ID_OK)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("product:read 권한만 있는 토큰으로 삭제하면 403 Forbidden을 반환한다")
    void deleteProductWithReadScopeOnly() {
        client.mutateWith(mockJwt().authorities(READ))
                .delete()
                .uri("/product-composite/" + PRODUCT_ID_OK)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    @DisplayName("product:write 권한이 있는 토큰으로 삭제하면 200 OK를 반환한다")
    void deleteProductWithWriteScope() {
        client.mutateWith(mockJwt().authorities(READ, WRITE))
                .delete()
                .uri("/product-composite/" + PRODUCT_ID_OK)
                .exchange()
                .expectStatus().isOk();
    }

    private WebTestClient.BodyContentSpec getAndVerifyProduct(int productId, HttpStatus expectedStatus) {
        return client.mutateWith(mockJwt().authorities(READ))
                .get()
                .uri("/product-composite/" + productId)
                .accept(APPLICATION_JSON)
                .exchange()
                .expectStatus().isEqualTo(expectedStatus)
                .expectHeader().contentType(APPLICATION_JSON)
                .expectBody();
    }
}