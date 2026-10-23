# CLAUDE.md

이 파일은 이 저장소의 코드를 다룰 때 Claude Code(claude.ai/code)에게 가이드를 제공합니다.

## 프로젝트 개요

Magnus Larsson의 "Microservices with Spring Boot and Spring Cloud" 아키텍처를 따르는 리액티브 Spring Boot
마이크로서비스 시스템(Java 21, Spring Boot 4.1.1, Gradle 멀티 모듈)이며, 최신 Spring 버전에 맞게 조정되었습니다
(참고: `com.fasterxml.jackson`이 아닌 Jackson 3의 `tools.jackson.*` 패키지를 사용합니다). 비즈니스 도메인은
product/recommendation/review 애그리거트로, 컴포지트 API를 통해 노출되며, OAuth2/JWT 보안을 적용한 Spring Cloud
Gateway가 전면에 있고, Spring Cloud Config를 통한 중앙화된 설정을 사용합니다. 서비스 디스커버리(Eureka)는 쓰지 않으며,
서비스 이름을 DNS(docker compose 네트워크 / 쿠버네티스 Service)로 찾습니다. 쿠버네티스 매니페스트는 `kubernetes/services/`에 있습니다.

## 명령어

사전 요구사항: Docker Desktop 실행 중(단위 테스트의 Testcontainers와 docker compose 양쪽에서 사용), `jq` 설치,
`chmod +x gradlew test-em-all.bash`.

빌드/단위/통합 테스트 (Gradle, 모듈별 Mongo/MySQL용 Testcontainers):
```bash
./gradlew test                                                          # 전체 모듈
./gradlew :microservices:product-service:test                          # 단일 서비스
./gradlew :microservices:product-service:test --tests "*PersistenceTests"  # 단일 테스트 클래스
```
테스트 클래스 네이밍 규칙 (전체 표는 README.md 참고):
- `*PersistenceTests` — Testcontainers만 사용 (Mongo/MySQL)
- `*ServiceApplicationTests` — Testcontainers + `WebTestClient` + `messageProcessor()` 빈 직접 호출
- `MessagingTests` (컴포지트 서비스 전용) — Spring Cloud Stream 테스트 바인더(`OutputDestination`) 사용

실행 중인 스택을 대상으로 하는 엔드투엔드 테스트(`test-em-all.bash`), 항상 게이트웨이를 거쳐 HTTPS로 수행:
```bash
./gradlew clean build -x test                 # 먼저 jar를 다시 빌드 — Dockerfile이 build/libs/*.jar를 복사함
./test-em-all.bash                             # 이미 실행 중인 스택을 대상으로 실행
./test-em-all.bash start stop                  # 스택을 올리고, 테스트하고, 종료
COMPOSE_FILE=docker-compose-partitions.yml ./test-em-all.bash start stop   # RabbitMQ 파티션 변형
COMPOSE_FILE=docker-compose-kafka.yml ./test-em-all.bash start stop       # Kafka 변형
HOST=localhost PORT=7000 ./test-em-all.bash    # 로컬에서 띄운 서비스 대상(예: IntelliJ에서 실행), 게이트웨이 없이
                                               # (디스커버리가 없어 composite → 코어 서비스 호출은 로컬에서 실패함)
USE_AUTH0=false ./test-em-all.bash             # Auth0 대신 로컬 auth-server에서 토큰 발급
```
`test-em-all.bash`는 고정된 `productId`(1, 13, 113, 213, -1, `invalidProductId`)에 대해 특정 HTTP 코드/응답 본문을
기대하도록 assert합니다 — 시드 데이터나 검증 동작을 변경하기 전에 README.md의 표를 확인하세요.

필요하며 gitignore 처리된 로컬 시크릿: `.env` (compose용: `SERVER_SSL_KEY_STORE_PASSWORD`,
`CONFIG_SERVER_ENCRYPT_KEY`, `CONFIG_SERVER_USR`, `CONFIG_SERVER_PWD`) 및 `auth0.env`
(test-em-all.bash용: `AUTH0_CLIENT_ID`, `AUTH0_CLIENT_SECRET`, `AUTH0_USER`, `AUTH0_PASSWORD`).

수동 확인(포트/URL 하단 참고): curl 예제, Swagger UI URL, RabbitMQ/Kafka 조회 명령, `/actuator/bindings` 확인은
README.md 3절 참고.

## 모듈 구성

- `api` — 공유 DTO 및 REST API 인터페이스(`com.practice.magnus.api.core.{product,recommendation,review}`,
  컴포지트 애그리거트용 `com.practice.magnus.api.product.*`, `com.practice.magnus.api.event.Event`). 핵심 서비스
  인터페이스(`ProductService`, `RecommendationService`, `ReviewService`)는 각 코어 마이크로서비스(실제 로직)와
  `ProductCompositeIntegration`(원격 프록시) 양쪽에서 구현됩니다 — 이것이 모든 서비스가 컴파일 대상으로 삼는
  계약입니다.
- `util` — 공유 횡단 관심사 코드: `util.exceptions`(`NotFoundException`, `InvalidInputException`,
  `EventProcessingException`), `util.http.GlobalControllerExceptionHandler`(해당 예외들을 HTTP 상태로 매핑),
  `util.http.ServiceUtil`(응답에 서비스 자신의 주소/포트를 표시). `src/testFixtures`에는 모든 퍼시스턴스 테스트가
  공유하는 싱글턴 재사용 Testcontainers인 `MongoDbTestBase`/`MySqlTestBase`가 있습니다.
- `microservices/product-service`, `recommendation-service` — 각자의 MongoDB 컬렉션(`product-db`)을 소유하며,
  Spring Data MongoDB 리포지토리 + MapStruct 매퍼(엔티티 ↔ API 모델)를 통해 CRUD를 수행합니다.
- `microservices/review-service` — MySQL 테이블(`review-db`)을 소유하며, JPA 기반으로 동일한
  리포지토리/매퍼 패턴을 사용합니다.
- `microservices/product-composite-service` — 유일하게 인터넷에 노출되는 비즈니스 API(`/product-composite/**`).
  자체 데이터베이스는 없으며, `ProductCompositeServiceImpl`이 `ProductCompositeIntegration`을 통해 세 개의 코어
  서비스를 호출하여 `ProductAggregate`를 조합합니다.
- `spring-cloud/gateway` — 유일한 공개 진입점, TLS 종료(`keystore/` 아래의 자체 서명 인증서, `:8443`),
  OAuth2 리소스 서버, 그리고 컴포지트 서비스/auth 서버/config 서버로의 라우팅 테이블
  (`config-repo/gateway.yaml`, `lb://`가 아니라 `http://<서비스 이름>`).
- `spring-cloud/config-server` — 다른 모든 서비스의 설정을 `config-repo/`(native 프로필)에서 제공하며, HTTP
  Basic으로 보호되고(`/actuator/**`만 쿠버네티스 probe를 위해 인증 없이 열려 있음), `/encrypt`/`/decrypt`를 지원합니다.
- `spring-cloud/authorization-server` — Auth0의 대체재인 로컬 Spring Authorization Server(OAuth2/OIDC)로,
  고정된 `reader`/`writer`/`noscopes` 클라이언트와 고정된 RSA 서명 키를 사용합니다(재시작해도 발급된 토큰이
  무효화되지 않도록). 기본적으로는 사용되지 않음 — 아래 보안 섹션 참고.

## 통신 패턴

- **동기(조회)**: `product-composite`는 일반 `WebClient`(`@LoadBalanced` 아님)로 세 개의 코어 서비스를 HTTP로
  호출합니다(서비스명을 DNS로 해석, 예: `http://product` — 포트 생략 = 80). 다운스트림의 404/422는
  `NotFoundException`/`InvalidInputException`으로 다시 매핑됩니다. `getRecommendations`/`getReviews`의
  에러는 삼켜지며(`onErrorResume(empty())`), 이를 통해 하나의 코어 서비스가 다운되어도 컴포지트가 부분 애그리거트를
  반환할 수 있습니다.
- **비동기(생성/삭제)**: `product-composite`는 코어 서비스에 직접 쓰지 않습니다. `StreamBridge`를 통해
  `*-out-0` 바인딩으로 `Event<K,T>`(타입 `CREATE`/`DELETE`)를 발행하며, 각 코어 서비스는
  `messageProcessor-in-0`에 바인딩된 단일 `Consumer<Event<...>> messageProcessor()` 빈
  (`*/config/MessageProcessorConfig.java`)을 통해 이를 소비합니다. 기본 바인더는 RabbitMQ이며, `kafka` Spring
  프로필(`docker-compose-kafka.yml`)로 Kafka를 활성화할 수 있습니다. 파티션된 소비(2개 인스턴스, `partitionKey`
  메시지 헤더를 통해 `productId`로 키 분할)는 `streaming_partitioned` + `streaming_instance_{0,1}` 프로필
  (`docker-compose-partitions.yml`)로 활성화됩니다. 컨슈머는 백오프와 함께 3회 재시도 후 RabbitMQ에서
  데드레터(DLQ) 처리됩니다.
- 새로운 비동기 사이드 이펙트를 작성할 때: 새 REST 엔드포인트가 아니라 해당 `MessageProcessorConfig`에 이벤트
  타입 분기를 추가하세요. 이렇게 해야 생성/삭제가 컴포지트의 요청 경로와 분리되어 idempotent하게 유지됩니다.

## 설정(Configuration)

모든 서비스는 Spring Cloud Config 클라이언트이며, 단일 소스 오브 트루스는 `config-repo/`(서비스별 YAML 하나씩
+ 공유 `application.yaml`)이고, `native` 프로필의 `config-server`가 이를 제공합니다. 각 서비스의 설정 파일은
Spring 프로필(`docker`(컨테이너 네트워킹, HTTP 포트 80), `kafka`, `streaming_partitioned`,
`streaming_instance_0`/`_1`)로 활성화되는 `---` 문서를 사용합니다. 서비스의 포트, 바인더, 라우팅을 변경할 때는
모듈 자체의 `src/main/resources`에 있는 `application.yaml`이 아니라 `config-repo/<service>.yaml`을
수정하세요(그것은 config 클라이언트가 연결되기 전의 로컬 개발용 폴백일 뿐입니다). `docker-compose.yml`은
`APP_CONFIG_SERVER`/`CONFIG_SERVER_USR`/`CONFIG_SERVER_PWD`를 프로필이 아니라 일반 환경 변수로 전달하는데,
이는 어떤 프로필이든 적용되기 전에 config 서버 주소가 필요하기 때문입니다.

## 쿠버네티스

- `kubernetes/services/base`(애플리케이션 + grafana-lgtm)와 `overlays/dev`(base + mongodb/mysql/rabbitmq),
  Kustomize로 묶는다: `kubectl apply -k kubernetes/services/overlays/dev`.
- 이미지는 `hands-on/*`(docker compose의 `image:`와 같은 이름), `imagePullPolicy: Never`라 minikube Docker에서 빌드한다.
- 구성 저장소 ConfigMap(`config-repo`)과 Secret은 YAML로 두지 않고 `kubectl create ... --from-file=config-repo/`로
  만든다 — `config-repo/`가 유일한 원본이고 비밀번호를 git에 남기지 않기 위해서다. 명령은 README.md 4절 참고.
- probe는 Spring Boot의 `/actuator/health/liveness`·`/readiness`(Boot 4에서는 쿠버네티스 밖에서도 기본 활성).
  새 서비스를 추가할 때 actuator가 인증 없이 열려 있는지 확인한다.

## 보안

- `gateway`와 `product-composite`는 둘 다 JWT를 검증하는 OAuth2 리소스 서버이며, 기본적으로 로컬
  `authorization-server`가 아니라 Auth0를 발행자(issuer)로 신뢰합니다
  (`config-repo/{gateway,product-composite}.yaml`의
  `spring.security.oauth2.resourceserver.jwt.issuer-uri`). `product:read`/`product:write` 스코프가
  `/product-composite/**`에 대한 GET과 POST/DELETE를 각각 제어합니다(각 모듈의 `SecurityConfig` 참고).
- 로컬 `authorization-server`는 즉시 대체 가능한 대안으로 존재합니다(test-em-all.bash의 `USE_AUTH0=false`는
  해당 서버의 `client_credentials` 그랜트를 사용함). 기본 발행자를 Auth0에서 이것으로 전환하는 것은 코드 변경이
  아니라 설정 변경입니다.
- `config-server`는 HTTP Basic을 사용합니다(자격 증명은 `CONFIG_SERVER_USR`/`PWD` 환경 변수, 쿠버네티스에서는
  `config-server-secrets`/`config-client-credentials` Secret에서 가져옴) — 내부용이며 JWT로 보호되지 않습니다.
  `/actuator/**`는 쿠버네티스 probe를 위해 인증 없이 열려 있습니다.
- TLS는 게이트웨이에서만 종료됩니다(`server.ssl` + `keystore/edge.p12`/`edge-test.p12`). 그 외 모든 내부
  트래픽(컴포지트 → 코어 서비스, 게이트웨이 → 나머지 전체)은 compose 네트워크 / 클러스터 내에서 평문 HTTP로 흐릅니다.

## 레거시 프로젝트 이식

- 레거시 경로: `<레거시 경로>` — 챕터별 폴더(`Chapter01`, `Chapter02` …)로 나뉘어 있다.
  참고용으로 읽기만 하고, 수정·빌드·실행은 절대 하지 않는다.
- 작업은 사용자가 지정한 챕터 하나 단위로만 진행한다. 지정하지 않은 챕터는 건드리지 않는다.
- 비교 기준은 "이전 챕터 → 지정한 챕터"에서 새로 생긴 기능이다. 현재 프로젝트에 이미 있는 기능은 다시 만들지 않는다.
- 레거시 코드를 복사하지 않고 현재 스택(Boot 4 / Cloud 2025.1 / Security 7 / Jackson 3)으로 다시 구현한다.
  알려진 변환 규칙은 `.claude/skills/migrate/upgrade-map.md`에 있고, 새로 알게 된 변환은 여기에 추가한다.
- 이식한 기능마다 테스트를 함께 만든다. 위의 테스트 이름 규칙을 따르고, 외부에서 보이는 동작은
  `test-em-all.bash`에 시나리오를 추가한다.
- `./gradlew test`와 `./test-em-all.bash start stop`이 모두 통과해야 완료로 본다.
- 커밋은 챕터 하나당 하나로 한다.
## 레거시 프로젝트 이식

- 레거시 경로: `/Users/jonggyu/Desktop/Hands-On-Microservices-with-Spring-Boot-and-Spring-Cloud-master`
  - 챕터별 폴더(`Chapter03` … `Chapter20`)로 나뉘어 있고, `Chapter08`은 없다.
  - Spring Boot 2.3.2 / Spring Cloud Hoxton.SR6 기반이다.
  - 참고용으로 읽기만 하고, 수정·빌드·실행은 절대 하지 않는다.
- 작업은 사용자가 지정한 챕터 하나 단위로만 진행한다. 지정하지 않은 챕터는 건드리지 않는다.
- 비교 기준은 "바로 앞 챕터 폴더 → 지정한 챕터 폴더"에서 새로 생긴 기능이다 (앞 챕터 폴더가 없으면 그 전 챕터와 비교).
  현재 프로젝트에 이미 있는 기능은 다시 만들지 않는다.
- 레거시 코드를 복사하지 않고 현재 스택(Boot 4 / Cloud 2025.1 / Security 7 / Jackson 3)으로 다시 구현한다.
  알려진 변환 규칙은 `.claude/skills/migrate/upgrade-map.md`에 있고, 새로 알게 된 변환은 여기에 추가한다.
- 이식한 기능마다 테스트를 함께 만든다. 위의 테스트 이름 규칙을 따르고, 외부에서 보이는 동작은
  `test-em-all.bash`에 시나리오를 추가한다.
- `./gradlew test`와 `./test-em-all.bash start stop`이 모두 통과해야 완료로 본다.
- 커밋은 챕터 하나당 하나로 한다.
