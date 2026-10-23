---
name: migrate
description: 레거시 Hands-On-Microservices 프로젝트의 한 챕터를 현재 저장소(Spring Boot 4 / Cloud 2025.1 / Security 7 / Jackson 3)로 이식한다. "/migrate ChapterNN" 형태로 호출한다.
---

# /migrate — 레거시 챕터 이식

## 사용법

```
/migrate Chapter13
```

인자는 레거시 저장소의 챕터 폴더 이름 하나(`Chapter03` ~ `Chapter20`, `Chapter08`은 없음)여야 한다.
인자가 없거나 존재하지 않는 챕터면 사용자에게 다시 확인한다.

## 지켜야 할 규칙 (CLAUDE.md "레거시 프로젝트 이식" 요약)

- 레거시 경로(`/Users/jonggyu/Desktop/Hands-On-Microservices-with-Spring-Boot-and-Spring-Cloud-master`)는
  **읽기 전용**이다. 절대 수정·빌드·실행하지 않는다.
- 작업은 인자로 받은 챕터 하나로 한정한다. 다른 챕터는 건드리지 않는다.
- 비교 기준은 "바로 앞 챕터 폴더 → 지정 챕터 폴더"에서 새로 생긴 것이다(앞 챕터 폴더가 없으면 그 전 챕터와 비교).
  현재 프로젝트에 이미 있는 기능은 다시 만들지 않는다.
- 레거시 코드를 복사하지 않고 현재 스택으로 다시 구현한다. 변환 규칙은
  `.claude/skills/migrate/upgrade-map.md`를 따르고, 거기 없는 새 변환을 발견하면 추가한다.
- 이식한 기능마다 테스트를 함께 만든다(`*PersistenceTests`, `*ServiceApplicationTests`, `MessagingTests` 네이밍 규칙).
  외부에서 보이는 동작은 `test-em-all.bash`에 시나리오를 추가한다.
- `./gradlew test`와 `./test-em-all.bash start stop`이 모두 통과해야 완료다.
- 커밋은 챕터 하나당 하나. **커밋 자체는 사용자 승인 후에만** 실행한다.

## 단계

이 스킬은 6단계로 진행하며, **2단계(계획) 종료 시점과 6단계(커밋) 전**에 반드시 멈춰서 사용자 승인을 받는다.
그 사이(3~5단계)는 이어서 진행해도 되지만, 5단계(검증)에서 실패가 반복되거나 계획을 벗어나는 변경이
필요해지면 다시 멈추고 보고한다.

### 1단계 — 분석

1. 레거시 챕터 폴더 번호에서 바로 앞 챕터 폴더를 찾는다(예: `Chapter13`의 앞은 `Chapter12`;
   `Chapter09`의 앞은 `Chapter07` — `Chapter08`이 없으므로). 챕터가 `Chapter03`이면 비교 대상이 없다는 점을
   보고하고 전체 챕터 내용을 분석 대상으로 삼는다.
2. 레거시 저장소에서 두 챕터 폴더를 비교한다. `.gradle`, `.idea`, `.DS_Store` 같은 IDE/빌드 캐시는 제외한다.
   예: `diff -rq <이전챕터> <지정챕터> --exclude=.gradle --exclude=.idea --exclude=.DS_Store`
3. 바뀐 파일마다 실제 내용 diff를 읽어 다음을 식별한다: 새 의존성(`build.gradle`), 새 설정 키
   (`config-repo/*.yaml` 또는 레거시의 `application.yml`/`bootstrap.yml`), 새 클래스/메서드/애너테이션,
   `docker-compose*.yml` 변경, `test-em-all.bash` 변경.
4. 식별한 각 항목에 대해 **현재 프로젝트에 이미 존재하는지** 확인한다(그랩/파일 존재 확인). 이미 있으면
   목록에서 제외하고 그 이유를 한 줄로 남긴다.
5. 결과를 다음 형식으로 정리해서 보여준다:
   - 챕터가 다루는 주제 한 줄 요약
   - 새로 생긴 기능/설정/의존성 표 (항목 | 레거시 근거 파일 | 현재 프로젝트에 없음 확인 방법)
   - 제외한 항목(이미 있음)과 이유
6. 이 단계는 보고만 하고 멈춘다. 구현으로 넘어가지 않는다.

### 2단계 — 계획

1. 1단계 분석을 바탕으로 바꿀 파일 목록(새 파일 포함)을 구체적인 경로로 나열한다.
2. `.claude/skills/migrate/upgrade-map.md`에서 이번 챕터에 적용할 변환 규칙을 골라 표로 보여준다.
   해당하는 규칙이 없으면 "새로 알아내야 할 변환"으로 표시한다.
3. 영향받는 테스트(신규/수정)와 `test-em-all.bash`에 추가할 시나리오를 미리 적는다.
4. 사용자 승인을 받을 때까지 멈춘다. 승인 전에는 파일을 만들거나 수정하지 않는다.

### 3단계 — 구현

1. 레거시 코드를 복사하지 않는다. 레거시 파일은 "무엇을 하는지"를 이해하기 위해서만 읽는다.
2. `upgrade-map.md`의 변환 규칙을 적용하며 현재 패키지 구조(`com.practice.magnus.*`,
   `tools.jackson.*`)와 기존 모듈 관례(MapStruct 매퍼, `util.exceptions`, `ServiceUtil` 등)를 따른다.
3. 외부 라이브러리의 정확한 API(특히 레거시와 좌표/패키지가 달라진 것, 예: Resilience4j의
   `*-spring-boot2` → `*-spring-boot3`류)가 확실하지 않으면, 추측하지 말고 공식 문서나 실제로 받은 jar의
   클래스 목록으로 확인한다.
4. 새 비동기 사이드 이펙트는 REST 엔드포인트가 아니라 해당 `MessageProcessorConfig`에 이벤트 타입 분기로
   추가한다(CLAUDE.md 통신 패턴 규칙).
5. 서비스 설정은 모듈 자체의 `application.yaml`이 아니라 `config-repo/<service>.yaml`을 수정한다.

### 4단계 — 테스트

1. 새 기능마다 적절한 네이밍 규칙으로 테스트를 추가한다:
   - `*PersistenceTests` — Testcontainers만 사용
   - `*ServiceApplicationTests` — Testcontainers + `WebTestClient` + `messageProcessor()` 직접 호출
   - `MessagingTests`(컴포지트 전용) — Spring Cloud Stream 테스트 바인더
2. 외부에서 보이는 동작(새 HTTP 응답, 새 상태 코드, 장애 주입 등)은 `test-em-all.bash`에 시나리오를 추가한다.
   기존 고정 `productId`(1, 13, 113, 213, -1, `invalidProductId`) 기대값을 바꾸기 전에 README.md의 표를 확인한다.

### 5단계 — 검증

1. `./gradlew test` 실행.
2. 필요하면 `./gradlew clean build -x test`로 jar를 다시 빌드(Dockerfile이 `build/libs/*.jar`를 복사).
3. `./test-em-all.bash start stop` 실행.
4. 실패하면 원인을 분석해 3단계로 돌아간다. 같은 이유로 2회 이상 반복 실패하면 멈추고 사용자에게 보고한다.

### 6단계 — 정리

1. 이번 작업에서 새로 알게 된 변환 규칙이 있으면 `.claude/skills/migrate/upgrade-map.md`에 표 행으로 추가한다.
2. 챕터 단위 커밋 메시지를 제안한다. **커밋은 사용자가 승인한 뒤에만** 실행한다.
3. 마지막에 다음을 요약한다:
   - 이식한 기능
   - 추가한 테스트(단위/통합 + `test-em-all.bash` 시나리오)
   - 검증 결과(`./gradlew test`, `./test-em-all.bash start stop`)
   - 일부러 뺀 것과 이유(이미 존재, 범위 밖, 다음 챕터로 미룸 등)
