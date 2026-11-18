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
| CDC | `infra/cdc/` | Kafka, Kafka Connect(Debezium), Kafka UI | `kafka`, `kafka-connect`, `kafka-console`(Compose 서비스명 `console`) |
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

### JVM·웹서버·WAS와 설정 값

| 구분 | 제품·버전 | 주요 설정 값 | 설정 위치 |
| --- | --- | --- | --- |
| JVM | Eclipse Temurin 21 (빌드 `eclipse-temurin:21-jdk`, 실행 `eclipse-temurin:21-jre`) | backend `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`, batch `-Xmx768m`, 시간대 `TZ=Asia/Seoul`, 실행 사용자 uid 10001 | `infra/compose.yaml`, `backend/Dockerfile` |
| WAS | Spring Boot 4.1.2 내장 Apache Tomcat 11.0.27 | 포트 8080(호스트 127.0.0.1:18080), 세션 만료 30분, `server.forward-headers-strategy=framework`(nginx의 X-Forwarded-* 반영), 프로필 `api`(웹)·`batch`(웹 없음) | `backend/src/main/resources/application-api.yml` |
| 웹서버(프록시) | nginx 1.30 | HTTP→HTTPS 301, TLS 1.2·1.3, `/api/` 읽기 제한 300초·업로드 20MB·버퍼링 끔, 로그인 요청 분당 30회(burst 20), `/api/v1/internal*` 403, Swagger·Kafka UI Basic Auth | `infra/nginx/conf.d/default.conf` |
| 웹서버(정적 파일) | nginx 1.30-alpine | 포트 80(호스트 127.0.0.1:10080), SPA fallback(`try_files ... /index.html`) | `frontend/nginx.conf` |
| ASGI 서버(AI) | uvicorn 0.54.0 + FastAPI 0.143.0 | `uvicorn main:app --host 0.0.0.0 --port 8000`, 헬스체크 `/health` | `ai/Dockerfile` |
| DB | PostgreSQL 18.6 | DB `axcore`, 스키마 `platform`, 호스트 127.0.0.1:15432 | `infra/compose.yaml` |

### IDE

팀 공통으로 정한 IDE는 없습니다. 이 문서를 작성·검증한 환경은 다음과 같습니다. 포맷은 IDE가 아니라 Spotless·Prettier·Ruff 결과를 기준으로 합니다.

| IDE | 버전 | 용도 |
| --- | --- | --- |
| IntelliJ IDEA | 2026.1.4 | 백엔드(Java 21, Gradle) |
| Visual Studio Code | 1.139.1 | 프론트엔드(TypeScript), AI(Python), 문서 |

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

## 1.3 로컬 빌드와 실행

각 절은 저장소 루트에서 시작합니다. 아래 `REPO_ROOT`는 클론한 저장소의 절대 경로입니다. 로컬 Linux·macOS는 Bash, Windows는 해당 명령의 PowerShell 표기를 사용합니다.

```bash
git clone --branch develop https://lab.ssafy.com/s15-final/S15P31C106.git
cd S15P31C106
REPO_ROOT=$(pwd -P)
```

### 백엔드 (`backend/`, JDK 21과 Docker 필요)

```bash
cd "$REPO_ROOT/backend"
./gradlew build                 # 포맷 검사 + 시험 + 빌드 (Testcontainers로 PostgreSQL·Kafka를 띄움)
./gradlew spotlessApply         # 포맷 맞추기
./gradlew bootTestRun --args='--spring.profiles.active=api'   # 로컬 DB 없이 실행(Testcontainers)
```

로컬 PostgreSQL에 붙여 실행할 때는 프로필 `local,api`(API) 또는 `local,batch`(배치)를 쓰고 `DB_PASSWORD`를 넣습니다. 기본 접속은 `jdbc:postgresql://localhost:5432/axcore`, 사용자 `axcore`입니다(`application-local.yml`). 빈 DB에서 처음 띄우면 Flyway가 스키마 `platform`에 V1~V29를 적용합니다.

이미지 빌드는 저장소 루트에서 `SHA=$(git rev-parse --short HEAD)` 후 `docker build -t "axcore-backend:$SHA" backend/`로 실행합니다. 같은 이미지를 `backend`(api)와 `batch`(batch)가 프로필만 바꿔 씁니다. Windows는 클론한 폴더에서 `Set-Location backend` 후 `./gradlew.bat build`를 사용합니다.

### AI 분석 서버 (`ai/`, Python 3.11)

```bash
cd "$REPO_ROOT/ai"
python -m venv .venv
.venv/bin/python -m pip install -r requirements-dev.txt
.venv/bin/python -m pytest
.venv/bin/python -m ruff check .
.venv/bin/python -m uvicorn main:app --port 8000          # http://127.0.0.1:8000/health
```

Windows PowerShell은 클론한 폴더에서 `Set-Location ai` 후 `.venv/bin/python` 대신 `./.venv/Scripts/python.exe`를 사용합니다. 이미지는 저장소 루트에서 `docker build -t "axcore-ai:$SHA" ai/`로 만듭니다.

### 프론트엔드 (`frontend/`, Node 24)

```bash
cd "$REPO_ROOT/frontend"
npm ci
npm run dev          # MSW 모의 API로 실행, http://localhost:5173
npm run dev:api      # 실제 백엔드에 붙여 실행 (API_PROXY_TARGET, 기본 http://localhost:8080)
npm run lint && npm test -- --run && npm run format:check && npm run build
```

`npm test`만 실행하면 감시 모드라 종료를 기다립니다. 위 일괄 검증은 `--run`으로 한 번 실행하고 끝냅니다. Windows에서는 클론한 폴더에서 `Set-Location frontend` 후 각 `npm` 명령을 실행합니다. 운영 빌드는 환경 변수가 없고 API를 상대 경로 `/api/v1`로 부릅니다. 이미지는 저장소 루트에서 `docker build -t "axcore-frontend:$SHA" frontend/`로 만듭니다.

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
| `AXCORE_AGENT_OBJECT_PATHS_ENABLED` | 유형 사이 경로 후보 도구 켜기 | Compose 기본 false |
| `AXCORE_AGENT_OBJECT_SET_FIND_ENABLED` | 목표 유형 찾기 도구 켜기 | Compose 기본 false, 객체 집합 도구가 켜져 있어야 사용 |
| `AXCORE_AGENT_OBJECT_SETS_ENABLED` | 객체 집합 도구 켜기 | Compose 기본 true, 앱 직접 실행 기본 false |
| `AXCORE_CDC_CONSUMER_ENABLED`, `AXCORE_CHECK_SYNC_ENABLED` | batch의 CDC 소비·수집 후 검사 켜기 | compose 기본 false, 운영 true |
| `AI_ONTOLOGY_LLM_PROVIDER` | AI 분석 LLM 제공사(`gms`/`openai`/`openai-chat`/`none`/`mock`) | 기본 `gms` |
| `AI_ONTOLOGY_LLM_API_KEY` | AI 분석 LLM 키(GMS) | 🔒 |
| `AI_ONTOLOGY_LLM_BASE_URL`, `AI_ONTOLOGY_LLM_MODEL`, `AI_ONTOLOGY_LLM_TIMEOUT_SEC` | LLM 주소·모델·시간 제한(기본 60초) | |
| `GRAFANA_ADMIN_USER` | Grafana 관리자 | |
| `GRAFANA_ADMIN_PASSWORD` | Grafana 관리자 비밀번호 | 🔒, 비면 compose 해석 실패 |

Compose 안에 고정된 값: `SPRING_PROFILES_ACTIVE`(api/batch), `DB_URL=jdbc:postgresql://postgres:5432/${POSTGRES_DB}`, `AXCORE_AI_BASE_URL=http://ai:8000`, `AI_ONTOLOGY_BACKEND_URL=http://backend:8080`, `TZ=Asia/Seoul`, `JAVA_TOOL_OPTIONS`(backend `-XX:MaxRAMPercentage=75`, batch `-Xmx768m`)입니다. backend의 `AXCORE_AGENT_LLM_API_KEY`는 `AI_ONTOLOGY_LLM_API_KEY`에서 전달되고 `AXCORE_AGENT_LLM_BASE_URL`은 GMS 주소로 고정됩니다. 메인 `.env`에 같은 이름을 새로 적어도 이 고정 전달 관계가 바뀌지는 않습니다.

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
| `AXCORE_AGENT_HISTORY_MAX_TOKENS` | 2000 | 최근 대화 원문 문맥의 토큰 상한(문자 수로 추정) |
| `AXCORE_AGENT_HISTORY_SUMMARY_MAX_TOKENS` | 500 | 오래된 대화 요약 상한. 0이면 요약하지 않음 |
| `AXCORE_AGENT_LLM_REASONING_EFFORT` | `none` | Agent 요청의 추론 강도 |
| `AXCORE_SOURCE_LOAD_ZONE_ID` | `Asia/Seoul` | 원천 값의 날짜·시간 해석 기준 |
| `AXCORE_CDC_BOOTSTRAP_SERVERS` | `kafka:9092` | batch의 Kafka 주소 |

이 표는 앱이 직접 읽는 환경 변수입니다. 현재 Compose가 전달하지 않는 `AXCORE_AGENT_LLM_MODEL`, `AXCORE_AGENT_HISTORY_*`, `AXCORE_AGENT_LLM_REASONING_EFFORT`, `AXCORE_SOURCE_LOAD_ZONE_ID`, `AXCORE_CDC_BOOTSTRAP_SERVERS`는 서버 `.env`에만 적어서는 컨테이너에 적용되지 않습니다. 직접 앱 실행에서는 프로세스 환경으로 넣고, 운영 컨테이너의 전달 설정을 바꾸려면 Compose 변경·검토·배포 절차를 따릅니다.

원천 데이터 정책(금지·민감 컬럼 목록)은 `application.yml`의 `axcore.source-data-policy`에 있습니다. 비어 있으면 기동하지 않습니다(닫힌 기본값). 바꿀 때는 원천 허용 목록(`infra/source-db/sql/allowlist.sql`)도 같이 바꿉니다. CI가 두 목록이 같은지 검사합니다.

### AI 서버 (`ai/.env.example`)

`AI_ONTOLOGY_LLM_*`(위 표), `AI_ONTOLOGY_BACKEND_URL`(기본 `http://localhost:8080`), `AI_ONTOLOGY_INTERNAL_TOKEN`🔒, `AI_ONTOLOGY_SKIP_ENV_FILE=1`(Docker 이미지에서 `.env` 읽기 끔).

`AI_ONTOLOGY_LLM_CREDIT_LIMIT`은 분석 한 번의 예상 LLM 크레딧 상한이며 기본 10000, 0이면 한도 없음입니다. 초과가 예상되면 LLM 없이 코드 분석만 합니다. 현재 AI Compose에는 이 변수를 전달하는 항목이 없으므로 로컬 AI 프로세스의 환경 변수 또는 로컬 `.env` 설정이며, 서버 `.env`에만 추가해도 운영 AI 컨테이너의 기본값은 바뀌지 않습니다.

### 프론트엔드 (`frontend/.env.example`)

`VITE_API_MODE`(`mock`/`real`), `API_PROXY_TARGET`(개발 서버 프록시 대상). 운영 빌드에는 쓰지 않습니다.

### SonarQube (`~/axcore/sonarqube/.env`)

| 변수 | 뜻·형식 | 등록 위치 |
| --- | --- | --- |
| `SONAR_DB_PASSWORD` | 🔒 SonarQube DB `sonar`의 비밀번호. 배포 동기화 스크립트는 소문자 16진수 64자리 값과 기존 파일의 일치를 요구 | SonarQube 전용 `.env`(600)와 Jenkins Secret text `axcore-sonarqube-db-password`에 같은 값 |

메인·CDC `.env`와 별도 파일입니다. `infra/sonarqube/compose.yaml`에서 필수로 요구하며, 실제 값은 문서·명령 출력에 넣지 않습니다. 기존 서버에서는 새 값을 생성해 덮어쓰지 않습니다.

## 1.5 서버 배포

### 처음 세팅 (메인 서버)

아래는 **아직 운영 데이터·컨테이너가 없는 새 Ubuntu 서버**용 순서입니다. 기존 서버의 `.env`·인증서·Jenkins 홈을 덮어쓰는 갱신 절차가 아닙니다. 저장소의 도메인을 유지하는 경우 세 호스트(`k15c106.p.ssafy.io`, `ontologenie.com`, `sonar.ontologenie.com`)가 이 서버를 가리키고 80/443으로 접근할 수 있어야 합니다. 다른 도메인으로 옮기면 DNS뿐 아니라 nginx·Grafana·화면 기본 주소도 해당 환경에 맞게 별도 검토합니다.

1. Docker 29.x와 Compose v2.24.4 이상, Git, OpenSSL을 준비합니다. 1.3에서 클론한 저장소를 서버에도 두고 `REPO_ROOT`를 서버의 저장소 루트로 지정합니다. 배포 폴더는 `/home/ubuntu/axcore`로 고정합니다.

   ```bash
   cd "$REPO_ROOT"
   SHA=$(git rev-parse --short HEAD)
   install -d -m 755 /home/ubuntu/axcore
   cp -a infra/. /home/ubuntu/axcore/
   cd /home/ubuntu/axcore
   install -d -m 700 backup logs
   install -d -m 755 certbot/www certbot/conf frontend-assets jenkins-data
   sudo chown 1000:1000 jenkins-data
   stat -c '%g' /var/run/docker.sock
   ```

   출력된 docker 소켓 그룹 번호를 배포 사본 `compose.yaml`의 Jenkins `group_add`에 맞춥니다(현재 기준 988). Jenkins 실행 사용자 uid 1000이 배포 폴더를 쓸 수 있고 Docker 소켓에 접근할 수 있어야 합니다.

2. `.env.example`을 `.env`로 복사하고 1.4의 필수 값을 입력한 뒤 권한을 600으로 둡니다. `BACKEND_TAG`, `FRONTEND_TAG`, `AI_TAG`에는 위 `SHA`와 같은 태그를 넣습니다. DB 비밀번호는 `openssl rand -base64 24`, 새 원천 암호화 키는 `openssl rand -base64 32` 같은 방식으로 생성해 비밀값 저장소에 보관합니다. 내부 토큰은 backend·AI에 같은 값을 넣습니다. 기존 환경을 옮길 때는 기존 암호화 키·DB 비밀번호를 재사용합니다.

   ```bash
   cp .env.example .env
   chmod 600 .env
   ```

   서버 `.env`는 셸 코드로 `source`하지 않습니다. 아래 이미지 빌드는 저장소 루트에서 실행하고, 같은 태그 이미지가 이미 있으면 다시 굽지 않습니다.

   ```bash
   cd "$REPO_ROOT"
   for component in backend ai frontend; do
     if ! docker image inspect "axcore-$component:$SHA" >/dev/null 2>&1; then
       docker build -t "axcore-$component:$SHA" "$component/"
     fi
   done
   cd /home/ubuntu/axcore
   docker compose --env-file .env config --quiet
   docker compose --env-file .env build jenkins
   ```

3. 최초 인증서 발급에는 먼저 HTTP 인증 경로를 제공해야 합니다. 아직 정식 nginx는 인증서가 없어서 기동할 수 없으므로, 80 포트가 비어 있는 새 서버에서 **아래 임시 컨테이너만** 띄웁니다. 기본 nginx 문서 루트에 certbot webroot를 연결합니다.

   ```bash
   cd /home/ubuntu/axcore
   install -d -m 755 certbot/www/.well-known/acme-challenge
   printf 'axcore-acme-probe\n' > certbot/www/.well-known/acme-challenge/porting-probe
   docker run -d --rm --name axcore-acme-bootstrap \
     -p 80:80 \
     --mount "type=bind,src=$PWD/certbot/www,dst=/usr/share/nginx/html,readonly" \
     nginx:1.30
   for domain in k15c106.p.ssafy.io ontologenie.com sonar.ontologenie.com; do
     curl --fail --show-error --max-time 10 "http://$domain/.well-known/acme-challenge/porting-probe"
   done
   ```

   외부 클라이언트에서도 같은 인증 URL에 접근되는지 확인합니다. 실패하면 DNS와 80 포트를 확인하고 인증서 발급으로 진행하지 않습니다.

4. 메인 서비스용과 SonarQube용 인증서를 각각 발급합니다. 최초 이메일 주소·이용 약관 확인은 운영 담당자가 certbot의 안내에 따라 진행합니다.

   ```bash
   cd /home/ubuntu/axcore
   docker compose --env-file .env run --rm --no-deps certbot certonly --webroot -w /var/www/certbot \
     --cert-name k15c106.p.ssafy.io -d k15c106.p.ssafy.io -d ontologenie.com
   docker compose --env-file .env run --rm --no-deps certbot certonly --webroot -w /var/www/certbot \
     --cert-name sonar.ontologenie.com -d sonar.ontologenie.com
   sudo test -s certbot/conf/live/k15c106.p.ssafy.io/fullchain.pem
   sudo test -s certbot/conf/live/sonar.ontologenie.com/fullchain.pem
   docker stop axcore-acme-bootstrap
   ```

5. **첨부 플랫폼 덤프를 사용할 경우 먼저 [덤프 복원 순서](3-db-dump/README.md#복구)의 PostgreSQL 단독 기동·빈 DB 복원을 완료합니다.** 앱을 먼저 켜 Flyway가 표를 만들었다면 같은 덤프를 중복 적용하지 않습니다. 덤프 없이 시작할 경우에는 아래 전체 기동 때 Flyway가 빈 DB를 구성합니다.

   Swagger·Kafka UI용 Basic Auth 파일을 만들고 입력한 비밀번호를 안전하게 보관합니다. 다음으로 메인 Compose를 기동합니다. `axcore-net`은 메인 Compose가 만들므로 CDC·SonarQube보다 먼저입니다.

   ```bash
   cd /home/ubuntu/axcore
   printf 'swagger:%s\n' "$(openssl passwd -apr1)" > nginx/htpasswd
   chmod 644 nginx/htpasswd
   docker compose --env-file .env config --quiet
   docker compose --env-file .env up -d
   docker compose --env-file .env ps
   docker exec nginx nginx -t
   curl --fail --show-error http://127.0.0.1:18080/actuator/health
   curl --fail --show-error -o /dev/null https://ontologenie.com/
   ```

   postgres가 healthy가 된 뒤 backend·batch가 뜹니다. 기동 직후 HTTP 검사가 실패하면 `docker compose ps`·대상 컨테이너 로그를 확인하고 healthy 뒤 다시 검사합니다. backend의 Flyway가 V1~V29를 적용하고 ADMIN이 없으면 `AXCORE_INITIAL_ADMIN_*`로 최초 관리자를 만듭니다(첫 로그인 때 임시 비밀번호 변경). 덤프를 사용하려면 [덤프 복원 순서](3-db-dump/README.md#복구)를 먼저 읽고, 빈 DB에 복원한 뒤 앱을 기동합니다.

6. 아래 원천 ERP·MES 서버 설치를 마친 뒤, 원천별 CDC 비밀번호와 같은 값을 `cdc/.env`의 `ERP_CDC_PASSWORD`, `MES_CDC_PASSWORD`에 넣고 권한을 600으로 둡니다. 아직 커넥터·소비자가 준비되지 않았으므로 매핑을 승인하지 않습니다.

   ```bash
   cd /home/ubuntu/axcore/cdc
   cp .env.example .env
   chmod 600 .env
   # 운영 담당자가 두 비밀번호를 입력한 뒤 실행
   docker compose --env-file .env config --quiet
   docker compose --env-file .env up -d
   docker compose --env-file .env ps
   ```

7. 아래 `Jenkins Job과 Webhook 등록`, `SonarQube 최초 설치`를 완료합니다. 첫 Jenkins 배포 전에 모든 Credentials와 메인·CDC 환경 파일이 있어야 합니다. 원천 비밀번호·키와 Credentials는 같은 값을 유지합니다. 최초 배포가 일반 설정을 `.env.config`로 분리한 뒤에는 일반 설정을 그 파일에서 관리하고 실행용 `.env`를 직접 덮어쓰지 않습니다([배포 안내](../infra/deploy/README.md)).

8. `scripts/pg-backup.sh`를 수동 1회 실행해 성공과 백업 파일을 확인한 뒤, 운영 담당자가 crontab에 다음 두 줄을 등록합니다. 이미 같은 작업이 있으면 중복 등록하지 않습니다.

   ```text
   0 3 * * * /home/ubuntu/axcore/scripts/renew-cert.sh >> /home/ubuntu/axcore/logs/renew-cert.log 2>&1
   0 4 * * * /home/ubuntu/axcore/scripts/pg-backup.sh >> /home/ubuntu/axcore/logs/pg-backup.log 2>&1
   ```

### 원천 ERP·MES 서버

저장소 루트에서 실행합니다. MES는 `DB=mes`와 MES 서버 주소로 바꿉니다.

```bash
K=~/.ssh/C106_Source.pem
H=ec2-user@erp.example.internal   # 실제 ERP 원천 서버 주소로 바꿈
DB=erp
tar -C infra/source-db -cf - compose.yaml pg_hba.conf sql setup.sh server-init.sh | ssh -i $K $H 'mkdir -p ~/source-db && tar -C ~/source-db -xf -'
tar -C ai/data/data-v1.0 -cf - load_$DB.psql verify_counts.sql $DB | ssh -i $K $H 'mkdir -p ~/source-db/data && tar -C ~/source-db/data -xf -'
ssh -i $K $H '~/source-db/server-init.sh'   # 시간대·스왑·Docker
ssh -i $K $H "~/source-db/setup.sh $DB"     # .env·인증서 생성, 기동, 합성 데이터 적재·행 수 확인, 계정·허용 목록·publication
```

`setup.sh`는 다시 실행해도 됩니다. 원천은 `wal_level=logical`, 포트 15432이고, `pg_hba.conf`로 메인 서버 IP의 SSL scram 접속만 받습니다. 원천 서버 보안 그룹도 메인 서버 IP만 엽니다.

### CDC 연결 순서 (`infra/cdc/README.md`)

1. 메인 서버 `~/axcore/cdc`에서 Kafka·Connect를 띄웁니다(위 최초 설치 6번).
2. 커넥터를 등록합니다.
   ```bash
   tar -C infra/cdc -cf - register-connectors.sh connectors | ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io 'tar -C ~/axcore/cdc -xf -'
   ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io '~/axcore/cdc/register-connectors.sh'
   ```
   두 커넥터가 RUNNING이고 원천 슬롯이 `active`인지 확인합니다.
3. 서버 `~/axcore/.env.config`에 `AXCORE_CDC_CONSUMER_ENABLED=true`, `AXCORE_CHECK_SYNC_ENABLED=true`를 넣고 Jenkins의 Credentials 준비·설정 반영 절차로 실행용 `.env`를 갱신한 뒤 batch를 다시 만듭니다([운영 연결 순서](../infra/cdc/README.md#운영-연결-순서-s15p31c106-315)). 최초 Jenkins 전환 전의 수동 설치에서는 같은 두 값을 실행용 `.env`에 입력하고 `docker compose --env-file .env up -d --no-deps batch`로 반영합니다. `.env.config` 편집만으로 컨테이너 환경은 바뀌지 않습니다. batch 로그에 `CDC 소비자 시작 topic=ERP_MAIN.all`이 나오고 원천별 `cdc_offset`이 생겼는지 확인합니다.
4. 화면에서 원천 등록(코드 `ERP_MAIN`, `MES_MAIN`)과 조회 계정 비밀번호를 넣고, 분석·제안 승인으로 매핑을 만들면 수집이 시작됩니다.

### Jenkins Job과 Webhook 등록

Jenkins는 기동만으로 Job이나 Credentials가 생성되지 않습니다. 다음은 현재 `Jenkinsfile`이 요구하는 구성입니다. 서버에서 읽은 비밀값을 문서·로그에 옮기지 않고 Jenkins 입력 화면에서 등록합니다.

1. `https://k15c106.p.ssafy.io/jenkins/`에서 운영 담당자가 최초 잠금 해제·관리자 생성을 완료합니다. Git, Pipeline, Credentials Binding, GitLab, JUnit 플러그인이 있어야 합니다. Jenkins의 Git 도구 이름 `Default`와 GitLab Connection 이름 `ssafy-gitlab`을 설정합니다. 저장소 읽기용 자격 증명과 커밋 상태 기록용 GitLab API 자격 증명은 용도를 구분합니다.
2. Credentials에 `gitlab-clone`은 Git 플러그인이 사용하는 **Username with password** 형식으로 등록합니다(토큰은 password 칸). 아래 1.8의 나머지 운영 키·Webhook·Sonar 토큰은 해당 ID의 **Secret text**로 등록합니다. `mm-webhook`은 모든 Job이 시작할 때 필요합니다. 배포 비밀값은 서버의 기존 값과 같아야 하며, 내부 토큰은 하나의 `axcore-internal-api-token`에서 backend·AI에 함께 주입합니다. 정확한 매핑은 [배포 Credentials 표](../infra/deploy/README.md#비밀값-원본과-실행-파일)를 따릅니다.
3. 아래 이름 그대로 Pipeline Job 3개를 만듭니다. 공통으로 `Pipeline script from SCM` → Git → 저장소 `https://lab.ssafy.com/s15-final/S15P31C106.git`, 자격 증명 `gitlab-clone`, 브랜치 `*/develop`, Script Path `Jenkinsfile`을 지정합니다. MR의 검사 대상은 Checkout 단계에서 이벤트의 커밋 SHA로 바뀝니다. `gitlabSourceBranch`·`gitlabTargetBranch`를 별도 기본 파라미터로 만들지 않습니다.

   | Job | 트리거 | 역할 |
   | --- | --- | --- |
   | `axcore-mr-check` | MR 열림·갱신·원본 브랜치의 새 커밋 | MR 커밋 검사. 운영 배포 안 함 |
   | `axcore-deploy` | `develop` push만 | 항상 최신 `origin/develop`을 배포 |
   | `axcore-sonarqube` | Webhook·SCM polling 없음 | 배포 Job이 전달한 SHA를 별도 분석 |

4. 앞의 두 Job에서 GitLab 트리거를 설정하고 **Job 화면에 표시된 Webhook URL·Secret Token**을 GitLab 프로젝트의 Settings → Webhooks에 등록합니다. MR Job에는 MR 이벤트, 배포 Job에는 Push 이벤트와 `develop` 필터를 적용합니다. 이벤트 시험 결과가 성공이고 올바른 Job이 실행되는지 확인합니다. URL에 토큰을 직접 붙이거나 검증 없이 두 Job 모두에 모든 push를 전달하지 않습니다.
5. `axcore-sonarqube`는 첫 호출 전 `ANALYSIS_COMMIT`·`ANALYSIS_RECIPIENT` 문자열, `ANALYZE_BACKEND`·`ANALYZE_FRONTEND`·`ANALYZE_AI`·`RUN_SONARQUBE`·`FORCE_DEPLOY` Boolean 파라미터를 `Jenkinsfile`과 같은 기본값으로 등록합니다. 기존 Job과 같은 Docker CLI·Java·노드·Jenkins 홈을 사용합니다. [분석 Job 등록 절차](../infra/sonarqube/README.md#분석-job-등록-및-전환)를 함께 확인합니다.
6. 현재 기준 executor는 2개입니다. 저장소 체크아웃·Docker 접근·Credentials 검증이 되는지 확인한 뒤 배포 Job을 실행하고, Checkout SHA·GitLab 상태·각 서비스 health를 확인합니다. SonarQube 준비 전에는 전역 `ENABLE_SONARQUBE`를 켜지 않습니다. 운영 환경의 `SONARQUBE_DASHBOARD_URL`은 `https://sonar.ontologenie.com`입니다.

### SonarQube 최초 설치

이 절은 별도 SonarQube가 아직 없는 새 서버용입니다. 기존 `~/sonarqube` 설치·데이터 볼륨을 편입할 때는 프로젝트 이름과 볼륨을 유지하는 [기존 설치 편입 절차](../infra/sonarqube/README.md#기존-수동-설치-편입)를 따릅니다.

1. 메인 Compose가 만든 `axcore-net`, Sonar 도메인의 인증서와 nginx 경로를 먼저 준비합니다. [SonarQube Linux 호스트 요건](https://docs.sonarsource.com/sonarqube-community-build/server-installation/pre-installation/linux)에 따라 `vm.max_map_count >= 524288`, `fs.file-max >= 131072`를 확인합니다. 부족하면 운영 담당자가 호스트 설정과 재부팅 후 지속 여부를 확인한 뒤 기동합니다. Compose에는 Sonar 프로세스의 `nofile=131072`, `nproc=8192`, CPU 2·메모리 4GiB가 지정돼 있습니다.
2. 배포 폴더의 `sonarqube/compose.yaml`과 아래 전용 `.env`를 준비합니다. 다음 생성 코드는 새 파일일 때만 비밀번호를 만듭니다. 만들어진 값은 출력하지 않고 운영 담당자가 같은 값을 `axcore-sonarqube-db-password`에 등록합니다.

   ```bash
   cd /home/ubuntu/axcore/sonarqube
   if [ ! -e .env ]; then
     (umask 077; printf 'SONAR_DB_PASSWORD=%s\n' "$(openssl rand -hex 32)" > .env)
   fi
   chmod 600 .env
   sysctl vm.max_map_count fs.file-max
   docker compose --project-name sonarqube --env-file .env config --quiet
   docker compose --project-name sonarqube --env-file .env up -d db
   docker compose --project-name sonarqube --env-file .env up -d sonarqube
   curl --fail --show-error http://127.0.0.1:9000/api/system/status
   ```

   응답이 `UP`이 될 때까지 상태·로그를 확인합니다. `db` healthy 조건을 만족해야 본체가 기동합니다. `sonar.ontologenie.com` 인증서는 위 메인 서버 최초 설치의 4번에서 별도로 발급합니다.
3. `https://sonar.ontologenie.com`에서 초기 관리자 비밀번호를 변경하고 로그인 필수·프로젝트 Private 설정을 확인합니다. 기본 브랜치 `develop`의 프로젝트 `axcore-backend`, `axcore-frontend`, `axcore-ai`를 만들고 각 Project Analysis Token을 발급합니다. Jenkins ID는 각각 `axcore-sonarqube-backend-token`, `axcore-sonarqube-token`, `axcore-sonarqube-ai-token`입니다. Server base URL도 공용 HTTPS 주소로 설정합니다.
4. Jenkins의 전역 `ENABLE_SONARQUBE=true`, `SONARQUBE_DASHBOARD_URL=https://sonar.ontologenie.com`을 적용하고, `axcore-sonarqube` Job에서 분석할 전체 SHA를 지정해 검사합니다. 이후 배포 Job에서 `RUN_SONARQUBE=true`로 예약과 별도 분석 결과를 확인합니다. 세부 확인은 [SonarQube 운영 안내](../infra/sonarqube/README.md)를 따릅니다.

### 자동 배포 (Jenkins)

- **MR 검사 `axcore-mr-check`**: MR을 만들거나 갱신하면 바뀐 폴더만 검사합니다. backend `./gradlew spotlessCheck test build jacocoTestReport`, frontend `npm ci → format:check → lint → test:coverage → build`, AI `ruff check → pytest --cov`, infra 셸 문법·unittest. 결과는 GitLab MR 상태로 보이고, 실패하면 작성자에게 Mattermost DM이 갑니다.
- **배포 `axcore-deploy`**: develop에 머지하면 GitLab Webhook으로 실행됩니다.
  1. `origin/develop`을 체크아웃합니다.
  2. Jenkins Credentials와 서버 `.env.config`로 실행용 `.env`, `cdc/.env`를 만듭니다(`infra/deploy/prepare_credentials.py`, 권한 600).
  3. 허용된 설정 파일만 서버에 반영하고 검사합니다(`infra/deploy/sync-config.sh`, 실패하면 복구).
  4. 바뀐 서비스만 이미지를 빌드합니다(`axcore-<서비스>:<커밋 SHA>`). backend는 배포 전에 DB를 백업하고, 태그를 바꿔 `docker compose up -d backend`를 한 뒤 최대 90초 healthy를 기다리고 batch를 다시 만듭니다. 실패하면 직전 태그로 롤백합니다. AI, frontend 순으로 같은 방식입니다.
     frontend는 교체 전에 지금 컨테이너와 새 이미지의 `assets/`(해시가 붙은 JS·CSS)를 서버 `~/axcore/frontend-assets`에 보관합니다(`infra/deploy/retain-frontend-assets.sh`). 메인 nginx가 `/assets/` 요청을 이 폴더에서 먼저 찾고 없으면 frontend로 넘기므로, 배포 전에 열어 둔 탭도 이전 화면 파일을 계속 받습니다. 보관 기간은 Jenkins 파라미터 `FRONTEND_ASSET_RETENTION_DAYS`(기본 7일)이고, 지난 버전은 다음 frontend 배포 때 정리합니다.
  5. 이미지는 최근 3개 태그만 남깁니다. 결과는 GitLab 커밋 상태와 Mattermost DM으로 알립니다.

### 수동 배포와 롤백 (자동 배포가 안 될 때)

기존 서버의 backend와 batch를 같은 태그로 갱신하는 비상 절차입니다. Jenkins 배포가 실행 중이지 않은 작업 시간에 진행합니다. 저장소에서 검토한 커밋을 선택하고 `REPO_ROOT`를 그 체크아웃으로 지정합니다. DB 마이그레이션이 있으면 이전 앱과의 호환성도 확인합니다. 앱 태그 롤백과 DB 시점 복원은 별도 작업입니다.

먼저 같은 Bash 세션에 다음 상태 확인 함수를 정의합니다. API healthy와 batch의 새 기동 로그를 최대 90초 기다린 뒤, 같은 batch 프로세스가 재시작·OOM 없이 60초 유지되는지 검사합니다.

```bash
check_backend_batch() {
  local batch_id started state ready=false
  batch_id=$(docker inspect --format '{{.Id}}' batch) || return 1
  started=$(docker inspect --format '{{.State.StartedAt}}' batch) || return 1
  for attempt in $(seq 1 18); do
    state=$(docker inspect --format '{{.Id}} {{.State.StartedAt}} {{.State.Running}} {{.RestartCount}} {{.State.OOMKilled}}' batch) || return 1
    [ "$state" = "$batch_id $started true 0 false" ] || return 1
    if [ "$(docker inspect --format '{{.State.Health.Status}}' backend)" = healthy ] &&
        docker logs --since "$started" batch 2>&1 | grep 'Started PlatformApplication' >/dev/null; then
      ready=true
      break
    fi
    sleep 5
  done
  [ "$ready" = true ] || return 1
  for attempt in $(seq 1 12); do
    sleep 5
    state=$(docker inspect --format '{{.Id}} {{.State.StartedAt}} {{.State.Running}} {{.RestartCount}} {{.State.OOMKilled}}' batch) || return 1
    [ "$state" = "$batch_id $started true 0 false" ] || return 1
    [ "$(docker inspect --format '{{.State.Health.Status}}' backend)" = healthy ] || return 1
  done
}
```

다음 블록은 기존 두 컨테이너의 태그와 이전 이미지가 일치할 때만 진행합니다. `.env` 전체를 출력하거나 `source`하지 않고 이미지 태그 줄만 읽고 바꿉니다.

```bash
set -eu
cd /home/ubuntu/axcore
SHA=$(git -C "$REPO_ROOT" rev-parse --short HEAD)
PREV_BACKEND_TAG=$(sed -n 's/^BACKEND_TAG=//p' .env)
[[ "$PREV_BACKEND_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]
docker image inspect "axcore-backend:$PREV_BACKEND_TAG" >/dev/null
for service in backend batch; do
  test "$(docker inspect --format '{{.Config.Image}}' "$service")" = "axcore-backend:$PREV_BACKEND_TAG"
done
if ! docker image inspect "axcore-backend:$SHA" >/dev/null 2>&1; then
  docker build -t "axcore-backend:$SHA" "$REPO_ROOT/backend"
fi
./scripts/pg-backup.sh
sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=$SHA/" .env
if docker compose --env-file .env up -d --no-deps --pull never backend batch && check_backend_batch; then
  curl --fail --show-error http://127.0.0.1:18080/actuator/health
else
  sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=$PREV_BACKEND_TAG/" .env
  docker compose --env-file .env up -d --no-deps --pull never backend batch
  check_backend_batch || { printf '이전 버전 복구 검사 실패. 담당자 확인 필요\n' >&2; exit 1; }
  printf '새 버전 검사 실패. backend와 batch를 이전 태그로 복구함\n' >&2
  exit 1
fi
```

이미 성공한 배포를 나중에 되돌릴 때도 **backend·batch 둘 다** 갱신합니다. 같은 세션의 `PREV_BACKEND_TAG`가 아니라 별도 기록한 직전 정상 태그를 선택합니다.

```bash
set -eu
docker images axcore-backend --format '{{.Repository}}:{{.Tag}}'
ROLLBACK_TAG=replace-with-previous-sha   # 위 목록과 배포 기록의 직전 정상 태그로 바꿈
test "$ROLLBACK_TAG" != replace-with-previous-sha
[[ "$ROLLBACK_TAG" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]*$ ]]
docker image inspect "axcore-backend:$ROLLBACK_TAG" >/dev/null
cd /home/ubuntu/axcore
./scripts/pg-backup.sh
sed -i "s/^BACKEND_TAG=.*/BACKEND_TAG=$ROLLBACK_TAG/" .env
docker compose --env-file .env up -d --no-deps --pull never backend batch
check_backend_batch
```

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
- **객체 유형은 그룹을 지정해야 보입니다.** 그룹이 없는 유형은 관리자에게만 보이고, 다른 역할과 AI 업무 도우미에는 보이지 않습니다. 분석·승인 뒤 유형 편집에서 그룹(PRODUCTION, SALES_SUPPLY)을 지정합니다.
- **Agent LLM은 기본으로 꺼져 있습니다**(`AXCORE_AGENT_LLM=none`). 배포 준비 단계가 운영에서 `openai`로 켭니다.
- **원천 접속 비밀번호**는 화면이나 API로 등록하면 `AXCORE_SOURCE_CREDENTIAL_KEY_1`로 암호화해 플랫폼 DB에 저장합니다. 키를 바꾸면 기존 암호문을 다시 등록해야 합니다.
- **AI 서버와 backend는 같은 내부 토큰**을 씁니다. 다르면 분석 결과 수신이 막힙니다.
- **Testcontainers**: Jenkins 컨테이너 안에서 시험을 돌릴 때 `TESTCONTAINERS_HOST_OVERRIDE=172.18.0.1`(axcore-net 게이트웨이)이 필요합니다.
- **PostgreSQL 18 덤프 복구**에는 psql 18 이상이 필요합니다(덤프 첫머리의 `\restrict` 줄).
- **프론트 정적 파일 보관**: `~/axcore/frontend-assets`는 nginx에 읽기 전용으로 붙고(`/srv/frontend-assets`), 폴더는 Jenkins가 배포 때 만듭니다(심볼릭 링크면 배포 중단). 보관 목록이 깨지거나 같은 이름의 파일 내용이 다르면 배포를 멈추므로, 폴더를 통째로 지우지 말고 오류와 마지막 정상 목록부터 확인합니다. 보관 기간이 지난 탭은 화면의 `새 버전을 불러와 주세요` 안내에서 새로고침합니다. 자세한 내용은 `infra/deploy/frontend-assets.md`입니다.
- **자동 반영 제외 설정**: Jenkins·PostgreSQL·Certbot 정의, 서비스 추가·삭제, 프로젝트·볼륨·네트워크, systemd 변경은 자동 복사 전에 중단됩니다. 로그 옵션 변경도 이 서비스 정의에 포함됩니다. 이러한 변경은 병합 전에 별도 수동 적용 계획과 승인이 필요합니다([허용 범위](../infra/deploy/README.md#설정-자동-반영)).
- Blue-Green 전환(`compose.blue-green.yaml`)은 준비만 되어 있고 자동 배포에서는 쓰지 않습니다.

## 1.8 주요 계정과 설정 파일

### DB 접속 정보 (비밀번호는 저장소에 없음)

| DB | 접속 주소 | DB·스키마 | 계정 | 정의된 파일 |
| --- | --- | --- | --- | --- |
| 플랫폼 DB | 컨테이너 안 `postgres:5432`, 서버 `127.0.0.1:15432` | DB `axcore`, 스키마 `platform` (표 54개, ERD와 같은 구조를 Flyway V1~V29가 만듦) | `axcore` | `infra/.env`(`POSTGRES_*`), `infra/compose.yaml`(`DB_URL`), `backend/src/main/resources/application*.yml`(`spring.datasource`, `spring.flyway.schemas`) |
| 플랫폼 DB(로컬 개발) | `localhost:5432` | `axcore` / `platform` | `axcore` | `backend/src/main/resources/application-local.yml`, `backend/.env.example` |
| 원천 ERP | ERP 원천 서버 `:15432`(SSL 필수, 메인 서버 IP만 허용) | DB `erp`, 스키마 `erp` | `platform_ro`, `platform_ai`, `platform_cdc` | 원천 서버 `~/source-db/.env`, `infra/source-db/sql/setup_roles.sql`·`allowlist.sql`, 플랫폼 화면의 데이터 연결(접속 정보는 `data_source`·`data_source_credential`에 암호화 저장) |
| 원천 MES | MES 원천 서버 `:15432` | DB `mes`, 스키마 `mes` | 같음 | 같음 |
| Kafka | 컨테이너 안 `kafka:9092` | 토픽 `ERP_MAIN.all`, `MES_MAIN.all` | - | `infra/cdc/compose.yaml`, `application-batch.yml`(`AXCORE_CDC_BOOTSTRAP_SERVERS`) |

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
| `infra/deploy/retain-frontend-assets.sh`, `infra/deploy/frontend-assets.md` | 이전 프론트 정적 파일 보관과 정리 |
| `Jenkinsfile`, `infra/jenkins/` | CI/CD |
| `backend/src/main/resources/application.yml`, `application-api.yml`, `application-batch.yml`, `application-local.yml` | 백엔드 설정(원천 데이터 정책 포함) |
| `backend/src/main/resources/db/migration/V1~V29` | 플랫폼 DB 스키마·초기 데이터 |
| `backend/.env.example`, `ai/.env.example`, `frontend/.env.example` | 로컬 개발 변수 |
| `ai/app/config.py` | AI 서버 설정 |
