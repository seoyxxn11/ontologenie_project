# Agent 응답 방식 및 데이터 최신성 정책

- 관련 이슈: S15P31C106-153
- 기능: FN-AGT-04 · UAG-05·06
- 관련 API: 084 대화 메시지 목록, 085 질문하고 답변 받기
- 담당: 장민화
- 기준일: 2026-11-03
- 상태: 초안 (팀 검토 전)

## 1. 목적

Agent가 답변을 화면에 보내는 방식, 답변에 붙는 기준 시점의 뜻, 질문할 때 원천 데이터를 동기화하는 규칙을 정한다.
이 문서의 결정은 API 084·085 명세와 Agent BE(141·145·146)·FE(149) 구현의 기준이 된다.

기준 자료는 다음과 같다.

- Notion API 085·084 명세 (2026-10-27 수정본)
- `backend/src/main/resources/db/migration/V1__create_ontology_core.sql`: `sync_run`, `sync_run_resource`, `property_definition.temporal_role`
- `backend/src/main/resources/db/migration/V2__create_operations.sql`: `agent_message`, `action_execution`
- `backend/src/main/resources/db/migration/V5__switch_source_sync_to_cdc.sql`: CDC 전환 뒤의 `sync_run`, `object_instance.last_synced_at`, `cdc_offset` (2026-10-30)
- `docs/development-guide.md`: 기술 스택의 "사용하지 않는 것"(WebFlux·SSE), 오래 걸리는 작업의 202 방식
- `docs/사용자_AI_Agent_기능_명세서.md`: UAG-05 협의 필요 항목, 9장 7번
- Notion 기능 명세서 6장 13번 (스트리밍 여부는 장민화가 정해 손주연에게 전달)

## 2. 결정 요약

| 항목 | 결정 | 이유 |
|---|---|---|
| 응답 방식 | 스트리밍(SSE)하지 않는다. 085는 질문을 받으면 202로 접수하고, 화면은 084를 반복 조회해 진행 단계와 결과를 확인한다 | 개발 가이드가 SSE를 쓰지 않고 오래 걸리는 작업은 202로 받기로 정했다. 기다리는 동안 실제 진행 단계를 보여 줄 수 있다 |
| 기준 시점 `dataAsOf` | 그 정보에 쓴 원천 테이블마다 마지막으로 확인한 시각을 구하고, 그중 가장 이른 시각 | V5에서 `last_synced_at`은 내용이 바뀐 시각이 되었다. 안 바뀐 데이터가 오래된 것처럼 보이지 않게 테이블 확인 시각을 쓰고, 가장 이른 값을 골라 최신처럼 보이지 않게 한다(UAG-05) |
| 업무 기준일 | 응답 필드를 따로 두지 않고 답변 본문에서 값 옆에 말한다 | API 필드를 늘리지 않는다 |
| 질문 시 동기화 | 질문마다 필요한 `FULL_COMPARE` 테이블만 요청한다. CDC 테이블은 요청하지 않는다. 후속 질문도 같다 | V5에서 CDC 테이블은 변경 이벤트로 계속 반영된다. 질문 시 동기화(ON_DEMAND)는 `FULL_COMPARE` 테이블만 대상이다 |

## 3. 응답 방식

### 3.1 처리 순서

1. 대화 소유자와 요청 값을 검사한다. 같은 대화에 `RUNNING`인 답변이 있으면 409(`CONFLICT`)로 막는다. 막히면 지금처럼 JSON 오류 응답을 보낸다.
2. USER 메시지와 AGENT 메시지(`status=RUNNING`, `content=""`, `answerMeta={"stage":"INTERPRETING"}`)를 저장하고 2건을 202로 돌려준다.
3. 질문을 받은 api 서버가 Agent 실행기에서 이어서 처리한다(아래 설명 참고). 단계가 바뀔 때마다 AGENT 메시지의 `answerMeta.stage`를 고친다.
   1. 질문 해석 `INTERPRETING`. 대상이 불명확하면 되묻는 답변을 만들고 4번으로 간다.
   2. 질문 시 동기화 `SYNCING` (5장)
   3. 조회 도구 호출 `QUERYING`. 호출마다 `action_execution`(AGENT_QUERY)에 기록하고 `agent_message_id`에 이 AGENT 메시지를 넣는다.
   4. 답변 작성 `ANSWERING`
4. 끝나면 AGENT 메시지의 `status`, `content`, `answerMeta`(`syncStatus`, `results`)를 최종 값으로 저장한다. `stage`는 지운다.

AGENT 메시지를 처음부터 저장하므로, 조회 도구 기록(`action_execution.agent_message_id`)이 답변 메시지를 처리 중에도 가리킬 수 있다.

개발 가이드의 오래 걸리는 작업은 작업자가 `SKIP LOCKED`로 가져가 처리한다. Agent 답변은 이 방식 대신 질문을 받은 api 서버가 바로 처리한다(2026-11-03 결정). 처리 중 서버가 멈추면 3.4의 규칙으로 정리한다.

### 3.2 AGENT 메시지 상태

| `status` | `content` | `answerMeta` | 뜻 |
|---|---|---|---|
| `RUNNING` | `""` | `{"stage": "INTERPRETING" \| "SYNCING" \| "QUERYING" \| "ANSWERING"}` | 처리 중 |
| `SUCCEEDED` | 최종 답변 | `syncStatus`, `results` | 답변을 끝냄 (데이터 없음·미연결·권한 제한 설명 포함) |
| `FAILED` | 실패 안내 | `syncStatus`, `results` (얻은 만큼) | 답변을 끝내지 못함. `errorMessage` 필수 |

### 3.3 화면 처리

- 202를 받으면 AGENT 메시지 자리에 "답변 준비 중"과 단계 문구를 표시한다.

  | `stage` | 문구 |
  |---|---|
  | `INTERPRETING` | 질문 이해 중 |
  | `SYNCING` | 최신 데이터 확인 중 |
  | `QUERYING` | 조회 중 |
  | `ANSWERING` | 답변 작성 중 |

- `RUNNING`인 AGENT 메시지가 있는 동안 1~2초마다 084를 다시 읽는다. `RUNNING`이 없어지면 멈춘다. (React Query `refetchInterval`)
- `RUNNING`인 동안 같은 대화의 질문 입력을 막는다.
- 새로고침하거나 대화에 다시 들어왔을 때 `RUNNING`이 있으면 같은 방식으로 다시 읽는다. 연결이 끊긴 경우를 따로 처리하지 않아도 된다.

### 3.4 실패 처리

- 데이터 없음·미연결·권한 제한을 설명해 답변을 끝냈으면 `SUCCEEDED`다.
- LLM 오류·서버 오류·시간 초과로 답변을 끝내지 못하면 `FAILED`와 `errorMessage`를 저장한다.
- 처리 도중 서버가 재시작되면 `RUNNING`이 남는다. 정해 둔 시간이 지나도 `RUNNING`이면 `FAILED`("답변을 끝내지 못했습니다")로 바꾼다. (6장 확인 사항 1)

### 3.5 DB 변경 (2026-11-03 결정)

141 구현 때 새 마이그레이션 파일로 아래를 바꾸고 ERD에 반영한다. 진행 단계는 `answer_meta`에 넣으므로 컬럼은 늘리지 않는다.

- `ck_agent_message_status`: AGENT 메시지의 `status`에 `RUNNING`을 허용한다.
- 대화마다 `RUNNING`인 메시지를 하나만 둔다: `CREATE UNIQUE INDEX ... ON agent_message (conversation_id) WHERE status = 'RUNNING'`. 서버가 여러 대여도 같은 대화의 동시 질문을 막는다.

## 4. 기준 시점

### 4.1 시각의 종류

| 시각 | 위치 | 뜻 | 답변에서 쓰는 곳 |
|---|---|---|---|
| 업무 기준일 | `temporal_role=AS_OF` 속성 값 | 원천 업무가 정한 값의 기준일 (예: 장부가 기준일) | 답변 본문에서 값 옆에 말한다 |
| 테이블 확인 시각 | `FULL_COMPARE`: 그 테이블의 가장 최근 `SUCCEEDED`·`SKIPPED` 수집(`sync_run_resource.finished_at`)<br>CDC: 그 테이블 토픽의 `cdc_offset.updated_at` | 플랫폼이 그 원천 테이블을 마지막으로 읽어 반영한 시각 | `results[].dataAsOf` |
| 내용 변경 시각 | `object_instance.last_synced_at` | 그 객체의 내용이 마지막으로 바뀐 시각 (V5) | 기준 시점으로 쓰지 않는다 |
| 조회 시각 | `action_execution.requested_at` | Agent가 조회 도구를 부른 시각 | 기준 시점으로 쓰지 않는다 |

### 4.2 `dataAsOf` 정하는 법

- `results[]` 항목 하나에 쓴 원천 테이블마다 테이블 확인 시각을 구하고, 그중 가장 이른 시각을 넣는다.
- 같은 값을 그 조회의 `action_execution.data_as_of`에도 넣는다.
- 쓴 테이블 중 하나라도 확인 시각을 알 수 없으면 `null`이다.
- `NO_DATA`·`UNLINKED`도 조회한 원천 테이블이 있으므로 그 테이블 기준으로 값을 넣는다.
- `DENIED`와 `FAILED`(status) 항목은 `null`이다. 권한 제한 항목에 시각을 주면 데이터가 있다는 사실이 드러난다.
- 항목마다 `dataAsOf`가 다를 수 있다. 답변 본문에서 하나의 최신 시점처럼 합쳐 말하지 않는다.

### 4.3 답변 본문

- 답변은 정보별로 기준 시점을 말한다. 예: "10-27 08:40 기준, PR-03의 이번 달 비가동은 2건입니다."
- 값에 업무 기준일이 있으면 함께 말한다. 예: "장부가는 1억 2천만 원입니다(장부가 기준일 2026-09-30)."
- 조회 도구는 속성 값과 함께 그 속성의 `temporal_role`을 돌려줘야 한다. (144 구현 범위)

### 4.4 화면 표시

| 값 | 화면 |
|---|---|
| `dataAsOf` 있음 | "기준 시점 MM-DD HH:mm" (한국 시간) |
| `dataAsOf` 없음 | "기준 시점 확인 불가". 조회 시각이나 현재 시각으로 대신 채우지 않는다 |

## 5. 질문 시 동기화

### 5.1 규칙

085 명세의 규칙을 V5(CDC 전환)에 맞춰 쓴다.

- 질문에 필요한 원천 테이블 중 `sync_strategy=FULL_COMPARE`인 테이블만 대상으로 `sync_run`(`trigger_kind=ON_DEMAND`, `QUEUED`, `requested_by`=질문한 사용자)을 넣는다.
- CDC 테이블은 변경 이벤트로 계속 반영되므로 요청하지 않는다.
- 같은 테이블의 수집이 대기·실행 중이면 새로 만들지 않고 합류한다.
- 백엔드는 원천에 접속하지 않고 배치 내부 API도 부르지 않는다. 배치가 작업 표에서 가져가 테이블 전체를 읽고 바뀐 행만 반영한다. 그 사이 다른 실행이 이미 읽었으면 `SKIPPED`로 끝난다.
- 질문 하나에 최대 15초 기다린다. 15초를 넘으면 기다리지 않고 기존 데이터로 답하고, 수집은 계속된다.

### 5.2 이번에 정한 것

- **질문마다 요청한다.** 같은 대화의 후속 질문도 매번 요청한다. 최근에 동기화했다는 이유로 건너뛰지 않는다.
- **동기화하지 않는 경우:** 아래는 요청하지 않고 `syncStatus=NONE`이다.
  - 되묻는 답변 (대상이 아직 정해지지 않음)
  - 원천 테이블이 필요 없는 질문
  - 필요한 원천 테이블이 모두 CDC 테이블인 질문
- **필요한 테이블:** 질문 해석으로 정한 대상 객체 유형에 매핑된 원천 테이블이다.
- **`syncStatus` 판정:** 요청한 테이블이 여럿이면 아래 순서로 정한다.
  1. 15초 안에 하나라도 `FAILED`면 `FAILED`
  2. 실패는 없고 하나라도 끝나지 않았으면 `TIMED_OUT`
  3. 모두 `SUCCEEDED` 또는 `SKIPPED`면 `COMPLETED`

### 5.3 답변 본문과 화면

| `syncStatus` | 답변 본문 | 화면 |
|---|---|---|
| `NONE` | 기준 시점만 말한다 | 따로 표시하지 않는다 |
| `COMPLETED` | 기준 시점만 말한다 | 따로 표시하지 않는다 |
| `TIMED_OUT` | 기준 시점과 "동기화 중"을 말한다 | "최신 데이터 반영 중" 표시 |
| `FAILED` | 기준 시점과 "최신 데이터를 가져오지 못했습니다"를 말한다 | "최신 데이터를 가져오지 못함" 표시 |

## 6. 남은 확인 사항

| 번호 | 내용 | 확인할 사람 |
|---|---|---|
| 1 | 멈춘 `RUNNING`을 `FAILED`로 바꾸는 기준 시간(예: 5분)과, 누가 언제 바꿀지(084 조회 때 또는 api 서버의 주기 작업) | 장민화 (141 구현 때) |
| 2 | 배치가 작업 표를 몇 초마다 확인하는지. 이 주기만큼 질문마다 대기 시간이 생긴다 | 김산이 |
| 3 | CDC 테이블의 확인 시각. 토픽 파티션이 여럿이면 가장 이른 `updated_at`을 쓰는지, 변경이 없어 `updated_at`이 오래된 경우를 어떻게 볼지 | 이주헌 (CDC 소비 위치 S15P31C106-199) · 답변 대기 |
| 4 | 스트리밍을 하지 않으므로 nginx `/api/`의 `proxy_buffering off`는 Agent에 필요 없다(`infra/nginx/conf.d/default.conf`, "결정 13"). 유지할지 정한다 | 손주연 |
| 5 | 084·085 응답 예시의 시각이 `+09:00`이다. API 규칙대로 UTC(`Z`)로 고친 Notion 본문을 준비했다. Notion에 반영하면 이 항목을 지운다 | 장민화 |

## 7. 명세 반영 필요

개발 가이드는 바꾸지 않는다. 아래 내용을 Notion API 085·084와 ERD에 반영해야 한다. 저장소에서는 Notion을 고칠 수 없으므로 담당자가 직접 수정한다.

**085 질문하고 답변 받기**

- 성공 응답을 "HTTP 200, 메시지 2건"에서 "HTTP 202, USER 메시지와 `RUNNING`인 AGENT 메시지 2건"으로 바꾼다.
- 오류 응답에 409 `CONFLICT`(같은 대화에 처리 중인 답변이 있음)를 추가한다.
- 처리 규칙에 3.1 처리 순서와 3.4 실패 처리를 넣는다.
- `answerMeta.results[].dataAsOf` 설명을 4.2로 바꾼다.
- 질문 시 동기화 규칙을 5.1·5.2로 바꾼다. `FULL_COMPARE` 테이블만 요청하고, 판정 순서를 추가한다.

**084 대화 메시지 목록**

- AGENT 메시지 `status`에 `RUNNING`을 추가하고, `RUNNING`이면 `content`가 `""`이고 `answerMeta`에 `stage`만 있다고 적는다(3.2).
- 화면은 `RUNNING`인 메시지가 있는 동안 이 API를 반복 조회한다고 적는다(3.3).
- "기준 시점이 null이면 화면은 확인 불가로 표시하고 다른 시각으로 대신하지 않는다"를 추가한다.

**ERD**

- 3.5의 `agent_message` 변경을 반영한다.
