# 분석 연동 (FastAPI 쪽)

- 기준: 노션 "API 명세서"의 API 공통 규칙, 018 분석 실행, 105 분석 결과 내부 저장, 043 모델 요약 (모두 초안, 2026-10-27 확인)
- 후보 샘플: `results/proposals-v1.0.api.json` (data-v1.0 전체를 원천 코드 `ERP_MAIN`·`MES_MAIN`으로 분석한 105 요청 본문, LLM 없이)

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
| 043 `GET /api/v1/internal/ontology/model-summary` | 분석 전 | 없음. 이미 있는 정의와 같은 후보를 내지 않으려고 받음. `objectTypes[].code`, `properties[].objectTypeId`·`code`, `linkTypes[].code`를 씀. 실패하면 빈 요약으로 계속함 |

**105 `proposals[]` 한 건**

| 필드 | 내용 |
| --- | --- |
| `itemKind` | OBJECT_TYPE · PROPERTY · LINK_TYPE · OBJECT_MAPPING · PROPERTY_MAPPING · LINK_RULE · DOCUMENT_LINK |
| `payload` | 후보 내용. 객체·관계 유형 코드는 대문자(`WORK_ORDER`), 속성은 컬럼 이름 그대로. `sourceId`는 `data_source.code`(예: `MES_MAIN`), `resourceKey`는 `source_resource.resource_key`(예: `mes.work_order`). 표를 가리키는 값은 늘 `sourceId` + `resourceKey`. 속성 매핑 `sourceColumn`은 `{sourceId, resourceKey, table, column}`이고 `resourceKey`가 백엔드가 원천 표를 찾는 값(`table`은 `resourceKey`의 첫 점 뒤, 이전 후보와 맞추려고 남김) |
| `evidence` | 아래 표 |
| `aiConfidence` | 0~1. 근거 신호 점수의 합이고 정답 확률이 아님. 매칭률과 섞지 않음 |
| `options[]` | 같은 대상에 후보가 여럿일 때 `{optionNo, payload, evidence, aiConfidence}` |

**`evidence` 안 (105는 evidence를 객체로만 받으므로 검토에 필요한 것을 여기에 담습니다)**

| 필드 | 내용 |
| --- | --- |
| `reason`, `title` | 제안 이유, 화면 제목 |
| `items[]` | 근거 `{sourceId, locator, snippet, values, verified}`. 원천에서 다시 확인한 것만 |
| `confidenceItems[]`, `confidenceCap` | 신뢰도 점수 내역, 보류 상한 `{cap, reason}` |
| `holdRecommended`, `holdReason` | AI의 보류 권고. 105는 PENDING으로 저장하므로 권고로만 남음 |
| `method`, `llmJudgement` | `rule` · `llm` · `rule+llm`, LLM 판단 문장(점수에는 넣지 않음) |
| `candidateId`, `fingerprint` | 재분석 비교·과거 결정 대조용 후보 ID와 payload 지문 |
| `changeType` | 재분석에서만. ADDED · CHANGED · UNCHANGED |
| `originalText`, `observations[]`, `times[]` | DOCUMENT_LINK만. 원문 전체, 이 언급에 걸린 관찰 표현 `{text, spanStart, spanEnd, assertion}`, 글 속 시각 표현 `{text, spanStart, spanEnd, normalized, basis}`. `assertion`은 AFFIRMED(확인) · NEGATED(부정) · UNCERTAIN(추정). `normalized`는 원천에서 기준 시각을 알 때만 채움 |

DOCUMENT_LINK `payload`는 `linkTypeCode`, `documentId`, `spanStart`·`spanEnd`(원문 글자 위치, 끝 미포함), `mentionText`, `targetObjectRef`, `expressionKind`입니다. `expressionKind`는 OBSERVED(확인) · ESTIMATED(추정)입니다. 관찰이 모두 부정인 언급은 연결하지 않습니다. 설비 후보가 여럿이면 `options[]`에 후보마다 하나씩 두고 보류 권고(`holdRecommended`)로 보냅니다. 문서 연결이 쓰는 관계 유형(`MENTIONS_EQUIPMENT` 등, 메모 → 설비·작업지시·정비·출하, MANY_TO_MANY)도 LINK_TYPE 후보로 함께 냅니다. 관계 유형은 출발 객체 유형이 하나라서 정비 기록 연결은 `MAINTENANCE_ORDER_MENTIONS_EQUIPMENT`처럼 앞에 표 이름을 붙입니다.

PROPERTY `payload.sensitivity`는 ERD `property_definition.sensitivity`와 같은 값(NORMAL · RESTRICTED)입니다. 개인정보로 본 컬럼은 RESTRICTED입니다.

관계 개수 `MANY_TO_MANY`는 도착이 키가 아닌 종류 관계(라우팅 설비 종류 → 설비 종류)에 씁니다. 다른 표를 거쳐야 이어지는 관계(공정 실적 → 작업지시 → 라우팅)는 연결 규칙(`link_mapping_condition`, 두 객체의 속성 비교)으로 표현할 수 없어 LINK_RULE 없이 LINK_TYPE만 보류 권고로 냅니다.

## 3. 시작 알림 (API 명세 018 "FastAPI 분석 시작 알림")

명세는 "FastAPI가 QUEUED 분석을 시작할 때 105로 RUNNING"이라고만 하고, FastAPI가 실행을 어떻게 알게 되는지는 없습니다. 그래서 Spring이 실행을 만든 직후 한 번 알려 줍니다(#176).

`POST /internal/v1/analysis-runs` → `202 {runId, accepted}`. 같은 실행이 아직 돌고 있으면 `accepted: false`를 돌려줍니다.

**처리 (#271 Spring 전송, #272 수신)**

- 토큰이 없거나 틀리면 401(공통 오류 형식). `AI_ONTOLOGY_INTERNAL_TOKEN`이 비어 있으면 모두 거절합니다. 토큰은 본문보다 먼저 봅니다.
- 받으면 바로 202로 답하고 백그라운드에서 105 `RUNNING` → 원천 확인 → 분석 → 105 `SUCCEEDED`/`FAILED` 순서로 돕니다. `RUNNING` 알림이 실패하면(예: 백엔드가 이미 끝낸 실행, 409) 분석하지 않습니다.
- 원천 확인: POSTGRES는 AI 계정으로 `schema`에 접속해 고른 표(`resourceKey`의 점 뒤)를 읽을 수 있는지 봅니다. CSV는 `path` 폴더에 `<표>.csv`가 있는지 봅니다. 실패 문구는 4절입니다.
- 분석 엔진은 `app/analysis/engine.py`의 `analyze()`입니다(#168). 고른 표·컬럼만 임시 폴더로 옮겨 구조·분포를 분석하고 후보를 만듭니다. LLM 설정이 있으면 표 의미 해석을 더합니다. `mes.handover_note`·`mes.maintenance_order`를 고르면 글 속 언급을 DOCUMENT_LINK 후보로 더합니다(#166). 원천 키(설비 코드·작업지시 번호·정비 번호·로트 번호)는 코드로 찾고, LLM 설정이 있으면 설비 별명('3호기')·증상과 부정·추정도 뽑습니다. 문서 10개마다 LLM을 한 번 부르고, 호출이 실패한 묶음은 코드로 찾은 언급만 냅니다. 043·과거 결정 반영(#168 후속), 재분석 비교(#170)는 아직 연결하지 않았습니다.
- 진행 중인 `runId`는 프로세스 메모리에만 둡니다. AI 컨테이너는 하나만 띄웁니다.
- 선택 필드(`allowedTransforms`·`pastDecisions`·`previousProposals`·`options.useLlm`)는 Spring이 아직 보내지 않고(11-03 결정), 받아도 버립니다.
- Spring은 알리지 못하면(AI 오류·시간 초과·응답 형식 오류) 실행을 직접 FAILED로 바꿉니다(`AI 서비스에 분석 시작을 알리지 못했습니다`). AI 계정 없음, 한 원천에 schema 둘 이상, CSV `path` 없음, DOCUMENT 원천도 보내기 전에 FAILED입니다.

| 필드 | 필수 | 내용 |
| --- | --- | --- |
| `runId` | O | `analysis_run.id` |
| `runKind` | O | INITIAL · REANALYSIS |
| `targets[]` | O | `{sourceResourceId, dataSourceCode, resourceKey(원천.표), columns, sourceMetadata}` |
| `sources[]` | O | `{dataSourceCode, sourceKind(POSTGRES·CSV), connection, schema, path}`. 아래 `sources[].connection` |
| `allowedTransforms` | 선택 | 041 허용 변환. 목록 밖 변환을 쓰는 후보는 내지 않음 |
| `pastDecisions` | 선택 | `[{candidateId, itemKind, revision, fingerprint 또는 payload, decision, reason}]`. 같은 내용이 승인·거절됐으면 다시 내지 않음 |
| `previousProposals` | 재분석 | `[{candidateId, fingerprint}]`. 이전 후보 evidence에서 꺼낸 값 |
| `options.useLlm` | 선택 | false면 코드 분석만 |

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
| CSV 폴더·파일 없음 | `CSV 원천 폴더를 읽을 수 없습니다 (erp)` · `CSV 원천에서 이 테이블을 찾을 수 없습니다: erp.item` |
| 그 밖 | `분석 중 오류가 났습니다` (FastAPI 로그에는 오류 종류만 남김) |

LLM 설정이 없거나 LLM 호출이 실패해도 실행은 실패로 끝내지 않고 코드 분석 후보만 `SUCCEEDED`로 보냅니다. `resultSummary`에 아래 값이 함께 갑니다.

| 필드 | 내용 |
| --- | --- |
| `llmUsed` | LLM 답을 하나라도 썼는지 |
| `llmSkipReason` | LLM 없이 분석한 이유. `요청에서 LLM을 끔` · `LLM 설정 없음` · `LLM 호출이 모두 실패함`, 아니면 null |
| `llmCalls`, `llmFailedCalls` | 호출 수, 실패한 호출 수(표 의미 해석과 메모 추출을 합친 수) |
| `llmCredits` | 사용량(GMS 크레딧 기준 환산) |
| `documents`, `documentLinks` | 읽은 메모·정비 기록 수, DOCUMENT_LINK 후보 수 |

## 5. 정해야 할 것

| 항목 | 제안 |
| --- | --- |
| 과거 결정·허용 변환 | 3절 요청에 함께 넣음. 따로 받을 내부 API가 명세에 없음 |

## 6. 지금 구현 범위

- 저장소에 있는 것: 시작 알림 수신·105 전송·원천 확인(#272), PostgreSQL 읽기(#163), CSV 읽기·스냅샷(#164), 구조·분포 분석(#165), 후보 모델·근거·신뢰도(#169), 후보 생성·LLM 해석·엔진(#168), 설비 별칭·원천 식별자(#167), 메모·정비 기록 추출(#166). 재분석 비교는 아직 로컬에만 있습니다.
- LLM: `.env`의 `AI_ONTOLOGY_LLM_PROVIDER`·`_API_KEY`·`_BASE_URL`·`_MODEL`만 바꾸면 제공사를 바꿀 수 있습니다. `gms`(기본), `openai`(OpenAI·Azure OpenAI v1, Responses API 형식), `openai-chat`(사내 LLM 서버 등 Chat Completions 형식), `none`(코드 분석만). 실제 호출로 확인한 것은 GMS뿐입니다.
- 원천: CSV 폴더와 PostgreSQL(읽기 전용, psycopg). 로컬 PostgreSQL 18.6(SSL만 허용, 조회 전용 계정)에 data-v1.0을 넣고 확인했습니다. CSV로 분석한 후보 653개와 같게 나옵니다. 조회 전용 계정은 `information_schema`의 제약 조건이 안 보여 기본키는 `pg_catalog`로 읽습니다.
- 고른 표·컬럼만 임시 폴더로 옮겨 분석하고, 끝나면 지웁니다. 기본키는 늘 함께 읽습니다.
- 재분석: 원천 변경은 CDC로 수집하고 다시 분석할 표는 백엔드가 고릅니다(V5). 고른 표 전체를 다시 분석하고 이전 후보와 비교해 `changeType`을 붙입니다. 이전에 있다가 이번에 안 나온 후보는 판단하지 않습니다.
