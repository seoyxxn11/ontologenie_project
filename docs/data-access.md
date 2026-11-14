# 데이터 접근 범위와 공통 읽기 계층 (Jira 384)

객체 값이 사람이나 외부 모델에게 나갈 때 같은 규칙을 거치게 하는 장치다. 정책 전체(컬럼 분류, 역할, 한계와 이유)는 Jira S15P31C106-384 설명이 기준이다. 이 문서는 코드에 들어간 부분만 적는다.

## 용도 (`DataPurpose`)

같은 사람이라도 무엇에 쓰느냐에 따라 받는 값이 다르다. 역할 이름이 아니라 용도로 나누므로 역할이 늘어도 이 구분은 바뀌지 않는다.

| 용도 | 만드는 곳 | 보이는 유형 | 민감(RESTRICTED) 값 | 객체 값 읽기 |
|---|---|---|---|---|
| `VIEW` | `DataAccessPolicy.of(actor)` | 역할 범위(`role_data_scope`) | 허용 행이 열어 줄 때만 | 가능 |
| `MODEL` | `DataAccessPolicy.forModel(actor)` | `VIEW`와 같음 | **항상 가림** | 가능 |
| `DEFINITIONS` | `DataAccessPolicy.forDefinitions()` | 전부 | 정의는 봄 | **불가**(`requireObjectValues`가 막음) |

- 외부 언어 모델에 들어가는 값(Agent 도구 결과, Agent가 실행한 Action의 문구)은 `MODEL`로 읽는다.
- `DEFINITIONS`는 내부 서비스 토큰으로 부르는 모델 요약(API 043)만 쓴다. 이름이 정의 조회라 객체 값을 읽으려 하면 예외다. 예전 `internalService()`가 이것으로 바뀌었다.

## 공통 읽기 진입점 (`ScopedObjectReader`)

사용자 요청 경로(Action Handler, Agent 계산 등)가 객체(`object_instance`)를 읽을 때 쓰는 곳이다. 모든 메서드가 `DataAccess`를 받으므로 범위를 빼먹은 채로는 읽을 수 없다.

| 메서드 | 동작 |
|---|---|
| `findByIds(access, ids)` | 없는 ID와 볼 수 없는 유형의 객체는 결과에 없다(없는 객체와 같게, 205 D4). |
| `findActiveByProperty(access, typeCode, key, values)` | 유형을 볼 수 없거나 `key`가 가려야 하는 민감 속성이면 빈 목록이다. 찾기 결과로 민감 값을 알아낼 수 없게 한다. |

- 돌려주는 `ScopedObject`의 `properties`는 가린 뒤의 값이고, 가린 속성 코드는 `maskedPropertyCodes`에 있다.
- `displayName`도 객체 조회(076~078)와 같은 규칙으로 가린다. 대표 속성이 민감 속성이 된 뒤 남은 옛 표시 이름은, 가려야 하면 대표 속성(일반일 때) → 가리지 않는 원천 키 → `#id` 순으로 다시 만든다. 이 SQL 조각은 `ObjectDisplaySql`에 있고 `ObjectQueryRepository`와 같이 쓴다.
- 그룹이 없는(미분류) 유형은 일반 역할에게 보이지 않으므로 이 진입점으로도 읽히지 않는다.
- `object_instance`는 Entity가 없는 표이고 표시 이름 가림이 SQL 조각이라 JdbcClient로 읽는다.

## 우회 금지 시험 (`ObjectInstanceReadBoundaryTests`)

범위 검사는 읽는 쪽이 직접 불러야 동작한다. 그래서 `object_instance`를 SQL로 읽는 파일을 세 목록으로 고정하고, 목록 밖의 파일이 읽거나 목록의 파일이 더는 읽지 않으면 실패한다.

| 목록 | 뜻 |
|---|---|
| `SCOPED` | 범위를 적용해 돌려주는 곳(`ScopedObjectReader`, 객체 조회, Agent 조건 값 종류) |
| `INTERNAL` | 객체 값을 바로 응답에 담지 않는 곳(수집 쓰기, 검사 배치, 연결 재계산, ID·유형·건수만 읽는 확인). 검사 배치(`CheckObjectReader`)가 읽은 값은 사본으로 저장돼 문제·검사 결과로 나간다. 그 사본은 돌려줄 때 아래 "저장된 사본 (403)"대로 가린다 |
| `TO_MIGRATE` | 공통 진입점으로 옮겨야 하는 곳. 옮기면 목록에서 지운다 |

새로 `object_instance`를 읽어야 하면, 사용자 요청 경로는 `ScopedObjectReader`를 쓰고, 내부 작업이면 이유와 함께 `INTERNAL`에 올린다.

한계: 문자열을 이어 붙여 만든 SQL(표 이름을 변수로 받는 참조 검사 등)은 찾지 못한다. 그런 곳은 값이 아니라 존재 여부만 읽어야 한다.

## 원천 데이터 정책 (391)

원천 표·컬럼을 세 등급으로 나눈다. 목록은 `application.yml`의 `axcore.source-data-policy` 한 곳에 두고(`SourceColumnPolicy`), 원천 계정 권한 스크립트(395)와 같은 목록을 유지한다. 항목은 `스키마.표.컬럼`, 표 전체는 `스키마.표.*`이고 대소문자는 보지 않는다. 두 목록에 다 있으면 금지가 이긴다.

| 등급 | 컬럼 | 다루는 법 |
|---|---|---|
| 금지(FORBIDDEN) | `erp.attendance.*`, `erp.fixed_asset.*`, `erp.employee.{emp_name, position, hire_date, employment_type, status}`, `mes.worker.name` | 들이지 않는다. 매핑·표본·적재 모두 막는다 |
| 민감(RESTRICTED) | `mes.maintenance_order.technician`, `erp.vendor.contact_name`, `mes.worker.skill_level` | 들이지만 민감 속성으로만 담는다 |
| 일반 | 그 밖 | 제한 없음 |

적용하는 곳:

| 경로 | 금지 | 민감 |
|---|---|---|
| 객체 매핑 생성·수정(`ObjectMappingService`) | 키 컬럼이 금지면 422(`details.forbiddenColumns`) | 키 컬럼이 민감이면 422(`details.reason` RESTRICTED_KEY). 키 값은 원천 키·표시 이름 대체값으로 남는데, 원천 키 가림은 그 컬럼에 민감 속성이 매핑돼 있을 때만 동작한다 |
| 속성 매핑 생성·수정(`PropertyMappingService`) | 422 | 속성을 민감으로 올린다. 대표 속성이었으면 대표를 비운다(표시 이름은 원천 키). 식별 속성이나 쓰는 연결 규칙의 조건(키·출발·도착 조건)에 쓰는 속성이면 422(`details.reason` IDENTIFIER·LINK_KEY). 올렸으면 그 속성을 담는 켜진 매핑의 표를 다시 읽는다(같은 값을 다시 저장해도) |
| 속성 생성·수정(`PropertyService`) | — | 민감 속성의 값이 객체에 하나라도 남아 있으면 일반으로 내리면 422(`details.reason` RESTRICTED_VALUES_REMAIN, `objectCount`·`objectIds`, 401). 매핑이 없어도, 사람이 지정한 민감 속성이어도 같다. 요청에 `clearRestrictedValues: true`를 보내면 그 유형의 모든 객체에서 값을 지우고 내리며, 켜진 매핑의 표는 다시 읽어 일반 값으로 채운다. 민감 속성을 매핑의 키 컬럼에 둔 채 올리면 422(RESTRICTED_KEY). 민감 컬럼이 매핑된 속성을 일반으로 내리면 422. 내리려면 정책 목록을 바꾼다. 식별 속성·연결 규칙 조건에 쓰는 속성을 민감으로 바꾸면 422. 일반에서 민감으로 올리면 그 속성을 담는 켜진 매핑의 표를 다시 읽는다 |
| 제안 승인(`ApplyWriter` → 위 서비스) | 매핑 서비스와 같이 거절(`APPLY_PAYLOAD_INVALID`), 묶음 전체를 되돌린다 | 위 서비스와 같다. 대표로 고른 속성이 민감이면 승인은 그대로 하고 대표만 비운다(`summary.skippedDisplayPropertyCode`) |
| 연결 규칙 승인(`LinkRuleApplier`) | — | 민감 속성은 조건 키와 출발·도착 조건(`{property, equals}`)에 쓸 수 없다. 조건 값이 규칙 정의에 그대로 남는다 |
| 전체 적재·CDC 반영(`FullLoadPlanner`) | 켜진 매핑이 금지 표·컬럼을 가리키면 그 표 작업이 실패한다. CDC도 같은 계획을 써서 Kafka에 남은 옛 이벤트도 들어오지 않는다 | 민감 컬럼을 일반 속성이 담거나 키로 쓰면 실패한다. 기본값 NORMAL을 분류가 끝난 것으로 보지 않고 적재 때 다시 본다(391 이전 매핑 등) |
| 유형 그룹 지정(024, `ObjectTypeService`, 402) | 그 유형의 켜진 매핑에 위반(아래 세 가지)이 하나라도 있으면 그룹을 새로 지정하거나 바꿀 수 없다(422 `details.field` accessGroup, `details.violations`) | 같다 |
| 원천 표본(`SourceSampleJob`) | 금지 표는 실패, 금지 컬럼은 빼고 읽는다 | 빼고 읽는다(표본은 원천 값을 그대로 보여 준다) |
| AI 분석 접수(018, `AnalysisCreateService`) | 금지 표는 422(`details.forbiddenColumns` `["*"]`). 금지·민감을 빼면 남는 컬럼이 없어도 422(`details.reason` NO_ANALYZABLE_COLUMNS) | 고른 컬럼에 섞여 있어도 접수한다(빼는 일은 시작 알림이 한다) |
| AI 분석 시작 알림(018 하단, `AnalysisStartService`) | 금지 컬럼을 컬럼 목록·등록 메타데이터의 컬럼 설명에서 뺀다. 금지 표면 AI를 부르지 않고 실행이 FAILED다 | 금지와 같이 뺀다. 외부 모델에 보내지 않는 값이다 |
| AI 제안 수신(105, `AiProposalSourcePolicy`) | 근거의 예시 값을 지운다. 금지 표·컬럼을 매핑하는 후보·선택지는 보류 권고와 `evidence.sourcePolicy.forbiddenColumns`로 표시한다 | 근거의 예시 값을 지운다. 매핑은 그대로 둔다(들일 수 있는 컬럼이다) |
| 매핑 미리보기(040·102, `MappingPreviewPlanner`) | 금지 표·키 컬럼·속성 컬럼이면 422(`details.forbiddenColumns`) | 민감 컬럼은 422(`details.restrictedColumns`), 민감 속성(사람이 지정한 것 포함)은 422(`details.reason` RESTRICTED_PROPERTY). 미리보기는 관리자만 쓰고 원천 값을 그대로 보여 주는데 관리자는 민감 값을 볼 수 없다. 요청 접수와 배치가 같은 확인을 거쳐 원천을 읽기 전에 막는다 |
| 후보 시험(`CandidateCheckJob`, 400) | 시험할 매핑의 표·키·속성 컬럼 중 금지가 있으면 원천을 읽지 않고 `FAILED`. `error_message`에 금지 표·컬럼 이름만 남는다 | 출발·도착 조건에 민감 속성을 쓰면 읽지 않고 `FAILED`. 조건 키가 민감이면 시험은 하고 건수·분류는 저장하지만 민감 값과 원천 키는 표본(`sample_rows`)에 남기지 않는다. 자세한 규칙은 [check-run.md](check-run.md) "후보 시험과 원천 데이터 정책" |
| 연결 규칙 후보 접수(049 수동 추가·수정, `ProposalPayloadValidator`, 400) | — | 이미 있는 속성 중 민감 속성을 조건 키나 출발·도착 조건에 쓰면 400(`details.field` = 그 조건의 `sourceProperty`·`targetProperty`, `sourceFilter.property`·`targetFilter.property`). 승인 때도 거절되지만 후보 시험 전에 알린다 |

- **저장된 값 기준 하한(401):** 매핑을 끄고 지운 뒤 내리는 우회를 막는다. 꺼진 매핑의 객체와 원천에서 지워진 객체에는 값이 남고, 다시 읽기로는 비워지지 않는다. 그래서 내릴 때 남은 값을 지우기(`clearRestrictedValues`)를 명시해야 하고, 서버가 원천 자원을 잠근 채 값을 지운 뒤 내린다. 값이 하나라도 있으면 늘 이 방법을 쓴다. 매핑을 옮기고 다시 읽어도 새 컬럼 값이 같은 속성에 들어오므로 지우기 없이는 계속 422다. 지운 값은 켜진 매핑의 다시 읽기로만 돌아오고, 꺼진 매핑·원천에서 지워진 객체의 값은 돌아오지 않는다. 속성 삭제(029)는 값이 남은 객체를 참조로 보고 원래부터 막는다.
- **민감 속성은 키 컬럼에 두지 않는다(401):** 키 값은 원천 키·표시 이름으로 남는데, 원천 키 가림은 그 키 컬럼에 민감 속성이 매핑돼 있을 때만 동작한다. 그래서 속성 매핑 생성·수정, 민감으로 올리기(028과 매핑 저장 때 원천 정책으로 올리기 모두), 객체 매핑의 키 변경·다시 켜기에서 막는다(`RestrictedKeyGuard`, `details.reason` RESTRICTED_KEY). 401 이전에 만든 설정이 남아 있어도 객체 매핑 끄기·행 조건 수정은 막지 않는다. 속성 매핑 저장은 속성 행을 잠가 028과 동시에 커밋되지 않게 한다. 391이 민감 등급 원천 컬럼을 키로 쓰지 못하게 한 원칙을 사람이 지정한 민감 속성까지 넓힌 것이다.
- 한계: 실행 기록(입력·변경 내역)과 재배정 스냅샷은 지금의 민감도로 가린다. 그래서 속성을 일반으로 내리면 과거 기록에 남은 그 속성 값도 보인다. 기록의 무결성을 위해 고치지 않는다.
- 속성 민감도는 매핑된 원천 컬럼 등급보다 낮을 수 없다. 반대로 일반 컬럼만 담는 속성을 민감으로 올리는 것은 된다.
- 식별·연결 키·대표 속성과 매핑 키 컬럼은 민감이 될 수 없다. 객체를 찾고 잇는 값이라 가리면 조회·연결이 끊기고, 가리지 않으면 민감 값이 연결 근거·원천 키·규칙 정의로 드러난다. 대표만은 비우고 원천 키로 대신할 수 있어 비운다.
- 매핑 저장이 정책 때문에 속성을 민감으로 올리거나 대표를 비우면, 그 변경도 같은 편집·승인의 감사 기록(entity_change)에 남는다(`DefinitionSideEffects`).
- **391 이전 매핑과 CDC:** 민감 컬럼을 담는 일반 속성이 있으면 적재 계획이 실패하고, CDC 반영은 그 표의 이벤트를 건너뛰고 위치를 넘긴다(`CdcTransactionApplier`). 그동안의 변경·삭제는 빠진다. 그래서 속성을 민감으로 올리는 두 경로(028, 매핑 저장 때 정책으로 올림)가 그 속성을 담는 켜진 매핑의 표를 다시 읽게 한다(`MappedResourceReload`). 잠금은 원천 자원(ID 순서) → 속성 정의 순서다. 매핑 저장이 속성을 올릴 수 있으면(일반 속성에 민감 컬럼) 다시 읽을 표까지 처음부터 함께 ID 순서로 잠가, 수집(`SyncRunJob`)과 서로 기다리다 멈추지 않는다.
- 정책 목록을 바꾸면 앱을 다시 띄워야 한다. 이미 들어온 금지 값은 지우지 않는다. 지우고 다시 들이는 일은 397이다.
- **AI 분석(399):** AI 서버는 받은 컬럼과 기본키만 원천에서 읽고, 컬럼 목록이 없거나 비어 있으면 표 전체를 읽는다. 그래서 정책 항목이 있는 표(`SourceColumnPolicy.coversTable`)는 컬럼을 고르지 않아도 카탈로그의 현재 컬럼에서 금지·민감을 뺀 목록을 늘 보낸다. 정책 항목이 없는 표는 예전처럼 목록 없이 보낸다. 빈 목록은 보내지 않는다(위의 422). 시작 알림도 정책을 다시 보므로, 정책을 바꾸기 전에 접수된 실행에도 적용된다. AI는 고르지 않은 기본키도 읽으므로 기본키는 정책 목록에 넣으면 안 된다. 지금 목록에는 없다(정책 표의 기본키는 `erp.employee.emp_no`·`erp.vendor.vendor_code`·`mes.worker.id`·`mes.maintenance_order.mo_no`, `ai/app/dataset/schema.py` 기준).
- **AI 제안 근거(399):** 근거 항목(`evidence` 어디에 있든 `locator`를 가진 객체)의 위치가 `스키마.표.컬럼`·`스키마.표.a+b`로 금지 표나 금지·민감 컬럼을 가리키면 `values`를 비우고 `snippet`을 "원천 데이터 정책상 예시 값을 저장하지 않습니다."로 바꾼다. 위치는 남긴다. 제안 이유·제목 같은 자유 문장은 고치지 않는다. 문장 안의 값은 위치를 알 수 없어서다. 위 018 처리로 AI가 그 컬럼을 읽지 않으므로 이 단계는 두 번째 방어다. AI 서버의 하드코딩 가림 목록(`ai/app/dataset/schema.py` 등)은 그대로 두고, 기준은 이 정책 하나다(정리는 406).
- 사람 코드(`emp_no`·`worker_code`)는 일반이다. 식별자와 연결 키라 가리면 연결이 끊긴다. 이름 등 다른 값과 다시 묶을 수 없도록 그쪽을 금지로 둔다.

### 정리 → 공개 순서와 위반 보고 (402)

정리보다 공개가 먼저 되어 금지 값이 화면과 외부 모델로 나간 일(398)이 다시 생기지 않게 서버가 순서를 지킨다. 지금 들어 있는 데이터는 397에서 정책에 맞추거나 다시 들인다고 보고, 앞으로의 경로만 막는다.

- **위반의 정의**(`SourcePolicyViolation`, 적재 계획과 같은 판단 `FullLoadPlanner.violations`): 켜진 매핑이 ① 금지 표·컬럼을 키나 속성으로 가리킴(`FORBIDDEN_COLUMN`) ② 민감 컬럼을 키로 씀(`RESTRICTED_KEY`) ③ 민감 컬럼을 일반 속성이 담음(`RESTRICTED_UNDER_CLASSIFIED`). 꺼진 매핑은 적재하지 않으므로 보지 않는다.
- **공개 전 검사:** 024가 그룹을 새로 지정하거나 다른 그룹으로 바꿀 때 그 유형에 위반이 있으면 거절한다. 그룹을 비우는 것, 그룹을 그대로 둔 다른 수정은 막지 않는다(공개 뒤 정책이 바뀌어 위반이 생겨도 비울 수 있어야 한다). 023(생성)과 승인 반영은 유형을 새로 만들 때라 아직 매핑이 없고, 뒤이어 만드는 매핑은 위 표의 서비스가 거절한다.
- **위반 보고 API:** `GET /api/v1/sources/policy-violations`(ADMIN). 켜진 매핑의 위반 목록(`violations`: 종류, 원천 테이블·컬럼, 객체 매핑·속성 매핑, 유형 코드와 그룹, 속성)과, 켜진 매핑이 있지만 적재 계획을 만들 수 없는 테이블(`blockedResources`: 사유, 다시 읽기 대기 여부)을 돌려준다. `policyVersion`은 정책 목록 지문이다. 정책 파일을 바꾸거나 배포하기 전, 유형을 공개하기 전에 `violations`가 비었는지 본다. 테이블마다 계획을 만들어 보므로 가끔 부르는 관리용 조회다.
- **막힌 테이블:** 적재 계획을 만들 수 없는 테이블의 CDC 변경은 건너뛰고 다시 읽기를 표시한다. 감시 작업이 한 번 넣어 사유를 수집 실패로 남기고, 고치면 다시 읽는다. 복구 중인 원천은 이 테이블을 기다리지 않는다. 자세한 것은 [CDC 소비](cdc-consumer.md) "적재할 수 없는 테이블"이다.

## Agent가 읽는 값 (MODEL 용도)

Agent 도구 결과는 외부 언어 모델의 입력이 된다. 그래서 Agent는 같은 조회 서비스를 `DataPurpose.MODEL`로 부른다. 민감 값을 볼 수 있는 역할이 물어도 모델에는 가린 값이 간다.

| 서비스 | 화면(VIEW) | Agent(MODEL) |
|---|---|---|
| `ObjectSearchQuery` 검색·값으로 찾기 | `search(actor, request)` | `search(actor, MODEL, request)`. 민감 속성 값으로는 찾지 못함 |
| `ObjectSearchQuery` Object Set(380) ID 검색·ID로 읽기 | `searchIds(actor, …)`, `findByIds(actor, …)` | `searchIds(actor, MODEL, …)`, `findByIds(actor, MODEL, …)`. `findAvailableIds`는 ID만 돌려줘 용도와 관계없음 |
| `ObjectDetailQuery` 상세·관계·묶음·주변 탐색 | `detail(actor, id)` 등 | `detail(actor, MODEL, id)` 등 |
| `ObjectEvidenceQuery` 관계 근거 | `linkEvidence(actor, …)` | `linkEvidence(actor, MODEL, …)` |
| `PropertyValueSampleService` 값 종류 | — | `stringValues(actor, MODEL, …)`. 민감 속성은 넣지 않음 |
| 모델 요약·경로 계획·완료 시각 계산 | — | `DataAccessPolicy.forModel(asker)` |

- 용도를 받지 않는 기존 메서드는 화면(VIEW)과 같다. 두 메서드 모두 트랜잭션을 건다(프록시를 거치지 않는 위임으로 트랜잭션이 빠지지 않게).
- `DataAccessPolicy.of(actor, purpose)`는 VIEW·MODEL만 받는다. 정의 조회 범위는 `forDefinitions()`로만 만든다.
- Agent의 실행 이력 조회(감사)와 Agent가 실행한 Action의 문구는 김산이 담당 범위다(아래).

## Action·재배정·감사 응답 (390)

| 경로 | 범위 | 가림 |
|---|---|---|
| 091 실행(재배정 Handler) | 대상 객체는 공통 실행기가, 검사가 더 읽는 작업지시·공정·공정 표준은 Handler가 실행자의 범위로 읽는다(`ReassignmentObjectRepository` → `ScopedObjectReader`) | Agent가 실행하면 `ActionContext.dataPurpose()`가 `MODEL`이라 차단 문구에 민감 값이 들어가지 않는다 |
| 092 Action 결과 | 실행자 본인·ADMIN | input·변경 내용을 097·098과 같은 `AuditValueMasker`로 가리고 `restrictedPropertyCodes`를 준다. result는 Handler가 만든 ID·건수다 |
| 093~095 재배정 계획 | 설비·작업지시·공정 유형을 모두 볼 수 있어야 계획이 보인다(아니면 목록은 비고 한 건은 404) | 항목의 공정 코드와 스냅샷을 Handler가 변경 기록에 알린 자리로 가린다(`AuditValueMasker.maskRow`) |
| 096·080 실행 이력 목록 | 기존과 같음 | 대상 표시 이름을 볼 수 있는 유형만, `ObjectDisplaySql`로 가린다. Agent는 080을 `MODEL`로 부른다 |
| 097·098 실행 상세·변경 | 기존과 같음 | ADMIN도 허용 행대로 가린다(예전엔 ADMIN은 원본) |

- 재배정 검사에 필요한 값이 보는 사람에게 가려져 있으면 그 검사는 "속성이 없음"으로 막힌다. 값을 알려 주지 않고 실패하는 쪽을 택했다.
- 스냅샷에 들어가는 자리는 Handler가 정한 것(작업지시 번호·상태, 공정 순서·설비 코드·상태)뿐이고, 그 자리는 `recordedPropertyPaths`로 알린다. 새 자리를 스냅샷에 넣으면 같이 알려야 가려진다.

## 역할별 범위 (V21)

역할 이름으로 나누지 않고 `role_data_scope` 허용 행으로만 정한다. ADMIN도 같다(정의·시스템 관리와 값 열람을 나눈다). 정의 조회 용도(`forDefinitions`)만 역할과 관계없이 모든 유형의 정의를 본다.

| 역할 | PRODUCTION | SALES_SUPPLY | 민감 값 |
|---|---|---|---|
| ADMIN | 봄 | 봄 | 못 봄 |
| PLANNER | 봄 | 봄 | 봄 |
| VIEWER | 봄 | 봄 | 못 봄 |
| MAINTENANCE | — | — | 미구성(행 없음) |

- 그룹이 없는(미분류) 유형은 어느 역할에도 보이지 않는다. API 024로 그룹을 지정해야 탐색기·Agent에 나온다. 그 유형의 켜진 매핑이 원천 데이터 정책을 어기면 지정할 수 없다(402).
- 역할을 추가하거나 MAINTENANCE를 쓰려면 이 표에 행을 넣는 마이그레이션 하나면 된다.
- 원천에서 삭제된 객체 조회 같은 기능 권한은 아직 역할 이름으로 검사한다. 권한 코드로 바꾸는 일은 Jira 258이다.

## 민감 값 열람 기록 (394)

가리지 않은 민감(RESTRICTED) 값을 사람에게 내보낸 응답을 `action_execution`에 `RESTRICTED_VIEW`로 남긴다(V23). 개인정보 처리 시스템의 접속 기록이다(`RestrictedViewRecorder`).

| 항목 | 내용 |
|---|---|
| 언제 | 응답에 가리지 않은 민감 값(null 아님)이 하나라도 있을 때, 응답 한 번에 한 행. 가렸거나 값이 비었으면 남기지 않는다 |
| 경로(`input.view`) | 077 `OBJECT_DETAIL`, 076 `OBJECT_SEARCH`, 값으로 찾기 `OBJECT_VALUE_SEARCH`, ID로 읽기 `OBJECT_BY_IDS`, 092 `ACTION_RESULT`, 093~095 `REASSIGNMENT_PLAN`, 097 `AUDIT_DETAIL`, 098 `AUDIT_CHANGES`. 저장된 사본(403): 065 `ISSUE_SEARCH`, 066 `ISSUE_DETAIL`, 067 `ISSUE_STATUS`, 068 `ISSUE_FORWARD`, 070 `ISSUE_VERIFICATIONS`, 062 `CHECK_RESULTS`, 재검증 비교 `CHECK_COMPARISON`, 제안 목록 `PROPOSAL_SEARCH`, 049 `PROPOSAL_DETAIL`, 선택지 `PROPOSAL_OPTIONS`, 개정 이력 `PROPOSAL_REVISIONS`, 020 `ANALYSIS_PROPOSALS` |
| 남기는 것 | 요청자와 역할 스냅샷, 진입 경로(WEB), `result.seen`(대상 종류·ID·필드 코드), `result.policyVersion`(원천 데이터 정책 목록 지문). 대상이 하나면 `target_type`·`target_id`에도 둔다 |
| 남기지 않는 것 | 값 원문 |
| 실패 | 기록이 실패하면 응답도 실패한다. 접속 기록 없이 민감 값을 내보내지 않는다. 기록은 응답 트랜잭션과 따로 커밋한다 |
| 보는 사람 | ADMIN이 096에서 `eventKind=RESTRICTED_VIEW`로 골라 볼 때만(097·098도 ADMIN만 연다). 기본 목록, 다른 역할의 본인 기록, 080 객체별 이력, 092, Agent에는 나오지 않는다 |
| 보존 | 248(로그 보존 정책)이 정해지기 전까지 `action_execution`과 같다 |

- 기록 위치는 응답을 만드는 서비스다. 공통 가림(`DataAccess.mask`, `AuditValueMasker`)은 보여 준 민감 코드(`revealedPropertyCodes`)만 알려 준다. Action 검증처럼 값을 사람에게 내보내지 않는 내부 읽기는 남기지 않는다.
- 기록할 일이 없는 경로: Agent는 모델 용도라 늘 가린다(그래서 경로 AGENT는 생기지 않는다). 값 표본(`PropertyValueSampleService`)은 Agent만 쓴다. 미리보기·원천 표본은 민감 값을 읽지 않는다.
- 저장된 사본(403)도 같은 규칙으로 남긴다. 391부터 연결 키·원천 키는 민감일 수 없어 보통은 남을 일이 없다. 다만 391 이전 사본, 폐기된 규칙 개정본, 저장 뒤 민감으로 올린 속성의 사본은 민감 값일 수 있다. 지금 이 API를 쓰는 ADMIN은 민감 값을 볼 수 없어(V21) 늘 가려지고 기록도 남지 않는다. ADMIN 허용 행이 민감 값을 열면 그때부터 남는다. 대상 종류는 `issue`·`check_result`·`check_run`(비교는 재검증 실행)·`proposal_item`이고, 필드는 `유형코드.속성코드`다.
- 한계: 응답에 담긴 값을 기준으로 남긴다. 화면이 실제로 보여 줬는지는 모른다. 기록 뒤 응답 직렬화가 실패하면 기록만 남는다(넘치게 남는 쪽).

## 저장된 사본 (403)

객체 밖에 복사해 둔 객체 값도 객체와 같은 기준으로 돌려준다. ADMIN은 민감 값을 볼 수 없는 역할이라(V21) "ADMIN 전용 API"라는 것만으로는 안전하지 않다. 저장할 때가 아니라 **돌려줄 때** 지금의 정의와 보는 사람의 범위로 가린다. 그래서 저장 뒤 속성을 민감으로 올리거나 유형 그룹을 바꿔도 옛 사본이 그대로 나가지 않는다. 사본을 지우거나 다시 쓰지는 않는다.

| 사본 | API | 가리는 칸 | 기준이 되는 규칙 |
|---|---|---|---|
| 문제 | 065·066·067·068 | `rawValue`, `transformedValue`, `evidence.transformedValues` | 문제의 `linkMappingId` 개정본 |
| 재검증 기록 | 070 | `detail.rawValue`, `detail.transformedValue` | `detail.linkMappingId` 개정본. 없는 옛 기록은 가린다 |
| 검사 결과 표본 | 062 | `sampleRows[].rawValue`·`transformedValue`·`sourceKey`·`targetKey` | 승인 규칙이면 결과의 `linkMappingId`, 후보 시험이면 그 검사 실행이 시험한 후보 개정본 |
| 재검증 비교 | 059 하위 `/comparison` | `before`·`after`의 `rawValue`·`transformedValue` | 근거의 `linkMappingId` 개정본 |
| 제안 근거 | 제안 목록·049 상세·선택지·개정 이력·020 분석별 제안 | 근거 항목(`locator`를 가진 객체)의 `values`·`snippet` | 위치(`스키마.표[.컬럼]`)가 지금 담기는 유형·속성 |

**검사 값(`StoredCheckValues`):** 규칙의 출발·도착 유형을 모두 볼 수 있고, 비교 조건의 출발·도착 속성이 모두 가릴 값이 아닐 때만 비교값과 원천 키를 보여 준다. 하나라도 아니면 모두 비운다. 대응한 행은 양쪽 비교값이 같아서 한쪽만 비우면 다른 쪽 값과 원천 키로 알아낼 수 있기 때문이다(후보 시험 400과 같은 기준). 값을 보여 줄 때도 원천 키는 `ObjectDisplaySql`과 같은 기준으로 가린다. 보는 사람이 그 유형의 민감 값을 볼 수 없으면, 일반 속성에만 매핑돼 있다고 확인된 키 컬럼만 보여 주고, 매핑이 없거나(지운 매핑 포함) 민감 속성에 매핑된 키 컬럼의 값은 비운다. 같은 이름의 컬럼이 그 유형의 다른 원천 표에서 민감 속성에 매핑돼도 비운다. 규칙을 찾지 못하거나 후보를 읽을 수 없으면 모두 비운다. 후보 시험의 유형이 아직 만들지 않은 후보 유형이면 그 쪽에는 범위가 없으므로, 400이 저장할 때 원천 정책으로 가린 그대로 둔다.

**제안 근거(`ProposalEvidenceScope`):** 위치가 볼 수 없는 유형이 담는 표이거나, 보는 사람이 볼 수 없는 민감 속성이 담는 컬럼(묶은 컬럼 `a+b`는 하나라도)이면 `values`를 비우고 `snippet`을 "조회 범위상 예시 값을 보여 주지 않습니다."로 바꾼다. 위치는 남긴다. 꺼진 매핑도 본다. 아직 어느 유형에도 매핑되지 않은 표·컬럼은 원천 데이터 정책(105 수신 때 가림, 399)만 따른다. 원천 표본(225)과 같은 수준이다.

- 가린 칸 이름은 `restrictedFields`(이름순)에 둔다. 값이 원래 없어 `null`인 것과 가린 것을 화면이 나눌 수 있게 한다. 062는 후보 시험이 저장 때 비운 칸과 합친다. 제안 근거는 399와 같은 모양(빈 `values`, 고정 `snippet`)으로 알린다.
- 판정 분류·사유(`classification`·`reason`·`failureReason`)와 객체 ID, 건수는 남긴다. 사유 문장에는 속성 코드만 들어가고 값은 없다.
- 지금 이 API는 모두 ADMIN 전용이라 VIEWER·PLANNER는 403이다. 범위는 역할 이름이 아니라 허용 행으로 정하므로, 나중에 다른 역할에 열어도 같은 기준이 적용된다.
- 가리지 않은 민감 값을 내보내면 열람 기록(394)을 남긴다. 위 "민감 값 열람 기록"을 본다. 067·068은 문제 행을 잠근 채 응답을 만들므로 기록이 상태 변경보다 먼저 따로 커밋된다. 기록이 실패하면 상태 변경도 되돌리고, 기록 뒤 상태 변경 커밋이 실패하면 기록만 남는다(넘치게 남는 쪽).
- 제안 근거에서 항목을 하나라도 가렸으면 그 항목을 품은 근거(와 그 위)의 자유 문장·문서 칸도 "조회 범위상 예시 값이 든 설명을 보여 주지 않습니다."로 바꾼다. 대상은 `reason`·`holdReason`·`llmJudgement`(AI가 원천 값 예시를 넣기도 함), 문서 연결 근거의 `originalText`·`documentLabel`·`observations[].text`·`times[].text`·`targetLabels` 값이다. 칸 이름과 다른 칸은 남긴다.
- 개정 저장(`POST /proposals/{id}/revisions`): 화면은 가린 근거(049·선택지 응답)를 고쳐 통째로 보낸다. 그대로 저장하면 가린 문구가 원본을 영구히 덮으므로, 요청 근거의 각 자리가 "저장된 근거를 이 사용자 기준으로 가린 값"과 같으면 그 자리는 저장된 원본으로 되돌린다(`ProposalEvidenceScope.restore`). 사람이 실제로 바꾼 자리(예: `reason`)는 바꾼 값을 쓴다. 선택지 근거는 지금 개정본의 같은 순서 선택지와 맞춘다. 한계: 목록 길이를 바꾼 자리(항목을 빼거나 더함)는 그 목록을 요청대로 저장하므로, 그 안에 남은 가린 항목은 가린 모양으로 저장된다.
- 한계: 가린 항목이 없는 근거의 문장, 제목, payload(정의 후보, 연결 규칙 후보의 행 조건 값 포함)는 고치지 않는다. 문장 안의 값은 위치를 알 수 없어서다. 민감 속성은 행 조건에 쓸 수 없으므로(400) payload의 행 조건 값은 일반 값이다.
- 한계: 원천 표본(225)·매핑 미리보기(040·102)는 이 기준이 아니라 원천 데이터 정책만 따른다. 그룹이 없는 유형에 매핑된 표의 일반 컬럼도 ADMIN에게 보인다.
- 시험: `StoredCopyScopeApiTests`(ADMIN이 일반 값은 보고, 민감 속성을 조건으로 쓴 규칙·그룹 없는 유형의 값은 문제·재검증 기록·검사 결과·후보 시험·재검증 비교·제안 근거에서 못 봄, VIEWER 403, 민감 값을 여는 허용 행이면 값과 열람 기록).

## 아직 남은 것 (384 다른 작업)

- 그룹·민감도 지정 경로(392), 검증(396), 재적재(397)
