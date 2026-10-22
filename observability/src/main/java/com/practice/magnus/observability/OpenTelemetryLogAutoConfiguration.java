package com.practice.magnus.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.logback.appender.v1_0.OpenTelemetryAppender;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

// logback-spring.xml의 OpenTelemetryAppender는 OpenTelemetry 객체를 넘겨받아야 로그를 보내기 시작한다.
// Boot가 OpenTelemetry 빈을 만들어 주지만 어펜더에 연결해 주지는 않으므로 직접 연결한다.
// 자동 설정으로 등록해서(META-INF/spring/...AutoConfiguration.imports) 컴포넌트 스캔 범위와 상관없이
// 이 모듈을 의존성으로 추가한 모든 서비스에 적용된다.
@AutoConfiguration(afterName = "org.springframework.boot.opentelemetry.autoconfigure.OpenTelemetrySdkAutoConfiguration")
@ConditionalOnClass(OpenTelemetryAppender.class)
public class OpenTelemetryLogAutoConfiguration {

    @Bean
    @ConditionalOnBean(OpenTelemetry.class)
    InitializingBean installOpenTelemetryAppender(OpenTelemetry openTelemetry) {
        return () -> OpenTelemetryAppender.install(openTelemetry);
    }
}
