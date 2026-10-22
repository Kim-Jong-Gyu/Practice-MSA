package com.practice.magnus.composite;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.github.resilience4j.timelimiter.TimeLimiterRegistry;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

// Resilience4j 2.3.0의 health 연동은 Boot 3 API 기준이라 Boot 4에서는 등록되지 않는다.
// 그래서 product 서킷의 상태와 실제로 적용된 설정값을 직접 /actuator/health에 노출한다.
@Component
public class ProductCircuitBreakerHealthIndicator implements HealthIndicator {

    private static final String NAME = "product";
    private static final String IGNORE_EXCEPTIONS_KEY =
            "resilience4j.circuitbreaker.instances." + NAME + ".ignore-exceptions";

    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final TimeLimiterRegistry timeLimiterRegistry;
    private final Binder binder;

    public ProductCircuitBreakerHealthIndicator(
            CircuitBreakerRegistry circuitBreakerRegistry,
            TimeLimiterRegistry timeLimiterRegistry,
            Environment environment) {
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.timeLimiterRegistry = timeLimiterRegistry;
        this.binder = Binder.get(environment);
    }

    @Override
    public Health health() {
        return circuitBreakerRegistry.find(NAME)
                .map(this::toHealth)
                .orElseGet(() -> Health.unknown().withDetail("reason", "circuit breaker not created yet").build());
    }

    private Health toHealth(CircuitBreaker circuitBreaker) {
        CircuitBreaker.State state = circuitBreaker.getState();
        Health.Builder builder = Health.up()
                .withDetail("state", state)
                .withDetail("closed", state == CircuitBreaker.State.CLOSED)
                .withDetail("circuitbreaker", circuitBreakerConfigOf(circuitBreaker.getCircuitBreakerConfig()));
        timeLimiterRegistry.find(NAME)
                .ifPresent(timeLimiter -> builder.withDetail("timelimiter", timeLimiterConfigOf(timeLimiter.getTimeLimiterConfig())));
        return builder.build();
    }

    private Map<String, Object> circuitBreakerConfigOf(CircuitBreakerConfig config) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("minimumNumberOfCalls", config.getMinimumNumberOfCalls());
        details.put("slidingWindowSize", config.getSlidingWindowSize());
        details.put("failureRateThreshold", config.getFailureRateThreshold());
        // 대기 시간은 함수로 저장되어 있어서, 첫 번째 대기 시간을 밀리초로 꺼낸다
        details.put("waitDurationInOpenState", config.getWaitIntervalFunctionInOpenState().apply(1));
        details.put("permittedNumberOfCallsInHalfOpenState", config.getPermittedNumberOfCallsInHalfOpenState());
        details.put("automaticTransitionFromOpenToHalfOpenEnabled", config.isAutomaticTransitionFromOpenToHalfOpenEnabled());
        details.put("ignoreExceptions", ignoreExceptionsOf(config.getIgnoreExceptionPredicate()));
        return details;
    }

    // 설정 파일에 적은 클래스 이름을 읽고, 실제로 무시 대상으로 적용됐는지 확인한다
    private Map<String, Boolean> ignoreExceptionsOf(Predicate<Throwable> ignorePredicate) {
        List<String> classNames = binder.bind(IGNORE_EXCEPTIONS_KEY, Bindable.listOf(String.class)).orElse(List.of());
        Map<String, Boolean> details = new LinkedHashMap<>();
        for (String className : classNames) {
            details.put(className, isIgnored(className, ignorePredicate));
        }
        return details;
    }

    private boolean isIgnored(String className, Predicate<Throwable> ignorePredicate) {
        try {
            Class<?> type = Class.forName(className);
            Throwable sample = (Throwable) type.getDeclaredConstructor(String.class).newInstance("health check");
            return ignorePredicate.test(sample);
        } catch (ReflectiveOperationException | ClassCastException e) {
            return false;   // 클래스를 못 찾거나 만들 수 없으면 적용되지 않은 것으로 본다
        }
    }

    private Map<String, Object> timeLimiterConfigOf(TimeLimiterConfig config) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("timeoutDuration", config.getTimeoutDuration().toString());
        details.put("cancelRunningFuture", config.shouldCancelRunningFuture());
        return details;
    }
}