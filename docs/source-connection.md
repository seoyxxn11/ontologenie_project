# 원천 연결 API·서비스 연동 계약

- 이슈: S15P31C106-175·224 · 기능: FN-SRC-01·02·03 · API: 009~016, 100, 102
- 기준: 원천 연결은 develop `8ad8991`, 원천 표본 조회는 224의 코드. 코드와 이 문서가 다르면 코드가 맞고, 이 문서를 고칩니다.
- 관련 문서: AI 쪽 분석 시작·결과 계약은 [ai/docs/api-internal.md](../ai/docs/api-internal.md), 원천 서버 설정은 [infra/source-db/README.md](../infra/source-db/README.md)

## 1. 누가 무엇을 하는지

| 일 | 맡는 곳 |
| --- | --- |
| 원천 등록·수정·조회(009~012), 비밀번호 암호화 저장 | Spring api |
| 연결 검사 실행(013·100), 읽을 수 있는 표·컬럼 목록(카탈로그) 갱신 | Spring batch (`SourceConnectionCheckJob`, `batch` 프로필에서만 돎) |
| 표·컬럼 목록 조회(014·015) | Spring api (batch가 채운 카탈로그를 읽음) |
| 원천 표본 요청·결과 조회(016·102) | Spring api (작업 등록 및 저장된 결과 조회) |
| 원천 표본 읽기 | Spring batch (`SourceSampleJob`, 대기 중인 작업을 주기적으로 조회) |
| 분석 시작 때 AI 계정 복호화, 접속 정보 전달(018) | Spring api (`AnalysisStartService`) |
| 원천 접속, 고른 컬럼·행만 읽기, 분석 | FastAPI (`ai/app/sources/postgres_reader.py`) |

- FastAPI는 원천 비밀번호를 저장하지 않습니다. 분석 한 번마다 018 요청 본문으로 받고, 분석이 끝나면 버립니다.
- 원천에는 읽기만 합니다. 원천 계정은 모두 `default_transaction_read_only = on`입니다(`infra/source-db/sql/setup_roles.sql`).

## 2. API

모두 관리자(ADMIN)만 쓸 수 있습니다.

| 번호 | 경로 | 하는 일 |
| --- | --- | --- |
| 009 | `GET /api/v1/sources` | 원천 목록. `sourceKind`·`connectionStatus`로 거르고 쪽 나눔(기본 20개) |
| 010 | `POST /api/v1/sources` | 원천 등록. 201 |
| 011 | `GET /api/v1/sources/{id}` | 원천 상세 + 가장 최근 연결 검사 |
| 012 | `PATCH /api/v1/sources/{id}` | 원천 수정. host·port·database가 바뀌면 소비 중인 CDC 원천을 원천 교체(`SOURCE_CHANGED`)로 멈춘다([cdc-consumer.md](cdc-consumer.md) 199) |
| 013 | `POST /api/v1/sources/{id}/test-connection` | 연결 검사 요청. 202, 실제 검사는 batch |
| 100 | `GET /api/v1/sources/{id}/connection-checks/{checkId}` | 연결 검사 결과 |
| 014 | `GET /api/v1/sources/{id}/resources` | 읽을 수 있는 표·뷰 목록 |
| 015 | `GET /api/v1/sources/{id}/resources/{resourceId}/columns` | 표의 컬럼 목록 |
| 016 | `POST /api/v1/sources/{id}/resources/{resourceId}/samples` | 원천 표본 작업 접수. 202와 `previewId` 반환 |
| 102 | `GET /api/v1/ontology/source-previews/{previewId}` | 미리보기 상태 및 결과. 표본 결과는 `columns`·`rows` |
| (번호 없음, 199) | `POST /api/v1/sources/{id}/cdc/reconnected` | 원천 교체 뒤 커넥터를 새 DB에 맞췄다고 알림. 202 `{dataSourceId, recoveryReason: "SLOT_LOST", reloadTables}`. 원천 교체 중이 아니면 409(`details.expected = SOURCE_CHANGED`) |

**010 요청 본문**

| 필드 | 규칙 |
| --- | --- |
| `code` | 영문 대문자로 시작, 대문자·숫자·밑줄만(예: `ERP_MAIN`). CDC 토픽 이름 앞부분과 같아야 합니다(`infra/cdc/README.md`) |
| `name`, `sourceKind` | 필수. `sourceKind`는 지금 `POSTGRES`만 연결 검사가 됩니다 |
| `connectionConfig` | `{host, port, database}`만 받습니다. 다른 키(예: `schema`)는 400, 비밀번호 같은 비밀값 키도 400("계정은 credentials로 보냅니다") |
| `credentials[]` | `{purpose, username, password}`. `purpose`는 `SYNC` 또는 `AI`. POSTGRES는 두 계정이 모두 있어야 하고, 파일·문서 원천에는 넣지 않습니다 |
| `syncEnabled`, `syncIntervalMinutes` | 선택 |

## 3. 계정 두 개를 나누는 이유

| 용도 | 원천 계정 | 쓰는 곳 |
| --- | --- | --- |
| `SYNC` | `platform_ro` | batch: 연결 검사, 카탈로그, 전체 다시 읽기 |
| `AI` | `platform_ai` | FastAPI: 분석. 접속 5개, 쿼리 30초로 묶음 |

- 연결 상태(`CONNECTED`·`FAILED`)는 `SYNC` 계정 기준입니다. `AI` 계정이 실패하면 계정별 결과에만 남습니다.
- 표·컬럼 목록(014·015)은 `SYNC` 계정이 읽을 수 있는 것입니다. 원천 권한으로 막은 컬럼은 목록에 나오지 않습니다(`has_table_privilege`·`has_column_privilege`로 판단, 업무 행은 읽지 않음).
- AI 계정이 볼 수 없는 표를 고르면 FastAPI가 "읽을 권한이 없다"로 실패를 보냅니다. 예: 회계 표 4개는 AI 계정 권한에서 빠져 있습니다(2026-11-04).

## 4. 비밀번호 처리

- 화면에서 받은 비밀번호는 Spring이 AES-256-GCM으로 암호화해 `data_source_credential`에 저장합니다(`SourceCredentialCipher`).
- 키는 환경변수 `AXCORE_SOURCE_CREDENTIAL_KEY_1`(32바이트 base64), 버전은 `AXCORE_SOURCE_CREDENTIAL_KEY_VERSION`입니다. 운영 값은 Jenkins Credentials `source-credential-key-1`입니다.
- 암호문에 `{data_source_id}:{purpose}`를 함께 묶어서, 다른 원천·용도의 행에 옮겨 붙이면 풀리지 않습니다.
- 비밀번호는 응답과 로그에 넣지 않습니다(`CredentialRequest.toString`이 비밀번호를 뺌).
- 복호화는 두 곳에서만 합니다. batch의 연결 검사, api의 분석 시작(018 본문, AI 계정만).

## 5. 접속 방식

- 원천은 PostgreSQL에 SSL로 접속합니다. 연결 검사는 `AXCORE_SOURCE_SSL_MODE`(기본 `require`), 분석 시작은 늘 `sslmode=require`로 FastAPI에 넘깁니다(2026-11-03 결정).
- FastAPI는 고른 컬럼과 기본키만 SELECT 하고, 표 하나는 최대 10만 행까지 읽습니다(S15P31C106-280). 자세한 것은 `ai/docs/api-internal.md` 3·4절입니다.

## 6. 흐름

1. 관리자가 원천을 등록합니다(010). 비밀번호는 암호화해 저장합니다.
2. 연결 검사를 요청합니다(013). batch가 `SYNC`·`AI` 계정으로 각각 접속해 읽을 수 있는 표·컬럼을 세고, `SYNC` 목록으로 카탈로그를 갱신합니다.
3. 화면이 결과(100)와 표·컬럼 목록(014·015)을 보여 줍니다.
4. 관리자가 분석할 표·컬럼을 고르면(018) Spring이 `AI` 계정을 복호화해 FastAPI에 보냅니다. 원천 데이터 정책의 금지 표는 접수하지 않고, 금지·민감 컬럼은 컬럼 목록에서 빼고 보냅니다([데이터 접근](data-access.md#원천-데이터-정책-391)).
5. FastAPI가 원천을 읽고 분석해 결과를 105로 보냅니다.

원천 표본을 요청하면(016) API 서버는 `source_preview`에 `SAMPLE` 작업을 등록합니다. 배치는 작업 큐를 주기적으로 조회해 대기 중인 작업을 가져옵니다. 조회 시점에 `SYNC` 계정이 읽을 수 있는 컬럼 중 원천 데이터 정책상 일반 등급 컬럼만 읽습니다. 금지 표는 표본을 읽지 않고 실패합니다(정책은 [data-access.md](data-access.md)의 원천 데이터 정책). 화면은 `previewId`로 상태와 결과를 조회합니다(102).

## 7. 남은 것·확인할 것

- **225 원천 표본 탭:** 화면은 develop `119d85d`에 들어왔고, 016·102 실제 요청 함수를 화면에 연결하는 작업이 남았습니다. [화면 연동 안내](source-samples-225.md)를 참고합니다.
- **CSV·DOCUMENT 원천:** 연결 검사는 "지원하지 않음"으로 실패합니다. 원천 목록도 PostgreSQL 카탈로그에서 TABLE·VIEW만 만듭니다(`SourceCatalogReader`).
- **로컬 시험:** 연결 검사가 batch에서만 돌아서, api와 batch를 같은 플랫폼 DB로 함께 띄워야 표 목록이 채워집니다.
