# 원천 서버(ERP·MES) PostgreSQL

가상 기업의 ERP·MES 역할을 하는 원천 DB입니다. 로그 기반 CDC(Debezium + Kafka)로 변경을 읽기 위해 처음부터 `wal_level=logical`로 띄웁니다. 원천에는 변경 이력 테이블·트리거를 만들지 않습니다(S15P31C106-59~63, 2026-11-02 세팅).

| 서버 | 주소 | DB·스키마 | 표 |
|---|---|---|---|
| ERP | `3.35.230.145` (t3a.medium, Amazon Linux 2023) | `erp` | 18개 (CDC 17개) |
| MES | `3.34.192.251` (t3a.medium, Amazon Linux 2023) | `mes` | 15개 (CDC 14개) |

접속 사용자는 `ec2-user`, 키는 `C106_Source.pem`입니다. 두 서버의 구성은 `.env`의 `SOURCE_DB`만 다릅니다.

## 구성

| 항목 | 값 |
|---|---|
| 이미지 | `postgres:18.6` (메인 서버와 같음), 컨테이너 `source-db`, 볼륨 `source-pg-data` |
| 포트 | 호스트 `15432` → 5432. AWS 보안 그룹은 메인 서버(`13.125.73.49`)만 허용 |
| CDC 설정 | `wal_level=logical`, `max_wal_senders=4`, `max_replication_slots=4`, `max_slot_wal_keep_size=2GB` |
| 접속 | `pg_hba.conf`: 메인 서버 IP에서 오는 `platform_ro`·`platform_ai`·`platform_cdc`만 SSL(scram-sha-256)로 허용. 관리자 `source_admin`은 컨테이너 안에서만 |
| 계정 | `platform_ro` 배치용 조회 전용(SELECT, 읽기 전용 트랜잭션), `platform_ai` FastAPI 분석용 조회 전용(읽기 전용, `statement_timeout` 30초, 접속 5개까지), `platform_cdc` 복제(REPLICATION + 내보낼 표 SELECT, superuser 아님). 조회 계정을 나눈 이유는 아래 |
| publication | `axcore_cdc`: 스키마의 모든 표에서 `change_log`만 뺌. 복제 슬롯은 Debezium 커넥터를 등록할 때 만들어진다 |
| 서버 | 시간대 Asia/Seoul, 스왑 2GiB, Docker + compose 플러그인 v2.40.3 |

## 처음 세팅

노트북의 저장소 루트에서 실행합니다(ERP 예시. MES는 주소와 `erp` → `mes`).

```bash
K=~/.ssh/C106_Source.pem; H=ec2-user@3.35.230.145; DB=erp
tar -C infra/source-db -cf - compose.yaml pg_hba.conf sql setup.sh server-init.sh | ssh -i $K $H 'mkdir -p ~/source-db && tar -C ~/source-db -xf -'
tar -C ai/data/data-v1.0 -cf - load_$DB.psql verify_counts.sql $DB | ssh -i $K $H 'mkdir -p ~/source-db/data && tar -C ~/source-db/data -xf -'
ssh -i $K $H '~/source-db/server-init.sh'      # 시간대·스왑·Docker
ssh -i $K $H "~/source-db/setup.sh $DB"        # .env·인증서 생성, 기동, 데이터 적재, 행 수 확인, 계정·publication
```

`setup.sh`는 다시 실행해도 됩니다. `.env`와 인증서가 있으면 그대로 두고(없는 계정 비밀번호만 채움), 표가 있으면 적재를 건너뜁니다.

`pg_hba.conf`는 파일 하나를 마운트하므로, 바꿀 때는 tar로 덮어쓰지 말고 같은 파일에 내용을 써야 컨테이너에 보입니다(`cat 새파일 > pg_hba.conf`). 그 뒤 `SELECT pg_reload_conf()`로 다시 읽힙니다.

## 조회 계정을 둘로 나눈 이유 (2026-11-02)

- 배치는 테이블 전체를 오래 읽고, FastAPI 분석은 짧게 여러 번 읽습니다. 계정을 나눠 AI 쪽에만 시간 제한(30초)과 접속 수 제한(5개)을 걸어 배치가 쓸 접속을 남깁니다.
- 외부 LLM으로 보내면 안 되는 제한 컬럼은 `platform_ai` 권한에서만 빼면 DB가 막습니다(A5). 정해지기 전에는 두 계정 모두 전체 표를 읽습니다.
- FastAPI 쪽 비밀번호가 새도 배치 계정은 그대로이고, AI 계정만 막거나 바꿀 수 있습니다. `pg_stat_activity`에서 누가 부하를 주는지도 계정으로 구분됩니다.

## 비밀값

- 서버 `~/source-db/.env`(권한 600)에만 있습니다: `POSTGRES_PASSWORD`, `PLATFORM_RO_PASSWORD`, `PLATFORM_AI_PASSWORD`, `PLATFORM_CDC_PASSWORD`. 화면·로그·저장소에 내보내지 않습니다.
- `PLATFORM_CDC_PASSWORD`는 메인 서버 `~/axcore/cdc/.env`의 `ERP_CDC_PASSWORD`·`MES_CDC_PASSWORD`로 옮겨 두었습니다(Kafka Connect용, `infra/cdc/`).
- 옮길 때는 값을 출력하지 않고 파이프로 넘깁니다. 예: `ssh 원천 'sed -n "s/^PLATFORM_CDC_PASSWORD=/ERP_CDC_PASSWORD=/p" ~/source-db/.env' | ssh 메인 'cat >> ~/axcore/cdc/.env'`

## 확인 (2026-11-02)

- 두 DB 모두 행 수가 `verify_counts.sql`과 같음(ERP 18표, MES 15표), README 확인 항목(한글, NULL 3건, 메모 줄바꿈 60건) 통과
- 메인 서버에서: `platform_ro` SSL 접속·SELECT 성공, DELETE 거부 / `platform_cdc` 논리 복제 접속(`IDENTIFY_SYSTEM`) 성공 / `source_admin`·SSL 없는 접속 거부
- 노트북에서 15432 연결 차단
- `platform_ai` 추가 뒤(같은 날): 메인 서버에서 SSL 접속·SELECT 성공, `statement_timeout` 30s, DELETE 거부, 틀린 비밀번호 거부. 두 DB 모두 행 수 그대로

## 원천에 테이블을 추가할 때

publication은 테이블 목록으로 만들었으므로 새 테이블은 저절로 CDC에 들어가지 않습니다. 스키마 단위(`FOR TABLES IN SCHEMA`)로 바꾸면 자동으로 들어가지만, 그러면 컬럼 목록을 쓸 수 없어 금지 컬럼을 뺄 수 없습니다(PostgreSQL 18 문서). 그래서 관리자가 직접 넣습니다.

1. 관리자 계정으로 `ALTER PUBLICATION axcore_cdc ADD TABLE erp.새표;`(금지 컬럼이 있으면 컬럼 목록을 적음)와 `GRANT SELECT ON erp.새표 TO platform_cdc;`. `platform_ro`·`platform_ai`는 기본 권한으로 이미 읽을 수 있다
2. 플랫폼에서 연결 검사 → 분석·승인 → 첫 적재. **1번이 첫 적재보다 먼저**여야 그 사이 변경을 놓치지 않는다

## 남은 것

- 금지 컬럼(A5)이 정해지면 publication에서 그 컬럼을 뺀다: `ALTER PUBLICATION axcore_cdc SET TABLE erp.employee (컬럼, ...), ...`
- 시나리오 5 원천 수정분(`ai/data/scenario-v1.0/s5_fix.sql`)은 시연 때만 관리자 계정으로 적용한다
