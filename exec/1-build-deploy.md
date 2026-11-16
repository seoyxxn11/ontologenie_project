# 1. 빌드·배포 매뉴얼

GitLab 저장소(`https://lab.ssafy.com/s15-final/S15P31C106.git`)를 클론한 뒤 빌드하고 서버에 배포하는 방법입니다. 비밀값은 적지 않고, 어디에 넣는지만 적었습니다(🔒 표시).

## 1.1 구성

| 구성 요소 | 폴더 | 하는 일 | 컨테이너 |
| --- | --- | --- | --- |
| 프론트엔드 | `frontend/` | React 사용자·관리자 화면 | `frontend` (nginx로 정적 파일 서빙) |
| 백엔드 API | `backend/` | Spring Boot. 온톨로지 정의·제안 검토·조회·Action·인증·AI Agent | `backend` (프로필 `api`) |
| 수집 배치 | `backend/` (같은 코드) | CDC 이벤트 반영, 전체 읽기, 정합성 검사, 작업 큐 | `batch` (프로필 `batch`) |
| AI 분석 서버 | `ai/` | FastAPI. 원천 분석·후보 제안 | `ai` |
| 플랫폼 DB | - | PostgreSQL, 스키마 `platform` | `postgres` |
| CDC | `infra/cdc/` | Kafka, Kafka Connect(Debezium), Kafka UI | `kafka`, `kafka-connect`, `kafka-ui` |
| 원천 ERP·MES | `infra/source-db/` | 고객사 원천 DB를 가정한 PostgreSQL 2대(별도 서버) | `source-db` |
| 프록시 | `infra/nginx/` | HTTPS, 경로 분기 | `nginx` |
| CI/CD | `Jenkinsfile`, `infra/jenkins/`, `infra/deploy/` | MR 검사, develop 자동 배포 | `jenkins` |
| 모니터링 | `infra/monitoring/` | Prometheus, Grafana, Loki, Alloy | 같은 이름 |
| 코드 품질 | `infra/sonarqube/` | SonarQube | `sonarqube` |

```text
브라우저 --HTTPS--> nginx --/--------> frontend
                         --/api/----> backend --(내부 HTTP)--> ai --> LLM(GMS)
                                         |  \--> LLM(GMS, Agent)
                                         v
                                    플랫폼 DB <-- batch <-- Kafka <-- Debezium <-- 원천 ERP·MES(WAL)
                                                    \---------- 읽기 전용 조회 -----------/
```

## 1.2 사용 도구와 버전

### 백엔드

| 항목 | 버전 | 근거 |
| --- | --- | --- |
| JDK | 21 (빌드 `eclipse-temurin:21-jdk`, 실행 `eclipse-temurin:21-jre`) | `backend/build.gradle`, `backend/Dockerfile` |
| Gradle Wrapper | 9.8.2 | `backend/gradle/wrapper/gradle-wrapper.properties` |
| Spring Boot | 4.1.2 (Spring Framework 7.0.10, Spring Batch 6.0.6, Hibernate 7.4.13) | `backend/build.gradle` |
| Spring AI | 2.0.2 (OpenAI 호환 chat) | 같은 파일 |
| Flyway | 12.4.0 | Spring Boot BOM |
| Kafka clients | 4.2.2 (spring-kafka는 쓰지 않음) | 같은 BOM |
| PostgreSQL JDBC | 42.7.14 | 같은 BOM |
| springdoc-openapi | 3.2.0 | `backend/build.gradle` |
| Testcontainers | 2.0.5 (시험에 Docker 필요) | Spring Boot BOM |
| 포맷 | Spotless 8.10.4 + google-java-format 1.36.1 (AOSP) | `backend/build.gradle` |

### 프론트엔드

| 항목 | 버전 |
| --- | --- |
| Node.js | 24.18.0 (`node:24.18.0-alpine`) |
| npm | Node 24 동봉 (`package-lock.json` lockfileVersion 3) |
| React | 19.3.1 |
| TypeScript | 6.0.3 |
| Vite | 8.3.5 |
| react-router | 8.5.0 |
| TanStack Query | 5.105.0 |
| Tailwind CSS | 4.3.3 |
| Vitest | 5.0.5 |
| MSW (모의 API) | 3.0.3 |
| 서빙 | `nginx:1.30-alpine` (`frontend/nginx.conf`, SPA fallback) |

### AI 분석 서버

| 항목 | 버전 |
| --- | --- |
| Python | 3.11 (`python:3.11-slim`) |
| FastAPI | 0.143.0 |
| uvicorn | 0.54.0 |
| pandas | 3.0.6 |
| psycopg[binary] | 3.3.6 |
| 시험·검사 | pytest 9.1.1, ruff 0.17.2 |

LLM SDK는 쓰지 않고 OpenAI 호환 API를 직접 호출합니다(`ai/app/llm/client.py`).

### 인프라

| 항목 | 이미지·버전 |
| --- | --- |
| 플랫폼 DB, 원천 DB | `postgres:18.6` |
| 프록시 | `nginx:1.30` |
| 인증서 | `certbot/certbot:v4.1.1` |
| Kafka | `apache/kafka:4.2.2` (KRaft 단일 노드) |
| Kafka Connect + Debezium | `quay.io/debezium/connect:3.6.3.Final` |
| Kafka UI | `redpandadata/console:v3.12.0` |
| Jenkins | `jenkins/jenkins:2.580.1-lts-jdk21` 기반 `axcore-jenkins:2.580.1` (docker CLI·compose·buildx 포함) |
| SonarQube | `sonarqube:26.10.0.133078-community` (+ `postgres:17.11`) |
| 모니터링 | Prometheus v3.14.0, Grafana 13.3.0, Loki 3.7.9, Alloy v1.21.3 |
| Docker | 29.x, Docker Compose v2.24.4 이상 (`!override`·`!reset` 사용) |

### 서버와 개발 도구

- 메인 서버: AWS Lightsail Ubuntu, 메모리 16GB, 접속 사용자 `ubuntu`, 배포 폴더 `/home/ubuntu/axcore`
- 원천 서버 2대(ERP, MES): Amazon Linux 2023, t3a.medium, 접속 사용자 `ec2-user`
- IDE: 팀 공통으로 정한 IDE는 없습니다(이 문서 작성 환경: IntelliJ IDEA 2026.1, VS Code). 포맷은 IDE가 아니라 Spotless·Prettier·Ruff 결과를 기준으로 합니다.

## 1.3 로컬 빌드와 실행

저장소 루트에서 시작합니다.

### 백엔드 (`backend/`, JDK 21과 Docker 필요)

```bash
cd backend
./gradlew build                 # 포맷 검사 + 시험 + 빌드 (Testcontainers로 PostgreSQL·Kafka를 띄움)
./gradlew spotlessApply         # 포맷 맞추기
./gradlew bootTestRun --args='--spring.profiles.active=api'   # 로컬 DB 없이 실행(Testcontainers)
```

로컬 PostgreSQL에 붙여 실행할 때는 프로필 `local,api`(API) 또는 `local,batch`(배치)를 쓰고 `DB_PASSWORD`를 넣습니다. 기본 접속은 `jdbc:postgresql://localhost:5432/axcore`, 사용자 `axcore`입니다(`application-local.yml`). 빈 DB에서 처음 띄우면 Flyway가 스키마 `platform`에 V1~V26을 적용합니다.

빌드 결과 이미지는 `docker build -t axcore-backend:<태그> backend`로 만듭니다. 같은 이미지를 `backend`(api)와 `batch`(batch)가 프로필만 바꿔 씁니다.

### AI 분석 서버 (`ai/`, Python 3.11)

```bash
cd ai
python -m venv .venv
.venv/Scripts/python -m pip install -r requirements-dev.txt   # Linux·macOS: .venv/bin/python
.venv/Scripts/python -m pytest
.venv/Scripts/python -m ruff check .
.venv/Scripts/python -m uvicorn main:app --port 8000          # http://127.0.0.1:8000/health
```

이미지는 `docker build -t axcore-ai:<태그> ai`로 만듭니다.

### 프론트엔드 (`frontend/`, Node 24)

```bash
cd frontend
npm ci
npm run dev          # MSW 모의 API로 실행, http://localhost:5173
npm run dev:api      # 실제 백엔드에 붙여 실행 (API_PROXY_TARGET, 기본 http://localhost:8080)
npm run lint && npm test && npm run format:check && npm run build
```

운영 빌드는 환경 변수가 없고 API를 상대 경로 `/api/v1`로 부릅니다. 이미지는 `docker build -t axcore-frontend:<태그> frontend`로 만듭니다.

## 1.4 빌드·실행 환경 변수

### 메인 서버 Compose (`infra/.env.example` → 서버 `~/axcore/.env`)

| 변수 | 뜻 | 비고 |
| --- | --- | --- |
| `POSTGRES_DB`, `POSTGRES_USER` | 플랫폼 DB 이름·사용자 (운영 `axcore`/`axcore`) | |
| `POSTGRES_PASSWORD` | 플랫폼 DB 비밀번호 | 🔒 |
| `BACKEND_TAG`, `FRONTEND_TAG`, `AI_TAG` | 실행할 이미지 태그(커밋 SHA). Jenkins가 갱신 | |
| `BACKEND_GREEN_TAG` | Blue-Green 전환용 backend 태그 | 수동 전환 때만 |
| `AXCORE_INITIAL_ADMIN_LOGIN_ID` | 최초 관리자 로그인 ID | |
| `AXCORE_INITIAL_ADMIN_PASSWORD` | 최초 관리자 임시 비밀번호 | 🔒 |
| `AXCORE_INTERNAL_API_TOKEN` | backend 내부 API 토큰. AI 서버의 `AI_ONTOLOGY_INTERNAL_TOKEN`과 같은 값 | 🔒, 비면 내부 API 전부 차단 |
| `AXCORE_SOURCE_CREDENTIAL_KEY_1` | 원천 접속 비밀번호 암호화 키(32바이트 base64, AES-256-GCM) | 🔒, backend·batch만 |
| `AXCORE_SOURCE_CREDENTIAL_KEY_VERSION` | 사용할 키 버전 (기본 1) | |
| `AXCORE_ACTION_NOTIFY_WEBHOOK_URL` | Handler 구현 요청 알림용 Mattermost Webhook | 🔒 |
| `AXCORE_ACTION_NOTIFY_SCREEN_BASE_URL` | 알림에 넣을 화면 주소(예: `https://ontologenie.com`) | |
| `AXCORE_AGENT_LLM` | Agent LLM 사용 여부(`none`/`openai`). 운영은 `openai` | |
| `AXCORE_AGENT_LLM_API_KEY` | Agent LLM 키(GMS 키와 같은 값) | 🔒 |
| `AXCORE_AGENT_LLM_BASE_URL` | Agent LLM 주소(GMS) | |
| `AXCORE_AGENT_OBJECT_SETS_ENABLED` 등 | Agent 도구 스위치(`OBJECT_PATHS`, `OBJECT_SET_FIND`, `OBJECT_SETS`) | |
| `AXCORE_CDC_CONSUMER_ENABLED`, `AXCORE_CHECK_SYNC_ENABLED` | batch의 CDC 소비·수집 후 검사 켜기 | compose 기본 false, 운영 true |
| `AI_ONTOLOGY_LLM_PROVIDER` | AI 분석 LLM 제공사(`gms`/`openai`/`openai-chat`/`none`/`mock`) | 기본 `gms` |
| `AI_ONTOLOGY_LLM_API_KEY` | AI 분석 LLM 키(GMS) | 🔒 |
| `AI_ONTOLOGY_LLM_BASE_URL`, `AI_ONTOLOGY_LLM_MODEL`, `AI_ONTOLOGY_LLM_TIMEOUT_SEC` | LLM 주소·모델·시간 제한(기본 60초) | |
| `GRAFANA_ADMIN_USER` | Grafana 관리자 | |
| `GRAFANA_ADMIN_PASSWORD` | Grafana 관리자 비밀번호 | 🔒, 비면 compose 해석 실패 |

compose 안에 고정된 값: `SPRING_PROFILES_ACTIVE`(api/batch), `DB_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}`, `AXCORE_AI_BASE_URL=http://ai:8000`, `AI_ONTOLOGY_BACKEND_URL=http://backend:8080`, `TZ=Asia/Seoul`, `JAVA_TOOL_OPTIONS`(backend `-XX:MaxRAMPercentage=75`, batch `-Xmx768m`).

### CDC (`infra/cdc/.env.example` → 서버 `~/axcore/cdc/.env`)

| 변수 | 뜻 |
| --- | --- |
| `ERP_CDC_PASSWORD`, `MES_CDC_PASSWORD` | 🔒 원천 `platform_cdc` 계정 비밀번호(원천 서버 `.env`의 `PLATFORM_CDC_PASSWORD`와 같은 값) |

### 원천 서버 (`infra/source-db`, `setup.sh`가 `~/source-db/.env`를 만듦)

| 변수 | 뜻 |
| --- | --- |
| `SOURCE_DB` | `erp` 또는 `mes` |
| `POSTGRES_USER` | `source_admin` |
| `POSTGRES_PASSWORD`, `PLATFORM_RO_PASSWORD`, `PLATFORM_AI_PASSWORD`, `PLATFORM_CDC_PASSWORD` | 🔒 `setup.sh`가 비어 있을 때만 생성 |

### 백엔드 설정 파일의 주요 자리 (`backend/src/main/resources/application*.yml`)

| 변수 | 기본값 | 뜻 |
| --- | --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`🔒 | local 프로필: `localhost:5432/axcore`, `axcore` | 플랫폼 DB |
| `AXCORE_SOURCE_SSL_MODE` | `require` | 원천 접속 SSL |
| `AXCORE_SOURCE_CDC_PUBLICATION` | `axcore_cdc` | Debezium publication 이름 |
| `AXCORE_SOURCE_LOAD_MAX_ROWS` | 200000 | 전체 읽기 한 번의 최대 행 수 |
| `AXCORE_SOURCE_SCHEDULE_ENABLED` | true | 수집 주기 실행 |
| `AXCORE_ANALYSIS_QUEUED_TIMEOUT`, `AXCORE_ANALYSIS_RUNNING_TIMEOUT` | 10m, 60m | 분석 작업 시간 제한 |
| `AXCORE_AGENT_LLM_MODEL` | `gpt-5.6-luna` | Agent 모델 |
| `AXCORE_AGENT_HISTORY_MAX_TURNS` | 4 | Agent 대화 문맥 턴 수 |
| `AXCORE_CDC_BOOTSTRAP_SERVERS` | `kafka:9092` | batch의 Kafka 주소 |

원천 데이터 정책(금지·민감 컬럼 목록)은 `application.yml`의 `axcore.source-data-policy`에 있습니다. 비어 있으면 기동하지 않습니다(닫힌 기본값). 바꿀 때는 원천 허용 목록(`infra/source-db/sql/allowlist.sql`)도 같이 바꿉니다. CI가 두 목록이 같은지 검사합니다.

### AI 서버 (`ai/.env.example`)

`AI_ONTOLOGY_LLM_*`(위 표), `AI_ONTOLOGY_BACKEND_URL`(기본 `http://localhost:8080`), `AI_ONTOLOGY_INTERNAL_TOKEN`🔒, `AI_ONTOLOGY_SKIP_ENV_FILE=1`(Docker 이미지에서 `.env` 읽기 끔).

### 프론트엔드 (`frontend/.env.example`)

`VITE_API_MODE`(`mock`/`real`), `API_PROXY_TARGET`(개발 서버 프록시 대상). 운영 빌드에는 쓰지 않습니다.

## 1.5 서버 배포

### 처음 세팅 (메인 서버)

1. Docker 29.x와 Compose v2를 설치하고, 저장소 `infra/` 내용을 `/home/ubuntu/axcore`에 둡니다.
2. `compose.yaml`의 `group_add: "988"`을 서버의 docker 소켓 그룹 번호로 맞춥니다(`stat -c '%g' /var/run/docker.sock`).
3. `jenkins-data/` 폴더를 만들고 소유자를 uid 1000으로 바꿉니다.
4. `.env`를 만듭니다: `cp .env.example .env` → 값 입력 → `chmod 600 .env`. DB 비밀번호는 `openssl rand -base64 24`로 만듭니다.
5. 인증서를 발급합니다.
   ```bash
   docker compose run --rm -T certbot certonly --webroot -w /var/www/certbot \
     --cert-name k15c106.p.ssafy.io --expand -d k15c106.p.ssafy.io -d ontologenie.com
   ```
6. Swagger·Kafka UI용 Basic Auth 파일을 만듭니다: `echo "swagger:$(openssl passwd -apr1)" > ~/axcore/nginx/htpasswd`
7. crontab에 인증서 갱신과 DB 백업을 등록합니다.
   ```text
   0 3 * * * /home/ubuntu/axcore/scripts/renew-cert.sh >> /home/ubuntu/axcore/logs/renew-cert.log 2>&1
   0 4 * * * /home/ubuntu/axcore/scripts/pg-backup.sh >> /home/ubuntu/axcore/logs/pg-backup.log 2>&1
   ```
8. 기동합니다. 메인 compose가 `axcore-net` 네트워크를 만들므로 먼저 올립니다.
   ```bash
   cd ~/axcore && docker compose config --quiet && docker compose up -d
   cd ~/axcore/cdc && docker compose config --quiet && docker compose up -d
   ```
   postgres가 healthy가 된 뒤 backend·batch가 뜹니다. backend가 처음 뜰 때 Flyway가 스키마를 만들고, ADMIN이 없으면 `AXCORE_INITIAL_ADMIN_*` 값으로 최초 관리자를 만듭니다(임시 비밀번호, 첫 로그인 때 변경).

### 원천 ERP·MES 서버

저장소 루트에서 실행합니다. MES는 `DB=mes`와 MES 서버 주소로 바꿉니다.

```bash
K=~/.ssh/C106_Source.pem; H=ec2-user@<ERP 원천 서버 주소>; DB=erp
tar -C infra/source-db -cf - compose.yaml pg_hba.conf sql setup.sh server-init.sh | ssh -i $K $H 'mkdir -p ~/source-db && tar -C ~/source-db -xf -'
tar -C ai/data/data-v1.0 -cf - load_$DB.psql verify_counts.sql $DB | ssh -i $K $H 'mkdir -p ~/source-db/data && tar -C ~/source-db/data -xf -'
ssh -i $K $H '~/source-db/server-init.sh'   # 시간대·스왑·Docker
ssh -i $K $H "~/source-db/setup.sh $DB"     # .env·인증서 생성, 기동, 합성 데이터 적재·행 수 확인, 계정·허용 목록·publication
```

`setup.sh`는 다시 실행해도 됩니다. 원천은 `wal_level=logical`, 포트 15432이고, `pg_hba.conf`로 메인 서버 IP의 SSL scram 접속만 받습니다. 원천 서버 보안 그룹도 메인 서버 IP만 엽니다.

### CDC 연결 순서 (`infra/cdc/README.md`)

1. 메인 서버 `~/axcore/cdc`에서 Kafka·Connect를 띄웁니다(위 8번).
2. 커넥터를 등록합니다.
   ```bash
   tar -C infra/cdc -cf - register-connectors.sh connectors | ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io 'tar -C ~/axcore/cdc -xf -'
   ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io '~/axcore/cdc/register-connectors.sh'
   ```
   두 커넥터가 RUNNING이고 원천 슬롯이 `active`인지 확인합니다.
3. 서버 `~/axcore/.env.config`에 `AXCORE_CDC_CONSUMER_ENABLED=true`, `AXCORE_CHECK_SYNC_ENABLED=true`를 넣고 batch를 다시 만듭니다. batch 로그에 `CDC 소비자 시작 topic=ERP_MAIN.all`이 나오면 됩니다.
4. 화면에서 원천 등록(코드 `ERP_MAIN`, `MES_MAIN`)과 조회 계정 비밀번호를 넣고, 분석·제안 승인으로 매핑을 만들면 수집이 시작됩니다.

### 자동 배포 (Jenkins)

- **MR 검사 `axcore-mr-check`**: MR을 만들거나 갱신하면 바뀐 폴더만 검사합니다. backend `./gradlew spotlessCheck test build jacocoTestReport`, frontend `npm ci → format:check → lint → test:coverage → build`, AI `ruff check → pytest --cov`, infra 셸 문법·unittest. 결과는 GitLab MR 상태로 보이고, 실패하면 작성자에게 Mattermost DM이 갑니다.
- **배포 `axcore-deploy`**: develop에 머지하면 GitLab Webhook으로 실행됩니다.
  1. `origin/develop`을 체크아웃합니다.
  2. Jenkins Credentials와 서버 `.env.config`로 실행용 `.env`, `cdc/.env`를 만듭니다(`infra/deploy/prepare_credentials.py`, 권한 600).
  3. 허용된 설정 파일만 서버에 반영하고 검사합니다(`infra/deploy/sync-config.sh`, 실패하면 복구).
  4. 바뀐 서비스만 이미지를 빌드합니다(`axcore-<서비스>:<커밋 SHA>`). backend는 배포 전에 DB를 백업하고, 태그를 바꿔 `docker compose up -d backend`를 한 뒤 최대 90초 healthy를 기다리고 batch를 다시 만듭니다. 실패하면 직전 태그로 롤백합니다. AI, frontend 순으로 같은 방식입니다.
  5. 이미지는 최근 3개 태그만 남깁니다. 결과는 GitLab 커밋 상태와 Mattermost DM으로 알립니다.

### 수동 배포와 롤백 (자동 배포가 안 될 때)

```bash
SHA=$(git -C ~/axcore/jenkins-data/workspace/axcore-deploy rev-parse --short HEAD)
docker build -t axcore-backend:$SHA ~/axcore/jenkins-data/workspace/axcore-deploy/backend
# ~/axcore/.env 의 BACKEND_TAG=$SHA 로 수정
cd ~/axcore && docker compose up -d backend
curl -s 127.0.0.1:18080/actuator/health    # {"status":"UP"}
```

롤백은 `docker images axcore-backend`로 직전 태그를 찾아 `.env`의 태그를 되돌리고 `docker compose up -d backend`를 합니다.

### DB 백업·복구

- `scripts/pg-backup.sh`가 매일 04:00에 `pg_dump -Fc`로 `~/axcore/backup/axcore_YYYYMMDD_HHMM.dump`를 만들고 7일 보관합니다.
- 복구: 앱 컨테이너(backend·batch)를 멈춘 뒤 `docker compose exec -T postgres pg_restore -U axcore -d axcore --clean --if-exists < backup/<파일>.dump`, 다시 켭니다.
- 저장소에 넣은 덤프 파일과 쓰는 방법은 [3-db-dump](3-db-dump/README.md)에 있습니다.

## 1.6 포트와 경로

바깥에는 80/443만 엽니다. 나머지는 `127.0.0.1`에만 묶습니다.

| 서비스 | 호스트 포트 | 외부 경로 |
| --- | --- | --- |
| nginx | 80, 443 | `https://ontologenie.com`, `https://k15c106.p.ssafy.io` |
| frontend | 10080 | `/` |
| backend | 18080 | `/api/` (로그인 분당 30회 제한, `/api/v1/internal*`는 403) |
| AI | 18000 | 외부 공개 안 함(backend가 내부 호출) |
| PostgreSQL | 15432 | 외부 공개 안 함 |
| Jenkins | - | `/jenkins/` |
| Grafana | 13000 | `/grafana/` |
| Kafka UI | 18082 | `/kafka-ui/` (Basic Auth) |
| Swagger | - | `/swagger-ui.html` (Basic Auth) |
| SonarQube | 9000 | `https://sonar.ontologenie.com` |

## 1.7 배포 특이사항

- **원천은 읽기만 합니다.** 플랫폼은 원천에 쓰지 않습니다. 조회는 `platform_ro`(batch)·`platform_ai`(분석) 읽기 전용 계정, CDC는 `platform_cdc`(REPLICATION)만 씁니다. 원천에 트리거나 변경 이력 표를 만들지 않습니다.
- **원천 데이터 정책은 두 곳이 같아야 합니다.** 백엔드 `axcore.source-data-policy`와 원천 허용 목록 `infra/source-db/sql/allowlist.sql`. 금지 표·컬럼(근태, 고정자산, change_log, 직원 이름·직위·입사일·고용형태·재직 상태, 작업자 이름)은 원천 권한부터 막고, 민감 컬럼(정비 담당자, 거래처 담당자, 작업자 숙련도)은 민감 속성으로만 담습니다. 자세한 기준은 [docs/data-access.md](../docs/data-access.md)입니다.
- **최초 관리자는 Flyway가 아니라 기동 때 만들어집니다.** DB에 ADMIN이 한 명도 없을 때만 `AXCORE_INITIAL_ADMIN_*`로 만듭니다.
- **객체 유형은 그룹을 지정해야 보입니다.** 그룹이 없는 유형은 어느 역할에도 보이지 않습니다. 분석·승인 뒤 유형 편집에서 그룹(PRODUCTION, SALES_SUPPLY)을 지정합니다.
- **Agent LLM은 기본으로 꺼져 있습니다**(`AXCORE_AGENT_LLM=none`). 배포 준비 단계가 운영에서 `openai`로 켭니다.
- **원천 접속 비밀번호**는 화면이나 API로 등록하면 `AXCORE_SOURCE_CREDENTIAL_KEY_1`로 암호화해 플랫폼 DB에 저장합니다. 키를 바꾸면 기존 암호문을 다시 등록해야 합니다.
- **AI 서버와 backend는 같은 내부 토큰**을 씁니다. 다르면 분석 결과 수신이 막힙니다.
- **Testcontainers**: Jenkins 컨테이너 안에서 시험을 돌릴 때 `TESTCONTAINERS_HOST_OVERRIDE=172.18.0.1`(axcore-net 게이트웨이)이 필요합니다.
- **PostgreSQL 18 덤프 복구**에는 psql 18 이상이 필요합니다(덤프 첫머리의 `\restrict` 줄).
- Blue-Green 전환(`compose.blue-green.yaml`)은 준비만 되어 있고 자동 배포에서는 쓰지 않습니다.

## 1.8 주요 계정과 설정 파일

### 계정 (비밀번호는 저장소에 없음)

| 계정 | 용도 | 비밀값 위치 |
| --- | --- | --- |
| 플랫폼 DB `axcore` | backend·batch의 플랫폼 DB 접속 | Jenkins Credentials `axcore-postgres-password` → `.env` `POSTGRES_PASSWORD` |
| 최초 관리자 (`AXCORE_INITIAL_ADMIN_LOGIN_ID`) | 첫 ADMIN 계정. 이후 계정은 화면(계정·권한)에서 만듦 | `axcore-initial-admin-password` |
| 원천 `source_admin` | 원천 DB 관리자(원천 서버 컨테이너 안에서만) | 원천 서버 `~/source-db/.env` |
| 원천 `platform_ro` | batch 조회 전용(금지 표·컬럼 SELECT 없음) | 원천 서버 `.env` → 화면에서 등록해 플랫폼 DB에 암호화 저장 |
| 원천 `platform_ai` | AI 분석 조회 전용(금지·민감 컬럼 SELECT 없음, 30초 제한) | 같음 |
| 원천 `platform_cdc` | Debezium 복제(publication 컬럼 목록으로 제한) | 원천 서버 `.env`, Jenkins `axcore-erp-cdc-password`·`axcore-mes-cdc-password` |
| Grafana 관리자 | 모니터링 | `axcore-grafana-admin-password` |
| Basic Auth `swagger` | Swagger·Kafka UI | 서버 `~/axcore/nginx/htpasswd` |
| SonarQube | 코드 분석(프로젝트 `axcore-backend`, `axcore-frontend`, `axcore-ai`) | `axcore-sonarqube-*` |
| SSH | 메인 `K15C106T.pem`(ubuntu), 원천 `C106_Source.pem`(ec2-user) | 팀 보관(저장소에 없음) |

역할은 `ADMIN`, `PLANNER`, `VIEWER`, `MAINTENANCE`(미구성) 4개이며 Flyway 초기 데이터로 들어갑니다. 민감 값은 `PLANNER`만 봅니다.

### Jenkins Credentials ID

`axcore-postgres-password`, `axcore-initial-admin-password`, `axcore-grafana-admin-password`, `axcore-internal-api-token`, `axcore-gms-api-key`, `source-credential-key-1`, `axcore-action-notify-webhook-url`, `axcore-erp-cdc-password`, `axcore-mes-cdc-password`, `mm-webhook`, `gitlab-clone`, `axcore-sonarqube-db-password`, `axcore-sonarqube-token`, `axcore-sonarqube-backend-token`, `axcore-sonarqube-ai-token`

### 설정 파일 목록

| 파일 | 내용 |
| --- | --- |
| `infra/.env.example` | 메인 서버 Compose 변수 |
| `infra/compose.yaml`, `infra/compose.blue-green.yaml` | 메인 서버 서비스 정의 |
| `infra/cdc/compose.yaml`, `infra/cdc/.env.example`, `infra/cdc/connectors/*.json` | Kafka·Debezium·커넥터 |
| `infra/source-db/compose.yaml`, `pg_hba.conf`, `sql/*.sql`, `setup.sh` | 원천 DB·계정·허용 목록·publication |
| `infra/nginx/conf.d/*.conf` | 프록시 경로·HTTPS |
| `infra/deploy/prepare_credentials.py`, `configuration.py`, `sync-config.sh` | 배포 때 `.env` 생성과 설정 반영 |
| `Jenkinsfile`, `infra/jenkins/` | CI/CD |
| `backend/src/main/resources/application.yml`, `application-api.yml`, `application-batch.yml`, `application-local.yml` | 백엔드 설정(원천 데이터 정책 포함) |
| `backend/src/main/resources/db/migration/V1~V26` | 플랫폼 DB 스키마·초기 데이터 |
| `backend/.env.example`, `ai/.env.example`, `frontend/.env.example` | 로컬 개발 변수 |
| `ai/app/config.py` | AI 서버 설정 |
