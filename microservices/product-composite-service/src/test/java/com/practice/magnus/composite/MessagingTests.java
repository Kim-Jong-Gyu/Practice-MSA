package com.practice.magnus.composite;

import static com.practice.magnus.composite.IsSameEvent.sameEventExceptCreatedAt;
import static java.util.Collections.singletonList;
import static org.springframework.boot.test.context.SpringBootTest.WebEnvironment.MOCK;
import static org.springframework.http.HttpStatus.OK;
import static reactor.core.publisher.Mono.just;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;
import static com.practice.magnus.api.event.Event.Type.CREATE;
import static com.practice.magnus.api.event.Event.Type.DELETE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.api.core.recommendation.Recommendation;
import com.practice.magnus.api.core.review.Review;
import com.practice.magnus.api.event.Event;
import com.practice.magnus.api.product.ProductAggregate;
import com.practice.magnus.api.product.RecommendationSummary;
import com.practice.magnus.api.product.ReviewSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.cloud.stream.binder.test.OutputDestination;
import org.springframework.cloud.stream.binder.test.TestChannelBinderConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.test.web.reactive.server.WebTestClient;

@DisplayName("Product Composite 메시지 발행 테스트")
@SpringBootTest(
    webEnvironment = MOCK,
    properties = {
            "spring.cloud.stream.default-binder=integration",
            "spring.cloud.config.enabled=false"
    }
)
@AutoConfigureWebTestClient
@Import(TestChannelBinderConfiguration.class)
class MessagingTests {

    private static final Logger LOG = LoggerFactory.getLogger(MessagingTests.class);

    private static final String PRODUCTS = "products";
    private static final String RECOMMENDATIONS = "recommendations";
    private static final String REVIEWS = "reviews";

    @Autowired
    private WebTestClient client;

    @Autowired
    private OutputDestination target;

    @BeforeEach
    void setUp() {
        purgeMessages(PRODUCTS);
        purgeMessages(RECOMMENDATIONS);
        purgeMessages(REVIEWS);
    }

    @Test
    @DisplayName("추천과 리뷰 없이 상품을 생성하면 product CREATE 이벤트만 발행된다")
    void createCompositeProduct_withoutRecommendationsAndReviews() {
        ProductAggregate composite = new ProductAggregate(1, "name", 1, null, null, null);
        postAndVerifyProduct(composite, OK);

        List<String> productMessages = getMessages(PRODUCTS);
        List<String> recommendationMessages = getMessages(RECOMMENDATIONS);
        List<String> reviewMessages = getMessages(REVIEWS);

        // product 생성 이벤트 1건
        assertThat(productMessages).hasSize(1);

        Event<Integer, Product> expectedEvent = new Event<>(CREATE, composite.getProductId(),
            new Product(composite.getProductId(), composite.getName(), composite.getWeight(), null));
        assertThat(productMessages.getFirst(), is(sameEventExceptCreatedAt(expectedEvent)));

        // 추천, 리뷰 이벤트는 없어야 함
        assertThat(recommendationMessages).isEmpty();
        assertThat(reviewMessages).isEmpty();
    }

    @Test
    @DisplayName("추천과 리뷰를 포함해 상품을 생성하면 product, recommendation, review CREATE 이벤트가 각각 발행된다")
    void createCompositeProduct_withRecommendationsAndReviews() {
        ProductAggregate composite = new ProductAggregate(1, "name", 1,
            singletonList(new RecommendationSummary(1, "a", 1, "c")),
            singletonList(new ReviewSummary(1, "a", "s", "c")), null);
        postAndVerifyProduct(composite, OK);

        List<String> productMessages = getMessages(PRODUCTS);
        List<String> recommendationMessages = getMessages(RECOMMENDATIONS);
        List<String> reviewMessages = getMessages(REVIEWS);

        // product 생성 이벤트
        assertThat(productMessages).hasSize(1);
        Event<Integer, Product> expectedProductEvent = new Event<>(CREATE, composite.getProductId(),
            new Product(composite.getProductId(), composite.getName(), composite.getWeight(), null));
        assertThat(productMessages.getFirst(), is(sameEventExceptCreatedAt(expectedProductEvent)));

        // recommendation 생성 이벤트
        assertThat(recommendationMessages).hasSize(1);
        RecommendationSummary rec = composite.getRecommendations().getFirst();
        Event<Integer, Recommendation> expectedRecommendationEvent = new Event<>(CREATE, composite.getProductId(),
            new Recommendation(composite.getProductId(), rec.getRecommendationId(),
                rec.getAuthor(), rec.getRate(), rec.getContent(), null));
        assertThat(recommendationMessages.getFirst(), is(sameEventExceptCreatedAt(expectedRecommendationEvent)));

        // review 생성 이벤트
        assertThat(reviewMessages).hasSize(1);
        ReviewSummary rev = composite.getReviews().getFirst();
        Event<Integer, Review> expectedReviewEvent = new Event<>(CREATE, composite.getProductId(),
            new Review(composite.getProductId(), rev.getReviewId(),
                rev.getAuthor(), rev.getSubject(), rev.getContent(), null));
        assertThat(reviewMessages.getFirst(), is(sameEventExceptCreatedAt(expectedReviewEvent)));
    }

    @Test
    @DisplayName("상품을 삭제하면 product, recommendation, review DELETE 이벤트가 각각 발행된다")
    void deleteCompositeProduct() {
        deleteAndVerifyProduct(1, OK);

        List<String> productMessages = getMessages(PRODUCTS);
        List<String> recommendationMessages = getMessages(RECOMMENDATIONS);
        List<String> reviewMessages = getMessages(REVIEWS);

        assertThat(productMessages).hasSize(1);
        Event<Integer, Product> expectedProductEvent = new Event<>(DELETE, 1, null);
        assertThat(productMessages.getFirst(), is(sameEventExceptCreatedAt(expectedProductEvent)));

        assertThat(recommendationMessages).hasSize(1);
        Event<Integer, Recommendation> expectedRecommendationEvent = new Event<>(DELETE, 1, null);
        assertThat(recommendationMessages.getFirst(), is(sameEventExceptCreatedAt(expectedRecommendationEvent)));

        assertThat(reviewMessages).hasSize(1);
        Event<Integer, Review> expectedReviewEvent = new Event<>(DELETE, 1, null);
        assertThat(reviewMessages.getFirst(), is(sameEventExceptCreatedAt(expectedReviewEvent)));
    }

    /** 이전 테스트에서 남은 메시지를 비운다. */
    private void purgeMessages(String destination) {
        getMessages(destination);
    }

    /** destination에 쌓인 메시지를 모두 꺼내 JSON 문자열 목록으로 반환한다. */
    private List<String> getMessages(String destination) {
        List<String> messages = new ArrayList<>();
        Message<byte[]> message;
        while ((message = getMessage(destination)) != null) {
            messages.add(new String(message.getPayload()));
        }
        return messages;
    }

    private Message<byte[]> getMessage(String destination) {
        try {
            return target.receive(0, destination);
        } catch (NullPointerException npe) {
            // 아직 한 번도 메시지가 발행되지 않은 destination이면 테스트 바인더가 NPE를 던진다
            LOG.debug("No destination named '{}' yet", destination);
            return null;
        }
    }

    private void postAndVerifyProduct(ProductAggregate compositeProduct, HttpStatus expectedStatus) {
        client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_product:write")))
            .post()
            .uri("/product-composite")
            .body(just(compositeProduct), ProductAggregate.class)
            .exchange()
            .expectStatus().isEqualTo(expectedStatus);
    }

    private void deleteAndVerifyProduct(int productId, HttpStatus expectedStatus) {
        client.mutateWith(mockJwt().authorities(new SimpleGrantedAuthority("SCOPE_product:write")))
            .delete()
            .uri("/product-composite/" + productId)
            .exchange()
            .expectStatus().isEqualTo(expectedStatus);
    }
}