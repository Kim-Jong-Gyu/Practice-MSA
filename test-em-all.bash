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
# 모든 요청은 게이트웨이(HTTPS, 자체 서명 인증서라 curl -k)를 거친다.
#
# 토큰 발급처
#   USE_AUTH0=true  (기본) Auth0에서 password 그랜트로 사용자 토큰 발급
#                   필요한 값은 auth0.env 파일에서 읽는다 (git에 올리지 않음)
#   USE_AUTH0=false 우리 auth-server에서 client_credentials로 발급
#
: ${HOST=localhost}
: ${PORT=8443}
: ${EUREKA_USER=u}
: ${EUREKA_PASSWORD=p}
: ${EXPECTED_EUREKA_APPS=6}   # product, recommendation, review, product-composite, gateway, auth-server
: ${PROD_ID_REVS_RECS=1}
: ${PROD_ID_NOT_FOUND=13}
: ${PROD_ID_NO_RECS=113}
: ${PROD_ID_NO_REVS=213}
: ${USE_AUTH0=true}
: ${AUTH0_DOMAIN=dev-5r5apv33hgiui71h.us.auth0.com}
: ${AUTH0_AUDIENCE=https://localhost:8443/product-composite}

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
    if $url -ks -f -o /dev/null
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

function waitForEurekaRegistrations() {
    # Eureka는 외부 포트를 닫았으므로 게이트웨이의 /eureka/api/** 라우트를 통해 조회한다
    echo -n "Wait for $EXPECTED_EUREKA_APPS services to register in Eureka... "

    n=0
    until [[ "$(curl -sk -u $EUREKA_USER:$EUREKA_PASSWORD -H "accept:application/json" https://$HOST:$PORT/eureka/api/apps | jq '.applications.application | length' 2>/dev/null)" == "$EXPECTED_EUREKA_APPS" ]]
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
    echo "DONE"

    # 등록된 서비스 수와 이름 확인
    assertCurl 200 "curl -k -u $EUREKA_USER:$EUREKA_PASSWORD -H \"accept:application/json\" https://$HOST:$PORT/eureka/api/apps -s"
    assertEqual $EXPECTED_EUREKA_APPS $(echo $RESPONSE | jq ".applications.application | length")
    echo "Registered: $(echo $RESPONSE | jq -r '[.applications.application[].name] | sort | join(", ")')"
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

if [[ $@ == *"start"* ]]
then
    echo "Restarting the test environment..."
    echo "$ docker compose down --remove-orphans"
    docker compose down --remove-orphans
    echo "$ docker compose up -d --build"
    docker compose up -d --build
fi

waitForService curl -k https://$HOST:$PORT/actuator/health

# 6개 서비스가 모두 Eureka에 등록될 때까지 대기 (auth-server가 등록되어야 게이트웨이가 토큰 요청을 전달할 수 있음)
waitForEurekaRegistrations

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

echo "End, all tests OK:" `date`

if [[ $@ == *"stop"* ]]
then
    echo "Stopping the test environment..."
    echo "$ docker compose down --remove-orphans"
    docker compose down --remove-orphans
fi
