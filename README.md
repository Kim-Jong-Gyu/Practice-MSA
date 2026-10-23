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
| 쿠버네티스(minikube) 대상 | `kubectl port-forward svc/gateway 8443:443` 후 `USE_K8S=true ./test-em-all.bash` (4절 참고) |

- `start`: `docker compose down --remove-orphans` 후 `docker compose up -d --build`
- `stop`: 테스트가 끝나면 `docker compose down --remove-orphans`
- `start`/`stop`은 docker compose 전용입니다. `USE_K8S=true`와 함께 쓰면 스크립트가 바로 중단합니다.
- 서킷 브레이커 테스트는 게이트웨이로 노출되지 않은 product-composite의 actuator를 내부 네트워크에서 호출합니다.
  docker compose에서는 `my-network`에 일회용 alpine 컨테이너를, 쿠버네티스에서는 `alpine-client` Pod를 띄워 `kubectl exec`로 부릅니다.
  `USE_K8S`를 지정하지 않으면 `HOST`가 `localhost`가 아닐 때 쿠버네티스로 판단합니다 (`NAMESPACE` 기본값 `hands-on`).
- `COMPOSE_FILE`은 docker compose가 자동으로 읽는 환경변수라, 띄울 때와 내릴 때 같은 파일이 사용됩니다.
- 로컬 실행은 서비스 디스커버리가 없어서 composite가 `http://product` 등을 찾지 못합니다. 코어 서비스를 거치는 시나리오는 docker compose / 쿠버네티스에서 확인합니다.

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

외부에 열린 포트는 게이트웨이(`https://localhost:8443`, 자체 서명 인증서라 `curl -k`)뿐입니다.
서비스끼리는 Eureka 없이 서비스 이름(`http://product`, 포트 생략 = 80)을 DNS로 찾습니다.

### 상태 확인

```bash
docker compose ps
# 게이트웨이 health에 뒤쪽 서비스(auth-server, product, recommendation, review, product-composite) 상태가 함께 나온다
curl -sk https://localhost:8443/actuator/health | jq
```

### 생성 / 조회 / 삭제

`/product-composite/**`는 JWT가 필요합니다 (조회 `product:read`, 생성/삭제 `product:write`).
토큰 발급 방법은 `test-em-all.bash`의 `getAuth0AccessToken`(기본) / `getAccessToken`(`USE_AUTH0=false`)을 참고합니다.

```bash
ACCESS_TOKEN=...   # product:read product:write 스코프의 토큰

curl -sk -X POST https://localhost:8443/product-composite -H "Authorization: Bearer $ACCESS_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"name":"product 1","weight":100,"recommendations":[{"recommendationId":1,"author":"a","rate":5,"content":"c"}],"reviews":[{"reviewId":1,"author":"a","subject":"s","content":"c"}]}'

curl -sk https://localhost:8443/product-composite/1 -H "Authorization: Bearer $ACCESS_TOKEN" | jq

curl -sk -X DELETE https://localhost:8443/product-composite/1 -H "Authorization: Bearer $ACCESS_TOKEN"
```

생성은 메시지로 비동기 처리되므로 1~2초 뒤에 조회합니다.

### Config Server

```bash
# 쿠버네티스 probe용 actuator는 인증 없이 열려 있다
curl -sk https://localhost:8443/config/actuator/health/liveness | jq

# 설정 조회는 HTTP Basic (.env의 CONFIG_SERVER_USR / CONFIG_SERVER_PWD)
curl -sk https://$CONFIG_SERVER_USR:$CONFIG_SERVER_PWD@localhost:8443/config/product/docker | jq
```

### Swagger UI

- 로컬 실행: http://localhost:7000/openapi/swagger-ui.html
- docker compose에서는 게이트웨이에 `/openapi/**` 라우트가 없어 열리지 않습니다.

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
# composite (보내는 쪽, docker compose 네트워크 안에서 호출)
docker run --rm --network=my-network alpine wget -qO - product-composite/actuator/bindings | jq

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

## 4. 쿠버네티스 (minikube)

매니페스트는 `kubernetes/services/`에 있습니다 (Kustomize).

| 경로 | 내용 |
|---|---|
| `base/` | config-server, auth-server, gateway, product-composite, product, recommendation, review, grafana-lgtm |
| `overlays/dev/` | base + mongodb, mysql, rabbitmq |
| `overlays/prod/` | base만 배포하고 DB/RabbitMQ는 클러스터 밖에 있다고 가정. 이미지 `:v1` 고정, `SPRING_PROFILES_ACTIVE=docker,prod`, 메모리 requests 200Mi, config-server는 WARN 로깅 + `health,info`만 노출 |

- Eureka는 쓰지 않습니다. 쿠버네티스 Service 이름이 DNS로 풀리므로 서비스 디스커버리가 내장되어 있습니다.
- 이미지는 `imagePullPolicy: Never`라 minikube 안의 Docker에서 빌드해야 합니다. 이미지 이름(`hands-on/*`)은 docker compose의 `image:`와 같습니다.
  `eval $(minikube docker-env)`는 실행한 셸에만 적용되므로 `docker compose build`를 같은 터미널에서 실행합니다.
- 서비스 메모리 limit은 모두 512Mi로, docker compose의 `mem_limit: 512m`과 같습니다. 350Mi에서는 review(JPA/Hibernate)와 mysql이 기동 중 OOMKilled됩니다.
- Config Server 주소는 `APP_CONFIG_SERVER` 환경변수로 넘깁니다. `config-repo` ConfigMap은 config-server에만 마운트되는 설정 원본이라, 클라이언트가 접속할 주소를 대신해 주지 않습니다.
- `overlays/prod`는 아직 `config-repo/`에 `prod` 프로필이 없어 그대로는 동작하지 않습니다 (외부 DB/RabbitMQ 주소가 필요). 배포 전에 이미지에 `:v1` 태그도 붙여야 합니다.
- probe는 Spring Boot의 `/actuator/health/liveness`, `/actuator/health/readiness`를 사용합니다 (grafana-lgtm은 Grafana `/api/health`와 OTel Collector `:13133/ready`).
- 구성 저장소 ConfigMap과 Secret은 YAML로 두지 않고 명령으로 만듭니다. `config-repo/`가 유일한 원본이고, 비밀번호는 git에 남기지 않기 위해서입니다.

```bash
./gradlew clean build -x test
eval $(minikube docker-env)
docker compose build

kubectl create namespace hands-on
kubectl config set-context $(kubectl config current-context) --namespace=hands-on

kubectl create configmap config-repo --from-file=config-repo/ --save-config
kubectl create secret generic config-server-secrets \
  --from-literal=ENCRYPT_KEY=... \
  --from-literal=SPRING_SECURITY_USER_NAME=... \
  --from-literal=SPRING_SECURITY_USER_PASSWORD=... \
  --save-config
kubectl create secret generic config-client-credentials \
  --from-literal=CONFIG_SERVER_USR=... \
  --from-literal=CONFIG_SERVER_PWD=... \
  --save-config

kubectl apply -k kubernetes/services/overlays/dev
kubectl wait --timeout=600s --for=condition=ready pod --all
```

진행 상황은 `kubectl wait` 대신 다른 터미널에서 실시간으로 볼 수 있습니다.

```bash
kubectl get pods -w                 # 상태가 바뀔 때마다 한 줄씩
kubectl get events -w               # ErrImageNeverPull, OOMKilled, probe 실패 등
kubectl logs -f deploy/product      # 특정 서비스 로그
kubectl get pods -o custom-columns='NAME:.metadata.name,IMAGE:.spec.containers[*].image,MEM:.spec.containers[*].resources.limits.memory'
```

zsh에서는 `[]`가 들어간 jq 필터와 custom-columns 인자를 따옴표로 감싸야 합니다 (`no matches found` 방지).

### 테스트

게이트웨이는 NodePort `31443`으로 열리지만 (`https://$(minikube ip):31443`), macOS의 docker 드라이버 minikube에서는 호스트가 `minikube ip`에 직접 접근할 수 없습니다.
포트 포워딩으로 `localhost:8443`에 연결한 뒤 테스트합니다.

```bash
kubectl port-forward svc/gateway 8443:443      # 다른 터미널에 띄워 둠
USE_K8S=true ./test-em-all.bash
```

docker compose 스택과 minikube를 동시에 띄우지 않습니다. 같은 Docker Desktop 메모리를 나눠 쓰다 Pod가 재시작되고, 8443 포트도 겹칩니다.

### 자주 만나는 문제

| 증상 | 원인 | 해결 |
|---|---|---|
| `ErrImageNeverPull` | 이미지가 호스트 Docker에만 있고 minikube Docker에 없음 | 같은 셸에서 `eval $(minikube docker-env)` → `docker compose build` → `kubectl rollout restart deploy` |
| `CreateContainerConfigError` | Secret/ConfigMap 이름이 매니페스트와 다름 (예: `config-server-secret`) | `kubectl describe pod`의 Events에서 찾는 이름을 확인하고 그 이름으로 다시 생성 |
| `OOMKilled` → `CrashLoopBackOff` | 메모리 limit 부족 | 매니페스트 수정 후 `kubectl apply -k`까지 해야 반영됨 (`kubectl get deploy <이름> -o jsonpath='{.spec.template.spec.containers[0].resources}'`로 확인) |
| 기동 직후 몇 번 재시작 | config-server가 아직 Ready가 아님 | 정상. config-server가 뜨면 따라서 Ready가 됨 |
| port-forward `Error creating error stream: Timeout` | minikube 과부하 (주로 compose 스택을 같이 띄운 경우) | `docker compose down --remove-orphans` 후 Pod가 회복되면 port-forward를 다시 띄움 |

### 정리

```bash
kubectl delete -k kubernetes/services/overlays/dev
```

