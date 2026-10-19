# 테스트 실행 가이드

## 사전 준비

```bash
chmod +x gradlew test-em-all.bash
brew install jq
```

- Docker Desktop이 실행 중이어야 합니다 (단위 테스트의 Testcontainers, docker compose 모두 필요).

## 1. 단위/통합 테스트 (Gradle)

전체 테스트:

```bash
./gradlew test
```

서비스별 테스트:

```bash
./gradlew :microservices:product-service:test
./gradlew :microservices:recommendation-service:test
./gradlew :microservices:review-service:test
./gradlew :microservices:product-composite-service:test
```

특정 테스트 클래스만:

```bash
./gradlew :microservices:product-service:test --tests "*PersistenceTests"
./gradlew :microservices:product-composite-service:test --tests "*MessagingTests"
```

| 테스트 | 사용하는 것 |
|---|---|
| `PersistenceTests` | Testcontainers (MongoDB / MySQL 싱글톤 컨테이너) |
| `*ServiceApplicationTests` | Testcontainers + `WebTestClient` + `messageProcessor` 직접 호출 |
| `MessagingTests` (composite) | 테스트 바인더 (`OutputDestination`) |

## 2. 엔드 투 엔드 테스트 (`test-em-all.bash`)

먼저 jar를 빌드합니다. Dockerfile이 `build/libs/*.jar`를 복사하므로 코드를 바꿀 때마다 다시 빌드해야 합니다.

```bash
./gradlew clean build -x test
```

### 실행 방법

| 목적 | 명령 |
|---|---|
| 이미 떠 있는 환경에서 테스트만 | `./test-em-all.bash` |
| 환경을 새로 띄우고 테스트 후 내리기 | `./test-em-all.bash start stop` |
| 파티션 버전 (RabbitMQ) | `COMPOSE_FILE=docker-compose-partitions.yml ./test-em-all.bash start stop` |
| Kafka 버전 | `COMPOSE_FILE=docker-compose-kafka.yml ./test-em-all.bash start stop` |
| 로컬(IntelliJ)에서 띄운 서비스 대상 | `HOST=localhost PORT=7000 ./test-em-all.bash` |

- `start`: `docker compose down --remove-orphans` 후 `docker compose up -d --build`
- `stop`: 테스트가 끝나면 `docker compose down --remove-orphans`
- `COMPOSE_FILE`은 docker compose가 자동으로 읽는 환경변수라, 띄울 때와 내릴 때 같은 파일이 사용됩니다.

### 검증 항목

| productId | 기대 결과 |
|---|---|
| `1` | 200, 추천 3개 + 리뷰 3개 |
| `13` | 404, `No product found for productId: 13` |
| `113` | 200, 추천 0개 + 리뷰 3개 |
| `213` | 200, 추천 3개 + 리뷰 0개 |
| `-1` | 422, `Invalid productId: -1` |
| `invalidProductId` | 400, `Type mismatch.` |

productId는 환경변수로 바꿀 수 있습니다 (`PROD_ID_REVS_RECS`, `PROD_ID_NOT_FOUND`, `PROD_ID_NO_RECS`, `PROD_ID_NO_REVS`).

## 3. 수동 확인 명령

### 상태 확인

```bash
docker compose ps
curl -s http://localhost:8080/actuator/health | jq
```

### 생성 / 조회 / 삭제

```bash
curl -s -X POST http://localhost:8080/product-composite -H "Content-Type: application/json" \
  -d '{"productId":1,"name":"product 1","weight":100,"recommendations":[{"recommendationId":1,"author":"a","rate":5,"content":"c"}],"reviews":[{"reviewId":1,"author":"a","subject":"s","content":"c"}]}'

curl -s http://localhost:8080/product-composite/1 | jq

curl -s -X DELETE http://localhost:8080/product-composite/1
```

생성은 메시지로 비동기 처리되므로 1~2초 뒤에 조회합니다.

### Swagger UI

- docker compose: http://localhost:8080/openapi/swagger-ui.html
- 로컬 실행: http://localhost:7000/openapi/swagger-ui.html

### RabbitMQ

- 관리 화면: http://localhost:15672 (guest / guest)
- 정상 상태: `products.productsGroup` 등 큐가 있고 Consumers가 1 이상, `*.dlq`의 메시지가 0
- 파티션 버전: 큐 이름에 `-0`, `-1`이 붙음

### Kafka

```bash
# 토픽의 파티션 수
docker compose -f docker-compose-kafka.yml exec kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --describe --topic products

# 토픽의 메시지와 파티션 번호
docker compose -f docker-compose-kafka.yml exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 --topic products --from-beginning \
  --property print.partition=true --timeout-ms 5000

# 컨슈머 그룹의 파티션 할당
docker compose -f docker-compose-kafka.yml exec kafka /opt/kafka/bin/kafka-consumer-groups.sh \
  --bootstrap-server localhost:9092 --describe --group productsGroup
```

### 어느 인스턴스가 처리했는지 (파티션 버전)

```bash
docker compose -f docker-compose-kafka.yml logs product product-p1 | grep "Create product with ID"
```

### 바인딩 설정 확인

```bash
# composite (보내는 쪽, docker compose에서 8080으로 열려 있음)
curl -s http://localhost:8080/actuator/bindings | jq

# product (받는 쪽, 로컬에서 띄웠을 때)
curl -s http://localhost:7001/actuator/bindings | jq
```

- 보내는 쪽: `products-out-0` 등의 `name`이 `products`인지
- 받는 쪽: `messageProcessor-in-0`의 `name`(destination)과 `group`이 의도한 값인지

docker compose에서는 product, recommendation, review의 포트를 외부에 열지 않았으므로, 받는 쪽은 로컬 실행으로 확인합니다.

## 정리

```bash
docker compose down --remove-orphans
COMPOSE_FILE=docker-compose-partitions.yml docker compose down --remove-orphans
COMPOSE_FILE=docker-compose-kafka.yml docker compose down --remove-orphans
```

데이터까지 지우려면 `-v`를 붙입니다.
