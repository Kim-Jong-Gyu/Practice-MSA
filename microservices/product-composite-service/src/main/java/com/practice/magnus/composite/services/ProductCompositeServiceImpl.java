package com.practice.magnus.composite.services;

import com.practice.magnus.api.core.product.Product;
import com.practice.magnus.api.core.recommendation.Recommendation;
import com.practice.magnus.api.core.review.Review;
import com.practice.magnus.api.product.*;
import com.practice.magnus.util.exceptions.NotFoundException;
import com.practice.magnus.util.http.ServiceUtil;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@RestController
public class ProductCompositeServiceImpl implements ProductCompositeService {

    private static final Logger LOG = LoggerFactory.getLogger(ProductCompositeServiceImpl.class);

    // 인증 정보가 없을 때(테스트 등) Mono.zip이 비지 않도록 대신 넣는 빈 SecurityContext
    private final SecurityContext nullSC = new SecurityContextImpl();

    private final ServiceUtil serviceUtil;
    private final ProductCompositeIntegration integration;

    public ProductCompositeServiceImpl(ServiceUtil serviceUtil, ProductCompositeIntegration integration) {
        this.serviceUtil = serviceUtil;
        this.integration = integration;
    }

    /**
     * WebFlux에서는 인증 정보가 ThreadLocal이 아니라 리액티브 컨텍스트에 들어 있으므로
     * ReactiveSecurityContextHolder.getContext()로 꺼낸 뒤 처리한다.
     */
    @Override
    public Mono<Void> createCompositeProduct(ProductAggregate body) {
        return ReactiveSecurityContextHolder.getContext()
            .doOnSuccess(sc -> internalCreateCompositeProduct(sc, body))
            .then();
    }

    private void internalCreateCompositeProduct(SecurityContext sc, ProductAggregate body) {

        try {

            logAuthorizationInfo(sc);

            LOG.debug("createCompositeProduct: creates a new composite entity for productId: {}", body.getProductId());

            Product product = new Product(body.getProductId(), body.getName(), body.getWeight(), null);
            integration.createProduct(product);

            if (body.getRecommendations() != null) {
                body.getRecommendations().forEach(r -> {
                    Recommendation recommendation = new Recommendation(body.getProductId(), r.getRecommendationId(), r.getAuthor(), r.getRate(), r.getContent(), null);
                    integration.createRecommendation(recommendation);
                });
            }

            if (body.getReviews() != null) {
                body.getReviews().forEach(r -> {
                    Review review = new Review(body.getProductId(), r.getReviewId(), r.getAuthor(), r.getSubject(), r.getContent(), null);
                    integration.createReview(review);
                });
            }

            LOG.debug("createCompositeProduct: composite entities created for productId: {}", body.getProductId());

        } catch (RuntimeException re) {
            LOG.warn("createCompositeProduct failed: {}", re.toString());
            throw re;
        }
    }

    @Override
    public Mono<ProductAggregate> getCompositeProduct(int productId, int delay, int faultPercent) {
        return Mono.zip(
            values -> createProductAggregate(
                (SecurityContext) values[0],
                (Product) values[1],
                (List<Recommendation>) values[2],
                (List<Review>) values[3],
                serviceUtil.getServiceAddress()),
            ReactiveSecurityContextHolder.getContext().defaultIfEmpty(nullSC),
            integration.getProduct(productId, delay, faultPercent)
                .onErrorResume(CallNotPermittedException.class, ex -> getProductFallbackValue(productId)),
            integration.getRecommendations(productId).collectList(),
            integration.getReviews(productId).collectList())
            .doOnError(ex -> LOG.warn("getCompositeProduct failed: {}", ex.toString()))
            .log();
    }

    /**
     * 서킷 브레이커가 OPEN 상태일 때 호출된다. productId 13은 README/test-em-all.bash가
     * "항상 404" 시나리오로 고정해 둔 값이라, fallback 캐시에도 없는 것으로 취급한다.
     */
    private Mono<Product> getProductFallbackValue(int productId) {
        LOG.warn("Creating a fallback product for productId = {}", productId);
        if (productId == 13) {
            return Mono.error(new NotFoundException("Product Id: " + productId + " not found in fallback cache!"));
        }
        return Mono.just(new Product(productId, "Fallback product" + productId, productId, serviceUtil.getServiceAddress()));
    }


    @Override
    public Mono<Void> deleteCompositeProduct(int productId) {
        return ReactiveSecurityContextHolder.getContext()
            .doOnSuccess(sc -> internalDeleteCompositeProduct(sc, productId))
            .then();
    }

    private void internalDeleteCompositeProduct(SecurityContext sc, int productId) {

        try {

            logAuthorizationInfo(sc);

            LOG.debug("deleteCompositeProduct: Deletes a product aggregate for productId: {}", productId);

            integration.deleteProduct(productId);
            integration.deleteRecommendations(productId);
            integration.deleteReviews(productId);

            LOG.debug("deleteCompositeProduct: aggregate entities deleted for productId: {}", productId);

        } catch (RuntimeException re) {
            LOG.warn("deleteCompositeProduct failed: {}", re.toString());
            throw re;
        }
    }

    private ProductAggregate createProductAggregate(SecurityContext sc, Product product, List<Recommendation> recommendations, List<Review> reviews, String serviceAddress) {

        logAuthorizationInfo(sc);

        // 1. Setup product info
        int productId = product.getProductId();
        String name = product.getName();
        int weight = product.getWeight();

        // 2. Copy summary recommendation info, if available
        List<RecommendationSummary> recommendationSummaries = (recommendations == null) ? null :
             recommendations.stream()
                .map(r -> new RecommendationSummary(r.getRecommendationId(), r.getAuthor(), r.getRate(), r.getContent()))
                .collect(Collectors.toList());

        // 3. Copy summary review info, if available
        List<ReviewSummary> reviewSummaries = (reviews == null)  ? null :
            reviews.stream()
                .map(r -> new ReviewSummary(r.getReviewId(), r.getAuthor(), r.getSubject(), r.getContent()))
                .collect(Collectors.toList());

        // 4. Create info regarding the involved microservices addresses
        String productAddress = product.getServiceAddress();
        String reviewAddress = (reviews != null && !reviews.isEmpty()) ? reviews.getFirst().getServiceAddress() : "";
        String recommendationAddress = (recommendations != null && !recommendations.isEmpty()) ? recommendations.getFirst().getServiceAddress() : "";
        ServiceAddresses serviceAddresses = new ServiceAddresses(serviceAddress, productAddress, reviewAddress, recommendationAddress);

        return new ProductAggregate(productId, name, weight, recommendationSummaries, reviewSummaries, serviceAddresses);
    }

    /**
     * 요청에 담긴 JWT의 정보(누가, 어떤 권한으로)를 DEBUG 로그로 남긴다.
     */
    private void logAuthorizationInfo(SecurityContext sc) {
        if (sc != null && sc.getAuthentication() instanceof JwtAuthenticationToken jwtAuthentication) {
            logAuthorizationInfo(jwtAuthentication.getToken());
        } else {
            LOG.warn("No JWT based Authentication supplied, running tests are we?");
        }
    }

    private void logAuthorizationInfo(Jwt jwt) {
        if (jwt == null) {
            LOG.warn("No JWT supplied, running tests are we?");
        } else if (LOG.isDebugEnabled()) {
            LOG.debug("Authorization info: Subject: {}, scopes: {}, expires: {}, issuer: {}, audience: {}",
                jwt.getSubject(), jwt.getClaim("scope"), jwt.getExpiresAt(), jwt.getIssuer(), jwt.getAudience());
        }
    }
}
