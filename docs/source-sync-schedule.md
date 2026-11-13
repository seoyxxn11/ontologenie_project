# FULL_COMPARE 주기 수집과 수집 주기 설정 (S15P31C106-318)

CDC로 받을 수 없는 원천 테이블(`source_resource.sync_strategy = FULL_COMPARE`, 뷰 등)을 정한 주기마다 전체 읽기로 반영한다. 기능 FN-BAT-01·02, API 071·072.

- 코드: `source.service.SyncScheduleService`(071·072), `ScheduledSyncJob`·`ScheduledSyncService`(배치), `source.repository.ScheduledSyncRepository`
- 실제 읽기는 수동 수집(073)과 같은 `SyncRunJob`이다([전체 읽기](full-load.md)).

## API 071·072

| API | 경로 | 내용 |
|---|---|---|
| 071 | `GET /api/v1/sources/{id}/sync-schedule` | `syncEnabled`, `syncIntervalMinutes`, `rowVersion` |
| 072 | `PUT /api/v1/sources/{id}/sync-schedule` | 같은 세 필드를 받아 바꾼다 |

- 저장 위치는 `data_source.sync_enabled`, `sync_interval_minutes`, `row_version`이다(V1). 새 원천은 `false`·`null`이다. Schema 변경은 없다.
- 둘 다 ADMIN만 쓴다. `syncEnabled`가 `true`이면 `syncIntervalMinutes`(1 이상)가 필요하다.
- 072는 보낸 값으로 바꾼다. `syncEnabled=false`이고 주기를 생략하면 주기는 `null`이 된다. 값이 그대로면 `rowVersion`을 올리지 않는다.
- `rowVersion`이 서버 값과 다르면 409(`VERSION_CONFLICT`)다.
- 072는 관리 Action `UPDATE_SYNC_SCHEDULE`(권한 `SYNC_SCHEDULE_MANAGE`)이다.
  - 성공: `action_execution`(ACTION, WEB, `data_source`)과 바뀐 컬럼의 `entity_change`(UPDATE)를 같은 트랜잭션에 남긴다.
  - 권한(403)·업무 검증(400)·없는 원천(404)·버전(409)에서 막히면 `BLOCKED`와 사유를 따로 남긴다. 없는 원천이면 대상은 비운다.
  - 요청 형식 검사(Bean Validation)에서 막힌 400은 Controller에서 끝나 기록하지 않는다. Action 정의 088·090과 같다.

## 409 프론트 연동

데이터 연결의 수집 대상 탭에서 PostgreSQL 원천에 FULL_COMPARE 자원이 있으면 071로 설정을 조회하고, 072에 세 필드와 조회한 `rowVersion`을 전송한다. 저장 후 설정·원천 목록·상세를 다시 조회한다. CDC 변경 반영 간격과는 별개임을 화면에 표시한다.

중복 제출과 자동 재시도를 막는다. 입력 오류(400·422)는 입력을 유지하고 수정할 수 있다. 충돌·권한·없는 대상·통신 오류·응답 미확인에서는 다시 저장하지 못하게 하며, 최신 설정을 명시적으로 다시 불러와 저장된 값과 버전을 확인해야 한다. 이 동작은 편집 중 입력을 서버 값으로 대체한다는 안내를 제공한다. 조회 실패 시 저장 폼을 표시하지 않는다.

원천별 저장 요청을 화면 이동 후에도 추적하며 데이터 연결의 저장 요청 상태에서 결과와 대상을 확인할 수 있다. 진행 중에는 재제출을 막고 실패한 입력·잠금은 복귀해도 유지한다. 미저장 주기 입력의 탭·원천·메뉴·뒤로 가기는 폐기 확인을 제공하고 재로드에는 브라우저 기본 경고를 요청한다. 저장 성공분은 미저장 입력으로 취급하지 않는다.

기존 배치·CDC·분류 정책은 수정하지 않았고 별도 스케줄러를 추가하지 않았다.

## 배치 주기 수집

`ScheduledSyncJob`이 `axcore.source-load.schedule-watch.delay`(기본 30초)마다 확인한다. batch 프로필에서만 돈다.

1. 주기가 된 원천을 찾는다.
   - `sync_enabled`가 켜져 있다.
   - 그 원천의 마지막 `SCHEDULED` 실행 접수 시각(`sync_run.requested_at`)에서 주기가 지났다. 없으면 바로 넣는다.
   - 켜진 매핑이 있고 대기·실행 중이 아닌 `FULL_COMPARE` 테이블이 있다.
2. 원천마다 한 트랜잭션에서 넣는다.
   - 원천 행을 `FOR NO KEY UPDATE`로 잠그고 주기 조건을 다시 본다. 여러 배치 인스턴스가 같은 원천을 함께 골라도 한 번만 들어간다.
   - `sync_run`을 `trigger_kind=SCHEDULED`, 요청자 없이 넣는다.
   - 켜진 매핑이 있는 `FULL_COMPARE` 테이블만 `mode=FULL`로 넣는다. CDC 테이블은 넣지 않는다.
   - 이미 대기·실행 중인 테이블은 그 실행에 합류한 것으로 보고 넣지 않는다(217 규칙).
   - 넣은 테이블이 없으면 롤백한다. 실행을 남기지 않고 다음 확인에서 다시 본다.
   - 감사 기록 접수 줄(SYSTEM + BATCH, `SYNC_RUN`)에 넣은 테이블(`sourceResourceIds`)과 합류한 테이블(`joinedSourceResourceIds`)을 남긴다.
3. 실패한 실행도 주기 기준에 들어간다. 다음 주기에 다시 넣는다.

## 설정

| 키 | 기본값 | 뜻 |
|---|---|---|
| `axcore.source-load.schedule-watch.enabled` | `true`(`AXCORE_SOURCE_SCHEDULE_ENABLED`) | 끄면 작업을 띄우지 않는다. 시험 설정은 `false`다 |
| `axcore.source-load.schedule-watch.delay` | 30s | 주기가 됐는지 확인하는 간격 |

- 스케줄러 스레드 풀(`spring.task.scheduling.pool.size`, batch)을 8에서 9로 늘렸다. 주기 작업 8개와 여유 1개다.
- batch 프로필 시험은 시험 설정(`src/test/resources/config/application-batch.yml`)으로 이 작업을 끈다. 넣는 로직은 `ScheduledSyncService`를 직접 불러 시험한다.

## 한계

- 대기·실행 중인 실행에 모든 테이블이 합류하면 주기 기준이 앞으로 가지 않는다. 그 실행이 끝나면 다음 확인에서 바로 넣는다.
- 원천 종류를 거르지 않는다. 수동 수집(073)과 같다. `SyncRunJob`은 PostgreSQL 원천만 읽으므로 다른 종류에 주기를 켜면 실행이 실패로 남는다.
- 주기 기준 조회는 `ix_sync_run_source_requested`(원천, 접수 시각)를 거꾸로 훑는다. 마지막 SCHEDULED 뒤의 CDC 실행 행만큼 읽는다.
