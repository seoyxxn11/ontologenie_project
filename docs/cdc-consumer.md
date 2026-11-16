# CDC 소비자 (Jira 198·199)

Debezium이 원천마다 토픽 하나(`{data_source.code}.all`)에 보낸 변경을 읽어 객체에 반영한다. 작은 단위로 나눠 만든다. 규칙의 근거는 결정 문서 `decisions/2026-11-05-cdc-initial-load.md` 8·9·10장(작업 폴더)이다.

| 단위 | 내용 | 상태 |
|---|---|---|
| 198-1 | Debezium 값 해석, 197과 같은 정규형 행(`SourceRow`) | 완료, 이 문서 아래 |
| 198-2 | 메시지 해석·원천 트랜잭션 조립(BEGIN~END) | [cdc-message-contract.md](cdc-message-contract.md) |
| 198-3 | txId epoch 복원 | 완료(`TransactionIdRestorer`) |
| 198-4 | V12(원천별 소비 상태, 수집 실패 기록, 확인 대기) | 이 문서 아래 |
| 198-5 | 원천 트랜잭션 하나 → 플랫폼 트랜잭션 하나 | 이 문서 아래 |
| 198-6 | Kafka 소비 루프 | 이 문서 아래 |
| 198-7 | 연결 재계산 호출(181 `LinkRecomputeService`) | 이 문서 아래 |
| 199 | 복구 해제와 이어 받기, 원천 교체(012)·커넥터 재연결 알림 | 이 문서 아래 |

## 198-1 값 해석

전체 읽기(197)와 CDC 소비자(198)는 같은 원천 행에서 **같은 원천 키 문자열과 같은 속성 값**을 만들어야 한다. 객체와 삭제 흔적은 `source_key`를 jsonb로 그대로 비교하기 때문이다.

```
197: JDBC 값  ── SourceTableReader ──┐
                                     ├─ SourceRow(정규형 값, 키 문자열) ── SourceRowConverter ── SyncedObject
198: Debezium 값 ── DebeziumRowDecoder ┘
```

- 입력 해석만 경로마다 다르고, 정규형 행 이후(매핑·변환·키 문자열 `SourceKeyText`)는 같은 코드다.
- `DebeziumRowDecoder`는 메시지 스키마의 형식과 카탈로그 원천 자료형(`source_column.source_data_type`)을 같이 본다. numeric은 `decimal.handling.mode=string`이라 스키마로는 문자열 컬럼과 구별되지 않기 때문이다.

| 원천 형식 | Debezium 값 | 정규형 |
|---|---|---|
| int2·int4·int8 | 정수 | Long |
| numeric | 문자열(NaN은 `NAN`) | BigDecimal(NaN·무한대는 Double) |
| float4·float8 | 실수 | Double |
| boolean | 참거짓 | Boolean |
| date | 1970-01-01부터 일수(`io.debezium.time.Date`) | LocalDate |
| time | 자정부터 µs(`MicroTime`), time(0~3)은 ms(`Time`) | LocalTime |
| timestamp | 1970-01-01T00:00부터 µs(`MicroTimestamp`), timestamp(0~3)은 ms(`Timestamp`) | LocalDateTime |
| timestamptz | ISO 문자열 UTC(`ZonedTimestamp`) | OffsetDateTime(UTC) |
| json·jsonb | 원천이 정규화한 JSON 텍스트(`io.debezium.data.Json`) | 읽은 JSON 값. 키 문자열은 텍스트 그대로 |
| uuid, text·varchar·char | 문자열 | String |

- **지원하지 않는 형식**: bytea, 배열, timetz, interval 등. `UnsupportedValueException`을 던진다(값은 메시지에 넣지 않는다). 197은 이 형식을 문자열로 읽으므로 두 경로 값이 다르다. 이 형식의 컬럼을 매핑하지 않게 막는 일은 198-5에서 정한다.
- **변경되지 않은 TOAST 값**(`__debezium_unavailable_value`)은 값에 넣지 않고 `unavailableColumns`로 알린다. 198-5는 그 속성의 기존 값을 유지한다.

### 비교 시험

`DebeziumRowDecoderParityTests`가 같은 원천 행을 두 경로로 넣어 비교한다.

- 원천 행: `src/test/resources/cdc-parity/typed.sql`(형식 17가지 + 복합 키·날짜 키·시각 키·문자 키 표, 빈 값·NaN·1970년 이전·마이크로초·시간대·이모지 포함)
- Debezium 쪽: 같은 SQL을 Debezium 3.6.3(서버와 같은 커넥터 설정, 원천 토픽 하나)에 흘려 받은 **실제 메시지** `cdc-parity/debezium-typed-events.jsonl`. SQL을 고치면 메시지도 다시 받는다.
- 확인: 모든 행의 정규형 값과 키 문자열이 같다. 모든 속성 자료형(STRING~JSON)으로 매핑했을 때 같은 객체가 나오고 변환 실패 수도 같다.

## 198-4 V12 표

`V12__track_cdc_consumer_and_pending_checks.sql`. 표만 만들고, 쓰는 코드는 198-5 이후에 붙인다.

| 표 | 바뀐 것 | 쓰는 곳 |
|---|---|---|
| `cdc_offset` | 원천마다 한 행(`{code}.all`, 파티션 0). `last_txid`(epoch 복원 기준값), `last_commit_at`, `last_message_at`, `processing_lag_ms`, `healthy_since`, `recovery_reason`·`recovery_at` 추가 | 198-5가 원천 트랜잭션을 반영하는 같은 트랜잭션에서 쓴다. 복구가 필요하면(`recovery_reason`) `healthy_since`는 NULL(CHECK) |
| `source_load_failure` | 새 표. 객체로 만들지 못한 행(키 없음·키 형식)과 값 변환 실패. 열린 기록은 같은 실패당 하나이고 횟수만 올린다 | 197·198이 쓴다. issue로 올리지 않는다. 도착 표의 연결 기준 컬럼에 열린 기록이 있으면 미연결 판정 보류 |
| `link_check_pending` | 새 표. 원천 사이 규칙의 미연결 확인 대기(WAITING → TRANSIENT 또는 PROMOTED) | SYNC 검사(199·241). 승격과 issue 등록은 한 트랜잭션 |
| `check_run` | `sync_run_id`가 SYNC에서도 선택(`ck_check_run_sync`) | 배치가 모아서 돌리는 SYNC는 `data_basis`로 기준을 남긴다 |

시험은 `DatabaseMigrationTests`(제약마다 정상·거부).

## 198-5 원천 트랜잭션 반영

`source/cdc/CdcTransactionApplier`. 198-6 소비 루프가 원천 트랜잭션을 END까지 모은 뒤 `apply(source, tx, initialReference, receivedAt)`를 부른다. 한 호출이 한 플랫폼 트랜잭션이다(결정 문서 10.2·11장).

1. 원천의 `cdc_offset` 행을 `FOR UPDATE`로 잡는다(없으면 만든다). END가 다음 위치보다 앞이면 `ALREADY_APPLIED`(아무것도 안 씀). 복구가 필요한 원천이면 `RECOVERY_PENDING`.
2. `last_txid`(없으면 `initialReference` = 원천 xmax)로 txId epoch를 복원한다. 확신할 수 없으면 `recovery_reason = EPOCH_UNCERTAIN`, `healthy_since = NULL`, 그 원천 CDC 표 `loaded_lsn = NULL`만 쓰고 `RECOVERY_NEEDED`. 위치는 옮기지 않는다.
3. 변경이 걸린 표를 찾아 원천 테이블 행을 ID 순서로 `FOR SHARE` 잠근다. 전체 읽기 저장·매핑 변경(`FOR UPDATE`)과 잠금 순서가 같아져 교착이 없고, 반영 중 매핑이 바뀌지 않는다. 매핑 계획은 197과 같은 `FullLoadPlanner`다. 없는 표·CDC가 아닌 표·켜진 매핑이 없는 표·지금 적재할 수 없는 매핑(정책 위반, 행 조건 등)은 건너뛴다(`skippedEvents`). 적재할 수 없는 표는 다시 읽기를 표시한다(402, 아래 "적재할 수 없는 테이블").
4. 변경을 원천 커밋 순서대로 쓴다. 값 해석은 **매핑한 컬럼만** 한다. 해석하지 못하는 형식의 컬럼은 빼고 다시 해석해 그 컬럼만 실패로 남긴다.

| 변경 | 처리 |
|---|---|
| `c`·`u` | 매핑마다 `SourceRowConverter` → `ObjectSyncService.applyChange`. `before`에 다른 키가 오면 옛 키부터 삭제 표시(PK 변경) |
| `u`의 TOAST 미제공 값 | 그 속성은 기존 값 유지. 객체가 없어 유지할 값이 없으면 그 속성 없이 만들고 표를 다시 읽기 대상(`loaded_lsn = NULL`)으로 |
| `d` | `before`의 키로 `applyDelete`. 그 키의 열린 수집 실패 기록을 닫는다 |
| `t` | 그 표를 다시 읽기 대상으로. 행 정리는 다시 읽기의 "안 나온 객체 삭제 표시"가 한다 |
| 데이터 불량 | `source_load_failure`에 남기고 진행(키 없음·키 형식 → 객체 없음, 값 변환·지원하지 않는 형식 → 그 속성만 뺌). 같은 매핑·키를 다시 쓸 때 이번에 실패하지 않은 열린 기록은 닫는다 |

5. 변경이 있으면 `sync_run`(CDC, SUCCEEDED) 하나와 표별 `sync_run_resource`(INCREMENTAL, LSN 구간·건수)를 남긴다. 반영한 변경이 없으면 만들지 않는다.
6. `cdc_offset`에 다음 위치(END + 1)·`last_txid`·`last_commit_at`·`last_message_at`·`processing_lag_ms`를 쓰고, `healthy_since`가 비어 있으면 지금으로 채운다.

처리 장애(DB 예외 등)는 잡지 않는다. 전체가 롤백되어 위치가 그대로이고 198-6이 같은 트랜잭션을 다시 반영한다. 결과의 `changedObjectIds`(쓰거나 삭제 표시한 객체)로 같은 트랜잭션 마지막에 연결을 다시 계산한다(198-7, 아래).

`markRecovery(source, reason)`는 198-6(트랜잭션 크기 초과·메시지 순서 오류·보관 초과)도 부른다. 다시 읽기 실행은 배치 감시 작업이 `RECOVERY`로 넣고, 표시는 다시 읽기를 저장하는 트랜잭션이 푼다(199, 아래).

알려진 한계: 대표 속성(표시 이름)이 TOAST 미제공 값이면 표시 이름이 원천 키 값으로 바뀐다(2KB 넘는 표시 이름에서만 생긴다).

### 시험

`CdcTransactionApplierTests`(실제 PostgreSQL). 198-2의 실제 메시지(트랜잭션 756~765)를 파서·조립기에 흘려 반영한다.

- 순서대로 반영: 수정값, 삭제 표시, PK 변경(옛 키 삭제·새 키 생성), TOAST 값 유지, TRUNCATE 다시 읽기 표시, 위치·`last_txid`, 트랜잭션마다 CDC 실행
- 중간 변경에서 장애 → 객체·실행·위치 모두 없음
- 재전송: 위치로 거르고, 위치를 잃고 처음부터 받아도 LSN으로 값이 되돌아가지 않음
- 전체 읽기 스냅샷에서 보이는 트랜잭션은 건너뛰고, 안 보이는 트랜잭션은 반영
- 값 변환 실패 기록 → 키 삭제로 닫힘, 새 키는 다시 열림
- 해석할 수 없는 형식의 매핑 컬럼 하나 → 행은 반영하고 그 속성만 실패
- 객체 없이 TOAST 미제공 값 → 속성 없이 만들고 다시 읽기 표시
- epoch 불확실 → 복구 표시, 위치·객체 그대로, 다음 트랜잭션도 반영 안 함
- 매핑된 변경이 없는 트랜잭션 → 실행 없이 위치만 전진

## 198-7 연결 재계산 호출

181의 `LinkRecomputeService`(`docs/link-recompute.md`)를 수집 트랜잭션 안에서 부른다(결정 문서 9.4). issue는 만들지 않는다(SYNC 검사가 만든다).

| 부르는 곳 | 호출 | 언제 |
|---|---|---|
| 198-5 `CdcTransactionApplier.apply` | `recomputeObjects(changedObjectIds)` | 소비 위치까지 저장한 뒤 마지막에. 쓰거나 삭제 표시한 객체가 없으면 부르지 않는다. 결과는 `Result.links`로 돌려준다(보류 건 처리 238에 넘길 수 있다) |
| 197 `SyncRunJob.complete` | `recomputeObjectTypes(읽은 매핑의 객체 유형)` | 모든 표를 쓰고 표별 기록을 남긴 뒤 마지막에 |

- 재계산 서비스는 관계 유형 잠금을 트랜잭션 끝까지 쌓는다. 그래서 트랜잭션당 한 번, 객체를 다 쓴 뒤에만 부른다(잠금 순서: 원천 테이블 행 → 객체 → 관계 유형).
- 같은 트랜잭션이라 반영이 롤백되면 연결도 같이 롤백된다. 객체와 연결이 갈라지지 않는다.
- 늦게 들어온 쪽이 양쪽으로 다시 계산해 연결을 완성한다. 예: 작업지시만 있을 때는 연결이 없고, 설비가 들어오는 트랜잭션이 그 설비 유형이 도착인 관계 유형을 다시 계산해 이어 준다.
- 원천에서 지운 객체(`DELETED_AT_SOURCE`)의 연결은 INACTIVE가 된다(지우지 않음).

### 시험

- `CdcTransactionApplierTests.recomputesLinksOfChangedObjectsInSameTransaction`: 실제 메시지로 756에서 7→3·8→3 생성, 758에서 8 삭제로 꺼지고 9→3 생성, 761(설비 PK 변경)에서 모두 INACTIVE, issue 없음
- `SyncRunJobTests.fullLoadRecomputesLinksOfLoadedTypes`: 작업지시만 읽으면 연결 없음, 열린 주문(뷰)을 읽은 실행이 연결을 완성

## 198-6 Kafka 소비 루프

`source/cdc/CdcConsumerSupervisor`(배치 프로필, `axcore.cdc.consumer.enabled=true`일 때만)가 CDC 표가 있는 원천마다 `CdcSourceConsumer` 하나를 전용 스레드로 띄운다. `@Scheduled` 스레드 풀과 따로 돈다(결정 문서 11장 7). 의존성은 `kafka-clients`만 쓴다(spring-kafka 아님).

| 단계 | 처리 |
|---|---|
| 시작 위치 | `cdc_offset.next_offset`으로 assign + seek. Kafka에 위치를 커밋하지 않는다(그룹 없음). 처음 소비하는 원천은 토픽 첫 위치부터 |
| 보관 기간 초과 | 저장된 위치가 토픽 첫 위치보다 앞이면 복구(`OFFSET_EXPIRED`) |
| 처음 epoch 기준값 | `last_txid`가 없으면 `CdcStartReference`가 SYNC 계정으로 원천 `pg_current_snapshot()` xmax를 읽는다(쓰지 않음) |
| 변경·경계 | 198-2 조립 → END가 오면 198-5 반영(198-7 연결 재계산 포함) |
| heartbeat | `last_message_at` 갱신. 트랜잭션 밖이면 위치를 그 다음으로 옮긴다(앞으로만). 트랜잭션 중간이면 옮기지 않는다 |
| 수집 정상 여부 | 메시지 시각(heartbeat 시각·변경 처리 시각)보다 `max-lag`(2분) 넘게 늦거나, `stale-after`(3분) 동안 메시지가 없으면 `healthy_since = NULL`. 다시 기준 안이면 그때 시각으로 |
| 처리 장애 | 위치를 옮기지 않고 `retry-delay`(5초) 뒤 저장된 위치부터 다시 읽는다. **원천 트랜잭션 반영(`applier.apply`)**이 같은 위치에서 `max-apply-failures`(5번) 연속 실패하면 다시 시도해도 같은 오류(DB 제약 위반·버그)로 보고 복구(`APPLY_FAILED`, V16)로 돌린다(364). 일시 장애는 세지 않는다(383): 위치 잡기·받기 중 Kafka 오류, 원천 시작 기준 읽기 실패, 플랫폼 DB 연결 실패, 반영 중 교착·잠금 대기·질의 시간 초과(Spring `TransientDataAccessException`·`DataAccessResourceFailureException`·`CannotCreateTransactionException`·`TransactionTimedOutException`, JDBC `SQLTransientException` 계열, 원인 사슬 포함). 일시 장애가 사이에 끼어도 횟수는 그대로 둔다. 실패 뒤 저장된 위치를 다시 읽지 못하면(플랫폼 DB에 닿지 못함) 그 실패도 세지 않는다. 복구가 풀리면 `resume_after` 뒤부터 이어 받으므로 그 트랜잭션은 건너뛰고, 그 값은 다시 읽은 스냅샷으로 맞춘다 |
| 트랜잭션 크기 초과·메시지 오류 | 복구(`TRANSACTION_TOO_LARGE`·`PROTOCOL_ERROR`)로 돌리고 멈춘다 |
| 복구 중 | 읽지 않는다. `refresh-interval`(30초)마다 표시가 풀렸는지 보고, 풀리면 `resume_after` 기준으로 이어 받는다(199, 아래) |

설정은 `application-batch.yml`의 `axcore.cdc.consumer`다. 기본값은 꺼져 있다(`AXCORE_CDC_CONSUMER_ENABLED=false`). 서버에서 켜려면 배치 컨테이너 환경 변수에 `AXCORE_CDC_CONSUMER_ENABLED=true`를 넣는다(Kafka 주소 기본값 `kafka:9092`, 같은 axcore-net). 켜는 순서는 315(소비자 기동 → 매핑 승인 → 감시 작업이 첫 적재)를 따른다.

아직 하지 않은 것(모니터링): 커넥터 상태(RUNNING)와 복제 슬롯 유실(`SLOT_LOST`)은 이 루프가 보지 않는다. 자동 감지는 모니터링 쪽(Jira 68)에서 하고, 지금은 관리자 알림(아래 199)으로만 `SLOT_LOST` 복구를 시작한다.

### 시험

`CdcConsumerLoopTests`(실제 Kafka apache/kafka 4.2.2 + PostgreSQL, 198-2의 실제 메시지 37건을 같은 순서로 토픽에 넣음):

- 순서대로 반영하고 마지막 END 뒤 heartbeat까지 지나 위치 37, `last_txid` 765, CDC 실행 7건
- 소비자를 멈췄다 다시 켜고 위치를 0으로 되돌려도(재전송) 값이 되돌아가지 않음
- 모양이 깨진 메시지 → `PROTOCOL_ERROR`로 멈추고 뒤 트랜잭션도 반영 안 함, 표시가 풀리면 저장된 위치부터 이어서 반영

`SyncRunJobTests.startReferenceReadsSourceXmaxWithSyncAccount`: 실제 SYNC 계정으로 원천 xmax를 읽는다.

## 199 복구 해제와 이어 받기

결정: 작업 폴더 `decisions/2026-11-10-199-cdc-recovery-design.md`(Q1~Q4). 스키마는 V14(`cdc_offset.resume_after`, 주석).

### 흐름

1. 소비자가 이어 받을 수 없다고 표시한다(198-5·198-6): `recovery_reason`, `recovery_at`, `healthy_since = NULL`, 그 원천 CDC 테이블 `loaded_lsn = NULL`.
   - `loaded_lsn`은 엔티티가 아니라 한 문장(`SourceResourceRepository.requireFullReloadOfTables`)으로 값과 관계없이 모든 행을 갱신한다(364). 이미 비어 있는 행(첫 적재·다시 읽기 중)도 잠가야, 그 사이 끝나는 전체 읽기 저장이 아직 커밋되지 않은 복구 표시를 못 보고 `loaded_lsn`을 채우는 일이 없다. 채우면 감시 작업이 다시 읽기를 넣지 않고 해제 조건도 채워지지 않아 복구가 멈춘다. 재연결(`/cdc/reconnected`)도 같다.
   - **잠금 순서는 `cdc_offset` → `source_resource`다.** CDC 반영·복구 표시·재연결이 이 순서이고, 전체 읽기 저장(`SyncRunJob.complete`)도 CDC 테이블이 있으면 그 원천의 `cdc_offset`을 먼저 잠근다(`lockSourceOffsets`). 그래서 복구 표시와 겹치면 그 커밋을 기다렸다가 복구 시각을 보고 `loaded_lsn`을 정하고, 끝에서 복구를 풀 때(`cdc_offset` 갱신) 교착이 생기지 않는다.
2. 배치 감시 작업이 켜진 매핑이 있는 `loaded_lsn = NULL` 테이블을 `RECOVERY`로 다시 읽는다.
3. **다시 읽기 저장 트랜잭션 끝에서 푼다**(`SyncRunJob.complete` → `CdcConsumerRepository.releaseRecoveryIfReloaded`).
   - 조건: 그 원천의 CDC 테이블 중 켜진 매핑이 있는 테이블이 모두 `recovery_at` **뒤에 시작한** 전체 읽기로 적재됨. 매핑이 없는 테이블은 기다리지 않는다.
   - 원천 교체(`SOURCE_CHANGED`)는 풀지 않는다(아래 알림 전).
   - 풀 때: `recovery_reason`·`recovery_at`·`last_txid`를 비우고, `resume_after` = 그 테이블들의 마지막 전체 읽기 중 가장 이른 시작 시각.
   - 적재 계획을 만들 수 없는 테이블은 기다리지 않는다(402, 아래).
4. 소비자가 표시가 풀린 걸 보면(30초 주기) `resume_after` 뒤에 토픽에 쓰인 첫 메시지(`offsetsForTimes`, 없으면 토픽 끝)로 간다.
   - 처음 만나는 BEGIN 전의 메시지(기준 시각 앞에 시작한 트랜잭션의 나머지)는 버린다. heartbeat는 마지막 메시지 시각·정상 여부만 남기고 위치는 옮기지 않는다(트랜잭션 중간일 수 있다).
   - 첫 BEGIN에서 `next_offset` = 그 BEGIN, `resume_after = NULL`로 저장하고 평소대로 반영한다.
   - `last_txid`가 비었으므로 epoch 기준값은 원천 `xmax`를 새로 읽는다.

**왜 맞나:** `resume_after` 뒤에 커밋된 원천 트랜잭션은 커밋 뒤에야 Connect가 토픽에 쓰므로 Kafka 시각이 그 뒤다(건너뛰지 않음). 그 앞에 쓰인 메시지의 트랜잭션은 그 전에 커밋되어 다시 읽은 스냅샷에 들어 있다. 겹치는 부분은 지금처럼 스냅샷 가시성이 거른다. 그래서 복구를 일으킨 메시지(큰 트랜잭션·깨진 메시지)나 원천 교체 전 옛 DB 이벤트를 다시 만나지 않는다.

- 조건: Kafka 메시지 시각이 Connect가 토픽에 쓴 시각(CreateTime)이어야 한다. Debezium은 레코드 시각을 따로 넣지 않는다. 운영에서 커넥터를 다시 연결할 때(315) 실제 메시지로 확인한다.
- `resume_after`(플랫폼 DB 시계)와 Kafka 시각(Connect)은 둘 다 메인 서버 시계다.

**복구 전에 시작한 읽기:** `SyncRunRepository.markLoaded`는 그 원천의 `recovery_at`보다 먼저 시작한 실행이면 세대만 올리고 `loaded_lsn`은 그대로 둔다. 복구 원인보다 옛 스냅샷이라 감시 작업이 다시 읽는다. 객체는 그대로 써도 된다(스냅샷 가시성).

### 원천 교체(SOURCE_CHANGED)와 커넥터 재연결 알림

| 언제 | 하는 일 |
|---|---|
| API 012에서 host·port·database가 실제로 바뀜 | 소비 중인 원천(`cdc_offset` 행 있음)이면 `recovery_reason = SOURCE_CHANGED`, `recovery_at = now()`, `healthy_since = NULL`. `loaded_lsn`은 그대로(커넥터가 아직 옛 DB를 본다). 감시 작업도 이 원천은 건너뛴다. 아직 소비하지 않은 원천이라도 CDC 테이블이 하나라도 있으면 `{data_source.code}.all` 0번 파티션 행을 `SOURCE_CHANGED`로 새로 만든다(385). 토픽에 쌓인 옛 DB 이벤트를 새 DB 스냅샷 위에 반영하지 않기 위해서다. CDC 테이블이 없으면 만들지 않는다. 이름·계정만 바뀌면 안 한다 |
| 관리자가 커넥터를 새 DB에 맞춘 뒤 `POST /api/v1/sources/{id}/cdc/reconnected` | `SOURCE_CHANGED`일 때만 받는다(아니면 409). `SLOT_LOST`로 바꾸고 `recovery_at = now()`, 그 원천 CDC 테이블을 모두 `loaded_lsn = NULL`. 이후는 위 흐름 2~4와 같다 |

### 시험

- `SyncRunJobTests`
  - `reloadAfterRecoveryReleasesItWithResumeTime`: 보관 초과 복구 → 다시 읽기로 풀림, `last_txid` 비움, `resume_after` = 실행 시작 시각, 위치 그대로, 매핑 없는 CDC 테이블은 기다리지 않음
  - `loadStartedBeforeRecoveryDoesNotReleaseIt`: 복구 전에 시작한 읽기는 세대만 올리고 `loaded_lsn` 그대로·복구 유지, 다음 읽기가 풀음
  - `sourceChangeIsNotReleasedByReload`: 원천 교체는 다시 읽어도 안 풀림
- `CdcConsumerLoopTests.resumesFromFirstBeginAfterResumeTimeWhenRecoveryIsReleased`: 깨진 메시지로 복구 → 풀리면 기준 시각 뒤로 가서 앞 트랜잭션의 END·heartbeat를 건너뛰고 첫 BEGIN(758)부터 반영, 위치 20, `resume_after` 비움, `last_txid` 758
- `SourceApiTests`
  - `configChangeStopsCdcOfConsumedSourceWithoutReload`: 012에서 접속 설정이 바뀌면 `SOURCE_CHANGED`, `loaded_lsn` 그대로, 이름만 바꾸거나 CDC 테이블 없는 소비 전 원천은 그대로
  - `configChangeStopsCdcOfSourceNotConsumedYet`: 소비 전 원천도 CDC 테이블이 있으면 `SOURCE_CHANGED` 행이 생기고 `loaded_lsn` 그대로, 재연결하면 `SLOT_LOST`·`loaded_lsn` 비움
  - `reconnectAfterSourceChangeRequestsReload`: 교체 중이 아니면 409, 맞으면 202·`SLOT_LOST`·`loaded_lsn` 비움, ADMIN만

## 적재할 수 없는 테이블 (402)

켜진 매핑이 있는데 적재 계획(`FullLoadPlanner`)을 만들 수 없는 테이블이다. 원천 데이터 정책 위반(금지 컬럼, 민감 키, 민감 컬럼을 담는 일반 속성), 원천에서 없어진 컬럼, 행 조건, SYNC 계정이 읽을 수 없는 테이블 등이다. 정책 파일이 바뀌면 이미 있던 매핑이 이렇게 될 수 있다.

| 무엇 | 동작 |
|---|---|
| CDC 반영 | 그 표의 변경은 건너뛰고(`skippedEvents`) 위치는 넘긴다. 변경을 잃지 않게 `loaded_lsn`을 비운다(이미 비었으면 그대로, `loaded_lsn`만 바꾸는 SQL로 다른 칸을 되돌리지 않는다). 같은 트랜잭션의 다른 표는 반영한다 |
| 계획 실패의 전파 | 반영은 `FullLoadPlanner.tryPlan`(예외 대신 사유를 돌려줌)을 쓴다. `plan`의 예외가 트랜잭션 경계를 넘으면 반영 트랜잭션이 rollback-only가 되어 잡아도 커밋하지 못한다. 402 전에는 막힌 표가 섞인 원천 트랜잭션이 통째로 실패해 같은 위치에서 멈췄다 |
| 감시 작업 | 막힌 테이블을 처음에는 넣어, 실행이 사유를 수집 실패(074·075 화면의 실패 사유)로 남기게 한다. 마지막 시도가 같은 사유로 실패했으면 넣지 않는다. 그래서 고칠 때까지 실패 기록이 쌓이지 않는다. 매핑을 고치면 계획이 만들어져, 마지막 실패 뒤 재시도 대기(`retry-delay`, 5분)가 지나면 넣는다. 사유가 바뀌면 한 번 더 넣는다. 원천 데이터 정책 때문에 생긴 실패(문구가 "원천 데이터 정책상 "으로 시작)는 멈춤 횟수(`max-failures`)에 세지 않는다. 세면 다른 실패와 합쳐 3번이 되어 고친 뒤에도 자동으로 넣지 않는다. 계획은 마지막 시도가 실패인 테이블만 만들어 본다. 예상하지 못한 오류(깨진 변환 단계 등)는 막힌 것으로 보지 않고 넣어, 그 실행만 실패한다. 후보는 200개까지 보고 20개까지 넣는다(막힌 테이블이 앞 번호를 차지해도 다른 테이블을 넣게) |
| 복구 해제 | 복구 중인 원천(`SOURCE_CHANGED` 제외)에 막힌 CDC 테이블이 있으면, 감시 작업이 주기마다 다시 읽기를 기다리는 CDC 테이블의 계획을 만들어 보고, 막힌 테이블을 빼고 해제 조건을 본다(`releaseRecoveryIfReloaded(source, blocked)`, `cdc_offset`을 먼저 잠근다). 막힌 테이블 목록은 잠금 전에 구하므로 그사이 고친 테이블은 기다리지 않고 풀 수 있는데, 그 테이블은 다시 읽기 표시가 남아 뒤에 다시 읽어 맞춘다. 기다리면 그 테이블의 다시 읽기가 끝나지 않아 원천 전체의 CDC가 멈춘다. 막힌 테이블은 다시 읽기 표시가 남아 고친 뒤 따로 다시 읽고, 그때까지 CDC는 그 표의 변경을 건너뛴다. 모든 테이블이 막혔으면 이어 받을 기준이 없어 풀지 않는다 |
| 보기 | `GET /api/v1/sources/policy-violations`의 `blockedResources`(사유, 다시 읽기 대기 여부), 수집 이력의 실패 사유 |

시험: `CdcTransactionApplierTests.skippedTableThatCannotBePlannedIsMarkedForReload`, `CdcReloadServiceTests.queuesBlockedTableOnceThenWaitsUntilMappingIsFixed`·`queuesBlockedTableAgainWhenReasonDiffers`·`releasesRecoveryWithoutWaitingForBlockedTable`·`keepsRecoveryWhileLoadableTableIsNotReloaded`.

## 335 처리량·정합성 측정

원천 하나 = 토픽 하나 = 소비 스레드 하나라서, 한 원천의 반영 속도는 스레드 하나가 원천 트랜잭션을 반영하는 속도로 정해진다. 이 속도와 정합성을 운영과 같은 구성으로 쟀다(2026-11-15).

### 시험

`CdcThroughputMeasureTests`. 측정용이라 평소 빌드에서는 건너뛰고 `AXCORE_CDC_MEASURE=true`일 때만 돈다(Docker 필요, 약 10분).

```bash
AXCORE_CDC_MEASURE=true ./gradlew cleanTest test --tests '*CdcThroughputMeasureTests'
```

- 구성: 원천 PostgreSQL 18.6(`ai/data/data-v1.0`을 erp·mes DB에 적재, wal_level=logical) → Debezium 3.6.3(`infra/cdc/connectors/*.json`과 같은 설정) → Kafka 4.2.2 → 실제 batch 소비자. 플랫폼에는 같은 데이터로 만든 시연 온톨로지(`agent-scenario`, 객체 3,396·연결 10,118)를 넣고 첫 적재를 마친 상태로 시작한다.
- 운영 batch와 같은 힙(768MB, `build.gradle`)으로 돈다. SYNC 검사·다시 읽기 감시 작업은 끈다.
- 부하: 시나리오 SQL(`ai/data/scenario-v1.0` cdc_01~04·99), 행 하나짜리 트랜잭션 묶음, 큰 트랜잭션, 거래 표를 복사해 10배로 늘리기, 복사본 하나 삭제. `AXCORE_CDC_MEASURE_COPIES`(기본 9), `AXCORE_CDC_MEASURE_LOAD`(작은 트랜잭션 건수 배율, 기본 1)로 줄일 수 있다.
- 확인: 매핑한 원천 표마다 원천 행과 플랫폼 활성 객체의 키·속성 값이 같은지, CDC로 맞춘 연결이 전체 재계산과 같은지, 복구로 간 원천이 없는지.
- 결과: `build/cdc-measure/`(summary.txt, 트랜잭션별 tx.jsonl, 1초 표본 samples.jsonl).

### 결과 (2026-11-15, 로컬)

정합성은 모두 맞았다. 원천 표 25개의 행과 객체(10배 뒤 32,043개)가 키·값까지 같고, CDC 연결 82,910개가 전체 재계산과 같았다. 복구·처리 지연 비정상은 없었다.

| 부하 | 1배 | 10배 |
|---|---|---|
| 행 하나짜리 트랜잭션 반영 p50(공정·작업지시·수주) | 94 · 131 · 66ms | 467 · 553 · 187ms |
| 그중 연결 재계산 | 84~92% | 95~98% |
| 작업지시 수정을 한꺼번에 넣고 다 반영까지 | 500건 68초 | 200건 114초 |
| 큰 트랜잭션 하나 | 1,211건 1.5초 | 12,110건 13.5초 |

- 작은 트랜잭션은 거의 연결 재계산이다. 바뀐 객체가 하나여도 그 유형이 닿는 관계 유형마다 양 끝 유형의 활성 객체와 연결을 모두 읽어 다시 계산하므로(198-7, 181 MVP), 비용이 전체 객체 수에 비례한다.
- ERP·MES가 동시에 들어오면 같은 관계 유형 잠금을 기다려 서로 느려진다(수주 66 → 177ms).
- 큰 트랜잭션은 객체 쓰기가 대부분이다(초당 약 800~900건).
- 지금 규모에서는 고치지 않는다. 객체가 늘어 작은 트랜잭션이 몰릴 때 처리 지연이 기준(`max-lag` 2분)에 가까워지면, 밀린 원천 트랜잭션 여러 개를 한 플랫폼 트랜잭션으로 반영하고 연결은 한 번만 다시 계산하는 것부터 본다. 그다음은 바뀐 쌍만 계산하기(비교값 저장 칸 필요)다.
- 한계: 노트북 Docker(PostgreSQL fsync=off, CPU 제한 없음)에서 쟀다. 운영 batch는 CPU 1개라 두 원천이 동시에 들어올 때 더 느릴 수 있다.
