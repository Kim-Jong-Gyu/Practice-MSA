package com.practice.magnus.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.health.contributor.CompositeReactiveHealthContributor;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.ReactiveHealthContributor;
import org.springframework.boot.health.contributor.ReactiveHealthIndicator;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 게이트웨이의 /actuator/health에 뒤쪽 마이크로서비스들의 상태를 함께 보여준다.
 * 서비스 주소는 Eureka에 등록된 이름(spring.application.name)으로 찾는다.
 */
@Configuration
public class HealthCheckConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(HealthCheckConfiguration.class);

    private final WebClient webClient;

    public HealthCheckConfiguration(@LoadBalanced WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder.build();
    }

    @Bean
    ReactiveHealthContributor healthcheckMicroservices() {
        Map<String, ReactiveHealthIndicator> registry = new LinkedHashMap<>();

        registry.put("auth-server",       () -> getHealth("http://auth-server"));
        registry.put("product",           () -> getHealth("http://product"));
        registry.put("recommendation",    () -> getHealth("http://recommendation"));
        registry.put("review",            () -> getHealth("http://review"));
        registry.put("product-composite", () -> getHealth("http://product-composite"));

        return CompositeReactiveHealthContributor.fromMap(registry);
    }

    private Mono<Health> getHealth(String baseUrl) {
        String url = baseUrl + "/actuator/health";
        LOG.debug("Will call the Health API on URL: {}", url);

        return webClient.get().uri(url).retrieve()
            .bodyToMono(String.class)
            .map(s -> Health.up().build())
            .onErrorResume(ex -> Mono.just(Health.down(ex).build()))
            .log();
    }
}
