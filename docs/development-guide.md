# C106 개발 가이드

## 1. 기본 원칙

- 모든 개발 작업은 Jira 이슈를 기준으로 진행한다.
- 개발자는 기능 브랜치에서 작업한다.
- 코드는 MR(Merge Request)을 통해 병합한다.
- 최소 1명의 다른 팀원이 코드를 검토한다.
- API와 DB 변경은 관련 명세서를 함께 수정한다.
- 정상 동작뿐 아니라 실패 조건도 테스트한다.
- 중요한 설계 변경은 담당자와 협의하고 기록한다.

**확정된 프로젝트 구성**

| 항목 | 기준 |
|---|---|
| 저장소 | SSAFY GitLab 모노레포 |
| 개발 통합·배포 브랜치 | `develop` |
| 배포 | GitLab → Jenkins → Docker Compose |
| 백엔드 | Java 21 · Spring Boot 4.1 (세부 스택은 6.2) |
| AI | Python · FastAPI |
| 프론트엔드 | React · TypeScript |
| DB | PostgreSQL 18 |
| DB Migration | Flyway |
| API 문서 | OpenAPI 3.1 · Swagger |
| 업무 관리 | Jira (프로젝트 키 `S15P31C106`) |
| 설계 문서 | Notion |

이 문서는 Notion과 저장소의 `docs/development-guide.md`에 같은 내용으로 보관한다. 한쪽을 수정하면 다른 쪽도 함께 수정한다.

---

## 2. Git 브랜치 전략

### 2.1 브랜치 구성

| 브랜치 | 용도 | 병합 방식 |
|---|---|---|
| `main` | 안정 버전 관리 | `develop`에서 MR |
| `develop` | 기능 통합 및 자동 배포 | 작업 브랜치에서 MR |
| `feature/*` | 신규 기능 | `develop`으로 MR |
| `fix/*` | 개발 중 버그 수정 | `develop`으로 MR |
| `hotfix/*` | 긴급 오류 수정 | 대상 브랜치에 MR |
| `refactor/*` | 기능 변경 없는 구조 개선 | `develop`으로 MR |
| `docs/*` | 문서 추가·수정 | `develop`으로 MR |
| `test/*` | 테스트 추가·수정 | `develop`으로 MR |
| `chore/*` | 설정·개발 환경 변경 | `develop`으로 MR |

`develop`은 병합 시 자동 배포한다. `main`은 안정 버전을 보관하는 브랜치로 운영한다.

6인 단기 프로젝트이므로 별도 `release` 브랜치는 만들지 않는다.

### 2.2 브랜치 이름

형식: `{type}/{이슈번호}-{description}`

이슈 번호는 Jira 이슈 키 `S15P31C106-123`에서 뒤의 숫자 `123`만 사용한다.

예시:

- `feature/123-action-execution`
- `feature/124-ontology-mapping`
- `fix/125-duplicate-sync`
- `refactor/126-review-service`

규칙:

- 소문자 영문과 숫자, 하이픈을 사용한다.
- Jira 이슈 번호를 포함한다.
- Jira 이슈가 없는 작업은 번호를 생략한다. 예: `docs/development-guide`
- 기능을 이해할 수 있는 이름을 사용한다.
- 한 브랜치에서는 하나의 작업 목표를 처리한다.

### 2.3 작업 순서

1. Jira 이슈를 확인한다.
2. `develop`의 최신 코드를 가져온다.
3. 작업 브랜치를 생성한다.
4. 기능을 구현하고 테스트한다.
5. GitLab에 브랜치를 push한다.
6. `develop` 대상으로 MR을 생성한다.
7. 코드 리뷰와 검증을 완료한다.
8. MR을 병합한다.
9. Jenkins 배포 결과를 확인한다.

공유 브랜치에서는 강제 push를 사용하지 않는다.

`develop`과 `main`에는 직접 push하지 않는다. GitLab의 보호 브랜치 설정으로 이를 제한한다.

---

## 3. 커밋 메시지 규칙

Conventional Commits 형식을 사용한다. scope는 사용하지 않는다.

형식: `type: 작업 내용`

### 3.1 커밋 유형

| Type | 사용 기준 |
|---|---|
| `feat` | 신규 기능 |
| `fix` | 버그 수정 |
| `refactor` | 동작 변경 없는 구조 개선 |
| `test` | 테스트 추가·수정 |
| `docs` | 문서 변경 |
| `style` | 포맷·코드 스타일 수정 |
| `perf` | 성능 개선 |
| `build` | 의존성·빌드 설정 변경 |
| `ci` | Jenkins·CI/CD 변경 |
| `chore` | 기타 관리 작업 |

### 3.2 커밋 예시

```text
feat: 재배정 계획 생성 API 구현
fix: 중복 수집 요청 처리 오류 수정
refactor: 객체 매핑 검증 로직 분리
test: 승인 시 버전 불일치 테스트 추가
ci: Jenkins 백엔드 테스트 단계 추가
docs: 정합성 검사 응답 필드 수정
```

커밋 메시지는 한국어로 작성한다. Type은 영어를 사용한다.

제목은 50자 이내로 작성할 것을 권장한다. 강제하지는 않는다.

**커밋 단위**

- 하나의 커밋은 하나의 논리적 변경을 포함한다.
- 기능 구현과 무관한 코드 정리를 섞지 않는다.
- 테스트는 관련 기능과 같은 커밋에 포함할 수 있다.
- 디버깅 코드와 불필요한 주석은 커밋하지 않는다.

---

## 4. Merge Request 규칙

### 4.1 MR 생성 기준

코드 리뷰를 받을 수 있는 단위로 MR을 작성한다.

- 신규 API 하나 또는 관련 API 묶음
- 독립적으로 검증 가능한 기능
- DB 스키마 변경
- 공통 코드 변경
- 버그 수정

작업이 완료되지 않아도 리뷰가 필요하면 Draft MR을 생성할 수 있다. Draft MR은 아직 병합 준비가 끝나지 않은 MR이다.

### 4.2 MR 제목

형식: `[JiraKey] type: 변경 내용`

MR 제목에는 Jira 이슈 키 전체를 사용한다.

예시:

`[S15P31C106-123] feat: 재배정 계획 생성 API 구현`

Jira 이슈가 없는 작업은 키를 붙이지 않는다.

`docs: 개발 가이드 추가`

### 4.3 MR 템플릿

GitLab MR 템플릿(`.gitlab/merge_request_templates/Default.md`)을 사용한다. 템플릿은 다음 내용을 포함한다.

**변경 내용**
- 구현한 기능
- 수정한 API
- 변경한 DB 테이블

**관련 작업**
- Jira 이슈
- 기능 명세서 ID
- 관련 API 명세

**검증 결과**
- [ ] 정상 요청 테스트
- [ ] 잘못된 입력 테스트
- [ ] 권한 검증
- [ ] 관련 자동 테스트 통과
- [ ] 기존 기능 영향 확인
- [ ] API·ERD 변경 시 문서 반영

**리뷰 요청**
- 중요 구현 사항
- 리뷰어가 확인할 부분
- 알려진 제한 사항

### 4.4 리뷰 규칙

- MR 작성자 외 최소 1명이 리뷰한다.
- 공통 코드 변경은 영향을 받는 도메인 담당자에게 리뷰를 요청한다.
- API 계약 변경은 해당 API를 사용하는 담당자와 확인한다.
- DB 공통 스키마 변경은 DB 관련 담당자와 확인한다.
- 보안·인증·배포 설정 변경은 해당 영역 담당자가 확인한다.

### 4.5 병합 조건

모든 조건을 충족하면 병합한다.

- 리뷰어가 승인했다.
- 미해결 필수 수정 의견이 없다.
- MR 파이프라인을 포함한 관련 테스트를 통과했다.
- MR 충돌이 없다.
- API 또는 DB 변경 사항을 문서에 반영했다.

일반 기능 MR은 Squash Merge로 병합한다. 여러 개발 커밋을 하나로 합쳐 이력을 간결하게 유지한다.

GitLab에서 필수 승인 설정을 사용할 수 없다면 팀 리뷰를 수동으로 확인한다.

---

## 5. Jira 작업 관리

### 5.1 작업 상태

| 상태 | 의미 |
|---|---|
| 해야 할 일 | 작업 전 |
| 진행 중 | 구현 중, MR 생성·리뷰 중 |
| 완료 | 병합·검증 완료 |

다른 작업이나 결정 때문에 막히면 상태를 바꾸지 않는다. 이슈의 선행·후행 작업 항목에 의존 관계를 기록한다.

### 5.2 이슈 작성 기준

하나의 이슈는 완료 여부를 확인할 수 있는 작업으로 만든다.

이슈 유형은 작업(Task)을 사용한다. 스토리는 사용하지 않는다. 이슈는 관련 에픽 아래에 둔다.

**이슈 제목**

형식: `[영역][도메인] 작업 내용`

예시: `[BE][Action] 재배정 계획 생성 API 구현`

- 영역: `BE`, `AI`, `FE`, `Infra`, `Docs`
- 기획·문서 작업은 `Docs`를 쓴다.
- 도메인: 6.1의 백엔드 도메인 이름을 쓴다. 해당하는 도메인이 없으면 생략한다. 예: `[Infra] nginx 설치 및 기본 페이지 확인`, `[Docs] 개발 컨벤션 확정`

**스토리포인트**

- 1포인트는 1시간이다.
- 1, 2, 3, 5만 사용한다. 5포인트를 넘는 작업은 이슈를 나눈다.
- 한 사람이 한 주에 40포인트를 넘지 않게 한다. 추가 이슈가 생길 여유를 남긴다.

**설명**

다음 순서로 작성한다. 해당하지 않는 항목은 생략할 수 있다.

1. 기능 ID: 기능 명세서의 ID. 예: `FN-ACT-05`
2. 목적
3. 배경·근거
4. 작업 내용
5. 산출물
6. 완료 기준
7. 담당·검토
8. 선행·후행 작업
9. 참고 문서

작업 내용과 완료 기준 예시:

**작업 내용**
- Handler 실행 결과를 검증한다.
- 재배정 계획과 항목을 저장한다.
- 감사 기록을 생성한다.
- 검증 실패 시 계획을 저장하지 않는다.

**완료 기준**
- [ ] 정상 계획이 저장된다.
- [ ] 중복 작업지시·공정은 거절된다.
- [ ] 같은 설비로 재배정할 수 없다.
- [ ] 실패한 실행의 사유를 기록한다.
- [ ] 관련 자동 테스트를 통과한다.

### 5.3 작업 분리 원칙

API 수만 기준으로 이슈를 만들지 않는다.

예를 들어 재배정 계획 생성은 Controller, Service, Handler, Repository가 함께 동작해야 한다. 하나의 기능으로 완료 조건을 검증할 수 있다면 같은 이슈로 관리한다.

반대로 다른 담당자의 작업을 기다려야 한다면 이슈를 분리하고 의존 관계를 기록한다.

---

## 6. 프로젝트 디렉터리 구조

모노레포 안에서 서비스별 코드를 분리한다.

**권장 구조**

```text
/
├── backend/
│   ├── src/
│   ├── build.gradle
│   └── Dockerfile
├── ai/
│   ├── app/
│   ├── tests/
│   └── Dockerfile
├── frontend/
│   ├── src/
│   ├── package.json
│   └── Dockerfile
├── infra/
│   ├── compose.yaml
│   ├── nginx/
│   └── scripts/
├── docs/
│   ├── api/
│   └── architecture/
├── .gitlab/
│   └── merge_request_templates/
└── Jenkinsfile
```

실제 저장소의 기존 폴더 이름을 우선 사용한다. 구조 변경이 필요할 때만 팀과 협의한다.

### 6.1 백엔드 패키지 구조

**도메인 중심 패키지 구성**을 사용한다.

도메인은 인증, 온톨로지, 검사처럼 하나의 업무 영역을 뜻한다.

```text
com.axcore.platform
├── global
│   ├── config
│   ├── error
│   ├── security
│   ├── response
│   └── entity
├── auth
├── account
├── ontology
├── source
├── mapping
├── analysis
├── review
├── check
├── issue
├── batch
├── action
├── agent
└── audit
```

각 도메인 내부는 필요한 계층으로 구분한다.

```text
action/
├── controller/
├── dto/
├── service/
├── domain/
├── repository/
└── handler/
```

규칙:

- Controller는 HTTP 요청과 응답을 처리한다.
- Service는 업무 흐름과 트랜잭션을 처리한다.
- Repository는 데이터 저장·조회를 처리한다.
- Handler는 Action의 검증과 실행을 담당한다.
- DTO는 API 요청·응답 데이터를 표현한다.
- Entity를 API 응답으로 직접 반환하지 않는다.
- 도메인 간 데이터 변경은 명확한 Service 경계를 통해 처리한다.

기능과 관계없는 범용 코드를 `global`에 넣지 않는다. 여러 도메인이 실제로 사용하는 코드만 공통화한다.

### 6.2 백엔드 기술 스택

2026-10-26 확정했다. 버전을 적지 않은 라이브러리는 Spring Boot BOM이 관리하는 버전을 쓴다.

| 영역 | 기술 |
|---|---|
| 언어·프레임워크 | Java 21, Spring Boot 4.1.x |
| 빌드 | Gradle(Wrapper), 프로젝트 1개·Docker 이미지 1개 |
| 실행 모드 | 프로필 `api`(웹 서버), `batch`(배치 컨테이너, 웹 서버 없음), `local`(로컬 개발) |
| 웹 | Spring MVC, Bean Validation, `@RestControllerAdvice` 공통 오류 처리 |
| API 문서 | springdoc-openapi 3.x (OpenAPI 3.1, Swagger UI) |
| 데이터 | PostgreSQL 18, Spring Data JPA(`@Version`, jsonb), JdbcClient(대량 upsert), Flyway |
| 원천 DB | 조회 전용 계정용 HikariCP 커넥션 풀을 따로 둔다(전체 다시 읽기·연결 검사). |
| 원천 CDC | Debezium(Kafka Connect) + Kafka, 소비는 `spring-kafka`(배치 컨테이너). 원천 변경 수집 전용이며 서비스 사이 메시징에는 쓰지 않는다. 인프라는 `infra/cdc/`·`infra/source-db/` |
| CSV | Jackson CSV |
| 오래 걸리는 작업 | 실행 기록 테이블에 요청을 넣고 202를 응답한다. 작업자가 `FOR UPDATE SKIP LOCKED`로 가져가 처리한다. 여러 인스턴스 운영을 기준으로 한다. |
| 배치 | `@Scheduled` + Spring Batch 6(JDBC JobRepository, 실행 기록 테이블은 Flyway V6). 주기 실행은 배치 컨테이너에만 둔다. 정합성 검사(수동 검사·재검증·후보 시험·수집 중 분류)도 배치 컨테이너에서 실행한다. api는 요청을 받아 실행 기록 테이블에 넣기만 한다(2026-10-30 결정). Spring Batch Job은 첫 적재처럼 큰 작업에만 쓰고, 작은 작업은 10.3의 작업 표 방식으로 처리한다. |
| 인증 | Spring Security(세션 인증), Spring Session JDBC(세션을 플랫폼 DB에 저장), BCrypt |
| 외부 호출 | RestClient (시간 제한 필수) |
| AI Agent | Spring AI 2.0.x (GMS 연결) |
| 운영 | Actuator, Micrometer Prometheus |
| 코드 품질 | Spotless + google-java-format(AOSP), Lombok(7.2 허용 범위) |
| 테스트 | JUnit, Mockito, Spring MVC Test, Testcontainers(PostgreSQL 18) |

Agent 답변(API 085)은 오래 걸리는 작업의 예외다. 202로 접수하는 것은 같지만 작업자가 가져가지 않고 질문을 받은 api 서버가 바로 처리하며, 화면은 084를 반복 조회해 진행 단계와 결과를 확인한다(2026-11-03, `docs/agent-response-policy.md`).

사용하지 않는 것: Redis, WebFlux·SSE, QueryDSL, Spring AI 기본 대화 메모리(대화는 플랫폼 DB에 저장한다). Kafka는 원천 CDC에만 쓰고, 서비스 사이 작업 전달은 지금처럼 실행 기록 테이블로 한다(2026-10-30 CDC 결정, S15P31C106-266).

- 같은 프로세스를 여러 개 띄워도 맞게 동작해야 한다. 작업·잠금·카운터 같은 상태를 인스턴스 메모리에만 두지 않는다.
- 의존성은 그 기능을 구현하는 이슈에서 설정과 함께 추가한다.

**백엔드 명령** (`backend/`에서 실행)

| 목적 | 명령 |
|---|---|
| 빌드·테스트·포맷 검사 | `./gradlew build` |
| 테스트 | `./gradlew test` |
| 포맷 적용 | `./gradlew spotlessApply` |
| 포맷 검사 | `./gradlew spotlessCheck` |
| 로컬 DB 없이 실행 (Testcontainers) | `./gradlew bootTestRun --args='--spring.profiles.active=api'` |

테스트와 `bootTestRun`은 Docker가 실행 중이어야 한다.

---

## 7. 코드 스타일

### 7.1 공통 규칙

- UTF-8을 사용한다.
- 들여쓰기에 탭 대신 공백을 사용한다.
- 파일의 줄 끝은 LF로 통일한다.
- 의미가 분명한 이름을 사용한다.
- 약어는 널리 쓰이는 경우에만 사용한다.
- 사용하지 않는 import와 변수를 삭제한다.
- 중요한 업무 조건은 코드나 테스트에서 확인할 수 있게 작성한다.

### 7.2 Java

| 항목 | 규칙 |
|---|---|
| 클래스 | PascalCase |
| 메서드 | camelCase |
| 변수 | camelCase |
| 상수 | UPPER_SNAKE_CASE |
| 패키지 | 소문자 |
| 들여쓰기 | 공백 4칸 |
| import | 와일드카드 import 금지 |
| 의존성 주입 | 생성자 주입 |
| DTO | 단순 데이터에는 record 사용 가능 |

Google Java Style Guide의 이름·중괄호·import 원칙을 참고한다. 들여쓰기 4칸은 C106 결정이며 Google 규칙과 동일하지 않다.

포맷은 Spotless + google-java-format(AOSP 스타일, 4칸)으로 맞춘다. 커밋 전에 `./gradlew spotlessApply`를 실행한다. `./gradlew build`는 포맷이 맞지 않으면 실패한다.

Lombok은 아래 5개만 쓴다. 다른 Lombok 기능은 `backend/lombok.config`에서 컴파일 오류로 막는다.

| 기능 | 조건 |
|---|---|
| `@Getter` | - |
| `@RequiredArgsConstructor` | 생성자 주입에 쓴다. |
| `@Slf4j` | 로거는 이것 하나로 통일한다. |
| `@NoArgsConstructor` | Entity에만, `access = AccessLevel.PROTECTED`로 쓴다. |
| `@Builder` | 생성자나 정적 메서드에만 붙인다. 클래스에 붙이지 않는다. 필수 값 검사와 기본값은 그 생성자 안에서 처리한다. |

위치와 접근 수준 조건은 설정 파일로 검사할 수 없으므로 리뷰에서 확인한다.

**이름 예시**

```java
ActionExecutionService
ReassignmentPlanRepository
CreateReassignmentPlanRequest
validateExecutionPermission()
findActiveObject()
```

**Service 작성 규칙**

- 읽기 전용 조회는 `@Transactional(readOnly = true)`를 사용한다.
- 하나의 업무에서 함께 성공해야 하는 DB 변경은 하나의 트랜잭션으로 처리한다.
- 단순 Entity 조회만 수행하는 Service를 불필요하게 분리하지 않는다.
- 외부 AI 호출이나 원천 DB 조회를 장시간 DB 트랜잭션 안에서 실행하지 않는다.
- 업무 예외를 `RuntimeException` 하나로 처리하지 않는다.
- Entity에는 `@Data`, `@Setter`를 쓰지 않는다. DTO는 record로 만든다.

### 7.3 Python · FastAPI

- PEP 8을 기본 스타일로 사용한다.
- 들여쓰기는 공백 4칸이다.
- 함수와 변수는 snake_case를 사용한다.
- 클래스는 PascalCase를 사용한다.
- 공개 함수의 입력과 반환 타입을 명시한다.
- 요청·응답 형식은 Pydantic 모델로 정의한다.
- Ruff를 포맷·정적 검사 도구로 사용한다.
- 복잡한 모델 호출과 데이터 변환은 API 라우터에서 분리한다.

### 7.4 React · TypeScript

- TypeScript를 사용한다.
- 컴포넌트는 PascalCase를 사용한다.
- 함수와 변수는 camelCase를 사용한다.
- Hooks는 `use` 접두사를 사용한다.
- API 호출 코드를 화면 컴포넌트에서 분리한다.
- ESLint와 Prettier로 스타일을 통일한다.
- 서버 응답 형식은 공통 TypeScript 타입으로 관리한다.
- 모든 요청에서 로딩·실패 상태를 구분한다.

---

## 8. API 개발 규칙

**기준 문서:** C106 OpenAPI 3.1 명세서

### 8.1 API 기본 규칙

- API 기본 경로는 `/api/v1`이다.
- 경로는 소문자와 하이픈을 사용한다.
- 자원 이름에는 명사를 사용한다.
- 목록 조회는 복수형을 사용한다.
- GET은 조회에 사용한다.
- POST는 생성 또는 명시적인 실행 요청에 사용한다.
- PATCH는 일부 수정에 사용한다.
- DELETE는 명세된 삭제 작업에만 사용한다.

예시:

```text
GET   /api/v1/ontology/object-types
POST  /api/v1/ontology/object-types
PATCH /api/v1/ontology/object-types/{id}

POST  /api/v1/sources/{id}/sync-runs
GET   /api/v1/action-executions/{id}
```

기존 API 명세서의 경로가 우선한다. 구현 편의만을 이유로 경로를 바꾸지 않는다.

API 문서(springdoc)는 `api` 프로필에서만 열린다. Swagger UI는 `/swagger-ui.html`, OpenAPI 문서는 `/v3/api-docs`이다. 코드에서 만든 문서는 확인용이다. 계약의 원본은 OpenAPI 명세서(Notion API 명세서)다.

### 8.2 성공 응답

기존 API 명세서의 `data`, `meta` 구조를 사용한다.

```json
{
  "data": {
    "id": 12,
    "name": "MES"
  }
}
```

목록 조회에는 페이지 정보를 포함한다.

```json
{
  "data": [],
  "meta": {
    "page": 0,
    "size": 20,
    "totalElements": 0,
    "hasNext": false
  }
}
```

Controller는 `global.response.ApiResponse`로 감싸서 반환한다. `meta`는 목록 응답에만 나간다.

```java
return ApiResponse.of(SourceResponse.from(source));   // 단건
return ApiResponse.ofPage(page.map(SourceResponse::from)); // 목록(Spring Data Page)
```

### 8.3 오류 응답

모든 API는 같은 오류 구조를 사용한다.

```json
{
  "error": {
    "code": "RESOURCE_NOT_FOUND",
    "message": "요청한 대상을 찾을 수 없습니다.",
    "details": {},
    "requestId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

- `code`: 프로그램이 오류를 구분할 때 사용한다.
- `message`: 사용자에게 보여줄 수 있는 설명이다.
- `details`: 필요한 추가 정보다.
- `requestId`: 서버 로그에서 해당 요청을 찾는 식별자다.

**requestId**

- 서버가 요청마다 새로 만든다. 클라이언트가 보낸 값은 쓰지 않는다.
- 같은 값을 응답 헤더 `X-Request-Id`, 오류 응답의 `requestId`, 그 요청의 모든 로그 줄에 남긴다.
- Action 실행 요청 본문의 `requestId`(API 091)는 중복 실행을 막는 키로, 이 추적 ID와 다른 값이다.

**공통 오류 코드**

| code | 상태 | 쓰는 경우 | details 예 |
|---|---|---|---|
| `VALIDATION_FAILED` | 400 | 입력값 검사 실패, JSON 형식 오류, 파라미터 형식·누락 | `field`, `reason`, `errors[]` |
| `UNAUTHENTICATED` | 401 | 로그인하지 않았거나 세션이 끝남 | - |
| `FORBIDDEN` | 403 | 권한 부족 | `requiredPermission` |
| `RESOURCE_NOT_FOUND` | 404 | 대상이나 경로가 없음 | `resource` |
| `METHOD_NOT_ALLOWED` | 405 | 경로에 없는 HTTP Method | - |
| `CONFLICT` | 409 | 중복·상태 충돌, 처리하지 못한 DB 제약 위반 | - |
| `VERSION_CONFLICT` | 409 | `rowVersion` 불일치 | `field`, `expected`, `actual` |
| `IN_USE` | 409 | 다른 데이터가 참조하고 있어 삭제·변경할 수 없음 | `references[]`(`tableName`, `count`, `ids`) |
| `BUSINESS_RULE_VIOLATION` | 422 | 형식은 맞지만 업무 규칙에 맞지 않음 | `field`, `reason` |
| `INTERNAL_ERROR` | 500 | 그 밖의 모든 오류. 내부 내용은 응답에 넣지 않는다. | - |

입력값 오류가 여러 개면 첫 번째 오류를 `field`·`reason`에, 전체 목록을 `errors`에 담는다.

```json
"details": {
  "field": "name",
  "reason": "공백일 수 없습니다",
  "errors": [
    { "field": "name", "reason": "공백일 수 없습니다" },
    { "field": "size", "reason": "널이어서는 안됩니다" }
  ]
}
```

**예외 처리 구현**

`global.error.GlobalExceptionHandler`가 모든 예외를 위 형식으로 바꾼다. Controller와 Service에서 오류 응답을 직접 만들지 않는다.

- 업무 규칙 위반은 `BusinessException`을 던진다. 오류 코드와 `details`를 담는다.
- 메시지는 오류 코드의 기본 문구를 쓴다. API 명세서가 그 API에 맞는 문구를 정해 두었으면 그 문구를 넘긴다. 예: `new BusinessException(CommonErrorCode.RESOURCE_NOT_FOUND, "미리보기 결과가 없거나 만료되었습니다. 다시 요청해 주세요.", Map.of())`
- 도메인 전용 오류 코드는 그 도메인 패키지에 `ErrorCode`를 구현한 enum으로 만든다. `CommonErrorCode`에 도메인 코드를 넣지 않는다.

```java
// issue/domain/IssueErrorCode.java
public enum IssueErrorCode implements ErrorCode {
    REVERIFICATION_REQUIRED(
            HttpStatus.UNPROCESSABLE_CONTENT, "해결로 변경하려면 이 문제의 재검증 통과 기록이 필요합니다.");
    // code(), status(), message() 구현
}

throw new BusinessException(
        IssueErrorCode.REVERIFICATION_REQUIRED,
        Map.of("field", "status", "reason", "재검증 통과 기록이 없습니다."));
```

- JPA 버전 충돌(`OptimisticLockingFailureException`)은 자동으로 `VERSION_CONFLICT`가 된다.
- DB 제약 위반(`DataIntegrityViolationException`)은 자동으로 `CONFLICT`가 된다. 중복처럼 예상할 수 있는 경우는 Service에서 먼저 확인하고 구체적인 코드로 던진다.
- 컨트롤러 밖(필터 등)에서 난 오류도 `/error`에서 같은 형식으로 응답한다.

**HTTP 상태 코드**

| 코드 | 사용 기준 |
|---|---|
| 200 | 정상 조회·수정·실행 완료 |
| 201 | 자원 생성 완료 |
| 202 | 비동기 작업 접수 |
| 204 | 응답 본문 없이 처리 완료 |
| 400 | 잘못된 요청 |
| 401 | 인증 필요·인증 실패 |
| 403 | 권한 부족 |
| 404 | 대상 없음 |
| 405 | 지원하지 않는 HTTP Method |
| 409 | 중복·상태 충돌·버전 충돌 |
| 422 | 유효한 형식이지만 업무 조건 위반 |
| 500 | 서버 내부 오류 |

실제 API마다 반환할 상태 코드는 OpenAPI 명세에 기록한다.

### 8.4 파라미터 규칙

- Java 필드와 JSON 필드는 camelCase를 사용한다.
- DB 컬럼은 snake_case를 사용한다.
- ID는 DB의 bigint와 대응한다.
- 현재 OpenAPI는 ID를 JSON 숫자로 정의한다.
- 시각은 시간대 정보가 있는 ISO 8601 문자열을 사용한다.
  - DTO의 시각 필드는 `Instant`를 쓴다. 응답은 UTC로 나간다. 예: `2026-10-28T03:15:30Z`
  - 요청은 시간대가 붙은 값만 받는다. `2026-10-28T12:15:30+09:00`은 받고, 시간대가 없는 `2026-10-28T12:15:30`은 400(`VALIDATION_FAILED`)으로 거절한다.
  - 현재 시각은 `Instant.now()` 대신 주입받은 `Clock`으로 구한다(`Instant.now(clock)`). 테스트에서 시각을 고정할 수 있다.
- 선택적인 입력과 필수 입력을 명확히 구분한다.
- 목록 조회는 `page`, `size` 등 명세된 파라미터를 사용한다.
- 입력값 검사는 Controller 진입 시점부터 수행한다.

JSON 숫자의 안전 범위와 실제 ID 증가 범위를 검토한 뒤 ID 표현을 최종 확정한다.

### 8.5 API 계약 변경

요청 필드, 응답 필드, 상태 코드, 경로가 바뀌면 다음 절차를 따른다.

1. 영향을 받는 담당자와 협의한다.
2. OpenAPI 명세를 수정한다.
3. 프론트엔드와 내부 호출자에게 변경 내용을 공유한다.
4. 코드를 변경한다.
5. 계약 테스트를 실행한다.
6. 문서 변경과 코드를 같은 MR에서 검토한다.

프론트엔드에서 필요한 값이 생겼다는 이유만으로 Entity 전체를 반환하지 않는다.

---

## 9. DB·JPA 규칙

### 9.1 DB 관리

- PostgreSQL을 사용한다.
- ERD와 실제 스키마가 일치해야 한다.
- 모든 테이블과 제약 조건 변경을 버전 관리한다.
- 개발자가 운영 DB를 직접 수정하지 않는다.
- 기존 데이터가 있는 컬럼을 삭제하거나 변경할 때 영향 범위를 확인한다.
- DB 자격 증명은 코드에 저장하지 않는다.

### 9.2 Migration

Flyway를 사용한다.

```text
src/main/resources/db/migration/
├── V1__create_ontology_core.sql
├── V2__create_operations.sql
└── V3__add_cross_domain_constraints.sql
```

Migration은 DB 변경 SQL을 버전별로 실행하는 방식이다.

- 실행한 Migration 파일을 수정하지 않는다.
- 변경이 필요하면 새 버전의 파일을 추가한다.
- 외래 키가 다른 영역의 테이블을 참조하면 생성 순서를 검증한다.
- 로컬·테스트·배포 DB에 같은 Migration을 적용한다.
- 스키마 변경 MR에는 적용 및 복구 영향을 작성한다.

### 9.3 Entity·Repository

- Entity와 API DTO를 분리한다.
- 연관 관계는 필요한 방향만 정의한다.
- 모든 연관 관계를 양방향으로 만들지 않는다.
- 조회 화면에 필요한 값만 가져온다.
- N+1 문제가 발생할 수 있는 조회는 테스트로 확인한다.
- Fetch Join, DTO 조회 등은 실제 조회 요구에 따라 선택한다.

공통 Entity (`global.entity`):

| 테이블의 컬럼 | 상속할 클래스 | 자동으로 채우는 값 |
|---|---|---|
| `created_at`, `updated_at` | `BaseTimeEntity` | 생성·수정 시각 |
| 위 컬럼 + `created_by`, `updated_by` | `BaseAuditEntity` | 생성·수정 시각, 생성·수정 계정 ID |

- `timestamptz` 컬럼은 Java `Instant`로 매핑한다.
- 생성·수정 계정은 로그인한 계정 ID다. 로그인 사용자가 없는 작업(배치, AI 제안)에서는 NULL로 남는다.
- 위 표에 맞지 않는 테이블(예: `created_at`만 있음)은 공통 Entity를 상속하지 않는다. 필드를 직접 선언한다.
- `row_version`은 공통 Entity에 없다. 컬럼이 있는 Entity가 `@Version`으로 직접 선언한다.

### 9.4 동시성

`rowVersion`이 있는 데이터는 동시 수정 충돌을 검사한다.

- 수정 요청에 기존 버전 값을 전달한다.
- DB에 저장된 버전과 비교한다.
- 버전이 다르면 변경을 거절한다.
- 클라이언트는 최신 데이터를 다시 조회하도록 안내한다.

고유성·중복 금지는 애플리케이션 검사뿐 아니라 DB 제약 조건으로도 보장한다.

### 9.5 C106 고유 데이터 규칙

- 원천 객체는 같은 원천 키를 기준으로 갱신한다.
- 원천에서 삭제된 객체는 `DELETED_AT_SOURCE`로 표시한다.
- Explorer와 Agent는 활성 객체와 활성 관계만 일반 조회에 사용한다.
- 승인 전 규칙은 제안 데이터에 보관한다.
- `link_mapping`에는 승인된 규칙 개정본을 저장한다.
- 규칙 개정 시 기존 관계를 재계산하고 성립하지 않는 관계는 비활성화한다.
- 정합성 문제는 재검증 통과 기록 없이 해결 상태로 바꾸지 않는다.
- 재배정 계획은 전용 테이블에 저장한다.
- 계획 생성은 원천 MES의 실제 배정을 변경하지 않는다.

---

## 10. 서비스 연동 규칙

### 10.1 Spring Boot ↔ FastAPI

- Spring Boot가 주요 업무 데이터의 저장과 권한 검사를 담당한다.
- FastAPI는 분석·제안 결과를 반환한다.
- FastAPI는 플랫폼 업무 DB에 직접 쓰지 않는다.
- 서비스 사이의 요청·응답 DTO를 별도로 정의한다.
- AI 호출에는 시간 제한을 둔다.
- 호출 실패와 분석 실패를 구분한다.
- 실패한 호출은 호출 식별자와 원인을 기록한다.
- 모델 출력은 서버에서 검증한 후 저장한다.

AI가 생성한 객체 유형이나 연결 규칙은 곧바로 활성 데이터로 반영하지 않는다. 검토와 승인 절차를 따른다.

### 10.2 ERP/MES 원천 연결

2026-10-30 원천 수집을 로그 기반 CDC(Debezium + Kafka)로 정했다. 원천에는 변경 이력 테이블·트리거를 만들지 않고 `wal_level=logical`, 복제 계정, publication만 둔다(S15P31C106-266, V5).

- PostgreSQL 원천에는 두 계정으로 접속한다. 플랫폼은 조회 전용 계정(SELECT)을, Debezium은 CDC 복제 계정(REPLICATION, superuser 아님)을 쓴다.
- 서버 수준에서 허용한 접근 경로만 사용한다(원천은 메인 서버 IP만 허용).
- 원천 DB 자격 증명을 API 응답이나 로그에 포함하지 않는다. CDC 복제 계정 비밀번호는 Kafka Connect에 config provider로만 넘긴다.
- 원천 테이블마다 수집 방식이 있다(`source_resource.sync_strategy`). `CDC`는 변경 이벤트로, `FULL_COMPARE`는 테이블 전체를 읽고 저장된 값과 비교해 반영한다. 뷰는 늘 `FULL_COMPARE`다.
- 매핑이 승인·변경된 테이블은 플랫폼이 조회 전용 계정으로 전체를 읽어 적재한다. 읽기 직전 LSN을 `source_resource.loaded_lsn`에 남기고, 그보다 앞선 CDC 이벤트는 버린다. 객체마다 마지막으로 반영한 LSN(`object_instance.source_lsn`)보다 앞선 변경도 반영하지 않는다.
- 체크포인트는 소비자의 토픽 offset이다(`cdc_offset`). 객체 반영과 같은 트랜잭션에서만 전진한다. offset이 토픽 보관 기간을 넘었거나 원천 복제 슬롯이 깨졌으면 그 원천의 CDC 테이블을 전체 다시 읽어 복구한다.

### 10.3 배치 실행

- CDC 반영은 배치 컨테이너의 상시 소비자가 한다. 소비자 그룹이 토픽마다 소비자 하나만 돌게 하고, 한 번에 가져온 묶음을 한 트랜잭션으로 반영하며 그 묶음을 `sync_run`(`trigger_kind=CDC`) 하나로 남긴다.
- 같은 테이블의 전체 다시 읽기를 중복 실행하지 않는다.
- 이미 실행 중이면 기존 실행 ID를 반환한다.
- 실행 시작·완료·실패 시각을 기록한다.
- 실행 중에는 heartbeat를 갱신한다.
- heartbeat가 중단된 작업은 정해진 시간 초과 기준으로 실패 처리한다.
- 수동 실행과 예약 실행은 같은 수집 로직을 사용한다.
- 반복 실행해도 같은 원천 데이터가 중복 생성되지 않도록 한다.

배치 앱 구조(2026-10-30, S15P31C106-215):

- 배치 컨테이너는 백엔드와 같은 이미지를 `SPRING_PROFILES_ACTIVE=batch`로 띄운다. 웹 서버는 없다.
- `@Scheduled`는 batch 프로필에서만 켜진다(`batch.config.BatchSchedulingConfig`). 정기 자동 검사는 두지 않는다.
- api가 접수하는 작업(연결 검사, 원천 표본·매핑 미리보기, 정합성 검사, 수동 수집)은 모두 작업 표 방식이다.
  - api가 작업 표(`source_connection_check`, `source_preview`, `check_run`, `sync_run`)에 `QUEUED` 행을 넣고 202를 응답한다.
  - 배치의 `QueuedJobPoller`가 `axcore.batch.poll-delay`(기본 2초)마다 확인한다. `QueuedJobRunner`가 `FOR UPDATE SKIP LOCKED`로 한 건씩 가져간다.
  - 도메인은 작업 종류마다 `QueuedJobHandler` Bean 하나만 만든다. `run`은 트랜잭션 밖에서 원천을 읽고, `complete`는 결과를 저장하며 행을 `SUCCEEDED`로 바꾼다.
  - 실패 이유를 화면에 보여 주려면 `QueuedJobFailure`를 던진다. 다른 예외는 일반 문구로 남기고 자세한 내용은 서버 로그에만 쓴다.
  - 감사 기록 결과 줄(SYSTEM + BATCH)은 `QueuedJobRunner`가 남긴다. Handler가 따로 남기지 않는다.
- 큰 작업(첫 적재, 테이블 전체 다시 읽기)은 Spring Batch Job으로 만들고, Handler의 `run`에서 그 Job을 시작한다. 실패하면 마지막으로 커밋한 청크 다음부터 이어서 실행한다. 업무 상태는 `sync_run`에 두고, Spring Batch 실행 기록 테이블(`batch_*`)은 화면과 API에서 읽지 않는다.
- 멈춘 작업(RUNNING인 채로 남은 행)의 정리는 heartbeat 작업(S15P31C106-218)에서 한다.

---

## 11. 인증·권한·보안

### 11.1 인증

2026-10-27 JWT 대신 서버 세션 방식으로 정했다. 잠긴 계정과 바뀐 역할을 바로 반영하고, Redis 없이 여러 인스턴스에서 같은 세션을 쓰기 위해서다.

**세션**

- 로그인하면 서버가 세션을 만들고 세션 쿠키를 발급한다. 이후 요청은 이 쿠키로 인증한다.
- 세션은 Spring Session JDBC로 플랫폼 DB에 저장한다.
- 세션 쿠키에는 `HttpOnly`와 `Secure`를 붙인다.
- 요청이 없는 상태로 30분이 지나면 세션이 만료된다.
- 로그아웃하면 서버의 세션을 지운다.
- 같은 계정으로 여러 곳에서 동시에 로그인할 수 있다.

**세션 끊기**

- 비밀번호를 바꾸면 그 계정의 다른 세션을 모두 끊는다. 지금 쓰는 세션만 남긴다.
- 관리자가 계정을 잠그거나 비활성화하거나 역할을 바꾸면 그 계정의 세션을 모두 끊는다.

**CSRF 방어**

- 상태를 바꾸는 요청(POST·PATCH·DELETE)은 CSRF 토큰을 검사한다.
- 서버는 토큰을 `XSRF-TOKEN` 쿠키로 보낸다. 화면은 같은 값을 `X-XSRF-TOKEN` 요청 헤더에 넣는다.

**공통**

- nginx 뒤에서도 `Secure` 쿠키와 HTTPS 주소가 맞도록 Spring Boot의 forward headers 설정(`server.forward-headers-strategy`)을 켠다.
- 인증 실패와 권한 부족을 구분한다.
- 비활성화하거나 잠긴 계정은 인증할 수 없다.
- 비밀번호는 복원할 수 없는 해시로 저장한다.
- 세션 ID·쿠키·비밀번호·인증 헤더를 로그에 남기지 않는다.

**구현에서 쓰는 방법**

- 로그인 사용자는 Controller에서 `@AuthenticationPrincipal LoginAccount`로 받는다. 계정 ID, 로그인 ID, 역할 코드, 임시 비밀번호 여부가 들어 있다.
- 로그인 없이 부를 수 있는 경로는 로그인(`POST /api/v1/auth/login`), API 문서(`/swagger-ui/**`, `/v3/api-docs/**`), `/actuator/health`, 내부 모니터링용 `GET /actuator/prometheus`다. 지표 경로는 nginx 외부 프록시에 추가하지 않으며 Docker 내부망 수집에 사용한다. 새 공개 경로가 필요하면 `global.security.SecurityConfig`를 고치고 리뷰를 받는다.
- 임시 비밀번호 상태(`mustChangePassword=true`)에서는 내 계정 조회, 로그아웃, 비밀번호 변경만 쓸 수 있다. 나머지는 403이다.
- 계정의 세션을 끊을 때는 `auth.service.AccountSessionService`를 쓴다. 비밀번호 변경은 `expireOthers`, 잠금·비활성화·역할 변경·비밀번호 초기화는 `expireAll`이다.
- 로그인 요청은 CSRF 검사에서 뺀다. 로그인에 성공하면 세션 ID를 새로 만들고 `XSRF-TOKEN` 쿠키를 준다. `GET /api/v1/auth/me`도 쿠키가 없으면 다시 준다.
- 로그인 실패 횟수와 마지막 로그인 시각은 관리자 수정이 아니라서 `row_version`을 올리지 않는다.
- 사용자가 정하는 비밀번호는 `account.service.PasswordPolicy`로 검사한다. 8자 이상, 영문·숫자·특수문자 모두 포함, 로그인 ID와 다름, 72바이트 이하(BCrypt 한계)다. 비밀번호 변경(003)은 현재 비밀번호와 같은 값도 거절한다.
- Service에서 찾은 입력 오류는 `BusinessException.invalidField(field, reason)`으로 던진다. 응답은 Bean Validation 오류와 같은 400 형식이다.
- 세션 테이블은 Flyway `V4__create_spring_session.sql`이 만든다.

**내부 API**

FastAPI 같은 내부 서비스가 부르는 `/api/v1/internal/**`는 세션 대신 서비스 토큰으로 인증한다(API 105·043, S15P31C106-270).

- 요청 헤더 `X-Internal-Token`에 공유 비밀을 넣는다. backend는 환경변수 `AXCORE_INTERNAL_API_TOKEN`, AI는 `AI_ONTOLOGY_INTERNAL_TOKEN`에 같은 값을 넣는다. 값은 서버 `.env`에만 둔다.
- 설정값이 비어 있으면 내부 API를 모두 막는다. 기본값을 두지 않는다.
- 토큰이 없거나 틀리면 401이다. 토큰 없이 사용자 세션 쿠키로 부르면 403이다.
- 세션을 만들거나 읽지 않고, 쿠키를 쓰지 않으므로 CSRF도 검사하지 않는다.
- 토큰은 내부 API에서만 통한다. 다른 API에 토큰을 보내도 인증되지 않는다.
- 인증된 요청의 사용자는 내부 서비스(`internal-service`)다. `LoginAccount`가 없으므로 `created_by`·`updated_by`는 빈 값이다.
- 보안 설정은 `global.security.SecurityConfig`의 내부 API 체인과 `InternalApiTokenFilter`다.
- nginx는 외부에서 오는 `/api/v1/internal/**` 요청을 막는다.

**첫 관리자**

공개 가입이 없으므로 관리자가 한 명도 없으면 서버(`api` 프로필)가 시작할 때 첫 관리자를 만든다.

- 환경변수 `AXCORE_INITIAL_ADMIN_LOGIN_ID`, `AXCORE_INITIAL_ADMIN_PASSWORD`로 값을 넣는다. 값이 없으면 만들지 않고 경고 로그만 남긴다.
- 만든 계정은 임시 비밀번호 상태다. 첫 로그인 뒤 비밀번호를 바꿔야 업무 API를 쓸 수 있다.
- 관리자가 이미 있으면 환경변수가 있어도 아무것도 하지 않는다.

### 11.2 권한

모든 권한은 서버에서 검사한다. 화면의 버튼을 숨기는 것은 보안 검사를 대신하지 않는다.

| 역할 | 권장 권한 |
|---|---|
| ADMIN | 계정·모델·규칙·승인 관리 |
| PLANNER | 허용된 데이터 조회·재배정 계획 생성 |
| MAINTENANCE | 허용된 정비 데이터 조회 |
| VIEWER | 허용된 업무 데이터 조회 |

세부 권한은 최종 권한 매트릭스를 기준으로 한다.

조회 권한은 객체 유형과 민감 속성 표시 권한을 구분한다.

### 11.3 비밀값 관리

GitLab에 다음 정보를 저장하지 않는다.

- 실제 `.env`
- DB 비밀번호
- AI API 키
- GitLab Access Token
- SSH 개인 키
- 원천 DB 접속 비밀값

설정값은 서버 환경변수와 Jenkins Credentials로 관리한다. `.env.example`에는 변수 이름과 설명만 둔다.

**원천 접속 비밀번호**

관리자가 등록한 원천 계정 비밀번호는 `data_source_credential`에 AES-256-GCM 암호문으로만 저장한다(S15P31C106-64·275).

- 암·복호화는 `source.service.SourceCredentialCipher`로만 한다. 계정을 풀어 쓸 때는 `source.service.SourceCredentialService.findAccount`를 쓴다.
- AAD는 `{data_source_id}:{purpose}`다. 암호문을 다른 원천이나 용도의 행에 옮기면 풀리지 않는다. 새 원천은 `data_source` 행을 먼저 만든 뒤 암호화한다.
- 키는 32바이트를 base64로 만들어(`openssl rand -base64 32`) 서버 `~/axcore/.env`의 `AXCORE_SOURCE_CREDENTIAL_KEY_1`에 둔다. backend·batch 컨테이너에만 넣고 ai 컨테이너에는 넣지 않는다. AI 계정은 분석 시작 요청으로 전달한다.
- 키가 없거나 형식이 틀려도 서버는 뜬다. 시작할 때 경고 로그를 남기고 암·복호화할 때 `SourceCredentialException`으로 실패한다.
- 푼 비밀번호(`SourceAccount`)를 로그·응답·감사 기록에 넣지 않는다.

키를 바꿀 때는 다음 순서를 따른다.

1. `application.yml`의 `axcore.source-credential.keys`에 다음 번호(예: `2: ${AXCORE_SOURCE_CREDENTIAL_KEY_2:}`)를 추가해 배포한다.
2. 서버 `.env`에 새 키를 넣고 `AXCORE_SOURCE_CREDENTIAL_KEY_VERSION`을 새 번호로 올린 뒤 backend·batch를 다시 띄운다. 이후 새로 저장하는 값은 새 키로 암호화된다.
3. 기존 행을 모두 새 키로 다시 암호화한다. 옛 행은 저장된 `key_version`의 키로 계속 풀린다.
4. 옛 버전 행이 남지 않은 것을 확인한 뒤에 옛 키를 지운다.

---

## 12. 테스트 규칙

### 12.1 테스트 종류

| 테스트 | 목적 | 권장 도구 |
|---|---|---|
| 단위 테스트 | Service·Handler 규칙 검증 | JUnit · Mockito |
| API 테스트 | 요청·응답·오류 확인 | Spring MVC Test |
| DB 통합 테스트 | 실제 SQL·제약·트랜잭션 확인 | Testcontainers |
| AI 테스트 | 분석·추출 결과 검증 | pytest |
| 프론트엔드 테스트 | 화면 상태·사용자 동작 확인 | Vitest 등 |
| E2E 테스트 | 전체 시나리오 확인 | 통합 시나리오 테스트 |

### 12.2 필수 테스트 범위

각 핵심 기능은 아래 경우를 확인한다.

- 정상 처리
- 필수 입력 누락
- 잘못된 입력값
- 존재하지 않는 대상
- 권한이 없는 사용자
- 중복 요청
- 상태 충돌
- 트랜잭션 실패 후 데이터 상태

Coverage 수치 기준은 두지 않는다. 테스트 충분성은 위 필수 범위와 12.3 핵심 테스트를 기준으로 판단한다.

CI에서 반드시 통과해야 하는 테스트 범위는 아직 정하지 않았다.

### 12.3 C106 핵심 테스트

**온톨로지·승인**
- 검사를 통과하지 않은 후보를 승인할 수 없다.
- 검사 후 후보 revision이 변경되면 재검사가 필요하다.
- 승인과 반영이 함께 성공하거나 함께 실패한다.
- 규칙 개정 후 유효하지 않은 기존 연결은 비활성화된다.

**수집·배치**
- 매핑된 테이블의 첫 적재는 전체를 읽는다.
- 이후 CDC 이벤트로 변경분만 반영한다. 전체 읽기보다 앞선 이벤트가 늦게 와도 값이 되돌아가지 않는다.
- 반복 수집해도 동일 객체의 ID가 유지된다.
- 동시 수집 요청은 하나의 실행으로 처리한다.
- 실패한 작업이 체크포인트(소비 offset)를 잘못 전진시키지 않는다.

**Action·재배정**
- 권한이 없으면 Action을 실행할 수 없다.
- 같은 요청 ID가 계획을 중복 생성하지 않는다.
- 작업지시·공정 중복을 거절한다.
- 재배정 계획 생성은 원천 MES를 변경하지 않는다.
- 차단된 실행도 감사 기록에 남긴다.

**정합성 문제**
- 같은 열린 문제가 중복 생성되지 않는다.
- 재검증을 통과하지 않으면 해결 상태가 될 수 없다.
- 재검증은 변경된 데이터를 기준으로 진행한다.

---

## 13. CI/CD·배포 규칙

### 13.1 파이프라인

**MR 검증**

```text
MR 생성·갱신
 → Jenkins MR 트리거
 → 코드 스타일 검사
 → 빌드
 → 자동 테스트
 → 결과를 MR에 표시
 → 리뷰
 → 병합
```

MR을 생성하거나 갱신하면 Jenkins가 빌드와 테스트를 실행한다. MR 파이프라인이 실패하면 병합하지 않는다. Jenkins 구성 방법은 인프라 문서에서 관리한다.

**develop 자동 배포**

```text
develop 병합
 → GitLab Webhook
 → Jenkins
 → 변경 서비스 빌드
 → Docker 이미지 생성
 → Docker Compose 배포
 → 헬스체크
 → 배포 결과 확인
```

### 13.2 배포 규칙

- 이미지에는 식별 가능한 버전 또는 커밋 SHA를 사용한다.
- 배포 실패 시 이전 정상 이미지로 되돌릴 수 있어야 한다.
- DB 데이터는 영속 볼륨에 저장한다.
- 애플리케이션 컨테이너를 재생성해도 DB 데이터가 유지되어야 한다.
- 배포 전후 헬스체크를 수행한다.
- 외부에 필요한 포트만 공개한다.
- DB와 내부 AI 서비스는 외부에 직접 노출하지 않는다.

### 13.3 환경 구분

| 환경 | 용도 |
|---|---|
| local | 개발자 개인 환경 |
| test | 자동 테스트 |
| dev | 통합 및 시연 서버 |

운영 환경을 별도로 만들면 `prod` 설정을 추가한다.

환경별로 비밀값과 외부 접속 주소를 분리한다.

---

## 14. 로깅·감사 규칙

### 14.1 기술 로그

로그에는 요청을 추적하고 장애를 분석하는 데 필요한 정보를 기록한다.

- 요청 식별자
- 서비스 및 작업명
- 실행 시각
- 처리 상태
- 소요 시간
- 실패 원인

비밀번호, 토큰, 개인정보 원문 등 민감한 값은 기록하지 않는다.

### 14.2 업무 감사 기록

C106은 일반 로그와 별도로 업무 변경 이력을 관리한다.

- 누가 실행했는가
- 어떤 Action을 실행했는가
- 어떤 권한을 검사했는가
- 어떤 데이터를 변경했는가
- 변경 전후 내용은 무엇인가
- 실행이 성공했는가
- 실패하거나 차단했다면 이유는 무엇인가

업무 감사 기록은 `action_execution`, `entity_change` 등 기존 ERD를 기준으로 저장한다.

Agent의 조회 Tool 호출은 `action_execution`(`event_kind=AGENT_QUERY`)에 남긴다. 관리자 감사 화면에는 보이지 않는다.

수집·분석·검사는 각 실행 정보와 연결해 확인할 수 있도록 한다.

**기록 주체와 진입 경로 (2026-10-30)**

- 기록은 백엔드(api)와 배치가 같은 코드로 DB에 직접 쓴다. FastAPI는 DB에 쓰지 않고 내부 API(105)로 알리며, 기록은 백엔드가 남긴다.
- 사람이 한 일은 `actor_type=USER`(Agent가 대신하면 `AGENT`), 사람 없이 시스템이 하거나 끝낸 일은 `SYSTEM`이다.
- `entry_point`는 `WEB`(화면), `AGENT`, `BATCH`(배치 컨테이너 안에서 생긴 일), `SYSTEM`(api 안에서 사람 없이 생긴 일), `INTERNAL`(다른 서비스가 내부 API로 알린 일)이다. 배치가 스스로 시작한 일은 `SYSTEM` + `BATCH`다.
- 오래 걸리는 작업(수집·검사·분석·연결 검사·원천 표본)은 접수 1줄과 결과 1줄을 남기고, 남긴 줄은 고치지 않는다.
  - 접수 줄: 요청한 사람, `SUCCEEDED`. 기존 작업에 합류하거나 결과를 재사용해도 남긴다.
  - 결과 줄: `SYSTEM`, 배치가 끝내면 `BATCH`, FastAPI가 알리면 `INTERNAL`. `SUCCEEDED` 또는 `FAILED`.
  - 두 줄은 `target_type`(작업 표 이름)과 `target_id`(작업 ID)로 묶는다. 요청자는 작업 표의 `requested_by`에서 찾는다.
  - 요청자가 없는 작업(주기 수집 등)은 결과 줄만 남긴다.
- 원본 규칙은 Notion API 공통 규칙의 '로그' 절이다.

**기록 남기는 법**

- `audit.service.AuditRecorder`로만 남긴다. `ActionExecution`·`EntityChange`를 직접 저장하지 않는다.
- 성공 기록은 `record`를 쓴다. 업무 변경과 같은 트랜잭션이라 업무가 롤백되면 기록도 없어진다.
- 차단·실패 기록은 `recordSeparately`를 쓴다. 새 트랜잭션에 바로 커밋해서 업무가 롤백돼도 남는다.
- 기록 내용은 `AuditEntry.builder()`로 만든다. 필수는 `eventKind`, `actor`, `entryPoint`, `status`이고, `RUNNING`은 받지 않는다.
- 실행자는 `Actor.user(loginAccount)`, `Actor.agent(loginAccount)`, `Actor.system()`으로 만든다. 역할은 실행 당시 값이 남는다.
- 바뀐 행은 `ChangeEntry.insert/update/delete`로 넣는다. 행 키는 PK 이름과 값(`Map.of("id", id)`)이다.
- `input`·`result`·전후 내용에서 이름에 password·secret·token·credential·apiKey 등이 들어간 키는 저장할 때 자동으로 빠진다. 그래도 비밀값은 처음부터 넣지 않는다.

```java
auditRecorder.record(
        AuditEntry.builder()
                .eventKind(EventKind.SYNC_RUN)
                .actor(Actor.user(loginAccount))
                .entryPoint(EntryPoint.WEB)
                .status(ExecutionStatus.SUCCEEDED)
                .targetType("sync_run")
                .targetId(syncRun.getId())
                .input(Map.of("sourceId", sourceId))
                .build());
```

---

## 15. 팀 협업 및 의사결정

### 15.1 변경 공유

다음 상황에서는 관련 담당자에게 변경을 알린다.

- 다른 도메인의 API 계약 변경
- 공통 DTO 변경
- DB 스키마 또는 공통 Entity 변경
- 권한 정책 변경
- 배치 실행 정책 변경
- AI 입력·출력 형식 변경
- 배포 설정 변경

### 15.2 의사결정 기록

설계에 영향을 주는 결정은 노션에 남긴다.

형식:

| 항목 | 내용 |
|---|---|
| 결정 사항 | 무엇을 결정했는가 |
| 배경 | 어떤 문제가 있었는가 |
| 선택한 방식 | 무엇을 사용할 것인가 |
| 선택 이유 | 왜 이 방식을 선택했는가 |
| 영향 범위 | 어떤 코드·API·DB가 바뀌는가 |
| 관련 담당자 | 누가 확인했는가 |

### 15.3 AI 도구 활용

- AI가 만든 코드는 개발자가 직접 검토한다.
- AI 생성 코드도 기존 코드 규칙을 따른다.
- AI가 제안한 라이브러리와 API는 실제 버전을 확인한다.
- 중요한 업무 로직은 테스트로 검증한다.
- 비밀번호·토큰·실제 민감 데이터를 외부 AI 도구에 입력하지 않는다.
- AI가 만든 테스트가 실제 검증 조건을 확인하는지 검토한다.
- AI 생성 결과를 확인하지 않은 채 MR을 병합하지 않는다.

저장소의 AI 작업 규칙은 `.ai/`에서 관리한다. 이 가이드를 바꾸면 `.ai/rules/`의 관련 규칙도 함께 확인한다.

---

## 16. 작업 완료 기준 (Definition of Done)

Definition of Done은 작업을 완료했다고 판단하는 공통 기준이다.

모든 기능은 다음 조건을 만족해야 한다.

- [ ] Jira 요구사항을 충족했다.
- [ ] API 계약을 준수했다.
- [ ] 정상·예외 흐름을 구현했다.
- [ ] 권한과 입력값을 검사한다.
- [ ] 필요한 자동 테스트를 통과했다.
- [ ] 관련 문서를 최신 상태로 수정했다.
- [ ] MR 리뷰를 완료했다.
- [ ] `develop`에 병합했다.
- [ ] 통합 환경에서 정상 동작을 확인했다.

단순 문서·설정 작업에는 관련 없는 항목을 적용하지 않는다.

---

## 17. 최초 설정 체크리스트

**GitLab**
- [ ] `main`, `develop` 보호 브랜치 설정
- [ ] 직접 push 제한
- [ ] MR 템플릿 추가
- [ ] 리뷰·병합 권한 확인
- [ ] Squash Merge 규칙 설정

**백엔드**
- [ ] 패키지 구조 확정
- [x] Java Formatter 결정 및 코드 포맷 통일
- [ ] 공통 API 응답 구현
- [ ] 공통 예외 처리 구현
- [ ] Bean Validation 적용
- [ ] 인증·권한 공통 처리
- [ ] Flyway 적용
- [x] Testcontainers 기본 테스트 구성

**AI·프론트엔드**
- [ ] FastAPI 요청·응답 모델 확정
- [ ] Ruff · pytest 적용
- [ ] ESLint · Prettier 적용
- [ ] 공통 API 클라이언트 작성
- [ ] 공통 인증·오류 처리 연결

**CI/CD**
- [ ] Jenkins MR 트리거로 빌드·테스트 검증
- [ ] CI 필수 테스트 범위 결정
- [ ] `develop` 병합 시 자동 배포
- [ ] 서비스 헬스체크
- [ ] 배포 실패 시 복구 절차
- [ ] 비밀값 노출 점검

---

## 참고 기준

- [Google Java Style Guide](https://google.github.io/styleguide/javaguide.html)
- [Conventional Commits](https://www.conventionalcommits.org/ko/v1.0.0/)
- [GitLab Merge Requests](https://docs.gitlab.com/user/project/merge_requests/)
- [GitLab Repository Protection](https://docs.gitlab.com/user/project/repository/protect/)
- [Zalando RESTful API Guidelines](https://opensource.zalando.com/restful-api-guidelines/)
- [Spring Boot Database Initialization](https://docs.spring.io/spring-boot/how-to/data-initialization.html)
- [Spring Boot Testing](https://docs.spring.io/spring-boot/reference/testing/)
- [OWASP REST Security Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html)
