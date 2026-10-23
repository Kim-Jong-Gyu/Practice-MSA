package com.practice.magnus.composite.services;

import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.api.core.product.ProductService;
import com.practice.magnus.api.core.recommendation.Recommendation;
import com.practice.magnus.api.core.recommendation.RecommendationService;
import com.practice.magnus.api.core.review.Review;
import com.practice.magnus.api.core.review.ReviewService;
import com.practice.magnus.api.event.Event;
import com.practice.magnus.util.exceptions.InvalidInputException;
import com.practice.magnus.util.exceptions.NotFoundException;
import com.practice.magnus.util.http.HttpErrorInfo;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import io.github.resilience4j.timelimiter.annotation.TimeLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;

import static com.practice.magnus.api.event.Event.Type.CREATE;
import static com.practice.magnus.api.event.Event.Type.DELETE;
import static reactor.core.publisher.Flux.empty;


@Component
public class ProductCompositeIntegration implements ProductService, RecommendationService, ReviewService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductCompositeIntegration.class);

    // 서비스 이름을 DNS로 찾는다 (docker compose 서비스 이름 / 쿠버네티스 Service 이름). 포트 생략 = 80
    private static final String PRODUCT_SERVICE_URL = "http://product";
    private static final String RECOMMENDATION_SERVICE_URL = "http://recommendation";
    private static final String REVIEW_SERVICE_URL = "http://review";


    private static final String PRODUCTS_BINDING = "products-out-0";
    private static final String RECOMMENDATIONS_BINDING = "recommendations-out-0";
    private static final String REVIEWS_BINDING = "reviews-out-0";

    private final WebClient webClient;
    private final JsonMapper mapper;
    private final StreamBridge streamBridge;


    public ProductCompositeIntegration(
            WebClient.Builder webClientBuilder,
            JsonMapper mapper,
            StreamBridge streamBridge
    ) {
        this.webClient = webClientBuilder.build();   // 늦은 초기화 없이 바로 생성
        this.mapper = mapper;
        this.streamBridge = streamBridge;
    }

    @Override
    public Product createProduct(Product body) {
        sendMessage(PRODUCTS_BINDING, new Event<>(CREATE, body.getProductId(), body));
        return body;
    }

    @Retry(name = "product")
    @CircuitBreaker(name = "product")
    @TimeLimiter(name = "product")
    @Override
    public Mono<Product> getProduct(int productId, int delay, int faultPercent) {
        URI url = UriComponentsBuilder.fromUriString(
                PRODUCT_SERVICE_URL + "/product/{productId}?delay={delay}&faultPercent={faultPercent}")
            .build(productId, delay, faultPercent);
        LOG.debug("Will call the getProduct API on URL: {}", url);

        return webClient.get().uri(url).retrieve()
                .bodyToMono(Product.class)
                .log()
                .onErrorMap(WebClientResponseException.class, this::handleException);
    }

    @Override
    public void deleteProduct(int productId) {
        sendMessage(PRODUCTS_BINDING, new Event<Integer, Product>(DELETE, productId, null));
    }

    @Override
    public Recommendation createRecommendation(Recommendation body) {
        sendMessage(RECOMMENDATIONS_BINDING, new Event<>(CREATE, body.getProductId(), body));
        return body;
    }

    @Override
    public Flux<Recommendation> getRecommendations(int productId) {
        String url = RECOMMENDATION_SERVICE_URL + "/recommendation?productId=" + productId;
        LOG.debug("Will call the getRecommendations API on URL: {}", url);

        // 일부 서비스가 실패해도 composite가 부분 응답을 줄 수 있도록 빈 결과로 대체
        return webClient.get().uri(url).retrieve()
                .bodyToFlux(Recommendation.class)
                .log()
                .onErrorResume(error -> empty());
    }

    @Override
    public void deleteRecommendations(int productId) {
        sendMessage(RECOMMENDATIONS_BINDING, new Event<Integer, Recommendation>(DELETE, productId, null));
    }

    @Override
    public Review createReview(Review body) {
        sendMessage(REVIEWS_BINDING, new Event<>(CREATE, body.getProductId(), body));
        return body;
    }

    @Override
    public Flux<Review> getReviews(int productId) {
        String url = REVIEW_SERVICE_URL + "/review?productId=" + productId;
        LOG.debug("Will call the getReviews API on URL: {}", url);

        // 일부 서비스가 실패해도 composite가 부분 응답을 줄 수 있도록 빈 결과로 대체
        return webClient.get().uri(url).retrieve()
                .bodyToFlux(Review.class)
                .log()
                .onErrorResume(error -> empty());
    }

    @Override
    public void deleteReviews(int productId) {
        sendMessage(REVIEWS_BINDING, new Event<Integer, Review>(DELETE, productId, null));
    }

    private <K, T> void sendMessage(String bindingName, Event<K, T> event) {
        LOG.debug("Sending a {} message to {}", event.getEventType(), bindingName);
        Message<Event<K, T>> message = MessageBuilder.withPayload(event)
                .setHeader("partitionKey", event.getKey())
                .build();
        streamBridge.send(bindingName, message);
    }

    private Throwable handleException(Throwable ex) {
        if (!(ex instanceof WebClientResponseException wcre)) {
            LOG.warn("Got a unexpected error: {}, will rethrow it", ex.toString());
            return ex;
        }

        HttpStatus status = HttpStatus.resolve(wcre.getStatusCode().value());
        if (status == null) {
            LOG.warn("Got a unknown HTTP status: {}, will rethrow it", wcre.getStatusCode());
            return ex;
        }

        switch (status) {
            case NOT_FOUND:
                return new NotFoundException(getErrorMessage(wcre));

            case UNPROCESSABLE_CONTENT:
                return new InvalidInputException(getErrorMessage(wcre));

            default:
                LOG.warn("Got a unexpected HTTP error: {}, will rethrow it", wcre.getStatusCode());
                LOG.warn("Error body: {}", wcre.getResponseBodyAsString());
                return ex;
        }
    }

    private String getErrorMessage(WebClientResponseException ex) {
        try {
            return mapper.readValue(ex.getResponseBodyAsString(), HttpErrorInfo.class).getMessage();
        } catch (JacksonException jex) {
            return ex.getMessage();
        }
    }
}