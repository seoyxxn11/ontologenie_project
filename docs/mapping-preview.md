# 객체 매핑 시험 변환 — S15P31C106-185

FN-MAP-04. 저장하기 전의 매핑 초안으로 원천 표본 행을 객체 모양으로 바꿔 본다. 업무 객체·연결·매핑은 저장하지 않는다. 화면은 S15P31C106-229(`mapping-preview-229.md`)다.

| API | 요청 | 결과 |
| --- | --- | --- |
| 040 | `POST /api/v1/ontology/source-resources/{id}/mapping-previews` | 202 `{previewId, status, joined, requestedAt, expiresAt}` |
| 102 | `GET /api/v1/ontology/source-previews/{previewId}` | 200 작업 상태와 결과(SAMPLE·MAPPING_PREVIEW 공용) |

둘 다 ADMIN만 쓸 수 있다.

## 흐름

1. **040 접수(api, `MappingPreviewService`)**: 백엔드는 원천에 접속하지 않는다. 초안을 지금 정의와 맞춰 본 뒤(`MappingPreviewPlanner`) `source_preview`에 `MAPPING_PREVIEW`·`QUEUED` 행을 넣는다. 요청 감사 기록(`SOURCE_PREVIEW`, 테이블·행 수·합류 여부만, 행 값·초안 내용 없음)을 같은 트랜잭션에 남긴다.
2. **배치 실행(`MappingPreviewJob`)**: 작업 표 확인 주기(2초)에 가져간다. 초안을 다시 맞춰 보고(요청 뒤 정의가 바뀌었을 수 있다), SYNC 계정으로 그 표의 앞 행을 최대 `rowLimit`개 읽어(순서는 정하지 않음) 전체 읽기와 같은 변환기(`SourceRowConverter`)로 바꾼다. 결과를 작업 행에 쓰고 SUCCEEDED로 바꾼다.
3. **102 조회(`SourcePreviewQueryService`)**: 작업 행만 읽는다. 결과 조회는 감사 기록을 남기지 않는다.
4. **정리(`SourcePreviewCleanupJob`)**: 결과에 원천 값이 들어 있어 요청 1시간 뒤(`expires_at`) 지운다(10분마다 확인, `axcore.source-preview.cleanup-delay`). 지운 뒤나 시각이 지났는데 아직 안 지운 작업은 102가 404다. 화면은 다시 요청한다.

## 040 요청

```json
{
  "mapping": {
    "objectTypeId": 1,
    "keyColumns": ["wo_no"],
    "rowFilter": {},
    "propertyMappings": [
      {"propertyDefinitionId": 11, "sourceColumnId": 201, "sourcePath": null,
       "transformSteps": [{"op": "STRIP_PREFIX", "arg": "W"}]}
    ]
  },
  "rowLimit": 20
}
```

- `rowLimit` 1~20, 기본 20. `rowFilter`·`propertyMappings`·`transformSteps`는 비우면 빈 값이다.
- **변환 단계는 038·039와 같은 `{"op", "arg"}` 형식이다.** 040 명세 예시의 `{"code", "args"}`가 아니다. 화면이 속성 매핑 편집 중인 값을 그대로 보내므로 저장 API와 같은 형식을 쓴다.
- **합류:** 같은 관리자가 같은 테이블에 같은 초안·같은 행 수로 넣은 작업이 대기·실행 중이면 새로 넣지 않고 그 작업을 돌려준다(`joined=true`). 빈 칸을 기본값으로 채운 모양으로 저장해 jsonb로 비교한다. 동시에 같은 요청이 와도 관리자·테이블별 잠금(`pg_advisory_xact_lock`)으로 하나만 넣는다.
- **배치를 깨우는 NOTIFY는 쓰지 않는다.** 배치가 2초마다 작업 표를 본다(다른 작업 표와 같음).

### 검증

| 상황 | 응답 |
| --- | --- |
| `mapping` 없음, `keyColumns` 비어 있음, `rowLimit` 범위 밖, `sourceColumnId`와 `sourcePath`를 둘 다 보내거나 둘 다 안 보냄 | 400 |
| 키 컬럼 중복, 같은 속성 두 번, 041에 없는 변환 함수·잘못된 인자 | 400 (`field`는 `mapping.…` 위치) |
| 없는 객체 유형, 이 테이블에 없는(또는 사라진) 컬럼, 다른 유형의 속성, 앞뒤가 안 맞는 변환 단계 | 422 `BUSINESS_RULE_VIOLATION` |
| `rowFilter`가 비어 있지 않음, `sourcePath`(문서 경로) | 422. 전체 읽기가 아직 못 읽는 매핑이라 미리보기에서만 보여 주지 않는다 |
| SYNC 계정이 읽을 수 없는 테이블(`isReadable=false`) | 422 |
| 원천 데이터 정책(391, [data-access.md](data-access.md)): 금지 표·키 컬럼·속성 컬럼 | 422 `details.forbiddenColumns`(표 전체면 `*`) |
| 원천 데이터 정책(391): 민감 속성(사람이 지정한 것 포함) | 422 `details.reason` RESTRICTED_PROPERTY |
| 원천 데이터 정책: 민감 컬럼(키·속성) | 422 `details.restrictedColumns`. 관리자는 민감 값을 볼 수 없어 미리보기에서 읽지 않는다. 그 컬럼을 빼고 미리본다 |
| 없는 원천 테이블 | 404 |

## 102 응답

`id`, `previewKind`, `sourceResourceId`, `status`, `requestedAt`, `startedAt`, `finishedAt`, `expiresAt`, `result`, `errorMessage`. 비어 있을 수 있는 칸도 `null`로 내보낸다.

MAPPING_PREVIEW 결과:

- `objects[]`: `sourceKey`(키 컬럼 → 키 문자열, 수집과 같은 형식), `displayName`(대표 속성, 없으면 키 값), `properties`(속성 코드 → 바꾼 값). 바꾸지 못한 속성은 빠진다.
- `rowErrors[]`: `rowIndex`(읽은 순서, 0부터), `column`, `transform`, `message`.
  - 키 값이 비어 객체가 되지 않은 행: `column`=키 컬럼, `transform`=null.
  - 변환 함수에서 실패: `transform`=그 함수 코드, `message`=함수가 낸 이유(원천 값이 들어갈 수 있다. 관리자에게만 1시간 보인다).
  - 변환 뒤 속성 자료형으로 못 바꿈: `transform`=null, `message`에 자료형.

작업 실패(FAILED)는 `errorMessage`만 있다. 원천 접속·권한·테이블 없음은 SQLSTATE로 고른 문구이고 드라이버 메시지(주소·계정명)는 남기지 않는다. 요청 뒤 정의가 바뀌어 초안이 맞지 않으면 "요청한 뒤 정의나 원천 카탈로그가 바뀌어 …"다.

102는 다른 관리자가 요청한 작업도 보여 준다(명세: 관리자 전용, 별도 가림 없음).

## 원천 표본(016)과 나누는 것

- `source_preview` 표와 102는 016(S15P31C106-224)과 같이 쓴다.
- 이 처리기는 `preview_kind = 'MAPPING_PREVIEW'`만 가져간다(`claimCondition`). SAMPLE은 016 쪽 처리기가 따로 가져가면 된다. 102는 SAMPLE 결과(`columns`·`rows`)도 그대로 돌려준다.
- 만료 정리는 두 종류 모두 지운다.
- 오래 멈춘 RUNNING은 공통 정리(`StaleJobCleanupJob`, `axcore.batch.timeout.source-preview` 10분)가 시작 시각부터 재서 FAILED로 바꾼다. 이 작업은 표본 20행만 읽어 heartbeat를 두지 않는다.

## 조회·저장 방식

- `source_preview`는 Entity를 두지 않는 작업 표라 `SourcePreviewRepository`(JdbcClient)로 다룬다(`sync_run`·`source_connection_check`와 같다). 합류 비교(jsonb)와 잠금도 여기서 한다.
- 초안 확인은 원천 테이블·컬럼·객체 유형·속성 Entity(JPA)로 읽는다.
- 원천 읽기는 `SourceTableReader.sample`(읽기 전용 트랜잭션, `limit` 바인딩 변수)이다.

## 시험

- `MappingPreviewApiTests`(api, 원천 접속 없음)
  - 접수 202, 저장된 요청 모양, 만료 시각, 감사 기록, 같은 초안 합류(빈 칸을 채워 보내도), 행 수가 다르면 새 작업
  - 400 6가지, 422 8가지(필드 위치 포함), 404, 422 때 작업이 안 생김
  - 102: QUEUED·SUCCEEDED·FAILED 응답, 만료·없는 ID 404, 0은 400
  - 401, 403(CSRF 없음 포함)
- `MappingPreviewJobTests`(batch, 같은 컨테이너의 실제 원천 스키마)
  - 초안 3속성으로 3행: 객체 3개, 날짜 아님(자료형 오류)·접두어 없음(`TO_INTEGER` 실패) 행별 오류 2개, 업무 객체 0개, 결과 감사 기록엔 건수만
  - `rowLimit` 2로 4행 중 2행만, 키가 빈 행은 행별 오류
  - SYNC 계정 권한을 회수하면 FAILED(권한 문구)
  - 만료된 미리보기만 정리

## 기준

- Notion API 040·102. 로컬 사본: 문서 저장소 `erd/ontology-mvp-v2/notion/api_fixed/040.md`, `102.md`.
- 스키마: `V1__create_ontology_core.sql`의 `source_preview`. DB 스키마·의존성 변경은 없다.
