# 레거시 → 현재 스택 변환표

레거시: Spring Boot 2.3.2 / Spring Cloud Hoxton.SR6 / Spring Security 5 / Jackson 2
현재: Spring Boot 4.1.1 / Spring Cloud 2025.1 / Spring Security 7 / Jackson 3

새 변환 규칙을 발견하면 이 표에 행을 추가한다. 패키지명(`se.magnus.*` → `com.practice.magnus.*`)처럼
챕터와 무관하게 항상 적용되는 규칙과, 특정 챕터에서만 등장하는 규칙을 구분해서 적되, 섹션으로만 나누고
표 형식은 통일한다.

## 보안 (Spring Security 5 → 7)

| 레거시 | 최신 | 비고 |
|---|---|---|
| `WebSecurityConfigurerAdapter` 상속 + `configure(HttpSecurity)` 오버라이드 | `SecurityFilterChain` `@Bean` + 람다 DSL (`http.authorizeHttpRequests(...).build()`) | `WebSecurityConfigurerAdapter`는 Security 5.7부터 deprecated, 6부터 제거됨 |
| `.antMatchers(...)` / `.mvcMatchers(...)` | `.requestMatchers(...)` | 리액티브 스택은 `authorizeExchange` + `pathMatchers` |
| `.oauth2ResourceServer().jwt()` (구식 체이닝) | `.oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> ...))` 람다 DSL | 전체 보안 설정이 람다 기반으로 통일됨 |
| `@EnableWebFluxSecurity` + `WebFluxSecurityConfiguration` 수동 구성 | `@EnableWebFluxSecurity` + `SecurityWebFilterChain` `@Bean` | 게이트웨이(리액티브)는 `ServerHttpSecurity` 사용 |

## 설정(Config) 클라이언트

| 레거시 | 최신 | 비고 |
|---|---|---|
| `bootstrap.yml`에 `spring.cloud.config.*` 작성 | `spring.config.import: "configserver:http://..."` (`application.yaml` 안에서) | Spring Cloud 2020.0부터 bootstrap 컨텍스트 제거, `spring.config.import`가 표준 진입점 |
| `spring.cloud.config.fail-fast: true` (암묵적 재시도 없음) | 동일 설정 + **`spring-retry` 의존성 필수** | `spring-retry`가 classpath에 없으면 `fail-fast`/`retry.*` 설정이 조용히 무시되고 재시도가 동작하지 않음 |
| `spring.profiles: docker` (YAML 문서 선택자) | `spring.config.activate.on-profile: docker` | `spring.profiles` 키는 Boot 2.4부터 deprecated, 3부터 제거 |
| Config 서버 주소를 `SPRING_CLOUD_CONFIG_URI` 같은 프로필별 변수로 전달 | `APP_CONFIG_SERVER`/`CONFIG_SERVER_USR`/`CONFIG_SERVER_PWD`를 **프로필이 아닌 일반 환경변수**로 docker-compose에 전달 | 어떤 프로필이든 활성화되기 전에 config 서버에 먼저 접속해야 하므로, 프로필 활성화에 의존하는 방식으로 넘기면 접속 타이밍 문제가 생김 |

## 테스트

| 레거시 | 최신 | 비고 |
|---|---|---|
| 서비스별 `application.yml`에 설정 중복 작성 | `src/test/resources/application.yaml`에서 `spring.config.import`로 `config-repo/<service>.yaml`을 직접 import | 운영 설정(config-repo)과 테스트 설정을 분리하지 않고 동일 소스를 재사용 |
| 설정 우선순위를 암묵적으로 가정 | 서비스 모듈 자체의 `src/main/resources/application.yaml`의 **기본(프로필 없는) 섹션**이 config-repo YAML의 **프로필 섹션**보다 우선 적용됨 | 로컬 폴백 설정을 건드릴 때 이 우선순위를 먼저 확인해야 함 — 그렇지 않으면 config-repo에서 바꾼 값이 적용 안 된 것처럼 보임 |

## 패키지 / 빌드

| 레거시 | 최신 | 비고 |
|---|---|---|
| `se.magnus.*` 패키지 | `com.practice.magnus.*` | 전체 패키지 네임스페이스 변경, 이식 시 기계적으로 치환 |
| `com.fasterxml.jackson.*` (Jackson 2) | `tools.jackson.*` (Jackson 3) | 예: `ObjectMapper` → `JsonMapper`, `JsonProcessingException` → `JacksonException` |
| Gradle Groovy DSL 변수 `ext { ... }`로 버전 관리 | 동일 패턴 유지 가능하나 좌표/버전만 최신 BOM(Spring Boot 4.1.1 / Spring Cloud 2025.1)에 맞춰 조정 | 의존성 추가 시 레거시 버전 문자열을 그대로 복사하지 않는다 |

## 그 외 확인된 변경

| 레거시 | 최신 | 비고 |
|---|---|---|
| 서비스 코어 로직에서 `WebSecurityConfigurerAdapter`를 쓰던 자리에 `GlobalControllerExceptionHandler`는 영향 없음 | 동일 | 예외 매핑(`NotFoundException`/`InvalidInputException` → HTTP 상태)은 Security 버전과 무관하게 레거시와 동일 패턴 유지 — 괜히 바꾸지 않는다 |
| Spring Boot 2의 `/actuator/health` 응답 구조 `.details.<이름>...` | `.components.<이름>...` | Boot 2.2부터 `details`가 `components`로 바뀜. 책의 `jq` 경로를 그대로 쓰면 `null`이 나온다 |
| `server.error.include-message: always` | `spring.web.error.include-message: always` | Boot 4.1.1에서 `server.error.include-message`는 deprecated이고, WebFlux 기본 오류 응답(500)에 `message`가 빠진다. 404/422는 `GlobalControllerExceptionHandler`가 직접 메시지를 넣어서 영향이 없어 보이므로 놓치기 쉽다. Chapter13 e2e의 타임아웃(500) 메시지 검증에서 발견 |
| Jackson 2는 enum을 JSON으로 쓸 때 `name()` 사용 | Jackson 3는 기본값이 `toString()` 사용(`EnumFeature.WRITE_ENUMS_USING_TO_STRING`) | 대부분의 enum은 `toString()`을 재정의하지 않아 차이가 없지만, 라이브러리 enum은 결과 문자열이 달라진다. 예: Resilience4j `StateTransition`은 `CLOSED_TO_OPEN`이 아니라 `State transition from CLOSED to OPEN`으로 나온다. JSON 응답의 enum 값을 비교하는 테스트는 실제 응답을 한 번 찍어 보고 맞출 것 |

## 서킷브레이커 / Resilience4j (Chapter13)

Spring은 모르는 설정 키를 **오류 없이 무시**하고, Resilience4j 자동 설정도 조건이 안 맞으면 **조용히 꺼진다.**
이 섹션의 함정 대부분이 "앱은 잘 뜨는데 기능만 안 된다" 형태이므로, 설정을 바꾼 뒤에는 반드시 실제 적용된 값을
확인한다 (`ProductCircuitBreakerHealthIndicator`가 `/actuator/health`에 적용된 설정값을 같이 보여준다).

| 레거시 | 최신 | 비고 |
|---|---|---|
| Resilience4j 좌표가 `resilience4j-spring-boot2` | `resilience4j-spring-boot3` + `resilience4j-reactor` | Spring Boot 4에서도 `-spring-boot3` 계열이 현재 최신 스타터다(2026-10 기준 `-spring-boot4` 전용 모듈 없음) |
| Resilience4j 버전을 Maven Central 최신(2.4.0)으로 임의 고정 | 이 프로젝트가 이미 쓰는 `spring-cloud-dependencies` BOM이 내부적으로 import하는 `spring-cloud-circuitbreaker-dependencies`가 `resilience4j.version=2.3.0`을 관리 버전으로 고정하고 있어, `./gradlew :module:dependencies`로 보면 2.4.0을 직접 선언해도 2.3.0으로 다운그레이드된다(`io.spring.dependency-management`가 BOM 관리 버전을 우선 적용). resilience4j 관련 좌표는 전부 BOM이 관리하는 버전(2.3.0)에 맞춰 선언해 버전 혼용을 피한다 | Chapter13 구현 중 `./gradlew :microservices:product-composite-service:dependencies --configuration runtimeClasspath`로 실제 발견 |
| (레거시엔 없음) Spring Cloud CircuitBreaker 스타터 선택 | 리액티브(WebFlux) 서비스는 `spring-cloud-starter-circuitbreaker-reactor-resilience4j` | 비리액티브용은 `spring-cloud-starter-circuitbreaker-resilience4j`. 리액티브 스타터를 쓰면 `resilience4j-reactor`, `resilience4j-retry`, `resilience4j-timelimiter`가 함께 들어온다(`./gradlew :module:dependencies`로 확인). `resilience4j-reactor`가 없으면 애노테이션이 `Mono`의 실제 결과가 아니라 "Mono를 반환했다"만 보고 성공으로 기록해 서킷이 절대 열리지 않는다 |
| Resilience4j 1.x의 `backends`/`ringBufferSizeInClosedState`/`ringBufferSizeInHalfOpenState`/`maxRetryAttempts` | 2.x에서 `instances`/`slidingWindowSize`/`permittedNumberOfCallsInHalfOpenState`/`maxAttempts`로 이름 변경(1.x 호환 키는 제거됨) | Chapter13 설정(`config-repo/product-composite.yaml`)에 적용. `resilience4j-framework-common`의 `CommonCircuitBreakerConfigurationProperties`/릴리스 노트 기준 |
| 레거시 1.x의 `ringBufferSizeInClosedState`(≈슬라이딩 윈도 크기이자 동시에 "이 호출 수만큼 쌓이면 바로 실패율 계산 시작") | 2.x의 `slidingWindowSize`와 `minimumNumberOfCalls`는 **서로 분리된 설정**이고, `minimumNumberOfCalls`의 기본값은 100이다(`CircuitBreakerConfig.DEFAULT_MINIMUM_NUMBER_OF_CALLS`) | `slidingWindowSize: 5`만 설정하고 `minimumNumberOfCalls`를 빼먹으면 호출이 100번 쌓이기 전까지 실패율이 계산되지 않아 서킷이 절대 OPEN되지 않는다. `slidingWindowSize`와 같은 값(또는 그 이하)으로 `minimumNumberOfCalls`를 반드시 같이 설정할 것 |
| (해당 없음, Boot 3 스타터부터 생긴 이슈) `@Retry`+`@CircuitBreaker`를 한 메서드에 같이 붙이면 기본 애스펙트 순서상 `@Retry`가 `@CircuitBreaker` 바깥에서 돌아 재시도 1회당 실패가 각각 집계됨 | `resilience4j.circuitbreaker.circuitBreakerAspectOrder=1`, `resilience4j.retry.retryAspectOrder=2`를 명시해 CircuitBreaker가 Retry를 감싸도록 고정 | `resilience4j-spring-boot3` README에 명시된 주의사항. 두 애너테이션을 같이 쓰는 모든 메서드에 적용 |
| 레거시 `resilience4j-spring-boot2`만 추가하면 `@CircuitBreaker`/`@Retry`가 바로 동작했음(당시엔 암묵적으로 AOP 스타터가 같이 들어왔을 가능성) | `resilience4j-spring-boot3`는 AspectJ 위빙 스타터를 `compileOnly`로만 선언해 두므로, **우리 모듈 build.gradle에 직접 추가해야 한다.** 빠뜨려도 컴파일은 되고 앱도 정상 기동하지만, `@CircuitBreaker`/`@Retry` 애스펙트가 조용히 적용되지 않아 `/actuator/circuitbreakers`의 `bufferedCalls`가 계속 0으로 남는다(에러도, 경고도 없음) | e2e(`test-em-all.bash`)에서 서킷이 OPEN으로 안 바뀌는 걸 보고 실제로 겪은 문제. 단위 테스트(Mockito로 `ProductCompositeIntegration` 자체를 모킹)는 AOP 프록시를 안 거치므로 이 누락을 못 잡아낸다 — 애스펙트 동작 확인은 반드시 실제 바인딩된 컨테이너(e2e)로 해야 한다 |
| 레거시 `org.springframework.boot:spring-boot-starter-aop` | Boot 4에서 `org.springframework.boot:spring-boot-starter-aspectj`로 **아티팩트 이름 자체가 바뀜**(`-aop` 좌표는 Boot 4.1.1 `spring-boot-dependencies` BOM에 아예 없음) | `grep artifactId spring-boot-dependencies-4.1.1.pom`으로 확인. 옛 이름 그대로 쓰면 "Could not find ...:." 처럼 버전이 빈 채로 해석 실패한다 |
| `@Retry` + `@CircuitBreaker`만 붙이고 타임아웃은 메서드 안 `.timeout(Duration)`으로 처리 | 애노테이션 방식에서는 `@TimeLimiter(name = "product")`를 **직접 붙여야** `resilience4j.timelimiter.instances.product` 설정이 적용된다 | 설정만 추가하면 레지스트리에 등록만 될 뿐(health에도 보임) 호출에는 걸리지 않는다. `@TimeLimiter`도 내부적으로 Reactor `timeout`을 써서 오류 메시지는 `Did not observe any item or terminal signal within 2000ms...`로 레거시와 같다. 애노테이션 대신 Spring Cloud 팩토리(`ReactiveCircuitBreakerFactory.create("product").run(mono, fallback)`)를 쓰면 TimeLimiter가 기본으로 켜진다 |
| (애노테이션 방식) `@CircuitBreaker(fallbackMethod = ...)` + `CallNotPermittedException` 인자로 fallback 대상 한정 | 이 프로젝트는 `ProductCompositeServiceImpl`에서 `onErrorResume(CallNotPermittedException.class, ...)`로 처리 | 서킷이 열렸을 때만 fallback, 타임아웃은 그대로 500. Spring Cloud 팩토리 방식으로 바꾸면 `run()`의 fallback은 **모든 오류**에서 불리므로(`ignoreExceptions`와 무관), `CallNotPermittedException`이 아니면 `Mono.error(ex)`로 다시 던져야 같은 동작이 된다 |
| 레거시 `integration.getProduct(productId).onErrorReturn(CallNotPermittedException.class, getProductFallbackValue(productId))` | `.onErrorResume(CallNotPermittedException.class, ex -> Mono.just(getProductFallbackValue(productId)))` | `onErrorReturn(Class, T value)`는 `value` 인자를 **즉시(eager)** 평가한다 — 에러가 실제로 나든 안 나든 메서드 호출 시점에 매번 실행됨. 레거시 fallback 메서드는 `productId==13`이면 즉시 `NotFoundException`을 던지므로, 서킷이 멀쩡히 CLOSED여도 productId 13 요청은 항상 "fallback cache" 메시지로 응답이 바뀌는 부작용이 있다(실제로 레거시 Chapter13 test-em-all.bash는 이 부작용 때문에 평상시 404 메시지 검증을 빼버렸다). `onErrorResume` + 람다로 지연 평가하면 서킷이 OPEN일 때만 fallback이 호출되어 기존 "No product found for productId: 13" 메시지가 유지된다 |
| 레거시(1.x)의 `registerHealthIndicator: true` + `management.health.circuitbreakers.enabled: true` → `/actuator/health`의 `components.circuitBreakers.details.<name>.details.state`로 상태 확인 | **Spring Boot 4에서는 동작하지 않는다.** `resilience4j-spring-boot3` 2.3.0의 `CircuitBreakersHealthIndicator`는 Boot 3의 `org.springframework.boot.actuate.health.HealthIndicator`를 구현하는데, 이 인터페이스가 Boot 4.1.1에서 제거됐다(새 위치: `org.springframework.boot.health.contributor.*`). 자동 설정이 조용히 꺼져서 `/actuator/health`에 `circuitBreakers`가 안 생긴다. 이 프로젝트는 `ProductCircuitBreakerHealthIndicator`(Boot 4 `HealthIndicator` 구현, `CircuitBreakerRegistry`/`TimeLimiterRegistry`에서 상태와 적용된 설정값을 읽음)를 직접 만들어 `.components.productCircuitBreaker.details.state`로 확인한다 | `registerHealthIndicator`/`management.health.circuitbreakers.enabled`는 효과가 없다. `/actuator/circuitbreakers`, `/actuator/circuitbreakerevents`는 Boot 4에도 있는 `@Endpoint` 기반이라 동작하지만, `management.endpoints.web.exposure.include`에 직접 추가해야 열린다(테스트에서는 상태 전이 이력 확인용으로 `circuitbreakerevents`만 사용) |
| 레거시(1.x) `/actuator/circuitbreakerevents/.../STATE_TRANSITION` 응답의 `stateTransition` 값이 `CLOSED_TO_OPEN` | `"State transition from CLOSED to OPEN"` | Resilience4j가 바뀐 게 아니라 **Jackson 3가 enum을 `toString()`으로 쓰기 때문**이다(위 "그 외 확인된 변경"의 Jackson 행 참고). 문자열 전체로 비교한다 |

## e2e 테스트 (`test-em-all.bash`)

| 레거시 | 최신 | 비고 |
|---|---|---|
| 서킷 테스트를 실패 호출 3번으로 바로 시작 | 먼저 정상 호출을 **`slidingWindowSize`만큼(5번)** 보내 슬라이딩 윈도를 성공으로 가득 채운 뒤 실패 3번을 보낸다 | 서킷의 슬라이딩 윈도는 컨테이너가 살아 있는 동안 이전 실행의 호출 결과를 계속 기억한다. 정상 호출을 덜 채우면 이전 실행의 실패가 섞여서 예상보다 일찍 열린다(실제로 타임아웃 2번 만에 OPEN). 5번 채우면 실패율이 20% → 40% → 60%로 올라 정확히 3번째 실패에서 열린다. 시작 시 서킷이 `CLOSED`인지 먼저 확인할 것 |
| `docker run --rm -it --network=... alpine wget ... \| jq` | `docker run --rm --network=my-network alpine wget -qO- ... \| jq` | 결과를 파이프로 넘길 때는 `-t`(TTY)를 빼야 출력에 불필요한 문자가 섞이지 않는다. compose 네트워크 이름은 `networks.my-network.name`으로 고정되어 있다. 서비스 이미지(`eclipse-temurin:21-jre`, Ubuntu 기반)에도 `wget`/`curl`이 있어서 `docker compose exec <서비스> curl ...`로도 된다 |

## 쿠버네티스 / Eureka 제거 (Chapter16)

| 레거시 | 최신 | 비고 |
|---|---|---|
| eureka-client 의존성만 빼면 `@LoadBalanced WebClient`가 그냥 DNS로 동작 (Hoxton에서는 Ribbon이 eureka-client로만 들어왔기 때문) | `@LoadBalanced`를 **직접 제거**하고 일반 `WebClient.Builder`로 `http://product`를 호출한다 | Spring Cloud LoadBalancer가 다른 경로로 남아 있으면 `@LoadBalanced`가 `SimpleDiscoveryClient`에서 인스턴스를 못 찾아 호출이 실패한다. gateway의 `HealthCheckConfiguration`도 같다 |
| 쿠버네티스 probe는 쿠버네티스 환경을 감지했을 때만 `/actuator/health/liveness`·`/readiness`가 켜짐 (Boot 2.3) | Boot 4.1에서는 `management.endpoint.health.probes.enabled`가 `matchIfMissing = true`라 **어디서나 기본 활성** | `spring-boot-health-4.1.1.jar`의 `AvailabilityProbesAutoConfiguration` 바이트코드로 확인. 이 group에는 기본적으로 다른 health indicator(rabbit 등)가 들어가지 않는다 |
| config-server `SecurityConfig`에 `/actuator/**` permitAll만 추가 | 이 프로젝트의 config-server에는 **actuator 의존성 자체가 없었으므로** `spring-boot-starter-actuator`도 추가해야 한다 | 없으면 probe 경로가 404. `@AutoConfigureMockMvc`는 Boot 4에서 `spring-boot-webmvc-test` 모듈의 `org.springframework.boot.webmvc.test.autoconfigure` 패키지에 있다 |
| ConfigMap/Secret은 `kubectl create configmap config-repo --from-file=config-repo/` 등 명령으로 생성 | 그대로 유지 (YAML로 두지 않음) | `config-repo/`가 유일한 원본이고 비밀번호를 git에 남기지 않기 위해서. Kustomize의 `bases:`는 deprecated라 `resources: [../../base]`로 참조한다 |
| `mysql:5.7` + `--ignore-db-dir=lost+found` | `mysql:8.4`, 옵션 제거 | 해당 옵션은 MySQL 8에서 제거됐다 |
| RabbitMQ probe `GET /api/aliveness-test/%2F` + Basic 헤더 | `exec: rabbitmq-diagnostics -q ping` | compose healthcheck와 같은 명령. 인증 헤더를 manifest에 넣지 않아도 된다 |
