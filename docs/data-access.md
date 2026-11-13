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
| `INTERNAL` | 사용자 응답에 객체 값을 내보내지 않는 곳(수집 쓰기, 검사 배치, 연결 재계산, ID·유형·건수만 읽는 확인) |
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
| 속성 생성·수정(`PropertyService`) | — | 민감 컬럼이 매핑된 속성을 일반으로 내리면 422. 내리려면 정책 목록을 바꾼다. 식별 속성·연결 규칙 조건에 쓰는 속성을 민감으로 바꾸면 422. 일반에서 민감으로 올리면 그 속성을 담는 켜진 매핑의 표를 다시 읽는다 |
| 제안 승인(`ApplyWriter` → 위 서비스) | 매핑 서비스와 같이 거절(`APPLY_PAYLOAD_INVALID`), 묶음 전체를 되돌린다 | 위 서비스와 같다. 대표로 고른 속성이 민감이면 승인은 그대로 하고 대표만 비운다(`summary.skippedDisplayPropertyCode`) |
| 연결 규칙 승인(`LinkRuleApplier`) | — | 민감 속성은 조건 키와 출발·도착 조건(`{property, equals}`)에 쓸 수 없다. 조건 값이 규칙 정의에 그대로 남는다 |
| 전체 적재·CDC 반영(`FullLoadPlanner`) | 켜진 매핑이 금지 표·컬럼을 가리키면 그 표 작업이 실패한다. CDC도 같은 계획을 써서 Kafka에 남은 옛 이벤트도 들어오지 않는다 | 민감 컬럼을 일반 속성이 담거나 키로 쓰면 실패한다. 기본값 NORMAL을 분류가 끝난 것으로 보지 않고 적재 때 다시 본다(391 이전 매핑 등) |
| 원천 표본(`SourceSampleJob`) | 금지 표는 실패, 금지 컬럼은 빼고 읽는다 | 빼고 읽는다(표본은 원천 값을 그대로 보여 준다) |
| AI 분석 접수(018, `AnalysisCreateService`) | 금지 표는 422(`details.forbiddenColumns` `["*"]`). 금지·민감을 빼면 남는 컬럼이 없어도 422(`details.reason` NO_ANALYZABLE_COLUMNS) | 고른 컬럼에 섞여 있어도 접수한다(빼는 일은 시작 알림이 한다) |
| AI 분석 시작 알림(018 하단, `AnalysisStartService`) | 금지 컬럼을 컬럼 목록·등록 메타데이터의 컬럼 설명에서 뺀다. 금지 표면 AI를 부르지 않고 실행이 FAILED다 | 금지와 같이 뺀다. 외부 모델에 보내지 않는 값이다 |
| AI 제안 수신(105, `AiProposalSourcePolicy`) | 근거의 예시 값을 지운다. 금지 표·컬럼을 매핑하는 후보·선택지는 보류 권고와 `evidence.sourcePolicy.forbiddenColumns`로 표시한다 | 근거의 예시 값을 지운다. 매핑은 그대로 둔다(들일 수 있는 컬럼이다) |
| 매핑 미리보기(040·102, `MappingPreviewPlanner`) | 금지 표·키 컬럼·속성 컬럼이면 422(`details.forbiddenColumns`) | 민감 컬럼은 422(`details.restrictedColumns`), 민감 속성(사람이 지정한 것 포함)은 422(`details.reason` RESTRICTED_PROPERTY). 미리보기는 관리자만 쓰고 원천 값을 그대로 보여 주는데 관리자는 민감 값을 볼 수 없다. 요청 접수와 배치가 같은 확인을 거쳐 원천을 읽기 전에 막는다 |
| 후보 시험(`CandidateCheckJob`, 400) | 시험할 매핑의 표·키·속성 컬럼 중 금지가 있으면 원천을 읽지 않고 `FAILED`. `error_message`에 금지 표·컬럼 이름만 남는다 | 출발·도착 조건에 민감 속성을 쓰면 읽지 않고 `FAILED`. 조건 키가 민감이면 시험은 하고 건수·분류는 저장하지만 민감 값과 원천 키는 표본(`sample_rows`)에 남기지 않는다. 자세한 규칙은 [check-run.md](check-run.md) "후보 시험과 원천 데이터 정책" |
| 연결 규칙 후보 접수(049 수동 추가·수정, `ProposalPayloadValidator`, 400) | — | 이미 있는 속성 중 민감 속성을 조건 키나 출발·도착 조건에 쓰면 400(`details.field` = 그 조건의 `sourceProperty`·`targetProperty`, `sourceFilter.property`·`targetFilter.property`). 승인 때도 거절되지만 후보 시험 전에 알린다 |

- 속성 민감도는 매핑된 원천 컬럼 등급보다 낮을 수 없다. 반대로 일반 컬럼만 담는 속성을 민감으로 올리는 것은 된다.
- 식별·연결 키·대표 속성과 매핑 키 컬럼은 민감이 될 수 없다. 객체를 찾고 잇는 값이라 가리면 조회·연결이 끊기고, 가리지 않으면 민감 값이 연결 근거·원천 키·규칙 정의로 드러난다. 대표만은 비우고 원천 키로 대신할 수 있어 비운다.
- 매핑 저장이 정책 때문에 속성을 민감으로 올리거나 대표를 비우면, 그 변경도 같은 편집·승인의 감사 기록(entity_change)에 남는다(`DefinitionSideEffects`).
- **391 이전 매핑과 CDC:** 민감 컬럼을 담는 일반 속성이 있으면 적재 계획이 실패하고, CDC 반영은 그 표의 이벤트를 건너뛰고 위치를 넘긴다(`CdcTransactionApplier`). 그동안의 변경·삭제는 빠진다. 그래서 속성을 민감으로 올리는 두 경로(028, 매핑 저장 때 정책으로 올림)가 그 속성을 담는 켜진 매핑의 표를 다시 읽게 한다(`MappedResourceReload`). 잠금은 원천 자원(ID 순서) → 속성 정의 순서다. 매핑 저장이 속성을 올릴 수 있으면(일반 속성에 민감 컬럼) 다시 읽을 표까지 처음부터 함께 ID 순서로 잠가, 수집(`SyncRunJob`)과 서로 기다리다 멈추지 않는다.
- 정책 목록을 바꾸면 앱을 다시 띄워야 한다. 이미 들어온 금지 값은 지우지 않는다. 지우고 다시 들이는 일은 397이다.
- **AI 분석(399):** AI 서버는 받은 컬럼과 기본키만 원천에서 읽고, 컬럼 목록이 없거나 비어 있으면 표 전체를 읽는다. 그래서 정책 항목이 있는 표(`SourceColumnPolicy.coversTable`)는 컬럼을 고르지 않아도 카탈로그의 현재 컬럼에서 금지·민감을 뺀 목록을 늘 보낸다. 정책 항목이 없는 표는 예전처럼 목록 없이 보낸다. 빈 목록은 보내지 않는다(위의 422). 시작 알림도 정책을 다시 보므로, 정책을 바꾸기 전에 접수된 실행에도 적용된다. AI는 고르지 않은 기본키도 읽으므로 기본키는 정책 목록에 넣으면 안 된다. 지금 목록에는 없다(정책 표의 기본키는 `erp.employee.emp_no`·`erp.vendor.vendor_code`·`mes.worker.id`·`mes.maintenance_order.mo_no`, `ai/app/dataset/schema.py` 기준).
- **AI 제안 근거(399):** 근거 항목(`evidence` 어디에 있든 `locator`를 가진 객체)의 위치가 `스키마.표.컬럼`·`스키마.표.a+b`로 금지 표나 금지·민감 컬럼을 가리키면 `values`를 비우고 `snippet`을 "원천 데이터 정책상 예시 값을 저장하지 않습니다."로 바꾼다. 위치는 남긴다. 제안 이유·제목 같은 자유 문장은 고치지 않는다. 문장 안의 값은 위치를 알 수 없어서다. 위 018 처리로 AI가 그 컬럼을 읽지 않으므로 이 단계는 두 번째 방어다. AI 서버의 하드코딩 가림 목록(`ai/app/dataset/schema.py` 등)은 그대로 두고, 기준은 이 정책 하나다(정리는 406).
- 사람 코드(`emp_no`·`worker_code`)는 일반이다. 식별자와 연결 키라 가리면 연결이 끊긴다. 이름 등 다른 값과 다시 묶을 수 없도록 그쪽을 금지로 둔다.

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

- 그룹이 없는(미분류) 유형은 어느 역할에도 보이지 않는다. API 024로 그룹을 지정해야 탐색기·Agent에 나온다.
- 역할을 추가하거나 MAINTENANCE를 쓰려면 이 표에 행을 넣는 마이그레이션 하나면 된다.
- 원천에서 삭제된 객체 조회 같은 기능 권한은 아직 역할 이름으로 검사한다. 권한 코드로 바꾸는 일은 Jira 258이다.

## 민감 값 열람 기록 (394)

가리지 않은 민감(RESTRICTED) 값을 사람에게 내보낸 응답을 `action_execution`에 `RESTRICTED_VIEW`로 남긴다(V23). 개인정보 처리 시스템의 접속 기록이다(`RestrictedViewRecorder`).

| 항목 | 내용 |
|---|---|
| 언제 | 응답에 가리지 않은 민감 값(null 아님)이 하나라도 있을 때, 응답 한 번에 한 행. 가렸거나 값이 비었으면 남기지 않는다 |
| 경로(`input.view`) | 077 `OBJECT_DETAIL`, 076 `OBJECT_SEARCH`, 값으로 찾기 `OBJECT_VALUE_SEARCH`, ID로 읽기 `OBJECT_BY_IDS`, 092 `ACTION_RESULT`, 093~095 `REASSIGNMENT_PLAN`, 097 `AUDIT_DETAIL`, 098 `AUDIT_CHANGES` |
| 남기는 것 | 요청자와 역할 스냅샷, 진입 경로(WEB), `result.seen`(대상 종류·ID·필드 코드), `result.policyVersion`(원천 데이터 정책 목록 지문). 대상이 하나면 `target_type`·`target_id`에도 둔다 |
| 남기지 않는 것 | 값 원문 |
| 실패 | 기록이 실패하면 응답도 실패한다. 접속 기록 없이 민감 값을 내보내지 않는다. 기록은 응답 트랜잭션과 따로 커밋한다 |
| 보는 사람 | ADMIN이 096에서 `eventKind=RESTRICTED_VIEW`로 골라 볼 때만(097·098도 ADMIN만 연다). 기본 목록, 다른 역할의 본인 기록, 080 객체별 이력, 092, Agent에는 나오지 않는다 |
| 보존 | 248(로그 보존 정책)이 정해지기 전까지 `action_execution`과 같다 |

- 기록 위치는 응답을 만드는 서비스다. 공통 가림(`DataAccess.mask`, `AuditValueMasker`)은 보여 준 민감 코드(`revealedPropertyCodes`)만 알려 준다. Action 검증처럼 값을 사람에게 내보내지 않는 내부 읽기는 남기지 않는다.
- 기록할 일이 없는 경로: Agent는 모델 용도라 늘 가린다(그래서 경로 AGENT는 생기지 않는다). 값 표본(`PropertyValueSampleService`)은 Agent만 쓴다. 정합성 검사 표본은 연결 키·원천 키만 담는데 391부터 둘 다 민감일 수 없다. 미리보기·원천 표본은 민감 값을 읽지 않는다.
- 한계: 응답에 담긴 값을 기준으로 남긴다. 화면이 실제로 보여 줬는지는 모른다. 기록 뒤 응답 직렬화가 실패하면 기록만 남는다(넘치게 남는 쪽).

## 아직 남은 것 (384 다른 작업)

- 그룹·민감도 지정 경로(392), 검증(396), 재적재(397)
