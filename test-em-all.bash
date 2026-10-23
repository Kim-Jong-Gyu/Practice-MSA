#!/usr/bin/env bash
#
# ./gradlew clean build -x test
# docker compose build
# docker compose up -d
#
# Sample usage:
#
#   ./test-em-all.bash
#   ./test-em-all.bash start stop
#   COMPOSE_FILE=docker-compose-kafka.yml ./test-em-all.bash start stop
#   USE_AUTH0=false ./test-em-all.bash        # 우리 auth-server로 토큰 발급
#
#   쿠버네티스(minikube) 대상: 게이트웨이를 포트 포워딩한 뒤 실행 (start/stop은 docker compose 전용)
#     kubectl port-forward svc/gateway 8443:443
#     USE_K8S=true ./test-em-all.bash
#
# 모든 요청은 게이트웨이(HTTPS, 자체 서명 인증서라 curl -k)를 거친다.
#
# 토큰 발급처
#   USE_AUTH0=true  (기본) Auth0에서 password 그랜트로 사용자 토큰 발급
#                   필요한 값은 auth0.env 파일에서 읽는다 (git에 올리지 않음)
#   USE_AUTH0=false 우리 auth-server에서 client_credentials로 발급
#
: ${HOST=localhost}
: ${PORT=8443}
: ${PROD_ID_REVS_RECS=1}
: ${PROD_ID_NOT_FOUND=13}
: ${PROD_ID_NO_RECS=113}
: ${PROD_ID_NO_REVS=213}
: ${USE_AUTH0=true}
: ${AUTH0_DOMAIN=dev-5r5apv33hgiui71h.us.auth0.com}
: ${AUTH0_AUDIENCE=https://localhost:8443/product-composite}
: ${NAMESPACE=hands-on}

# 내부 actuator(서킷 브레이커 상태)를 어디서 호출할지: localhost면 docker compose, 아니면 쿠버네티스로 본다
# port-forward로 쿠버네티스를 localhost에서 테스트할 때는 USE_K8S=true를 직접 지정한다
if [[ -z "$USE_K8S" ]]
then
    [[ "$HOST" == "localhost" ]] && USE_K8S=false || USE_K8S=true
fi

# Auth0 클라이언트 정보와 사용자 계정 (AUTH0_CLIENT_ID, AUTH0_CLIENT_SECRET, AUTH0_USER, AUTH0_PASSWORD)
if [[ -f "$(dirname "$0")/auth0.env" ]]
then
    source "$(dirname "$0")/auth0.env"
fi


function assertCurl() {

    local expectedHttpCode=$1
    local curlCmd="$2 -w \"%{http_code}\""
    local result=$(eval $curlCmd)
    local httpCode="${result:(-3)}"
    RESPONSE='' && (( ${#result} > 3 )) && RESPONSE="${result%???}"

    if [ "$httpCode" = "$expectedHttpCode" ]
    then
        if [ "$httpCode" = "200" ]
        then
            echo "Test OK (HTTP Code: $httpCode)"
        else
            echo "Test OK (HTTP Code: $httpCode, $RESPONSE)"
        fi
        return 0
    else
        echo  "Test FAILED, EXPECTED HTTP Code: $expectedHttpCode, GOT: $httpCode, WILL ABORT!"
        echo  "- Failing command: $curlCmd"
        echo  "- Response Body: $RESPONSE"
        return 1
    fi
}

function assertEqual() {

    local expected=$1
    local actual=$2

    if [ "$actual" = "$expected" ]
    then
        echo "Test OK (actual value: $actual)"
        return 0
    else
        echo "Test FAILED, EXPECTED VALUE: $expected, ACTUAL VALUE: $actual, WILL ABORT"
        return 1
    fi
}

function testUrl() {
    url=$@
    if $url --connect-timeout 2 --max-time 10 -ks -f -o /dev/null
    then
          return 0
    else
          return 1
    fi;
}

function waitForService() {
    url=$@
    echo -n "Wait for: $url... "
    n=0
    until testUrl $url
    do
        n=$((n + 1))
        if [[ $n == 100 ]]
        then
            echo " Give up"
            exit 1
        else
            sleep 3
            echo -n ", retry #$n "
        fi
    done
    echo "DONE, continues..."
}

# 인가 서버에서 client_credentials 방식으로 토큰을 받는다 (사용자 로그인 없음)
#   $1: 클라이언트 아이디 (secret은 secret-<클라이언트>)
#   $2: 요청할 scope
function getAccessToken() {
    local clientId=$1
    local scope=$2
    local token

    token=$(curl -sk https://$clientId:secret-$clientId@$HOST:$PORT/oauth2/token \
        -d grant_type=client_credentials \
        -d scope="$scope" | jq -r .access_token)

    if [[ -z "$token" || "$token" == "null" ]]
    then
        echo "Failed to get an access token for client '$clientId', WILL ABORT!" >&2
        exit 1
    fi
    echo "$token"
}

# Auth0에서 password 그랜트로 사용자 토큰을 받는다
#   Auth0 앱 설정에서 Password 그랜트를 켜고, 테넌트의 Default Directory를 지정해야 한다
#   $1: 요청할 scope
function getAuth0AccessToken() {
    local scope=$1
    local token

    for v in AUTH0_CLIENT_ID AUTH0_CLIENT_SECRET AUTH0_USER AUTH0_PASSWORD
    do
        if [[ -z "${!v}" ]]
        then
            echo "$v is not set (auth0.env 확인), WILL ABORT!" >&2
            exit 1
        fi
    done

    local response
    response=$(curl -s --request POST "https://$AUTH0_DOMAIN/oauth/token" \
        --header 'content-type: application/json' \
        --data "$(jq -n \
            --arg username "$AUTH0_USER" \
            --arg password "$AUTH0_PASSWORD" \
            --arg audience "$AUTH0_AUDIENCE" \
            --arg scope "$scope" \
            --arg client_id "$AUTH0_CLIENT_ID" \
            --arg client_secret "$AUTH0_CLIENT_SECRET" \
            '{grant_type: "password", username: $username, password: $password, audience: $audience,
              scope: $scope, client_id: $client_id, client_secret: $client_secret}')")
    token=$(echo "$response" | jq -r .access_token)

    if [[ -z "$token" || "$token" == "null" ]]
    then
        echo "Failed to get an access token from Auth0 (scope: $scope), WILL ABORT!" >&2
        echo "- Auth0 error: $(echo "$response" | jq -r '"\(.error): \(.error_description)"' 2>/dev/null || echo "$response")" >&2
        exit 1
    fi
    echo "$token"
}

function testCompositeCreated() {

    # productId $PROD_ID_REVS_RECS의 composite가 추천 3개, 리뷰 3개로 생성되었는지 확인
    assertCurl 200 "curl $AUTH -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS -s" || return 1
    assertEqual "$PROD_ID_REVS_RECS" $(echo $RESPONSE | jq .productId)    || return 1
    assertEqual 3 $(echo $RESPONSE | jq ".recommendations | length")      || return 1
    assertEqual 3 $(echo $RESPONSE | jq ".reviews | length")              || return 1
}

function waitForMessageProcessing() {
    echo "Wait for messages to be processed... "

    # 비동기 처리가 끝날 시간을 잠깐 줌
    sleep 1

    n=0
    until testCompositeCreated
    do
        n=$((n + 1))
        if [[ $n == 40 ]]
        then
            echo " Give up"
            exit 1
        else
            sleep 3
            echo -n ", retry #$n "
        fi
    done
    echo "All messages are now processed!"
}

function recreateComposite() {
    local productId=$1
    local composite=$2

    assertCurl 200 "curl $AUTH -X DELETE -k https://$HOST:$PORT/product-composite/${productId} -s"
    assertCurl 200 "curl $AUTH -X POST -k https://$HOST:$PORT/product-composite -H \"Content-Type: application/json\" --data '$composite' -s"
}

# product-composite의 actuator는 게이트웨이로 노출되지 않으므로 같은 네트워크 안의 alpine에서 wget으로 부른다
#   docker compose: my-network에 일회용 컨테이너
#   쿠버네티스:     네임스페이스에 alpine-client Pod를 띄워 kubectl exec
function startInternalClient() {
    if [[ "$USE_K8S" == "true" ]]
    then
        echo "Restarting alpine-client..."
        kubectl -n $NAMESPACE delete pod alpine-client --grace-period=1 --ignore-not-found
        kubectl -n $NAMESPACE run --restart=Never alpine-client --image=alpine --command -- sleep 600
        echo "Waiting for alpine-client to be ready..."
        kubectl -n $NAMESPACE wait --for=condition=Ready pod/alpine-client
        EXEC="kubectl -n $NAMESPACE exec alpine-client --"
    else
        EXEC="docker run --rm --network=my-network alpine"
    fi
}

function stopInternalClient() {
    if [[ "$USE_K8S" == "true" ]]
    then
        kubectl -n $NAMESPACE delete pod alpine-client --grace-period=1
    fi
}

# product-composite의 'product' 서킷 상태 (직접 만든 ProductCircuitBreakerHealthIndicator가 노출)
function getCircuitBreakerState() {
    $EXEC wget product-composite/actuator/health -qO - \
        | jq -r .components.productCircuitBreaker.details.state
}

# 서킷 상태 전이 이력 (resilience4j의 circuitbreakerevents 엔드포인트, exposure에 추가해야 열림)
function getStateTransition() {
    local index=$1
    $EXEC wget product-composite/actuator/circuitbreakerevents/product/STATE_TRANSITION -qO - \
        | jq -r ".circuitBreakerEvents[$index].stateTransition"
}

function testCircuitBreaker() {

    echo "Start Circuit Breaker tests!"

    startInternalClient

    # 처음에는 서킷이 닫혀 있어야 한다
    assertEqual "CLOSED" "$(getCircuitBreakerState)"

    # minimumNumberOfCalls=5라서 호출이 5개 쌓여야 실패율을 계산한다.
    # 슬라이딩 윈도(크기 5)를 정상 호출로 가득 채워서, 이전 실행의 기록을 밀어낸다
    for ((n=0; n<5; n++))
    do
        assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS $AUTH -s"
    done

    # delay=3 > timeoutDuration=2s 라서 타임아웃으로 3번 연속 실패시킨다
    for ((n=0; n<3; n++))
    do
        assertCurl 500 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS?delay=3 $AUTH -s"
        message=$(echo $RESPONSE | jq -r .message)
        assertEqual "Did not observe any item or terminal signal within 2000ms" "${message:0:57}"
    done
    assertEqual "OPEN" "$(getCircuitBreakerState)"

    # 서킷이 열리면 product를 부르지 않고 바로 fallback(200)을 돌려준다 (느린 호출도 기다리지 않음)
    assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS?delay=3 $AUTH -s"
    assertEqual "Fallback product$PROD_ID_REVS_RECS" "$(echo "$RESPONSE" | jq -r .name)"

    assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS $AUTH -s"
    assertEqual "Fallback product$PROD_ID_REVS_RECS" "$(echo "$RESPONSE" | jq -r .name)"

    # fallback에도 없는 상품($PROD_ID_NOT_FOUND)은 404
    assertCurl 404 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_NOT_FOUND $AUTH -s"
    assertEqual "Product Id: $PROD_ID_NOT_FOUND not found in fallback cache!" "$(echo $RESPONSE | jq -r .message)"

    # waitDurationInOpenState=10s 뒤에 자동으로 반열림 (automaticTransitionFromOpenToHalfOpenEnabled=true)
    echo "Will sleep for 10 sec waiting for the CB to go Half Open..."
    sleep 10
    assertEqual "HALF_OPEN" "$(getCircuitBreakerState)"

    # 정상 호출 3번(permittedNumberOfCallsInHalfOpenState)이 성공하면 다시 닫힌다
    for ((n=0; n<3; n++))
    do
        assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS $AUTH -s"
        assertEqual "product name C" "$(echo "$RESPONSE" | jq -r .name)"
    done
    assertEqual "CLOSED" "$(getCircuitBreakerState)"

    # 상태 전이가 순서대로 일어났는지 확인 (값의 형식은 아래 '확인할 것' 참고)
    assertEqual "State transition from CLOSED to OPEN"      "$(getStateTransition -3)"
    assertEqual "State transition from OPEN to HALF_OPEN"   "$(getStateTransition -2)"
    assertEqual "State transition from HALF_OPEN to CLOSED" "$(getStateTransition -1)"

    stopInternalClient

    echo "Circuit Breaker tests OK"
}

function setupTestdata() {

    body="{\"productId\":$PROD_ID_NO_RECS"
    body+=\
',"name":"product name A","weight":100, "reviews":[
    {"reviewId":1,"author":"author 1","subject":"subject 1","content":"content 1"},
    {"reviewId":2,"author":"author 2","subject":"subject 2","content":"content 2"},
    {"reviewId":3,"author":"author 3","subject":"subject 3","content":"content 3"}
]}'
    recreateComposite "$PROD_ID_NO_RECS" "$body"

    body="{\"productId\":$PROD_ID_NO_REVS"
    body+=\
',"name":"product name B","weight":200, "recommendations":[
    {"recommendationId":1,"author":"author 1","rate":1,"content":"content 1"},
    {"recommendationId":2,"author":"author 2","rate":2,"content":"content 2"},
    {"recommendationId":3,"author":"author 3","rate":3,"content":"content 3"}
]}'
    recreateComposite "$PROD_ID_NO_REVS" "$body"

    body="{\"productId\":$PROD_ID_REVS_RECS"
    body+=\
',"name":"product name C","weight":300, "recommendations":[
        {"recommendationId":1,"author":"author 1","rate":1,"content":"content 1"},
        {"recommendationId":2,"author":"author 2","rate":2,"content":"content 2"},
        {"recommendationId":3,"author":"author 3","rate":3,"content":"content 3"}
    ], "reviews":[
        {"reviewId":1,"author":"author 1","subject":"subject 1","content":"content 1"},
        {"reviewId":2,"author":"author 2","subject":"subject 2","content":"content 2"},
        {"reviewId":3,"author":"author 3","subject":"subject 3","content":"content 3"}
    ]}'
    recreateComposite "$PROD_ID_REVS_RECS" "$body"
}

set -e

if ! command -v jq > /dev/null
then
    echo "jq is required. Install it with: brew install jq"
    exit 1
fi

echo "Start Tests:" `date`

echo "HOST=${HOST}"
echo "PORT=${PORT}"
echo "COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.yml}"
echo "USE_AUTH0=${USE_AUTH0}"
echo "USE_K8S=${USE_K8S} (NAMESPACE=${NAMESPACE})"

# start/stop은 docker compose로 스택을 올리고 내린다
# 쿠버네티스 모드에서 쓰면 minikube 옆에 compose 스택이 하나 더 떠서 메모리와 8443 포트를 두고 다툰다
if [[ "$USE_K8S" == "true" && ( $@ == *"start"* || $@ == *"stop"* ) ]]
then
    echo "start/stop is only supported with docker compose (USE_K8S=false), WILL ABORT!"
    echo "- Kubernetes: kubectl apply -k kubernetes/services/overlays/dev / kubectl delete -k kubernetes/services/overlays/dev"
    exit 1
fi

if [[ $@ == *"start"* ]]
then
    echo "Restarting the test environment..."
    echo "$ docker compose down --remove-orphans"
    docker compose down --remove-orphans
    echo "$ docker compose up -d --build"
    docker compose up -d --build
fi

waitForService curl -k https://$HOST:$PORT/actuator/health

# config-server: 쿠버네티스 probe용 actuator는 인증 없이 열려 있고, 설정 조회는 HTTP Basic이 필요하다
assertCurl 200 "curl -k https://$HOST:$PORT/config/actuator/health/liveness -s"
assertCurl 200 "curl -k https://$HOST:$PORT/config/actuator/health/readiness -s"
assertCurl 401 "curl -k https://$HOST:$PORT/config/product/docker -s"


# writer: 조회, 생성, 삭제 모두 가능한 토큰
if [[ "$USE_AUTH0" == "true" ]]
then
    ACCESS_TOKEN=$(getAuth0AccessToken "openid email product:read product:write")
else
    ACCESS_TOKEN=$(getAccessToken writer "product:read product:write")
fi
AUTH="-H \"Authorization: Bearer $ACCESS_TOKEN\""

setupTestdata

waitForMessageProcessing

# 정상 요청: 추천 3개, 리뷰 3개
assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS $AUTH -s"
assertEqual "$PROD_ID_REVS_RECS" $(echo $RESPONSE | jq .productId)
assertEqual 3 $(echo $RESPONSE | jq ".recommendations | length")
assertEqual 3 $(echo $RESPONSE | jq ".reviews | length")

# 존재하지 않는 productId: 404
assertCurl 404 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_NOT_FOUND $AUTH -s"
assertEqual "\"No product found for productId: $PROD_ID_NOT_FOUND\"" "$(echo $RESPONSE | jq .message)"

# 추천이 없는 상품: 추천 0개, 리뷰 3개
assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_NO_RECS $AUTH -s"
assertEqual "$PROD_ID_NO_RECS" $(echo $RESPONSE | jq .productId)
assertEqual 0 $(echo $RESPONSE | jq ".recommendations | length")
assertEqual 3 $(echo $RESPONSE | jq ".reviews | length")

# 리뷰가 없는 상품: 추천 3개, 리뷰 0개
assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_NO_REVS $AUTH -s"
assertEqual "$PROD_ID_NO_REVS" $(echo $RESPONSE | jq .productId)
assertEqual 3 $(echo $RESPONSE | jq ".recommendations | length")
assertEqual 0 $(echo $RESPONSE | jq ".reviews | length")

# 범위를 벗어난 productId(-1): 422
assertCurl 422 "curl -k https://$HOST:$PORT/product-composite/-1 $AUTH -s"
assertEqual "\"Invalid productId: -1\"" "$(echo $RESPONSE | jq .message)"

# 숫자가 아닌 productId: 400
assertCurl 400 "curl -k https://$HOST:$PORT/product-composite/invalidProductId $AUTH -s"
assertEqual "\"Type mismatch.\"" "$(echo $RESPONSE | jq .message)"

# 토큰 없이 요청하면 401 Unauthorized
assertCurl 401 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS -s"

# reader: product:read만 있으므로 조회는 되고 삭제는 403 Forbidden
if [[ "$USE_AUTH0" == "true" ]]
then
    READER_ACCESS_TOKEN=$(getAuth0AccessToken "openid email product:read")
else
    READER_ACCESS_TOKEN=$(getAccessToken reader "product:read")
fi
READER_AUTH="-H \"Authorization: Bearer $READER_ACCESS_TOKEN\""

assertCurl 200 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS $READER_AUTH -s"
assertCurl 403 "curl -k https://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS $READER_AUTH -X DELETE -s"

testCircuitBreaker

echo "End, all tests OK:" `date`

if [[ $@ == *"stop"* ]]
then
    echo "Stopping the test environment..."
    echo "$ docker compose down --remove-orphans"
    docker compose down --remove-orphans
fi
