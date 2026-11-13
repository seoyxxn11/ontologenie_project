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

## 아직 남은 것 (384 다른 작업)

- 정책 파일·속성 등급 하한·금지 컬럼 매핑 차단(391), 그룹·민감도 지정 경로(392), 가드레일(393), 열람 기록(394), 원천 스크립트(395), 검증(396), 재적재(397)
