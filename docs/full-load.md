# 원천 테이블 전체 읽기 (Jira 197)

원천 테이블을 처음부터 끝까지 읽어 객체로 적재하는 배치 작업이다. 첫 적재, 매핑 변경 뒤 다시 읽기, 복구(RECOVERY), FULL_COMPARE 원천(뷰 등)의 수집이 모두 이 작업이다. 화면·API는 없다.

- 코드: `source.service.SyncRunJob`(작업), `FullLoadPlanner`(매핑 계획), `SourceRowConverter`(값 변환), `source.repository.SourceTableReader`(원천 읽기), `SyncRunRepository`(실행 기록)
- 객체 쓰기 규칙: [원천 객체 저장](object-sync.md), 결정 문서 `decisions/2026-11-05-cdc-initial-load.md`

## 실행을 만드는 쪽이 할 일

이 작업은 이미 들어온 실행만 처리한다. 실행을 만드는 곳(API 073 수동 수집, 매핑 변경 뒤 다시 읽기, 복구, FULL_COMPARE 주기 수집([source-sync-schedule.md](source-sync-schedule.md)))은 아래처럼 넣는다.

- `sync_run`: `status=QUEUED`, `trigger_kind`(MANUAL·RECOVERY·SCHEDULED·ON_DEMAND), 사람이 요청했으면 `requested_by`
- `sync_run_resource`: 읽을 테이블마다 `mode=FULL`, `status=QUEUED`
- 같은 테이블이 이미 대기·실행 중이면 `uq_sync_run_resource_active` 때문에 넣을 수 없다. 그 실행에 합류한다(217).

## 흐름

1. **run(트랜잭션 밖)**
   - 테이블마다 켜진 매핑으로 계획을 만든다. 원천 데이터 정책을 어긴 매핑(391·402), 행 조건이 있는 매핑, 문서 경로 속성, 원천에서 없어진 컬럼을 가리키는 매핑이 있으면 실패한다. 같은 판단을 유형 공개 검사와 위반 보고 API도 쓴다(`FullLoadPlanner.violations`). 다른 트랜잭션 안에서는 예외 대신 사유를 돌려주는 `tryPlan`을 쓴다([CDC 소비](cdc-consumer.md) "적재할 수 없는 테이블").
   - SYNC 계정으로 원천에 접속해 `REPEATABLE READ` 읽기 전용 트랜잭션 하나에서 `pg_current_snapshot()`, 현재 WAL LSN, 모든 테이블의 SELECT를 한다. 한 실행의 테이블은 같은 스냅샷으로 읽힌다.
   - 행을 객체 값으로 바꾼다.
2. **complete(한 트랜잭션)**
   - 원천 테이블 행을 잠그고 계획을 다시 만들어 비교한다. 읽는 동안 매핑·속성·객체 유형이 바뀌었으면 쓰지 않고 실패한다(다시 수집 필요).
   - 객체를 쓴다(`applyLoaded`). 세대는 `sync_run.id`다. 매핑마다 `finishLoad`로 이번에 안 나온 객체를 삭제 표시한다.
   - CDC 테이블은 `full_load_done_gen`을 이번 세대로, `loaded_lsn`을 읽기 시점 LSN으로 올린다(더 새 세대가 이미 끝났으면 그대로). 원천이 복구 중인데 이 실행이 복구 표시보다 먼저 시작했으면 `loaded_lsn`은 그대로 둔다(199). FULL_COMPARE 테이블은 두 칸을 쓰지 않는다.
   - 변환 실패를 수집 실패 기록(`source_load_failure`, 결정 문서 9.2)에 남긴다(362). 키를 만들지 못한 행은 키 없이(같은 매핑·컬럼·이유면 하나로 모임), 값을 바꾸지 못한 속성은 원천 키와 함께다. 같은 실패가 이미 열려 있으면 횟수와 `last_sync_run_id`만 올린다. 원천 키가 겹쳐 쓰지 않은 행의 실패는 남기지 않는다.
   - 그 매핑의 열린 실패 중 이번 읽기에서 다시 나오지 않은 것을 닫는다(`closeNotSeenInFullLoad`). 다시 읽어 회복한 행의 실패가 열린 채 남아 검사의 미연결 보류(check-run "미연결 보류")가 풀리지 않는 일을 막는다. 단 이 실행이 시작한 뒤 본 실패(그 사이 CDC가 남긴 것)는 이 스냅샷이 보지 못한 변경일 수 있어 닫지 않는다.
   - `sync_run_resource`를 SUCCEEDED(읽은 행, 쓴 행, 삭제 표시 수, CDC 테이블은 `to_lsn`), `sync_run`을 SUCCEEDED로 바꾼다.
   - 마지막에 읽은 매핑의 객체 유형으로 연결을 다시 계산한다(`LinkRecomputeService.recomputeObjectTypes`, 198-7). 트랜잭션당 한 번, 객체를 다 쓴 뒤에 부른다. issue는 만들지 않는다(결정 문서 8장).
   - CDC 테이블을 읽었으면 그 원천의 다시 읽기가 다 끝났는지 보고 CDC 복구를 푼다([cdc-consumer.md](cdc-consumer.md) 199).
3. 실패하면 `sync_run`과 끝나지 않은 `sync_run_resource`를 함께 FAILED로 바꾼다. 테이블 행을 남겨 두면 같은 테이블을 다시 넣을 수 없다.

## 값 변환

| 무엇 | 규칙 |
|---|---|
| 원천 키 | 키 컬럼 값을 공용 형식(`source.domain.SourceKeyText`, [원천 객체 저장](object-sync.md))의 문자열로 바꾼다. DB 세션 시간대에 따라 달라지는 PostgreSQL 텍스트 출력은 쓰지 않는다. 키 값이 비어 있는 행은 객체로 만들지 않는다(감사 기록 `skippedRows`, 수집 실패 `KEY_MISSING`) |
| 같은 원천 키 행 | 한 매핑에서 원천 키가 같은 행이 또 나오면(키가 유일하지 않은 뷰 등) 첫 행만 쓰고 나머지는 건너뛰고 센다(감사 기록 `duplicateKeyRows`, 경고 로그). 어느 행이 먼저인지는 정해지지 않으므로, 이 값이 0이 아니면 매핑 키를 고쳐야 한다 |
| 속성(변환 없음) | 읽은 값을 속성 자료형으로 바꾼다. STRING 문자열, INTEGER 정수, DECIMAL 숫자 문자열(`"3.00"`), DATE·TIME ISO 문자열, DATETIME 시간대 포함 ISO 문자열, BOOLEAN, JSON 그대로 |
| 속성(변환 있음) | 값을 문자열로 바꿔 변환 단계를 적용한 뒤 자료형으로 바꾼다(`TransformStepsValidator` 설명과 같다) |
| 바꾸지 못한 값 | 그 속성만 빼고 개수를 센다(감사 기록 `failedValues`, 수집 실패 `VALUE_CONVERSION`·`UNSUPPORTED_TYPE`). 원천 값은 로그·기록에 남기지 않는다. numeric의 `NaN`·`Infinity`도 여기에 해당한다(STRING 속성이면 그 글자로 남는다) |
| 빈 값 | 속성에 `null`로 남긴다 |
| 시간대 없는 timestamp | `axcore.source-load.zone-id`(기본 Asia/Seoul)의 시각으로 본다 |
| 표시 이름 | 객체 유형의 대표 속성 값, 없으면 원천 키 값을 ` / `로 이은 것. 300자(`display_name varchar(300)`)를 넘으면 자른다 |

## 설정

| 키 | 기본값 | 뜻 |
|---|---|---|
| `axcore.source-load.max-rows` | 200000 | 한 실행에서 읽는 행 수 상한. 넘으면 실패한다 |
| `axcore.source-load.zone-id` | Asia/Seoul | 시간대 없는 timestamp의 시간대 |

## 아직 하지 않은 것

- 실행을 만드는 쪽: API 073(수동 수집), 매핑 변경 뒤 자동 다시 읽기 요청, 217 합류. 지금 매핑 변경은 `loaded_lsn`만 비운다.
- 큰 테이블을 나눠 읽기(청크마다 스냅샷). 지금은 행을 모두 메모리에 올리고 `max-rows`로 막는다.
- 실행 중 `heartbeat_at` 갱신과 오래 멈춘 RUNNING 정리(U3).
