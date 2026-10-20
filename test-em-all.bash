#!/usr/bin/env bash
#
# ./gradlew clean build -x test
# docker compose build
# docker compose up -d
#
# Sample usage:
#
#   HOST=localhost PORT=7000 ./test-em-all.bash
#   ./test-em-all.bash start stop
#   COMPOSE_FILE=docker-compose-kafka.yml ./test-em-all.bash start stop
#
: ${HOST=localhost}
: ${PORT=8080}
: ${EUREKA_PORT=8761}
: ${EXPECTED_EUREKA_APPS=4}
: ${PROD_ID_REVS_RECS=1}
: ${PROD_ID_NOT_FOUND=13}
: ${PROD_ID_NO_RECS=113}
: ${PROD_ID_NO_REVS=213}

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
    echo -n "Wait for $EXPECTED_EUREKA_APPS services to register in Eureka... "

    n=0
    until [[ "$(curl -s -H "accept:application/json" http://$HOST:$EUREKA_PORT/eureka/apps | jq '.applications.application | length' 2>/dev/null)" == "$EXPECTED_EUREKA_APPS" ]]
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
    assertCurl 200 "curl -H \"accept:application/json\" http://$HOST:$EUREKA_PORT/eureka/apps -s"
    assertEqual $EXPECTED_EUREKA_APPS $(echo $RESPONSE | jq ".applications.application | length")
    echo "Registered: $(echo $RESPONSE | jq -r '[.applications.application[].name] | sort | join(", ")')"
}

function testCompositeCreated() {

    # productId $PROD_ID_REVS_RECS의 composite가 추천 3개, 리뷰 3개로 생성되었는지 확인
    assertCurl 200 "curl http://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS -s" || return 1
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

    assertCurl 200 "curl -X DELETE http://$HOST:$PORT/product-composite/${productId} -s"
    assertCurl 200 "curl -X POST http://$HOST:$PORT/product-composite -H \"Content-Type: application/json\" --data '$composite' -s"
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
echo "EUREKA_PORT=${EUREKA_PORT}"
echo "COMPOSE_FILE=${COMPOSE_FILE:-docker-compose.yml}"

if [[ $@ == *"start"* ]]
then
    echo "Restarting the test environment..."
    echo "$ docker compose down --remove-orphans"
    docker compose down --remove-orphans
    echo "$ docker compose up -d --build"
    docker compose up -d --build
fi

waitForService curl http://$HOST:$PORT/actuator/health

# Eureka에 4개 서비스(product, recommendation, review, product-composite)가 모두 등록될 때까지 대기
waitForEurekaRegistrations

setupTestdata

waitForMessageProcessing

# 정상 요청: 추천 3개, 리뷰 3개
assertCurl 200 "curl http://$HOST:$PORT/product-composite/$PROD_ID_REVS_RECS -s"
assertEqual "$PROD_ID_REVS_RECS" $(echo $RESPONSE | jq .productId)
assertEqual 3 $(echo $RESPONSE | jq ".recommendations | length")
assertEqual 3 $(echo $RESPONSE | jq ".reviews | length")

# 존재하지 않는 productId: 404
assertCurl 404 "curl http://$HOST:$PORT/product-composite/$PROD_ID_NOT_FOUND -s"
assertEqual "\"No product found for productId: $PROD_ID_NOT_FOUND\"" "$(echo $RESPONSE | jq .message)"

# 추천이 없는 상품: 추천 0개, 리뷰 3개
assertCurl 200 "curl http://$HOST:$PORT/product-composite/$PROD_ID_NO_RECS -s"
assertEqual "$PROD_ID_NO_RECS" $(echo $RESPONSE | jq .productId)
assertEqual 0 $(echo $RESPONSE | jq ".recommendations | length")
assertEqual 3 $(echo $RESPONSE | jq ".reviews | length")

# 리뷰가 없는 상품: 추천 3개, 리뷰 0개
assertCurl 200 "curl http://$HOST:$PORT/product-composite/$PROD_ID_NO_REVS -s"
assertEqual "$PROD_ID_NO_REVS" $(echo $RESPONSE | jq .productId)
assertEqual 3 $(echo $RESPONSE | jq ".recommendations | length")
assertEqual 0 $(echo $RESPONSE | jq ".reviews | length")

# 범위를 벗어난 productId(-1): 422
assertCurl 422 "curl http://$HOST:$PORT/product-composite/-1 -s"
assertEqual "\"Invalid productId: -1\"" "$(echo $RESPONSE | jq .message)"

# 숫자가 아닌 productId: 400
assertCurl 400 "curl http://$HOST:$PORT/product-composite/invalidProductId -s"
assertEqual "\"Type mismatch.\"" "$(echo $RESPONSE | jq .message)"

echo "End, all tests OK:" `date`

if [[ $@ == *"stop"* ]]
then
    echo "Stopping the test environment..."
    echo "$ docker compose down --remove-orphans"
    docker compose down --remove-orphans
fi