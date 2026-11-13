# 분석 연동 (FastAPI 쪽)

- 기준: 노션 "API 명세서"의 API 공통 규칙, 018 분석 실행, 105 분석 결과 내부 저장, 043 모델 요약 (모두 초안, 2026-10-27 확인)
- 후보 샘플: `results/proposals-v1.0.api.json` (data-v1.0에서 원천 정책의 금지 표·컬럼을 뺀 입력을 원천 코드 `ERP_MAIN`·`MES_MAIN`으로 분석한 105 요청 본문, LLM 없이. 원천 계정 허용 목록을 거친 입력과 같음)

## 1. 흐름

| 순서 | 처리 | 위치 |
| --- | --- | --- |
| 1 | 화면에서 분석 실행(018) → `analysis_run`을 QUEUED로 만듦 | Spring |
| 2 | FastAPI에 시작 알림 (3절) | Spring → FastAPI |
| 3 | 105로 `RUNNING` 보냄 | FastAPI → Spring |
| 4 | AI 계정으로 고른 표·컬럼만 읽어 분석 | FastAPI |
| 5 | 105로 `SUCCEEDED` + 후보, 또는 `FAILED` + 사유 | FastAPI → Spring |
| 6 | 후보 저장(검토 상태 PENDING), 화면 조회는 019·020 | Spring |

- FastAPI는 플랫폼 DB에 쓰지 않고 실행 상태도 저장하지 않습니다. 상태는 Spring의 `analysis_run`이 가집니다.
- 원천 연결 검사·표본·매핑 미리보기는 배치가 합니다. FastAPI는 AI 분석 때만 원천을 읽습니다.
- 내부 API(`/api/v1/internal/**`)는 `X-Internal-Token` 헤더를 붙여 부릅니다. 토큰 값은 환경변수 `AI_ONTOLOGY_INTERNAL_TOKEN`, 백엔드 주소는 `AI_ONTOLOGY_BACKEND_URL`입니다.

## 2. FastAPI가 부르는 백엔드 API

| API | 언제 | 보내는 것 |
| --- | --- | --- |
| 105 `POST /api/v1/internal/analyses/{id}/results` | 시작·끝·실패 | `status`, `resultSummary`, `proposals[]` 또는 `errorMessage` |
| 043 `GET /api/v1/internal/ontology/model-summary` | 분석 전 | 없음(`X-Internal-Token`). 이미 있는 정의와 같은 후보를 내지 않으려고 받음(#281). `objectTypes[].id`·`code`, `properties[].id`·`objectTypeId`·`code`, `linkTypes[].code`, `activeRules[].code`·`conditions[].sourcePropertyId`·`targetPropertyId`·`operator`·`sourceTransforms`·`targetTransforms`를 씀. 내부 토큰이 없거나 실패하면 요약 없이 계속함(`modelSummarySkipReason`) |

**105 `proposals[]` 한 건**

| 필드 | 내용 |
| --- | --- |
| `itemKind` | AI가 보내는 것은 OBJECT_TYPE · LINK_RULE · LINK_TYPE · DOCUMENT_LINK (아래 "묶음 후보"). PROPERTY · OBJECT_MAPPING · PROPERTY_MAPPING은 묶을 객체 후보가 없을 때만 따로 보냄 |
| `payload` | 후보 내용. 객체·관계 유형 코드는 대문자(`WORK_ORDER`), 속성은 컬럼 이름 그대로. `sourceId`는 `data_source.code`(예: `MES_MAIN`), `resourceKey`는 `source_resource.resource_key`(예: `mes.work_order`). 표를 가리키는 값은 늘 `sourceId` + `resourceKey`. 속성 매핑 `sourceColumn`은 `{sourceId, resourceKey, table, column}`이고 `resourceKey`가 백엔드가 원천 표를 찾는 값(`table`은 `resourceKey`의 첫 점 뒤, 이전 후보와 맞추려고 남김) |
| `evidence` | 아래 표 |
| `aiConfidence` | 0~1. 같은 근거로 나온 제안이 평가 데이터에서 실제로 맞은 비율. 매칭률과 섞지 않음 |
| `options[]` | 같은 대상에 후보가 여럿일 때 `{optionNo, payload, evidence, aiConfidence}` |

**`evidence` 안 (105는 evidence를 객체로만 받으므로 검토에 필요한 것을 여기에 담습니다)**

| 필드 | 내용 |
| --- | --- |
| `reason`, `title` | 제안 이유, 화면 제목 |
| `items[]` | 근거 `{sourceId, locator, snippet, values, verified}`. 원천에서 다시 확인한 것만 |
| `confidenceItems[]`, `confidenceCap` | 신뢰도 점수 내역, 보류 상한 `{cap, reason}` |
| `holdRecommended`, `holdReason` | AI의 보류 권고. 105는 PENDING으로 저장하므로 권고로만 남음 |
| `method`, `llmJudgement` | `rule` · `llm` · `rule+llm`, LLM 판단 문장(점수에는 넣지 않음) |
| `candidateId`, `fingerprint` | 재분석 비교·과거 결정 대조용 후보 ID와 payload 지문. 문서 연결 후보 ID는 `DL-{문서 번호}-{언급 글자}-{같은 글자 중 몇 번째}`라 LLM 답 순서와 무관 |
| `changeType` | 재분석(REANALYSIS)에서만. 이전 후보가 있으면 후보 ID·지문으로 ADDED·CHANGED·UNCHANGED를 표시합니다. 이전 후보가 없으면 043 모델과 비교하고, 모델과 같은 후보는 `changeCounts`에만 셉니다. |
| `originalText`, `observations[]`, `times[]` | DOCUMENT_LINK만. 원문 전체, 이 언급에 걸린 관찰 표현 `{text, spanStart, spanEnd, assertion}`, 글 속 시각 표현 `{text, spanStart, spanEnd, normalized, basis}`. `assertion`은 AFFIRMED(확인) · NEGATED(부정) · UNCERTAIN(추정). `normalized`는 원천에서 기준 시각을 알 때만 채움. 원문·관찰·근거 글의 이름·전화·이메일·주민번호 자리는 같은 글자 수의 `*`로 가림(위치는 원문 그대로) |
| `documentLabel`, `targetLabels` | DOCUMENT_LINK만. 화면 제목용 메모 이름(`10/29 야간 인수인계`, `정비 지시 RP-26-041 메모`)과 대상 이름 `{원천 표:키: 이름}`(`mes.equipment:P103` → `1라인 3호 프레스`). 대상 이름은 원천 표의 이름 컬럼(`name`·`*_name`·`*_nm`) 값이고, 이름 컬럼이 없는 표(작업지시 등)는 빠짐 |

**묶음 후보 (11-05~)**

관리자가 판단하는 단위로 묶어 보냅니다. 표 하나 = 객체 후보 한 건, 관계 하나 = 관계 후보 한 건입니다. 제안기 안에서는 지금처럼 컬럼 하나하나를 후보로 다루고(채점·근거 확인 단위), 보낼 때만 묶습니다.

| 종류 | `payload` | `evidence`에 더해지는 것 |
| --- | --- | --- |
| OBJECT_TYPE | 객체 유형 칸(`code`, `name`, `description`, `synonyms`, `note`, `category`) + `objectMapping` {`sourceId`, `resourceKey`, `keyColumns`} + `properties[]` {`code`, `name`, `dataType`, `description`, `isIdentifier`, `isRequired`, `unit`, `sensitivity`, `sourceColumn`, `transformSteps`} + `displayPropertyCode`(대표 속성으로 쓸 묶음 속성 코드, 없으면 칸 없음) | `objectMapping` {title, reason, items, …}, `properties[]` {code, title, reason, items, confidenceItems, mapping} |
| LINK_RULE | 연결 규칙 칸(`code`, `name`, `linkTypeCode`, `pattern`, `conditions`, `sourceFilter`, `description`) + `linkType` {`code`, `name`, `sourceTypeCode`, `targetTypeCode`, `cardinality`, `description`} | 없음(규칙과 관계 유형의 근거가 같음) |

대표 속성(`displayPropertyCode`)은 수집한 객체의 표시 이름이 됩니다. 묶음 속성 중 글자형이고 식별 키·민감 속성이 아닌 이름 성격 컬럼을 고릅니다. 우선순위는 `name` → `*_name`·`*_nm` → 한국어 이름이 '명'·'이름'으로 끝나는 속성('설명' 제외)입니다. 없으면 칸을 보내지 않고, 표시 이름은 원천 키 값이 됩니다. 이 칸은 재분석 지문에서 뺍니다(이 칸이 생기기 전에 승인한 유형이 '변경'으로 다시 올라오지 않게).

- `aiConfidence`는 객체 후보면 객체 유형의, 관계 후보면 연결 규칙의 점수입니다.
- 묶음 안 구성 후보 하나라도 보류 권고면 묶음도 `holdRecommended=true`이고, `holdReason`은 이유를 ` / `로 잇습니다. 어느 속성 때문인지는 `evidence.properties[].holdRecommended`·`holdReason`에 남습니다.
- 같은 관계 유형을 쓰는 연결 규칙이 여럿이면 규칙마다 `linkType`을 담습니다.
- 객체 유형 코드가 겹치거나 한 객체에 객체 매핑이 둘 이상이면, 묶을 곳이 모호한 후보는 원래 종류로 따로 보냅니다(빠지지 않음).
- `fingerprint`는 묶은 payload의 지문입니다. 이름·설명·다른 이름(`name`, `description`, `synonyms`)은 빼고 계산해 문구만 바뀐 재분석 결과를 변경으로 보지 않습니다. 대안(`options`)이 둘 이상이면 대안 지문도 함께 넣어, 대표 제안이 그대로여도 대안이 바뀌면 변경(CHANGED)으로 봅니다. 문서 연결은 승인한 지문(대표 또는 선택지)과 같은 대상이 있으면 다시 내지 않고, 같은 후보 ID라도 지문이 다르면 이유에 이전 승인을 적고 다시 묻습니다.
- 연결 규칙이 없는 관계 유형(문서 연결용, 다른 표를 거치는 관계)은 지금처럼 LINK_TYPE 한 건으로 보냅니다.
- 개수 예(개발용 데이터 A에서 금지 표·컬럼을 뺀 입력, LLM 없이, 11-13 확인): 보내는 후보 91건(객체 29, 관계 55, 규칙 없는 관계 유형 7). 문서 연결(DOCUMENT_LINK) 39건은 따로 더해집니다.

LINK_RULE·LINK_TYPE·DOCUMENT_LINK의 `name`(관계 유형은 `linkType.name`·`description`도)은 검토 화면 목록 제목용 한국어입니다(#327). 같은 분석의 객체 유형 이름과 속성 이름으로 만듭니다. 예: `작업지시 → 수주 (수주 참조 번호)`, `10/29 야간 인수인계 → 1라인 3호 프레스 (P103)`, `8/20 야간 인수인계 → 작업지시 JB2608-0129`. 이유(`evidence.reason`)는 현업이 읽는 문장입니다. 연결 규칙은 무엇으로 무엇을 찾는지, 몇 행이 이어지는지, 승인하면 무엇이 되는지를 씁니다(예: `공정 실적의 작업자 번호로 현장 작업자를 찾습니다. 공정 실적 640행이 모두 현장 작업자와 이어집니다. 승인하면 …`). 문서 연결은 왜 이 대상인지, 메모에 무엇이 적혔는지(관찰), 승인 효과를 씁니다(예: `메모에 설비 번호 P201이 그대로 적혀 있습니다. 메모 내용: “오후 3시쯤 P201 진동이 평소보다 커서 점검 요청함.” 승인하면 …`). 분석 범위 밖이라 이름을 모르는 객체는 코드를 그대로 씁니다. 코드(`code`·`linkTypeCode`)는 바꾸지 않습니다.

DOCUMENT_LINK `payload`는 `name`, `linkTypeCode`, `documentId`, `spanStart`·`spanEnd`(원문 글자 위치, 끝 미포함), `mentionText`, `targetObjectRef`, `expressionKind`입니다. `expressionKind`는 OBSERVED(확인) · ESTIMATED(추정)입니다. 관찰이 모두 부정인 언급은 연결하지 않습니다. 설비 후보가 여럿이면 `options[]`에 후보마다 하나씩 두고 보류 권고(`holdRecommended`)로 보냅니다. 문서 연결이 쓰는 관계 유형(`MENTIONS_EQUIPMENT` 등, 메모 → 설비·작업지시·정비·출하, MANY_TO_MANY)도 LINK_TYPE 후보로 함께 냅니다. 관계 유형은 출발 객체 유형이 하나라서 정비 기록 연결은 `MAINTENANCE_ORDER_MENTIONS_EQUIPMENT`처럼 앞에 표 이름을 붙입니다.

속성의 `sensitivity`(객체 후보 `payload.properties[].sensitivity`)는 ERD `property_definition.sensitivity`와 같은 값(NORMAL · RESTRICTED)입니다. 개인정보로 본 컬럼은 RESTRICTED입니다.

관계 개수 `MANY_TO_MANY`는 도착이 키가 아닌 종류 관계(라우팅 설비 종류 → 설비 종류)에 씁니다. 다른 표를 거쳐야 이어지는 관계(공정 실적 → 작업지시 → 라우팅)는 연결 규칙(`link_mapping_condition`, 두 객체의 속성 비교)으로 표현할 수 없어 LINK_RULE 없이 LINK_TYPE만 보류 권고로 냅니다.

## 3. 시작 알림 (API 명세 018 "FastAPI 분석 시작 알림")

명세는 "FastAPI가 QUEUED 분석을 시작할 때 105로 RUNNING"이라고만 하고, FastAPI가 실행을 어떻게 알게 되는지는 없습니다. 그래서 Spring이 실행을 만든 직후 한 번 알려 줍니다(#176).

`POST /internal/v1/analysis-runs` → `202 {runId, accepted}`. 같은 실행이 아직 돌고 있으면 `accepted: false`를 돌려줍니다.

**처리 (#271 Spring 전송, #272 수신)**

- 토큰이 없거나 틀리면 401(공통 오류 형식). `AI_ONTOLOGY_INTERNAL_TOKEN`이 비어 있으면 모두 거절합니다. 토큰은 본문보다 먼저 봅니다.
- 받으면 바로 202로 답하고 백그라운드에서 105 `RUNNING` → 원천 확인 → 분석 → 105 `SUCCEEDED`/`FAILED` 순서로 돕니다.
- 105 전송이 접속 실패·시간 초과·5xx로 실패하면 2·5·10초 뒤 같은 본문을 다시 보냅니다(최대 4번). 404(없는 실행)는 바로 포기합니다. 다시 보낸 요청이 409를 받으면 앞선 시도가 이미 저장된 것으로 봅니다(`RUNNING`이면 분석을 이어 가고 결과면 끝냅니다). 첫 시도의 `RUNNING`이 409면 이미 끝난 실행이라 분석하지 않습니다. `RUNNING` 알림이 끝내 실패해도 분석하지 않습니다. 결과(`SUCCEEDED`/`FAILED`)가 끝내 실패하거나, 400(본문 검증 실패)이거나, 첫 시도에서 409(저장 중 제약 위반일 수 있음)를 받으면 `FAILED` + `분석 결과를 저장하지 못했습니다. 다시 분석해 주세요`를 한 번 더 보냅니다. 보내지 않으면 실행이 백엔드 시간 제한까지 RUNNING으로 남기 때문입니다. 재시도 중 종료(SIGTERM)되면 중단 알림만 보내고 재시도는 멈춥니다.
- `targets[].resourceKey`는 `스키마.이름` 형식이어야 합니다. 점이 없거나 앞뒤가 비면 400(`VALIDATION_FAILED`)입니다.
- 원천 확인: POSTGRES는 AI 계정으로 `schema`에 접속해 고른 표(`resourceKey`의 점 뒤)를 읽을 수 있는지 봅니다. 읽을 수 있는 표는 백엔드 연결 검사와 같은 기준입니다(표·파티션 부모·뷰·구체화 뷰, 파티션 자식 제외, 스키마 USAGE와 표 또는 컬럼 하나 이상의 SELECT 권한). 컬럼 단위로만 권한을 준 표는 SELECT 권한이 있는 컬럼만 읽습니다. CSV는 `path` 폴더에 `<표>.csv`가 있는지 봅니다. 실패 문구는 4절입니다.
- 서로 다른 원천의 `resourceKey` 스키마 이름(점 앞)이 같으면 원천에 접속하기 전에 실패로 끝냅니다(4절). 임시 폴더와 후보 ID가 스키마 이름으로 갈리기 때문입니다.
- 분석 엔진은 `app/analysis/engine.py`의 `analyze()`입니다(#168). 고른 표·컬럼만 임시 폴더로 옮겨 구조·분포를 분석하고 후보를 만듭니다. LLM 설정이 있으면 표 의미 해석을 더합니다. `mes.handover_note`·`mes.maintenance_order`를 고르면 글 속 언급을 DOCUMENT_LINK 후보로 더합니다(#166). 원천 키(설비 코드·작업지시 번호·정비 번호·로트 번호)는 코드로 찾고, LLM 설정이 있으면 설비 별명('3호기')·증상과 부정·추정도 뽑습니다. 문서 10개마다 LLM을 한 번 부르고, 호출이 실패한 묶음은 코드로 찾은 언급만 냅니다. 043 모델 요약은 분석 전에 받아 이미 있는 것과 같은 후보를 뺍니다(#281, `app/proposers/existing.py`). 재분석이면 같은 비교로 추가·변경을 표시합니다(#170, 기준 043). 과거 결정이 전달되면 승인·거절한 같은 내용은 제외합니다. 유효한 이전 후보가 전달되면 이번에 생성된 후보를 이전 후보와 비교합니다.
- 진행 중인 `runId`는 프로세스 메모리에만 둡니다. AI 컨테이너는 하나만 띄웁니다.
- Spring은 새로 접수된 `REANALYSIS`에 유효한 `pastDecisions`와 `previousProposals`를 보냅니다(11-03 전송 결정 갱신, #339). 빈 목록은 생략합니다. `allowedTransforms`·`options.useLlm`은 AI가 받아도 버립니다.
- Spring은 알리지 못하면(AI 오류·시간 초과·응답 형식 오류) 실행을 직접 FAILED로 바꿉니다(`AI 서비스에 분석 시작을 알리지 못했습니다`). AI 계정 없음, 한 원천에 schema 둘 이상, CSV `path` 없음, DOCUMENT 원천도 보내기 전에 FAILED입니다.

| 필드 | 필수 | 내용 |
| --- | --- | --- |
| `runId` | O | `analysis_run.id` |
| `runKind` | O | INITIAL · REANALYSIS |
| `targets[]` | O | `{sourceResourceId, dataSourceCode, resourceKey(원천.표), columns, sourceMetadata}` |
| `sources[]` | O | `{dataSourceCode, sourceKind(POSTGRES·CSV), connection, schema, path}`. 아래 `sources[].connection` |
| `allowedTransforms` | 선택 | 041 허용 변환. 지금은 받아도 쓰지 않음(AI 안의 허용 변환 목록 FN-MAP-05를 씀) |
| `pastDecisions` | 선택 | `[{candidateId, itemKind, revision, fingerprint 또는 payload, decision, reason}]`. `decision`은 APPROVE·REJECT·HOLD. 같은 지문이 승인·거절됐으면 다시 내지 않음, 보류였으면 이유에 적음. 같은 후보 ID가 거절·보류됐는데 내용이 바뀌었으면 이유에 이전 결정을 적고 냄 |
| `previousProposals` | 재분석 | `[{candidateId, fingerprint}]`. 이전 후보 evidence에서 꺼낸 값. 보내면 `changeType`을 이전 후보 기준(같은 ID·지문 UNCHANGED, ID만 같음 CHANGED, 새 ID ADDED)으로 붙이고, 없으면 043 기준 |
| `options.useLlm` | 선택 | 지금은 받아도 쓰지 않음. LLM 사용은 AI 서버 설정(`AI_ONTOLOGY_LLM_PROVIDER`)으로 정함 |

**`targets[].sourceMetadata`** (선택): `source_resource.source_metadata`를 그대로 보냅니다. 연동 구현은 #223입니다. AI는 아래 키만 읽고 나머지는 무시합니다.

```json
{
  "description": "표 설명",
  "synonyms": ["표 동의어"],
  "memo": "표 메모",
  "columns": {
    "컬럼 이름": {"description": "컬럼 설명", "synonyms": ["컬럼 동의어"], "memo": "컬럼 메모"}
  }
}
```

| 값 | AI에서 쓰는 곳 |
| --- | --- |
| `description` | 원천 주석이 없을 때만 표 주석·컬럼 주석으로 씀. 주석은 객체·속성 이름이 되므로 원천 주석을 덮어쓰지 않음 |
| `synonyms`, `memo` | 표는 LLM 해석의 참고 문장으로, 컬럼은 주석 뒤 괄호로 덧붙임 |

**Spring의 재분석 이력 선택 (#339)**

- 두 목록 모두 현재 실행의 `requested_at`보다 먼저 완료된 `SUCCEEDED` 분석을 사용합니다. 현재 실행은 제외하고, 선택 컬럼은 이력 매칭에 사용하지 않습니다.
- `pastDecisions`는 분석 대상과 무관하게 접수 시점까지의 AI 후보 승인·거절을 조회합니다. 결정 당시 후보의 payload가 최초 AI 후보와 같으면 최초 개정본의 지문을 사용합니다. 선택지 결정은 최초 개정본의 해당 선택지 지문을 사용합니다. 문서 연결 선택지에 지문이 없으면 저장된 `payload`를 보내 AI가 지문을 계산합니다. 동일 지문 또는 동일 대체 payload의 결정 중 결정 시각과 ID가 가장 최신인 것을 보냅니다. AI는 같은 문서 연결의 선택지에 이미 승인한 내용이 있으면 그 후보를 다시 내지 않습니다.
- `previousProposals`는 대상 표마다 완료 시각과 실행 ID 기준으로 직전 성공 실행을 고릅니다. 선정된 실행의 최초 revision을 읽고, 같은 후보 ID는 최신 실행의 유효한 지문만 보냅니다. 이전 후보가 없으면 AI는 043 모델 요약을 사용합니다. 두 표 모두 공동 분석 이후에 각각 단독으로 분석했다면 과거 공동 분석은 직전 실행 목록에서 빠질 수 있습니다.

**`sources[].connection`** (2026-11-03 결정: Spring이 복호화해 보냄)

| 원천 | 보내는 값 |
| --- | --- |
| POSTGRES | `{host, port, database, sslmode, username, password}`. `host`·`port`·`database`·`sslmode`는 `data_source.connection_config`, `username`·`password`는 `data_source_credential`(purpose=AI)을 복호화한 값. `schema`는 `sources[].schema`로 따로 보냄 |
| CSV | `connection`은 빈 객체. `path`에 AI 컨테이너가 읽을 수 있는 폴더를 넣음(개발·시험용) |
| DOCUMENT | 아직 읽지 않음. 대상에 있으면 분석이 실패함 |

- `sslmode`가 없으면 `prefer`(SSL 먼저 시도)로 접속합니다. 원천 AI 계정(`platform_ai`)은 SSL만 받으므로 `require`를 권장합니다.
- AI는 이 계정으로 읽기 전용 세션만 엽니다(`default_transaction_read_only=on`, `statement_timeout` 30초).

**접속 정보 보안**

- 내부망 주소(`http://ai:8000`)로만 보내고 `X-Internal-Token`을 붙입니다. 없거나 틀리면 401입니다.
- Spring과 AI 모두 요청 본문과 접속 정보를 로그에 남기지 않습니다.
- AI는 접속 정보를 저장하지 않고 분석이 끝나면 버립니다.
- 105 결과와 `errorMessage`에 접속 정보를 넣지 않습니다. 접속 실패는 원인 코드(인증 실패·권한 없음 등)만 보냅니다.

## 4. 실패 메시지 (105 `errorMessage`, 019에 그대로 보임)

| 경우 | 메시지 |
| --- | --- |
| AI 계정이 고른 표를 못 읽음 | `AI 계정이 이 테이블을 읽을 수 없습니다: mes.employee` (명세 018 E3) |
| 원천 접속 실패 | `원천에 접속하지 못했습니다 (erp)` · `원천 계정 인증에 실패했습니다 (erp)` 등. SSL 없이 붙어 거절된 경우도 인증 실패. 접속 정보는 넣지 않음 |
| 원천 조회 실패 | `원천 조회가 시간 제한(30초)을 넘었습니다 (erp)`(57014) · `원천에서 고른 테이블을 찾지 못했습니다 (erp)`(42P01, 확인 뒤 표가 없어짐) · `원천을 읽을 권한이 없습니다 (erp)`(42501) |
| 원천 스키마 이름 겹침 | `서로 다른 원천이 같은 스키마 이름(public)을 써서 함께 분석할 수 없습니다: ERP_MAIN, MES_MAIN. 원천별로 나눠 분석해 주세요` |
| 결과를 105로 보내지 못함 | `분석 결과를 저장하지 못했습니다. 다시 분석해 주세요` (재시도 뒤에도 실패했거나 400일 때 한 번 더 보냄) |
| CSV 폴더·파일 없음 | `CSV 원천 폴더를 읽을 수 없습니다 (erp)` · `CSV 원천에서 이 테이블을 찾을 수 없습니다: erp.item` |
| AI 재시작(재배포 SIGTERM) 때 진행 중이던 실행 | `AI 서비스가 다시 시작되어 분석이 중단되었습니다. 다시 분석해 주세요` (종료 직전에 보냄) |
| 그 밖 | `분석 중 오류가 났습니다` (FastAPI 로그에는 오류 종류만 남김) |

`errorMessage`는 백엔드가 비어 있는지만 검사하는 글자입니다(정해진 값 목록 없음).

LLM 설정이 없거나 LLM 호출이 실패해도 실행은 실패로 끝내지 않고 코드 분석 후보만 `SUCCEEDED`로 보냅니다. LLM 호출이 실패하면 FastAPI 로그에 실행 번호·단계(표 의미 해석·시스템 간 연결·메모 추출)·오류 종류(HTTP 상태·시간 초과 등)와 건수만 남깁니다. 응답 본문·프롬프트·키는 남기지 않습니다. `resultSummary`에 아래 값이 함께 갑니다.

| 필드 | 내용 |
| --- | --- |
| `llmUsed` | LLM 답을 하나라도 썼는지 |
| `llmSkipReason` | LLM 없이 분석한 이유. `LLM 설정 없음` · `LLM 호출이 모두 실패함` · `예상 크레딧 N이 한도 M를 넘음`(`AI_ONTOLOGY_LLM_CREDIT_LIMIT`, 기본 10,000), 아니면 null |
| `llmCalls`, `llmFailedCalls`, `llmSkippedCalls` | 실제로 부른 수, 그중 실패한 수, LLM이 연속 3번 응답하지 않아 부르지 않고 건너뛴 수(표 의미 해석·시스템 간 연결·메모 추출을 합친 수) |
| `llmCredits` | 사용량(GMS 크레딧 기준 환산) |
| `llmRejectedLinks` | 규칙이 보류로 둔 관계를 LLM도 업무상 맞지 않다고 봐서 보내지 않은 수(관계 유형·연결 규칙 한 쌍을 1로 셈). 규칙이 대기로 둔 관계는 LLM이 반대해도 빼지 않고 보류로만 바꿈 |
| `llmCrossLinks` | 규칙이 못 이은 시스템 간 참조 컬럼을 LLM에게 묻고, 받은 변환을 실제 값으로 확인해 낸 연결 수. 모두 보류 권고(`method`=`llm`) |
| `documents`, `documentLinks` | 읽은 메모·정비 기록 수, DOCUMENT_LINK 후보 수 |
| `existingSkipped` | 043 모델 요약과 같아서 보내지 않은 수 `{"objectTypes": n, "linkRules": n, "linkTypes": n}`. 객체 유형은 속성이 모두 이미 있을 때, 연결 규칙은 같은 코드의 ACTIVE 규칙이 같은 속성끼리 같은 변환으로 비교할 때만 뺌. 새 속성·다른 비교·다른 변환이면 보내고 제안 이유에 적음 |
| `changeBasis`, `changeCounts` | 재분석 비교 기준과 수 `{"ADDED": n, "CHANGED": n, "UNCHANGED": n}`. `previousProposals`가 오면 `PREVIOUS_PROPOSALS`(유지도 후보로 보냄), 없으면 `MODEL_SUMMARY`(043, 유지는 보내지 않고 수만 셈). 첫 분석이거나 둘 다 없으면 null |
| `pastDecisionSkipped` | 과거 결정과 같은 내용이라 보내지 않은 수 `{"APPROVE": n, "REJECT": n}` |
| `modelSummarySkipReason` | 043 모델 요약 없이 분석한 이유. `내부 토큰 없음` · `모델 요약을 받지 못함(HTTP 403 등)`, 받았으면 null |
| `missingColumns` | 고른 컬럼 중 원천에서 사라졌거나 AI 계정의 컬럼 권한이 빠진 것 `{"mes.work_order": ["컬럼"]}`. 빼고 나머지로 분석함 |
| `sampledTables` | 행이 많아 앞에서 자른 표 `{"mes.sensor_reading": 100000}`. 기본키가 있으면 기본키 순서 |

## 5. 정해야 할 것

| 항목 | 제안 |
| --- | --- |
| 과거 결정·허용 변환 | 3절 요청에 함께 넣음. 따로 받을 내부 API가 명세에 없음 |

## 6. 지금 구현 범위

- 저장소에 있는 것: 시작 알림 수신·105 전송·원천 확인(#272), PostgreSQL 읽기(#163), CSV 읽기·스냅샷(#164), 구조·분포 분석(#165), 후보 모델·근거·신뢰도(#169), 후보 생성·LLM 해석·엔진(#168), 설비 별칭·원천 식별자(#167), 메모·정비 기록 추출(#166). 재분석 비교(#170)와 과거 결정 반영(#281)은 018에서 이력을 받아 사용합니다(#339).
- LLM: `.env`의 `AI_ONTOLOGY_LLM_PROVIDER`·`_API_KEY`·`_BASE_URL`·`_MODEL`만 바꾸면 제공사를 바꿀 수 있습니다. `gms`(기본), `openai`(OpenAI·Azure OpenAI v1, Responses API 형식), `openai-chat`(사내 LLM 서버 등 Chat Completions 형식), `none`(코드 분석만). 실제 호출로 확인한 것은 GMS뿐입니다.
- 원천: CSV 폴더와 PostgreSQL(읽기 전용, psycopg). 로컬 PostgreSQL 18.6(SSL만 허용, 조회 전용 계정)에 data-v1.0을 넣고 확인했습니다. 확인 당시 CSV로 분석한 구성 후보(653개)와 같게 나왔습니다. 조회 전용 계정은 `information_schema`의 제약 조건이 안 보여 기본키는 `pg_catalog`로 읽습니다.
- 원천에서 고른 컬럼과 기본키만 SELECT 합니다(고르지 않은 개인정보 컬럼은 읽지 않음). 표 하나는 최대 10만 행까지 읽습니다(AI 계정 쿼리 30초 제한). 기본키가 있으면 기본키 순서로, 없으면 정렬 없이 앞에서 자릅니다. 고른 컬럼이 모두 사라지고 기본키도 없는 표는 건너뛰고 `missingColumns`로만 알립니다. 쿼리마다 바로 끝내고(autocommit) 옮기기가 끝나면 원천 연결을 닫습니다. 그 뒤 분석·근거 확인·LLM 단계는 임시 폴더만 읽습니다. 값은 CSV와 같은 글자로 옮깁니다(불리언 `true`·`false`, JSON은 JSON 글자). 옮긴 임시 폴더(`ai-run-*`)는 분석이 끝나면 지우고, 강제 종료로 남은 폴더는 서버가 시작할 때 지웁니다.
- 재분석: 원천 변경은 CDC로 수집하고 다시 분석할 표는 백엔드가 고릅니다(V5). 고른 표를 다시 분석하고, 018로 받은 유효한 이전 후보가 있으면 후보 ID·지문을 비교합니다(`changeBasis`=`PREVIOUS_PROPOSALS`). 이전 후보가 없으면 확정된 모델(043)과 비교합니다(`changeBasis`=`MODEL_SUMMARY`). 이전에 있다가 이번에 안 나온 후보는 판단하지 않습니다.
