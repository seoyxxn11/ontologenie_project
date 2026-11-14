# CDC용 Kafka · Kafka Connect(Debezium)

원천(ERP·MES) PostgreSQL의 WAL을 Debezium이 읽어 Kafka 토픽에 내보냅니다. 플랫폼의 배치(소비자)가 토픽을 읽어 객체에 반영합니다(S15P31C106-267, 2026-11-02 기동).

메인 `compose.yaml`과 별도 프로젝트(`axcore-cdc`)로 관리합니다. 같은 `axcore-net`에 붙으므로 다른 컨테이너는 `kafka:9092`, `kafka-connect:8083`으로 닿습니다. 호스트 포트는 열지 않습니다.

| 컨테이너 | 이미지 | mem_limit | 힙 | 비고 |
|---|---|---|---|---|
| `kafka` | `apache/kafka:4.2.2` | 1.5g | 256m~512m | KRaft 단일 노드, 볼륨 `axcore-kafka-data`, 토픽 보관 7일 |
| `kafka-connect` | `quay.io/debezium/connect:3.6.3.Final` | 1.5g | 256m~768m | 내부 토픽 `connect_configs`·`connect_offsets`·`connect_statuses` |
| `kafka-console` | `redpandadata/console:v3.12.0` | 256m | - | 웹 화면. `127.0.0.1:18082`에만 열림, 설정 `console.yaml` |

## 웹 화면 (Redpanda Console)

브로커 상태, 토픽과 메시지 내용, 소비자 그룹과 지연, Kafka Connect 커넥터 상태·오류를 봅니다.

- 주소: **https://k15c106.p.ssafy.io/kafka-ui/**
- 화면 자체에는 로그인이 없어 nginx Basic Auth를 겁니다. 계정은 Swagger와 같은 `nginx/htpasswd`입니다(`infra/nginx/conf.d/default.conf`의 `/kafka-ui/`).
- 토픽 메시지(원천 행 값)를 보고 커넥터를 재시작·삭제할 수 있으므로 계정은 팀 안에서만 공유합니다.
- 컨테이너 포트는 `127.0.0.1:18082`에만 열려 있습니다. nginx를 거치지 않고 보려면 SSH 터널(`ssh -i ~/.ssh/K15C106T.pem -L 18082:127.0.0.1:18082 ubuntu@k15c106.p.ssafy.io`)을 켜고 `http://localhost:18082/kafka-ui/`를 엽니다.
- Console의 외부 분석 스크립트는 꺼 두었습니다(`console.yaml`의 `analytics.enabled: false`).

컨테이너 CPU·메모리는 Grafana(`axcore-resources` 대시보드)에서 봅니다.

기동 직후 실측: kafka 336MiB, kafka-connect 460MiB. 서버 전체 사용 3.6GiB / 15GiB.

## 서버에 적용

```bash
scp -i ~/.ssh/K15C106T.pem infra/cdc/compose.yaml ubuntu@k15c106.p.ssafy.io:axcore/cdc/
ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io
cd ~/axcore/cdc
docker compose config --quiet
docker compose up -d
docker compose ps        # 둘 다 (healthy)
```

운영 비밀값 원본은 Jenkins Credentials의 `axcore-erp-cdc-password`, `axcore-mes-cdc-password`입니다. Jenkins가 권한 600의 실행용 `~/axcore/cdc/.env`에 `ERP_CDC_PASSWORD`, `MES_CDC_PASSWORD`로 전달합니다. 원천 서버의 기존 `PLATFORM_CDC_PASSWORD`와 같은 값을 유지합니다(`infra/source-db/README.md`). 전환·자동 반영 범위와 운영 적용 여부는 [배포 안내](../deploy/README.md)를 확인합니다. 원천 서버 비밀번호 자체를 변경하는 작업은 포함하지 않습니다.

## 상태 확인

```bash
docker exec kafka-connect curl -s localhost:8083/                     # Connect 버전
docker exec kafka-connect curl -s localhost:8083/connector-plugins    # PostgresConnector 포함
docker exec kafka-connect curl -s localhost:8083/connectors           # 등록된 커넥터
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list
```

## 현재 상태 (2026-11-14)

두 커넥터 `cdc-erp`·`cdc-mes`가 RUNNING이고 원천 슬롯 `axcore_erp`·`axcore_mes`가 쓰이는 중입니다(2026-11-11 315 운영 연결). 원천 토픽은 `ERP_MAIN.all`·`MES_MAIN.all` 두 개이고, batch의 CDC 소비자와 수집 뒤 검사(SYNC)가 켜져 있습니다. 플랫폼 `cdc_offset`에 원천마다 한 행이 있습니다.

원천에는 아직 허용 목록(395)이 적용되지 않아 publication이 예전 정의입니다(ERP에 `attendance`·`fixed_asset`, 컬럼 목록 없음). 허용 목록을 적용하려면 슬롯을 지워야 하므로 아래 "허용 목록 적용 때 다시 붙이기" 순서를 따릅니다(S15P31C106-397).

## 운영 연결 순서 (S15P31C106-315)

실제 적재를 시작하기 전에 아래 순서로 연결합니다. 결정 문서(`2026-11-05-cdc-initial-load.md` 11.1)의 "소비자 기동 → 매핑 승인 → 감시 작업이 첫 적재"와 같습니다.

1. **매핑을 아직 켜지 않습니다.** 감시 작업(`CdcReloadJob`, 330)은 batch가 떠 있으면 늘 돌고, 켜진 매핑이 있고 `loaded_lsn`이 빈 CDC 표를 찾으면 바로 첫 적재(`FIRST_LOAD`)를 넣습니다. 커넥터보다 먼저 적재되면 적재와 슬롯 생성 사이의 변경을 받지 못합니다.
2. **커넥터 등록.** 아래 "커넥터"의 `register-connectors.sh`를 실행합니다. 두 커넥터와 태스크가 RUNNING인지, 원천 슬롯이 `active`인지 확인합니다. 원천 토픽 `ERP_MAIN.all`·`MES_MAIN.all`은 첫 heartbeat(1분 안)나 첫 변경 때 생깁니다.
3. **소비자·SYNC 켜기.** 서버 `~/axcore/.env.config`에 `AXCORE_CDC_CONSUMER_ENABLED=true`, `AXCORE_CHECK_SYNC_ENABLED=true`를 넣고 실행용 `.env`에 반영한 뒤 batch를 다시 만듭니다(Jenkins 설정 반영 범위는 [배포 안내](../deploy/README.md)). batch 로그에 `CDC 소비자 시작 topic=ERP_MAIN.all`이 나오고, `cdc_offset`에 원천마다 한 행이 생깁니다. 위치 기록이 없으면 토픽 처음부터 읽습니다.
4. **매핑 승인.** 감시 작업이 표마다 첫 적재를 넣습니다. 끝나면 `source_resource.loaded_lsn`·`full_load_done_gen`이 채워집니다.
5. **확인.** 원천 행 수와 객체 수, 원천에 변경을 한 번 넣었을 때 객체에 반영되는지, `cdc_offset`의 정상 표시(`healthy_since`), 수집 뒤 검사(SYNC) 결과를 봅니다.

- 순서를 어겨 커넥터 없이 첫 적재가 먼저 돌았으면, 커넥터를 등록한 뒤 그 표들을 다시 읽습니다(073 수동 다시 읽기).
- 적재 뒤 소비자가 토픽 보관 기간(7일)보다 오래 멈추면 소비자가 그 원천을 복구(`OFFSET_EXPIRED`)로 돌립니다. 전체 다시 읽기로 맞춥니다(199).
- 원천 접속 정보가 바뀌면(`SOURCE_CHANGED`) 커넥터를 새 DB에 맞춘 뒤에야 다시 읽습니다. 감시 작업도 그동안은 그 원천을 건너뜁니다.

### 허용 목록 적용 때 다시 붙이기 (S15P31C106-397)

원천의 publication 정의를 바꿀 때는 슬롯이 없어야 합니다. 슬롯이 있으면 바꾸기 전에 쌓인 WAL이 예전 정의로 풀려 금지 컬럼이 나갈 수 있고, 허용 목록 스크립트도 확정을 멈춥니다(`infra/source-db/README.md` "슬롯 확인"). 토픽과 플랫폼 `cdc_offset`은 그대로 둡니다. 새 슬롯의 변경이 같은 토픽 뒤에 이어 붙으므로 소비자는 하던 위치에서 계속 읽습니다.

1. 커넥터 멈춤 → Connect가 저장한 읽은 위치 삭제 → 커넥터 삭제(아래 "초기화"의 앞 세 줄). 위치를 지우지 않으면 새 슬롯에 예전 LSN을 요청한다.
2. 원천 서버마다 슬롯 삭제(`pg_drop_replication_slot`). publication은 남긴다(허용 목록 스크립트가 바꾼다).
3. 원천에 허용 목록 적용(`infra/source-db/README.md` "기존 서버에 적용", 시험 실행 → 확정).
4. 저장소의 `connectors/*.json`을 서버에 올리고 `register-connectors.sh`로 다시 등록한다. 새 슬롯이 만들어지고(`snapshot.mode=no_data`) 지금부터의 변경을 보낸다.
5. 플랫폼에서 원천마다 연결 검사(카탈로그 다시 받기) → CDC 표 전체 다시 읽기(073). 1~4 사이 원천 변경은 슬롯이 없어 오지 않으므로 다시 읽기로 메운다.

커넥터 설정의 `table.exclude.list`·`column.exclude.list`는 금지 표·컬럼을 한 번 더 뺀다(2차 방어, 397). 1차는 원천 publication 컬럼 목록이다. 정책 목록을 바꾸면 이 두 칸도 같이 고친다.

### 초기화 (2026-11-10에 한 순서)

연결을 끊고 처음 상태로 돌릴 때입니다. 커넥터만 지우면 원천 슬롯이 남아 WAL이 쌓이므로(최대 `max_slot_wal_keep_size` 2GB, 넘으면 슬롯을 잃음) 슬롯까지 지웁니다.

```bash
# 메인 서버: 커넥터 멈춤 → Connect가 저장한 읽은 위치 삭제 → 커넥터 삭제
for n in cdc-erp cdc-mes; do docker exec kafka-connect curl -s -X PUT localhost:8083/connectors/$n/stop; done
for n in cdc-erp cdc-mes; do docker exec kafka-connect curl -s -X DELETE localhost:8083/connectors/$n/offsets; done
for n in cdc-erp cdc-mes; do docker exec kafka-connect curl -s -X DELETE localhost:8083/connectors/$n; done
# 원천 서버(ERP·MES 각각): 슬롯 삭제. publication은 남긴다
docker exec source-db psql -U source_admin -d erp -c "select pg_drop_replication_slot('axcore_erp')"
# 메인 서버: 원천 토픽 삭제
docker exec kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --delete --topic ERP_MAIN.all
```

플랫폼 `cdc_offset`에 행이 있으면 토픽을 지우기 전에 같이 정리해야 합니다. 새 토픽은 위치 0부터 다시 시작하는데, 남은 행의 다음 위치가 그보다 크면 소비자가 그만큼 건너뜁니다. 2026-11-10에는 소비자를 켠 적이 없어 행이 없었습니다.

## 커넥터

| 커넥터 | 원천 | `topic.prefix` | 복제 슬롯 | 토픽 |
|---|---|---|---|---|
| `cdc-erp` | ERP `3.35.230.145:15432/erp` | `ERP_MAIN` | `axcore_erp` | `ERP_MAIN.all` |
| `cdc-mes` | MES `3.34.192.251:15432/mes` | `MES_MAIN` | `axcore_mes` | `MES_MAIN.all` |

**커넥터는 첫 적재보다 먼저 떠 있어야 합니다.** 스냅샷 없이(`no_data`) 등록한 순간부터의 변경만 받으므로, 플랫폼이 테이블 전체를 읽기 전에 슬롯이 있어야 그 사이 변경을 놓치지 않습니다. 전체 읽기와 이벤트가 겹치는 경계는 LSN이 아니라 읽기 트랜잭션의 스냅샷으로 판단합니다(V11). 소비자가 없어도 이벤트는 토픽에 7일간 쌓였다 지워질 뿐이고, Connect가 WAL을 계속 읽어 가므로 원천에 WAL이 쌓이지 않습니다.

`topic.prefix`는 플랫폼 `data_source.code`와 같아야 합니다. **플랫폼에 원천을 등록할 때 코드를 `ERP_MAIN`·`MES_MAIN`으로 씁니다**(API 009~012 예시와 같음). 원천 토픽(`<prefix>.all`)은 첫 heartbeat나 첫 변경 때 파티션 1개로 만들어집니다.

### 원천마다 토픽 하나 (S15P31C106-316, 2026-11-06)

원천의 모든 표 변경, 트랜잭션 경계(BEGIN·END), heartbeat를 토픽 하나(`<prefix>.all`), 파티션 1개에 넣습니다(`RegexRouter`). Kafka는 한 파티션 안에서만 순서를 지키므로, 이렇게 해야 같은 원천의 변경이 원천 커밋 순서대로, 트랜잭션 경계와 함께 옵니다. 소비자는 원천 트랜잭션을 END까지 모아 플랫폼에 한 번에 반영합니다. 그러면 같은 원천의 플랫폼 상태는 늘 "원천의 어느 커밋 시점 상태"와 같아, 같은 원천 안 규칙의 미연결 판정이 정확해집니다(결정 문서 8·10장). 표마다 토픽이면 heartbeat 같은 표시가 앞선 변경을 앞지릅니다(시험 `troubleshooting/cdc-heartbeat-order-test.md`).

- **원천 토픽의 파티션을 늘리지 않습니다.** 늘리면 순서 보장이 깨집니다. 커넥터 설정 `topic.creation.default.partitions=1`로 브로커 기본값과 상관없이 1개로 만듭니다.
- 표마다 처리를 나누려면 토픽이 아니라 소비자 안에서 메시지의 `source.schema`·`source.table`로 나눕니다.
- 바꾸기 전의 표별 토픽(`<prefix>.<스키마>.<표>`)과 `__debezium-heartbeat.<prefix>`에는 더 쓰지 않습니다. 보관 기간(7일)이 지나면 비고, 필요 없으면 지웁니다.
- `cdc_offset` 테이블 설명(V5)의 "토픽 이름은 {code}.{스키마}.{테이블}"은 198 마이그레이션에서 고칩니다.

확인(2026-11-06, 로컬에서 같은 이미지·같은 설정 파일로):

- 설정 검증 오류 0, 커넥터·태스크 RUNNING
- 브로커 기본 파티션을 일부러 3으로 둬도 `ERP_MAIN.all`은 파티션 1개로 만들어짐
- 두 표의 변경과 heartbeat가 `ERP_MAIN.all` 한 토픽에 커밋 순서대로 들어옴
- SAVEPOINT가 섞인 트랜잭션(추가 2건 + 되돌린 수정 1건 + 추가 1건)의 이벤트 3건이 모두 `txid_current()`와 같은 최상위 번호. 되돌린 수정은 오지 않음
- 원천 로그의 `START_REPLICATION` 옵션에 `streaming`이 없음(`proto_version` 1). 약 120MB짜리 트랜잭션도 커밋 전에는 이벤트가 0건, 커밋 뒤 한꺼번에 들어옴
- 트랜잭션 경계(`provide.transaction.metadata=true`)가 별도 토픽 없이 `ERP_MAIN.all`에 들어옴. 순서는 BEGIN → 그 트랜잭션의 변경 → END. END에 `event_count`와 표별 건수가 있음. **END는 커밋 직후 바로 옴**(뒤에 10초간 변경이 없어도 다음 트랜잭션을 기다리지 않음)
- heartbeat가 한 트랜잭션의 변경과 END 사이에 낄 수 있음(TRUNCATE 시험에서 관측). heartbeat를 트랜잭션 경계로 보면 안 됨
- 변경되지 않은 TOAST 값(약 9.6KB 문자열이 있는 행에서 다른 컬럼만 수정): `u`의 `after`에 `"__debezium_unavailable_value"`로 옴
- TRUNCATE(`skipped.operations=none`): `op=t` 이벤트가 자기 트랜잭션 경계와 함께 옴

등록·설정 변경은 `register-connectors.sh`로 합니다(설정 검증 → `PUT /connectors/<이름>/config` → RUNNING 확인). 다시 실행해도 됩니다.

```bash
tar -C infra/cdc -cf - register-connectors.sh connectors | ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io 'tar -C ~/axcore/cdc -xf -'
ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io '~/axcore/cdc/register-connectors.sh'
```

설정(`connectors/*.json`)에서 정한 것:

- `snapshot.mode=no_data`: 스냅샷 없이 변경만 받는다. 첫 적재는 플랫폼이 직접 전체를 읽는다(S15P31C106-266)
- `publication.name=axcore_cdc`, `publication.autocreate.mode=disabled`: 원천에서 만든 publication을 쓴다. 새 테이블은 원천에서 publication에 넣어야 들어온다(`infra/source-db/README.md`)
- `database.user=platform_cdc`, `database.password=${env:ERP_CDC_PASSWORD}`: 비밀번호는 참조만 적는다
- `decimal.handling.mode=string`: numeric 값을 정확한 문자열로 보낸다(기본값은 base64 바이트라 소비자가 풀기 번거롭다)
- `tombstones.on.delete=false`: 토픽 압축을 쓰지 않으므로 삭제 뒤 빈 메시지를 보내지 않는다
- `heartbeat.interval.ms=60000`: 대상 표에 변경이 없어도 1분마다 슬롯 위치를 앞으로 옮겨 원천 WAL이 쌓이지 않게 한다. heartbeat도 원천 토픽에 같이 들어온다. 값은 `{"ts_ms": Connect 서버 시각}`뿐이라 반영 완료의 증거가 아니다(화면의 "마지막 확인" 표시 정도로만 쓴다)
- `transforms=route`(`RegexRouter`), `topic.creation.default.partitions=1`: 원천마다 토픽 하나·파티션 1개(위 절)
- `provide.transaction.metadata=true`: 트랜잭션 BEGIN·END를 보낸다. 기본 토픽 `<prefix>.transaction`이 라우팅 정규식에 걸려 원천 토픽으로 함께 간다. 소비자는 END까지 모아 원천 트랜잭션 단위로 반영한다
- `skipped.operations=none`: TRUNCATE도 이벤트(`op=t`)로 받는다(기본값은 건너뜀). 행별 `d`가 오지 않으므로 소비자는 그 표를 다시 읽기 대상으로 표시한다
- **진행 중 트랜잭션 스트리밍은 쓰지 않는다.** Debezium은 `START_REPLICATION ... ("proto_version" '1', "publication_names" 'axcore_cdc', "messages" 'true')`로 접속해 커밋된 트랜잭션만 받는다. 소비자는 `txId`를 최상위 트랜잭션 번호로 보고 스냅샷 가시성을 판단하므로 이 동작을 바꾸는 옵션을 켜지 않는다

이벤트 모양(소비자 S15P31C106-198·199 참고):

- `payload.op`: `c`(추가) / `u`(수정) / `d`(삭제). `payload.source.lsn`이 플랫폼의 `source_lsn`과 같은 숫자다(재전송 거르기)
- `payload.source.txId`: 원천 트랜잭션 번호(32비트). 소비자가 epoch를 복원해 `xid8`로 바꾸고, 객체의 적재 스냅샷과 비교한다(`pg_visible_in_snapshot`)
- 원천 표의 REPLICA IDENTITY가 기본값(PK)이라 `u`의 `before`는 비어 있고, `d`의 `before`에는 PK 컬럼만 있다
- 메시지마다 컬럼 구성(`schema`)이 같이 온다. 원천 컬럼이 바뀌면 여기서 알 수 있다
- TRUNCATE는 `op=t` 이벤트로 온다(`skipped.operations=none`). 행별 삭제가 오지 않는다
- 트랜잭션 경계 메시지는 `status`(`BEGIN`/`END`)와 `id`(`txId:LSN`)가 있고, END에는 `event_count`·`data_collections`(표별 건수)가 있다. BEGIN과 END의 `id` 뒤쪽 LSN은 다르므로 `txId` 부분으로 맞춘다. 변경 이벤트에는 `transaction`(`id`, `total_order`, `data_collection_order`)이 붙는다
- 변경되지 않은 TOAST 값은 `__debezium_unavailable_value`로 온다(REPLICA IDENTITY DEFAULT). 소비자는 이 값으로 기존 값을 덮어쓰지 않는다
- 같은 원천 안에서는 표가 달라도 원천 커밋 순서대로 온다(원천마다 토픽 하나). ERP와 MES 사이의 순서는 없다
- 한 트랜잭션의 이벤트는 모두 같은 `txId`(최상위 번호)다. SAVEPOINT·하위 트랜잭션 안의 변경도 같고, 되돌린 SAVEPOINT의 변경은 오지 않는다
- heartbeat 메시지는 `source`가 없고 `ts_ms`만 있다. 소비자는 `source`·`status` 유무로 변경·트랜잭션 경계·heartbeat를 가린다

멈추기·다시 켜기(슬롯은 남아 있어 그동안의 변경을 이어서 받는다):

```bash
docker exec kafka-connect curl -s -X PUT localhost:8083/connectors/cdc-mes/stop
docker exec kafka-connect curl -s -X PUT localhost:8083/connectors/cdc-mes/resume
docker exec kafka-connect curl -s localhost:8083/connectors/cdc-mes/status
```

커넥터를 지워도 원천의 복제 슬롯은 남습니다. 다시 만들지 않을 거면 원천에서 슬롯을 지워야 WAL이 쌓이지 않습니다(`SELECT pg_drop_replication_slot('axcore_mes')`).

### 확인 (2026-11-02)

- 두 커넥터와 태스크 RUNNING, 원천 슬롯 `axcore_erp`·`axcore_mes` active, `wal_status=reserved`
- ERP `department`에 테스트 행 추가 → 수정 → 삭제: 토픽에 `c`·`u`·`d`가 LSN과 함께 들어옴(한글 정상). 원천 행 수는 그대로(8)
- MES 커넥터를 멈춘 동안 `worker`에 추가·수정·삭제 → 다시 켜자 세 이벤트를 모두 받음. 원천 행 수 그대로(16)
- `kafka-connect` 컨테이너 재시작 뒤 두 커넥터 RUNNING, 재시작 뒤 변경도 받음
- 복제 계정 비밀번호가 Connect 로그·`connect_configs` 토픽·REST 응답에 0건. 설정 조회에는 `${env:ERP_CDC_PASSWORD}`만 보임
- 호스트 포트 없음(`docker port` 비어 있음). 메모리 kafka 583MiB, kafka-connect 499MiB / 각 1.5GiB, 서버 사용 3.9GiB / 15GiB
