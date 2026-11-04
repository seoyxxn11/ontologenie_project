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

## 커넥터 (2026-11-02 등록)

| 커넥터 | 원천 | `topic.prefix` | 복제 슬롯 | 토픽 |
|---|---|---|---|---|
| `cdc-erp` | ERP `3.35.230.145:15432/erp` | `ERP_MAIN` | `axcore_erp` | `ERP_MAIN.erp.<표>` |
| `cdc-mes` | MES `3.34.192.251:15432/mes` | `MES_MAIN` | `axcore_mes` | `MES_MAIN.mes.<표>` |

**커넥터는 첫 적재보다 먼저 떠 있어야 합니다.** 스냅샷 없이(`no_data`) 등록한 순간부터의 변경만 받으므로, 플랫폼이 테이블 전체를 읽기(`loaded_lsn` 기록) 전에 슬롯이 있어야 그 사이 변경을 놓치지 않습니다. 소비자가 없어도 이벤트는 토픽에 7일간 쌓였다 지워질 뿐이고, Connect가 WAL을 계속 읽어 가므로 원천에 WAL이 쌓이지 않습니다.

`topic.prefix`는 플랫폼 `data_source.code`와 같아야 합니다. **플랫폼에 원천을 등록할 때 코드를 `ERP_MAIN`·`MES_MAIN`으로 씁니다**(API 009~012 예시와 같음). 토픽은 표에 첫 변경이 생길 때 만들어집니다.

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
- `heartbeat.interval.ms=60000`: 대상 표에 변경이 없어도 1분마다 슬롯 위치를 앞으로 옮겨 원천 WAL이 쌓이지 않게 한다. 토픽 `__debezium-heartbeat.<prefix>`는 소비자가 무시한다

이벤트 모양(소비자 S15P31C106-198·199 참고):

- `payload.op`: `c`(추가) / `u`(수정) / `d`(삭제). `payload.source.lsn`이 플랫폼의 `source_lsn`·`loaded_lsn`과 같은 숫자다
- 원천 표의 REPLICA IDENTITY가 기본값(PK)이라 `u`의 `before`는 비어 있고, `d`의 `before`에는 PK 컬럼만 있다
- 메시지마다 컬럼 구성(`schema`)이 같이 온다. 원천 컬럼이 바뀌면 여기서 알 수 있다
- TRUNCATE는 보내지 않는다(Debezium 기본값)
- 토픽이 표마다 따로라 표 사이의 순서는 보장되지 않는다

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
