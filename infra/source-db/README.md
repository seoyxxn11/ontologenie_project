# 원천 서버(ERP·MES) PostgreSQL

가상 기업의 ERP·MES 역할을 하는 원천 DB입니다. 로그 기반 CDC(Debezium + Kafka)로 변경을 읽기 위해 처음부터 `wal_level=logical`로 띄웁니다. 원천에는 변경 이력 테이블·트리거를 만들지 않습니다(S15P31C106-59~63, 2026-11-02 세팅).

| 서버 | 주소 | DB·스키마 | 표 | 플랫폼이 읽는 표 (CDC) |
|---|---|---|---|---|
| ERP | `3.35.230.145` (t3a.medium, Amazon Linux 2023) | `erp` | 18개 | 12개 (11개) |
| MES | `3.34.192.251` (t3a.medium, Amazon Linux 2023) | `mes` | 15개 | 15개 (14개) |

플랫폼이 읽는 표·컬럼은 아래 "허용 목록"으로 정합니다(S15P31C106-395). 기존 서버는 아직 예전 권한(표 전체)이고, 허용 목록 적용은 S15P31C106-397에서 승인 뒤에 합니다.

접속 사용자는 `ec2-user`, 키는 `C106_Source.pem`입니다. 두 서버의 구성은 `.env`의 `SOURCE_DB`만 다릅니다.

## 구성

| 항목 | 값 |
|---|---|
| 이미지 | `postgres:18.6` (메인 서버와 같음), 컨테이너 `source-db`, 볼륨 `source-pg-data` |
| 포트 | 호스트 `15432` → 5432. AWS 보안 그룹은 메인 서버(`13.125.73.49`)만 허용 |
| CDC 설정 | `wal_level=logical`, `max_wal_senders=4`, `max_replication_slots=4`, `max_slot_wal_keep_size=2GB` |
| 접속 | `pg_hba.conf`: 메인 서버 IP에서 오는 `platform_ro`·`platform_ai`·`platform_cdc`만 SSL(scram-sha-256)로 허용. 관리자 `source_admin`은 컨테이너 안에서만 |
| 계정 | `platform_ro` 배치용 조회 전용(읽기 전용 트랜잭션), `platform_ai` FastAPI 분석용 조회 전용(읽기 전용, `statement_timeout` 30초, 접속 5개까지), `platform_cdc` 복제(REPLICATION, superuser 아님). 세 계정 모두 쓰기 권한이 없고, 읽는 표·컬럼은 허용 목록대로다 |
| publication | `axcore_cdc`: 허용 목록에서 CDC로 표시한 표. 금지 컬럼이 있는 표는 컬럼 목록을 붙여 그 컬럼을 뺀다. 복제 슬롯은 Debezium 커넥터를 등록할 때 만들어진다 |
| 서버 | 시간대 Asia/Seoul, 스왑 2GiB, Docker + compose 플러그인 v2.40.3 |

## 처음 세팅

노트북의 저장소 루트에서 실행합니다(ERP 예시. MES는 주소와 `erp` → `mes`).

```bash
K=~/.ssh/C106_Source.pem; H=ec2-user@3.35.230.145; DB=erp
tar -C infra/source-db -cf - compose.yaml pg_hba.conf sql setup.sh server-init.sh | ssh -i $K $H 'mkdir -p ~/source-db && tar -C ~/source-db -xf -'
tar -C ai/data/data-v1.0 -cf - load_$DB.psql verify_counts.sql $DB | ssh -i $K $H 'mkdir -p ~/source-db/data && tar -C ~/source-db/data -xf -'
ssh -i $K $H '~/source-db/server-init.sh'      # 시간대·스왑·Docker
ssh -i $K $H "~/source-db/setup.sh $DB"        # .env·인증서 생성, 기동, 데이터 적재, 행 수 확인, 계정·허용 목록·publication
```

`setup.sh`는 다시 실행해도 됩니다. `.env`와 인증서가 있으면 그대로 두고(없는 계정 비밀번호만 채움), 표가 있으면 적재를 건너뜁니다. 계정·권한·publication(`sql/setup_roles.sql`)은 다시 실행하면 허용 목록 밖의 권한을 회수하고 허용 목록대로 다시 맞춥니다. publication 정의가 바뀌는데 그 DB에 논리 복제 슬롯이 있으면 멈춥니다(아래 "슬롯 확인"). 같은 정의로 다시 실행하는 것은 커넥터가 돌고 있어도 됩니다.

`pg_hba.conf`는 파일 하나를 마운트하므로, 바꿀 때는 tar로 덮어쓰지 말고 같은 파일에 내용을 써야 컨테이너에 보입니다(`cat 새파일 > pg_hba.conf`). 그 뒤 `SELECT pg_reload_conf()`로 다시 읽힙니다.

## 허용 목록 (S15P31C106-395)

원천에서 금지 데이터를 플랫폼으로 들이지 않도록, 계정별로 읽을 수 있는 표·컬럼을 명시합니다. 원천 관리자 계정이 적용하고, 플랫폼 계정은 계속 읽기 전용입니다.

| 파일 | 하는 일 |
|---|---|
| `sql/allowlist.sql` | 자료만 있다. 정책(금지·제한), 허용 표와 계정별 컬럼, CDC 여부, 일부러 뺀 표 |
| `sql/apply_allowlist.sql` | 허용 목록대로 권한·publication을 맞춘다. 정의 확인 → 전부 회수 → 허용 목록만 부여 → publication → 결과 대조. 문제가 있으면 예외로 전체를 되돌린다 |
| `sql/setup_roles.sql` | 새 설치·재실행. 계정을 만들고(비밀번호·속성) 위 두 파일을 한 트랜잭션으로 실행한다(`setup.sh`가 부른다) |
| `sql/migrate_allowlist.sql` | 기존 서버용. 계정은 건드리지 않고 권한·publication만 바꾼다. 기본은 시험 실행(되돌림), `-v apply=1`이면 확정. 슬롯이 있으면 확정을 멈춘다(`-v allow_slot=1`로만 넘어감) |
| `sql/report_access.sql` | 읽기만 한다. 계정별 읽을 수 있는 컬럼, publication 표·컬럼, replica identity, 남은 기본 권한, 복제 슬롯 |

정책은 백엔드 `application.yml`의 `axcore.source-data-policy`와 글자까지 같아야 합니다. `infra/tests/test_source_allowlist.py`가 둘을 비교합니다(백엔드에 정책이 없으면 건너뜀).

```bash
python -m unittest discover -s infra/tests -p 'test_source_allowlist.py' -v
```

| 등급 | 대상 | `platform_ro` | `platform_ai` | `platform_cdc`·publication |
|---|---|---|---|---|
| 금지 | `erp.attendance`·`erp.fixed_asset` 표 전체, `erp.employee`의 `emp_name`·`position`·`hire_date`·`employment_type`·`status`, `mes.worker.name` | 못 읽음 | 못 읽음 | 못 읽음·안 보냄 |
| 제한 | `mes.maintenance_order.technician`, `erp.vendor.contact_name`, `mes.worker.skill_level` | 읽음 | 못 읽음 | 읽음·보냄 |
| 제외 | ERP 회계 4표(`sales_invoice`·`purchase_invoice`·`gl_account`·`journal_entry`, S15P31C106-133) | 못 읽음 | 못 읽음 | 못 읽음·안 보냄 |
| 일반 | 나머지. `change_log`는 CDC에서만 뺀다 | 읽음 | 읽음 | 읽음·보냄 |

허용 목록의 규칙:

- **표는 목록에 있어야만 읽힌다.** 예전 스크립트의 "스키마 전체 SELECT"와 기본 권한(새 표 자동 SELECT)은 없앴다. 목록에 없는 표가 있으면 적용할 때 알림(NOTICE)을 낸다.
- **컬럼은 금지·제한 컬럼이 있는 표만 적는다.** `erp.employee`(`emp_no`, `dept_code`), `mes.worker`(RO·CDC는 `name` 뺌, AI는 `skill_level`도 뺌), AI의 `erp.vendor`·`mes.maintenance_order`. 이런 표는 새 컬럼이 생겨도 목록에 적기 전까지 아무도 못 읽는다(민감한 표는 닫힌 쪽이 기본).
- **나머지 표는 표 전체 권한**이고 publication에도 컬럼 목록 없이 넣는다. 새 컬럼이 생기면 바로 읽히고 CDC로도 온다(`ai/data/scenario-v1.0/cdc_04_add_column.sql`의 `mes.work_order.due_date` 같은 경우).
- **publication은 허용 목록에서 바로 만든다.** 예전처럼 `platform_ro`의 표 권한을 보고 만들지 않는다. 있으면 `ALTER PUBLICATION axcore_cdc SET TABLE ...`로 목록을 통째로 바꾼다.
- **다시 실행해도 넓어지지 않는다.** 실행할 때마다 스키마의 모든 표 권한을 세 계정에서 회수하고 허용 목록만 다시 준다. 누가 손으로 권한을 넓혀 두었어도 원래대로 돌아간다.
- **이름이 틀리면 멈춘다.** 정책·허용 목록에 적힌 표·컬럼이 원천에 없거나, 금지 컬럼이 목록에 들어 있거나, CDC 표의 기본 키가 컬럼 목록에 없으면(UPDATE·DELETE가 원천에서 실패함) 아무것도 바꾸지 않고 예외를 낸다.
- **결과를 대조한다.** 적용 뒤 세 계정이 컬럼 하나하나를 실제로 읽을 수 있는지(PUBLIC·물려받은 역할·소유 포함)가 허용 목록과 정확히 같은지, 쓰기 권한·물려받은 역할(`pg_read_all_data` 등)·넓은 계정 속성·남은 기본 권한이 없는지, publication의 표·컬럼이 기대와 같은지 본다. 다르면 되돌린다.

플랫폼은 원천 권한으로 읽을 수 있는 컬럼만 가져오므로(`SourceCatalogReader`·`SourceTableReader`의 `has_column_privilege`, `ai/app/sources/postgres_reader.py`) 컬럼 권한으로 바꿔도 `SELECT *` 오류가 나지 않습니다.

## 기존 서버에 적용 (S15P31C106-397, 승인 뒤)

2026-11-02 세팅한 서버는 예전 스크립트로 만들어졌습니다. 세 계정이 스키마의 모든 표를 읽고(이후 회계 4표는 회수), publication에 금지 표·컬럼이 들어 있습니다. `migrate_allowlist.sql`로 바꿉니다. 원천 관리자 계정(`source_admin`)으로 컨테이너 안에서 실행하고, 플랫폼 계정에는 아무 권한도 더하지 않습니다.

1. **커넥터가 없을 때 한다.** 슬롯이 있으면 바꾸기 전에 쌓인 WAL은 예전 publication 기준으로 풀려 금지 컬럼이 나갈 수 있다. 2026-11-13 현재 커넥터·슬롯이 없다(`infra/cdc/README.md`). 적용 전 보고의 "복제 슬롯"이 비어 있는지 본다. 있으면 커넥터를 멈추고 슬롯을 지운 뒤 적용하고, 커넥터는 적용 뒤 다시 등록한다.

   **슬롯 확인(스크립트가 막는다):** `apply_allowlist.sql`은 publication 정의가 실제로 바뀌는데 그 DB에 논리 복제 슬롯이 있으면 예외로 전체를 되돌린다. `setup_roles.sql`(setup.sh)과 `migrate_allowlist.sql` 확정 모두 같다. 시험 실행은 멈추지 않고 경고("확정하면 멈춘다")만 낸다. 슬롯을 지울 수 없는데 꼭 지금 바꿔야 하면 `migrate_allowlist.sql`을 `-v apply=1 -v allow_slot=1`로 실행한다. 그때는 바꾸기 전 WAL이 예전 정의로 풀린다는 것을 알고 하는 것이다.
2. 스크립트를 서버에 올린다. `sql/`은 폴더째 마운트되어 있어 바로 컨테이너에 보인다.

   ```bash
   K=~/.ssh/C106_Source.pem; H=ec2-user@3.35.230.145; DB=erp
   tar -C infra/source-db -cf - sql | ssh -i $K $H 'tar -C ~/source-db -xf -'
   ```

3. 시험 실행. 한 트랜잭션 안에서 바꾸고 적용 전·뒤 보고를 출력한 뒤 되돌린다. 적용 뒤 보고가 위 표와 같은지 본다.

   ```bash
   ssh -i $K $H "cd ~/source-db && docker compose exec -T source-db sh -c 'psql -X -q -U \"\$POSTGRES_USER\" -d $DB -f /sql/migrate_allowlist.sql'"
   ```

4. 확정. 같은 명령에 `-v apply=1`을 붙인다. 다시 실행해도 결과는 같다.

   ```bash
   ssh -i $K $H "cd ~/source-db && docker compose exec -T source-db sh -c 'psql -X -q -U \"\$POSTGRES_USER\" -d $DB -v apply=1 -f /sql/migrate_allowlist.sql'"
   ```

5. 확인. 메인 서버에서 각 계정으로 금지 컬럼 조회가 거부되는지(`select emp_name from erp.employee` → permission denied), 플랫폼 연결 검사·구조 조회가 되는지 본다. 커넥터를 등록한 뒤에는 `erp.employee`·`mes.worker` 토픽 메시지에 금지 컬럼이 없는지 본다(Debezium으로는 아직 시험하지 않았다. 아래 "남은 것").
6. 되돌리기가 필요하면 `git`의 예전 `setup_roles.sql`이 아니라 허용 목록을 고쳐 다시 적용한다. 예전 스크립트는 금지 데이터를 다시 열기 때문이다.

권한을 바꿔도 이미 플랫폼 DB·Kafka 토픽에 들어온 금지 데이터는 그대로입니다. 그 정리는 384의 사본 정리·재적재 단계에서 합니다.

## REPLICATION 권한의 한계

publication과 컬럼 목록은 **Debezium이 쓰는 pgoutput 출력**만 거릅니다. `platform_cdc`는 REPLICATION 권한이 있어 다른 출력 플러그인(`test_decoding` 등)으로 슬롯을 직접 만들면 publication과 상관없이 DB 전체의 변경(금지 표 포함)을 WAL에서 읽을 수 있습니다. 로컬 시험에서 `platform_cdc`로 `test_decoding` 슬롯을 만들어 `erp.attendance` 변경이 보이는 것을 확인했습니다. PostgreSQL에는 슬롯·표 단위로 복제를 묶는 권한이 없어서, CDC 경로는 강한 경계가 아니고 아래로 보완합니다.

- **자격 증명 보호**: `PLATFORM_CDC_PASSWORD`는 원천 `~/source-db/.env`(권한 600)와 메인 서버 `~/axcore/cdc/.env`(Kafka Connect)에만 둔다. 화면·로그·저장소에 내보내지 않고, 샜다고 의심되면 바로 바꾼다(`setup_roles.sql`의 `ALTER ROLE ... PASSWORD`).
- **접속 제한**: `pg_hba.conf`와 AWS 보안 그룹이 메인 서버 IP에서 SSL로 오는 접속만 받는다. 노트북 등 다른 곳에서는 `platform_cdc`로 접속할 수 없다.
- **슬롯 감시**: 정상 슬롯은 `axcore_erp`·`axcore_mes`(pgoutput) 둘뿐이다. 다른 이름이나 다른 플러그인의 슬롯이 보이면 누가 만들었는지 확인하고 지운다. `report_access.sql`의 "복제 슬롯"이나 아래 쿼리로 본다.

  ```sql
  select slot_name, plugin, active, active_pid from pg_replication_slots
  where slot_name not in ('axcore_erp', 'axcore_mes') or plugin <> 'pgoutput';
  ```

- 조회 계정(`platform_ro`·`platform_ai`)은 REPLICATION이 없어 이 한계가 없다. 원천 권한으로 막는 것이 강한 경계다.

## 조회 계정을 둘로 나눈 이유 (2026-11-02)

- 배치는 테이블 전체를 오래 읽고, FastAPI 분석은 짧게 여러 번 읽습니다. 계정을 나눠 AI 쪽에만 시간 제한(30초)과 접속 수 제한(5개)을 걸어 배치가 쓸 접속을 남깁니다.
- 외부 LLM으로 보내면 안 되는 제한 컬럼은 `platform_ai` 권한에서만 빼면 DB가 막습니다. 허용 목록에서 AI 컬럼을 따로 적는 이유입니다.
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

## 허용 목록 로컬 시험 (2026-11-13, S15P31C106-395)

노트북 Docker의 `postgres:18.6` 컨테이너에 `data-v1.0`을 적재해 시험했습니다. 실제 원천 서버에는 적용하지 않았습니다.

- **새 설치**: `setup_roles.sql`로 ERP 12표(CDC 11), MES 15표(CDC 14). `report_access.sql` 결과가 위 표와 같음
- **재실행**: 다시 실행한 뒤 보고가 글자까지 같음. 손으로 `GRANT SELECT ON ALL TABLES`·기본 권한·`INSERT`를 넓혀 둔 뒤 재실행해도 처음 상태로 돌아감
- **계정 접속**: `platform_ro`는 `employee.emp_name`·`SELECT *`·`attendance`·`fixed_asset`·회계 표 거부, `emp_no`·`dept_code`·`vendor.contact_name` 성공, 읽기 전용을 풀어도 `UPDATE` 거부. `platform_ai`는 `vendor.contact_name`·`worker.skill_level`·`maintenance_order.technician` 거부, 나머지 컬럼 성공. `platform_cdc`는 `employee.position`·`worker.name`·`change_log` 거부
- **기존 서버 흉내**: 예전 스크립트(afbc57d2)로 세팅하고 pgoutput 슬롯을 만든 상태에서 `migrate_allowlist.sql` 시험 실행 → 상태 그대로, `-v apply=1` → 새 설치와 같은 결과, 다시 확정 → 그대로(슬롯 확인을 넣기 전 기록. 지금은 아래처럼 슬롯을 지운 뒤 확정한다)
- **슬롯 확인**(2026-11-13, ERP): 새 설치 → 슬롯을 만들고 같은 정의로 `setup_roles.sql` 재실행은 통과 → publication을 예전처럼 넓힌 뒤 `setup_roles.sql`은 예외로 그대로, `migrate_allowlist.sql` 시험 실행은 경고만 하고 되돌림, `-v apply=1`은 예외로 그대로, `-v apply=1 -v allow_slot=1`은 경고 뒤 확정 → 슬롯을 지운 뒤 `-v apply=1`은 그냥 확정
- **정의 오류**: 정책 컬럼 오타, `ro_columns`에 금지 컬럼, 금지 표를 허용 목록에, AI 목록에 제한 컬럼, 제한 컬럼 표의 AI 목록 생략, 컬럼 목록에서 기본 키 누락 — 모두 예외로 멈추고 상태 그대로
- **실제 복제**: 다른 PostgreSQL에서 `platform_cdc`로 `axcore_cdc`를 구독. 첫 복사에서 `employee`의 금지 5컬럼·`worker.name`이 비어 있고 `attendance`·`fixed_asset`은 오지 않음. 이후 `UPDATE`(금지 컬럼 포함)·`INSERT`·`DELETE`가 전달되고 금지 컬럼 값은 오지 않음
- **REPLICATION 한계**: `platform_cdc`로 `test_decoding` 슬롯을 만들면 `erp.attendance` 변경이 보임(위 "한계")
- **정책 비교**: `test_source_allowlist.py` — develop(정책 없음)은 건너뜀, 391 브랜치의 `application.yml`과는 일치, 한 글자 바꾸면 실패

## 원천에 테이블을 추가할 때

새 표는 저절로 플랫폼 권한·CDC에 들어가지 않습니다(기본 권한 없음, publication은 표 목록). 스키마 단위(`FOR TABLES IN SCHEMA`)로 바꾸면 자동으로 들어가지만, 그러면 컬럼 목록을 쓸 수 없어 금지 컬럼을 뺄 수 없습니다(PostgreSQL 18 문서).

1. 금지·제한 컬럼이 있는지 정한다. 있으면 백엔드 `axcore.source-data-policy`와 `allowlist.sql`의 정책을 함께 고친다
2. `allowlist.sql`의 허용 목록에 한 줄 넣고 `migrate_allowlist.sql`로 시험 실행 → 확정(관리자 계정)
3. 플랫폼에서 연결 검사 → 분석·승인 → 첫 적재. **2번이 첫 적재보다 먼저**여야 그 사이 변경을 놓치지 않는다

## 남은 것

- 기존 원천 서버 두 대에 허용 목록 적용(S15P31C106-397, 승인 뒤, 위 절차)
- Debezium 커넥터가 컬럼 목록 publication에서 금지 컬럼 없이 메시지를 만드는지 커넥터 등록 뒤 확인. 두 번째 방어로 커넥터 설정에 `column.exclude.list`를 둘지 정한다(`infra/cdc/`)
- 시나리오 5 원천 수정분(`ai/data/scenario-v1.0/s5_fix.sql`)은 시연 때만 관리자 계정으로 적용한다
