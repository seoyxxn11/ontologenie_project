# infra

메인 서버(Lightsail, `k15c106.p.ssafy.io`)의 배포 설정 파일 사본입니다. 서버의 `/home/ubuntu/axcore/` 폴더와 같은 구조이며, 서버가 사라져도 같은 구성을 다시 만들 수 있도록 보관합니다.

## 구성

| 파일 | 역할 |
| --- | --- |
| `compose.yaml` | nginx · Jenkins · PostgreSQL 18 · backend · AI · frontend · Prometheus · Alloy 및 도구 프로필 certbot 정의 (이미지 버전 고정, 내부망 `axcore-net`) |
| `monitoring/` | Prometheus·Alloy 설정, Linux 서버 전용 컨테이너 지표 수집과 노트북 검증 설정. 상세는 `monitoring/README.md` |
| `.env.example` | `compose.yaml`이 읽는 환경 변수 이름 목록 (실제 값은 서버의 `.env`에만) |
| `nginx/conf.d/default.conf` | HTTP → HTTPS 리다이렉트, TLS 설정, `/jenkins/` 리버스 프록시, `/api/` → backend, `/` → frontend, Swagger Basic Auth |
| `jenkins/Dockerfile` | Jenkins 공식 이미지 + docker CLI · compose 플러그인 |
| `scripts/renew-cert.sh` | 인증서 자동 갱신 + nginx reload (cron에서 매일 03:00 실행) |
| `scripts/pg-backup.sh` | 플랫폼 DB `pg_dump` 백업 + 7일 지난 파일 삭제 (cron에서 매일 04:00 실행) |
| backend | Spring Boot 백엔드 (api 모드) · `backend/Dockerfile` · 호스트 `127.0.0.1:18080` · 이미지 태그 `.env`의 `BACKEND_TAG` |
| frontend | React 정적 파일 · `frontend/Dockerfile`(node 24.18.0로 빌드 → `nginx:1.30-alpine`이 dist 서빙, SPA fallback은 `frontend/nginx.conf`) · 호스트 `127.0.0.1:10080`(컨테이너 80) · 이미지 태그 `.env`의 `FRONTEND_TAG` · 메인 nginx `/` → frontend |
| AI | FastAPI · `ai/Dockerfile`(Python 3.11 slim, 비 root 실행) · 호스트 `127.0.0.1:18000`(컨테이너 8000) · 이미지 태그 `.env`의 `AI_TAG` · nginx 외부 경로 없음 |

## 포트 규칙

SSAFY 클라우드 방화벽은 tcp · udp 1024~65535를 열어 두므로, 서버의 ufw와 `127.0.0.1` 바인딩이 유일한 방어선입니다. Docker의 `ports:`는 ufw를 우회하므로 아래 규칙을 지킵니다.

- 바깥(`0.0.0.0`)에 여는 포트는 nginx의 80 · 443뿐입니다.
- 나머지 서비스는 호스트 포트를 열지 않거나 `127.0.0.1:1xxxx`(비기본값)로만 엽니다. 컨테이너 안 포트는 기본값을 유지합니다.
- 현재 할당: PostgreSQL `127.0.0.1:15432` (컨테이너 안 5432) · backend `18080` (8080) · frontend `10080` (80) · AI `18000` (8000). Grafana `13000`은 예정.

## nginx 경로 (바깥에서 보이는 주소)

| 경로 | 가는 곳 | 비고 |
| --- | --- | --- |
| `/` | frontend:80 (React 정적 파일) | resolver + 변수 proxy_pass(frontend 재시작 중에도 nginx 유지). SPA fallback은 frontend 컨테이너 안 nginx가 처리. 이미지 태그 `.env`의 `FRONTEND_TAG`, 호스트 `127.0.0.1:10080`(디버그용) |
| `/api/` | backend:8080 (경로 그대로) | 로그인 전 요청은 backend가 401로 응답. `X-Forwarded-Proto` 전달(세션 Secure 쿠키), `proxy_buffering off` + `proxy_read_timeout 300s`(Agent 스트리밍), 업로드 상한 20m |
| `/jenkins/` | jenkins:8080 | 팀원 Read 권한 |
| `/grafana/` | grafana:3000 | Grafana 로그인 필요. 하위 경로 유지, Live WebSocket 전달. 67 서버 반영 후 외부 검증 필요 |
| `/swagger-ui.html` · `/swagger-ui/` · `/v3/api-docs` | backend:8080 | nginx Basic Auth (아이디 · 비밀번호는 서버 `nginx/htpasswd`, 팀 DM으로 공유). 백엔드 로그인과 별개 |

바깥에 열지 않는 것: backend `/actuator/*`와 AI 서비스. backend Actuator 전용 nginx 프록시 경로는 없으며, AI도 nginx에 외부 경로를 추가하지 않는다. backend 헬스체크는 `/actuator/health`, Alloy의 앱 지표 수집은 내부 `backend:8080/actuator/prometheus`를 사용한다. backend 변경은 자동 배포되지만 Alloy 설정은 별도로 서버 반영해야 한다.

공통 보호: 모든 HTTPS 응답에 보안 헤더 3개(HSTS 1주 · `X-Content-Type-Options nosniff` · `X-Frame-Options SAMEORIGIN`). 로그인 `/api/v1/auth/login`만 IP별 요청 제한(20회 즉시 통과, 이후 분당 30회, 초과 시 429). 교육장은 팀이 공인 IP 하나를 같이 쓰므로 느슨하게 둔다.

Swagger 열쇠 파일 만들기 (서버): `echo "swagger:$(openssl passwd -apr1)" > ~/axcore/nginx/htpasswd && chmod 644 ~/axcore/nginx/htpasswd` (비밀번호 두 번 입력. 파일에는 해시만 저장되고 저장소에는 올리지 않음. nginx 일꾼 사용자가 읽어야 하므로 644)

## 저장소에 올리지 않는 것

`.gitignore`로 제외합니다. 서버에만 존재합니다.

- `certbot/` — Let's Encrypt 인증서와 개인키
- `jenkins-data/` — Jenkins 설정, 계정, 자격 증명
- `logs/` — 갱신 · 백업 로그
- `backup/` — DB 백업 파일 (7일 보관, 서버에만)
- `.env`, `*.pem` — 비밀값과 접속 키

## backend–AI 내부 통신 설정 (274)

backend에 `AXCORE_INTERNAL_API_TOKEN`을 서버 `.env`에서 전달하고 `AXCORE_AI_BASE_URL=http://ai:8000`을 지정한다. AI의 `AI_ONTOLOGY_INTERNAL_TOKEN`과 backend 토큰은 서버에서 같은 값으로 관리한다. 토큰 값은 저장소·MR·Jira·로그에 기록하지 않는다. 토큰이 비어 있을 때 요청을 거부하는 인증 구현은 270번 범위이며 이 설정만으로 인증 코드가 구현되는 것은 아니다. batch에는 두 변수를 추가하지 않는다.

nginx는 정확한 `/api/v1/internal` 및 `/api/v1/internal/` 하위 경로를 403으로 차단한다. Docker 내부망에서는 AI가 `http://backend:8080`으로 직접 호출하므로 nginx를 경유하지 않는다. 기존 일반 `/api/` 프록시는 유지한다. 명세의 토큰 관리 방식 설명은 176번에서 서버 `.env` 기준으로 맞춘다.

2026-11-02 사용자 실행 로컬 검증: Compose config 종료 코드 0, 인증서 설정을 제외한 nginx 1.30 문법 검사 통과. 임시 nginx에서 정확한 internal 경로, 모델 요약 GET, 분석 결과 POST 모두 403을 확인했다. 일반 `/api/v1/auth/me`와 유사 접두사 `/api/v1/internal-test`는 backend 부재로 502였다. 이는 차단 규칙과의 경로 구분 확인이며 실제 backend의 정상 응답 검증은 아니다. 임시 nginx는 정리했다. 실제 토큰은 생성하지 않았으며 서버 반영·내부 인증·API 연동 검증은 남아 있다.

리뷰·병합 후 서버 기존 Compose·nginx 설정을 백업·비교한다. 서버에서만 무작위 토큰 하나를 생성해 `.env`의 두 토큰 변수에 같은 값을 지정한다. 값이 이미 있다면 임의로 교체하지 않고 관련 서비스의 공유 상태를 먼저 확인한다. Compose 검사와 실제 인증서를 사용한 nginx 문법 검사 후 backend·AI 재생성 및 nginx reload를 수행한다. 외부 internal 경로 차단·일반 API 유지·앱 상태를 확인하고, 270 및 관련 내부 API 구현 상태에 맞춰 내부망 인증과 연동을 검증한다. 완료되지 않은 서버 작업은 Jira 274에 잔여 항목으로 남긴다. 54 배치 작업과 67 서버 반영은 별도 범위다.

## 원천 접속 정보 (64)

원천(ERP·MES) DB에는 세 계정으로 접속한다. 비밀번호를 두는 곳이 계정마다 다르다.

| 계정 | 쓰는 곳 | 비밀번호 위치 |
| --- | --- | --- |
| `platform_ro` (SYNC) | batch: 수집·연결 검사·전체 읽기 | 플랫폼 DB `data_source_credential`에 암호문으로. 관리자가 원천 등록(API 010·012)에서 넣는다 |
| `platform_ai` (AI) | FastAPI 분석 | 위와 같다. backend가 풀어 분석 시작 요청으로 AI에 넘긴다 |
| `platform_cdc` | Kafka Connect(Debezium) | `~/axcore/cdc/.env`의 `ERP_CDC_PASSWORD`·`MES_CDC_PASSWORD` (`cdc/README.md`) |

**암호화 키**

- 서버 `~/axcore/.env`의 `AXCORE_SOURCE_CREDENTIAL_KEY_1`에 둔다. backend·batch에만 전달하고 ai에는 전달하지 않는다.
- 원천을 처음 등록하기 전에 한 번만 만든다: `openssl rand -base64 32`. 값은 화면·채팅·MR·Jira에 남기지 않는다.
- 원천을 등록한 뒤에는 키를 바꾸거나 잃으면 저장된 비밀번호를 풀 수 없다(모든 원천을 다시 등록해야 함). 서버 밖 안전한 곳에 따로 보관한다.
- 키가 비어 있어도 backend·batch는 뜬다. 원천 등록·분석 시작·수집에서 암·복호화만 실패한다.
- `AXCORE_SOURCE_CREDENTIAL_KEY_VERSION`은 비워 두면 1이다. 키 교체 절차는 개발 가이드 11.3 "원천 접속 비밀번호"에 있다.

**비밀번호를 바꿀 때**

- `platform_ro`·`platform_ai`: 원천 서버 `~/source-db/.env`의 값을 바꾸고 `setup.sh`를 다시 실행해 계정 비밀번호를 바꾼다(`source-db/README.md`). 그다음 화면 또는 API 012로 해당 용도(SYNC·AI)의 계정을 다시 넣는다. 다시 넣기 전까지 그 용도의 접속은 실패한다.
- `platform_cdc`: 원천 계정 비밀번호를 위와 같이 바꾼 뒤 `~/axcore/cdc/.env`의 두 값을 같은 값으로 바꾸고 `cd ~/axcore/cdc && docker compose up -d kafka-connect`로 컨테이너를 다시 만든다. `docker restart`는 바뀐 `.env`를 읽지 않는다.
- 암호화 키: 위 "암호화 키"를 따른다.

## 서버에 적용하는 방법

backend · AI · frontend 이미지는 develop 머지 시 Jenkins가 서비스별 변경을 감지해 자동 배포합니다. 상세 흐름은 아래 "자동 배포" 절을 참고합니다.

`infra/compose.yaml` · nginx 설정 · 스크립트 같은 서버 설정 파일은 자동 복사되지 않습니다. 저장소에서 수정·리뷰·머지한 설정을 서버 파일과 비교하고, 기존 파일을 백업한 뒤 필요한 파일만 수동 반영합니다. 아래는 머지된 nginx 설정을 노트북의 저장소 루트에서 복사하는 예시입니다.

```bash
# 1. 바뀐 파일을 서버로 복사 (예: nginx 설정)
scp -i ~/.ssh/K15C106T.pem infra/nginx/conf.d/default.conf ubuntu@k15c106.p.ssafy.io:axcore/nginx/conf.d/

# 2. 서버에서 적용
ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io
cd ~/axcore
docker compose exec nginx nginx -t        # nginx 설정 문법 검사
docker compose exec nginx nginx -s reload # 설정만 바꿨을 때
# compose.yaml을 바꿨을 때는 설정 검사 후 변경한 서비스만 적용
docker compose config --quiet
# 예: AI 설정 변경 (AI_TAG에 지정한 이미지가 서버에 있어야 함)
docker compose up -d ai
docker compose ps
```

서버 `.env`·인증서·자격 증명은 덮어쓰거나 저장소로 복사하지 않습니다. 반영 전후에 설정 사본을 `diff`로 비교하고 서비스 상태·응답을 확인합니다. 긴급한 서버 변경이 있었다면 비밀값을 제외한 설정 변경을 저장소의 리뷰 절차에도 반영합니다.

## 새 서버에 처음 세팅할 때 필요한 것

이 폴더만으로는 바로 뜨지 않습니다. 서버마다 다른 값이 있습니다.

1. `compose.yaml`의 `group_add: "988"`은 docker 소켓 그룹 번호입니다. 새 서버에서는 `stat -c '%g' /var/run/docker.sock`으로 다시 확인합니다.
2. `jenkins-data/` 폴더를 만들고 소유자를 uid 1000(컨테이너의 jenkins 사용자)으로 맞춥니다.
3. 인증서는 certbot으로 새로 발급합니다. (`docker compose run --rm certbot certonly --webroot ...`)
4. `renew-cert.sh`를 crontab에 등록합니다. (`0 3 * * * /home/ubuntu/axcore/scripts/renew-cert.sh >> /home/ubuntu/axcore/logs/renew-cert.log 2>&1`)
5. `.env`를 만듭니다. `cp .env.example .env` 후 값을 채우고 `chmod 600 .env`. DB 비밀번호는 `openssl rand -base64 24`로 생성합니다.
6. PostgreSQL 데이터는 Docker 볼륨 `axcore-pg-data-18`에 있습니다. (컨테이너 안 `/var/lib/postgresql`, PostgreSQL 18 이미지는 그 아래 `18/docker`를 데이터 폴더로 씀) 서버를 옮길 때는 볼륨 복사가 아니라 `pg_dump` 백업 파일로 옮깁니다. 호스트 포트는 `127.0.0.1:15432`로만 열려 있어 바깥에서 접속할 수 없습니다.
7. `pg-backup.sh`를 crontab에 등록합니다. (`0 4 * * * /home/ubuntu/axcore/scripts/pg-backup.sh >> /home/ubuntu/axcore/logs/pg-backup.log 2>&1`)

## DB 백업 · 복구

플랫폼 PostgreSQL(`axcore`)은 `scripts/pg-backup.sh`가 매일 04:00에 `pg_dump`로 백업합니다.

- 백업 위치: 서버 `/home/ubuntu/axcore/backup/axcore_YYYYMMDD_HHMM.dump` (custom 형식, 압축)
- 보관 기간: 7일. 더 오래된 파일은 스크립트가 삭제합니다.
- 로그: `/home/ubuntu/axcore/logs/pg-backup.log` (실행 시각, 파일 이름, 크기, 남은 파일 수)
- 비밀번호 불필요: 컨테이너 안에서 실행하므로 로컬 접속으로 인증됩니다.

수동 백업:

```bash
~/axcore/scripts/pg-backup.sh
```

복구 테스트 (운영 DB를 건드리지 않고 별도 DB에 복구해 비교):

```bash
cd ~/axcore
docker compose exec postgres psql -U axcore -d axcore -c "create database axcore_restore_test;"
docker compose exec -T postgres pg_restore -U axcore -d axcore_restore_test < backup/<파일>.dump
# 원본(axcore)과 복구본(axcore_restore_test)의 표 개수 · 줄 수를 비교한 뒤 정리
docker compose exec postgres psql -U axcore -d axcore -c "drop database axcore_restore_test;"
```

운영 DB를 실제로 되돌려야 할 때 (장애 시에만, 현재 데이터를 백업 시점으로 덮어씀):

```bash
cd ~/axcore
# 1. DB를 쓰는 앱 컨테이너를 먼저 멈춘다 (5단계 이후 backend 등)
# 2. 백업 시점으로 되돌린다
docker compose exec -T postgres pg_restore -U axcore -d axcore --clean --if-exists < backup/<파일>.dump
# 3. 앱 컨테이너를 다시 켠다
```

## MR 검증 (MR 생성·갱신 → Jenkins axcore-mr-check)

MR을 올리거나 MR 브랜치에 push하면 Job `axcore-mr-check`가 MR 커밋을 받아, develop과 갈라진 뒤 **바뀐 폴더의 검사만** 돌린다. 결과는 GitLab MR 화면 ✅/❌로 보고, 실패했을 때만 MR 작성자에게 Mattermost DM이 간다(성공 DM은 배포 Job만). backend·AI의 JUnit 결과는 Jenkins 빌드 화면(Test Result)에서 확인한다. `backend/` · `ai/` · `frontend/` 밖의 파일만 바꾸면 서비스 검사는 생략된다. 각 서비스 폴더 안의 README 변경은 해당 서비스 검사를 실행한다. 수동 실행(Build with Parameters)은 develop을 받아 폴더가 있는 서비스를 전부 검사한다.

Job은 develop의 Jenkinsfile로 검사 절차를 읽고, Checkout 단계에서 MR 코드를 받는다. 따라서 Jenkinsfile만 변경한 MR의 성공 표시가 새 Jenkinsfile의 문법·실행 검증을 의미하지는 않는다.

| 폴더 | 검사 | 어디서 | 비고 |
| --- | --- | --- | --- |
| `backend/` | `./gradlew --no-daemon spotlessCheck build` (스타일 → 컴파일 → 테스트) | Jenkins 컨테이너 안 | Testcontainers가 docker.sock으로 테스트 DB를 띄움. gradle 캐시 `jenkins-data/.gradle` |
| `frontend/` | `npm ci` → `npm run lint` → `vitest run` → `npm run build` | 일회용 `node:24.18.0-alpine` 컨테이너 | Node 버전은 프론트 담당 기준(24.18.0). npm 캐시 볼륨 `axcore-npm-cache` |
| `ai/` | `pip install -r requirements-dev.txt` → `ruff check .` → `pytest` | 일회용 `python:3.11-slim` 컨테이너 | pip 캐시 볼륨 `axcore-pip-cache` |

frontend · ai 컨테이너에는 docker.sock을 붙이지 않는다. 작업 폴더는 호스트 경로(`/home/ubuntu/axcore/jenkins-data/workspace/axcore-mr-check/...`)로 마운트한다(Jenkins 컨테이너의 `/var/jenkins_home`이 호스트의 `jenkins-data`). 컨테이너가 만든 `node_modules` · `dist` · `.pytest_cache`는 root 소유로 작업 폴더에 남지만 다음 검사도 같은 방식이라 문제없다. 지워야 하면 `docker run --rm -v /home/ubuntu/axcore/jenkins-data/workspace/axcore-mr-check/frontend:/app alpine rm -rf /app/node_modules /app/dist`.

- 테스트는 Testcontainers로 테스트용 PostgreSQL 컨테이너를 띄운다. Jenkins 컨테이너의 docker.sock을 쓰며, 그 컨테이너는 기본 bridge에 뜨고 서버 포트로 열리므로 Jenkins 컨테이너에서는 axcore-net 게이트웨이(=서버)로 닿는다. 그 주소가 compose의 `TESTCONTAINERS_HOST_OVERRIDE`다. axcore-net을 지웠다 다시 만들면 게이트웨이가 바뀔 수 있으니 `docker network inspect axcore-net -f '{{(index .IPAM.Config 0).Gateway}}'`로 다시 확인한다.
- gradle 캐시는 `jenkins-data/.gradle`에 남아 두 번째부터 빠르다. 첫 실행은 Gradle 배포판과 의존성을 받느라 몇 분 걸린다.
- 메모리: compose `mem_limit: 4g`. 2026-11-01 실측(gradle 결과 폴더를 지우고 수동 실행해 컴파일·테스트 전부 돌림): 평소 0.9GiB, 빌드 중 최고 2.0GiB, 끝나면 0.9GiB로 복귀. 다시 재려면 `docker compose exec jenkins rm -rf /var/jenkins_home/workspace/axcore-mr-check/backend/build` 뒤 수동 실행하고 `docker stats --no-stream --format '{{.MemUsage}}' jenkins`를 10초 간격으로 찍는다.
- `junit` 단계는 Jenkins의 JUnit 플러그인이 있어야 돈다 (초기 추천 플러그인에 포함).
- 검사 대상은 GitLab이 알려 준 MR 커밋(`gitlabMergeRequestLastCommit`) 자체를 받는다. 빌드 로그 첫머리의 `gitlab*` 변수 목록과 "검사 대상:" 줄로 어떤 코드를 검사했는지 확인한다. MR 검사인데 develop을 받으면 빌드가 실패하도록 안전망이 있다 (2026-10-30, Jenkinsfile 파라미터 `gitlabSourceBranch`의 기본값이 플러그인 변수를 덮어써 develop을 검사하던 문제).
- 보안 메모: MR 브랜치의 `build.gradle`이 docker.sock이 있는 Jenkins 컨테이너 안에서 실행된다. 팀원 GitLab 계정을 신뢰하는 구성이다. 분리(docker.sock 없는 일회용 컨테이너 + 외부 테스트 DB)는 보안 후속 과제로 둔다.

## 자동 배포 (develop 머지 → Jenkins axcore-deploy)

`Jenkinsfile`의 배포 단계는 Job `axcore-deploy`에서만 켜진다 (MR 검증 Job은 배포하지 않는다).

배포 대상은 backend · AI · frontend 세 서비스다. 변경된 서비스를 backend → AI → frontend 순서로 처리하며, 서비스마다 같은 절차를 `deployService` 함수가 돌린다.

1. develop 머지 → GitLab Webhook → `axcore-deploy` 실행. 배포 Job은 GitLab이 어떤 변수를 보내든 항상 `origin/develop`만 받는다
2. 서비스마다 서버 `.env`의 태그(`BACKEND_TAG` · `AI_TAG` · `FRONTEND_TAG` = 지금 배포된 커밋)와 develop 사이에 해당 폴더(`backend/` · `ai/` · `frontend/`) 변경이 있는지 확인. 변경 없으면 그 서비스는 건너뜀 (`FORCE_DEPLOY` 파라미터면 세 서비스 모두 배포). 직전 커밋과 비교하지 않는 이유: 머지가 연달아 쌓이면 앞 머지의 변경을 놓친다
3. backend: `docker build -t axcore-backend:<커밋 SHA> backend` → `scripts/pg-backup.sh` 1회(마이그레이션 대비) → `.env`의 `BACKEND_TAG` 갱신 + `docker compose up -d backend` → 최대 90초 `(healthy)` 대기 → 실패하면 **직전 태그로 자동 롤백**
4. AI: `docker build -t axcore-ai:<커밋 SHA> ai` → `.env`의 `AI_TAG` 갱신 + `docker compose up -d ai` → 최대 90초 `(healthy)` 대기 → 실패하면 롤백. DB 백업은 없음. AI에는 backend에 대한 `depends_on`이 없어 backend 기동 상태를 기다리지 않고 단독 기동한다. AI `/health` 응답은 실제 LLM 호출·분석 연동 성공을 의미하지 않는다
5. frontend: `docker build -t axcore-frontend:<커밋 SHA> frontend` → `.env`의 `FRONTEND_TAG` 갱신 + `docker compose up -d frontend` → 90초 `(healthy)` 대기 → 실패하면 롤백. DB 백업은 없음
6. 서비스의 **배포 단계** 실패는 `catchError`로 처리해 이후 서비스 단계를 계속한다. 빌드는 실패로 표시된다. 이미지 빌드·DB 백업 단계는 이 처리 밖에 있어 실패하면 이후 단계가 중단될 수 있다. backend 배포 실패 후에도 AI 단계는 진행될 수 있다. backend → AI → frontend는 동시에 변경된 서비스의 처리 순서이며, AI 기동에 backend 배포 성공이 필요한 것은 아니다
7. backend · AI · frontend 이미지는 각각 최근 3개 태그만 보관, 3일 지난 빌드 캐시 삭제
8. 결과는 GitLab 커밋 상태 + Mattermost DM(머지한 사람). 서비스마다 `ai 배포 <이전> → <새 SHA>` / `ai 배포 실패 → <이전> 로 롤백됨` 등의 결과를 표시하며, 세 서비스 모두 변경이 없으면 `배포: 변경 없음`. 배포 대상 목록을 기준으로 메시지를 만들기 때문에 빌드 실패 등으로 배포 단계에 도달하지 못한 경우에는 Jenkins 단계 로그도 함께 확인한다

롤백은 직전 태그가 있을 때 해당 태그로 `up -d`를 실행하고 복구 후 준비 상태도 재확인한다. 첫 배포처럼 직전 태그가 없으면 실패한 신규 서비스를 정지하고 수동 복구 필요를 기록한다. backend·batch 묶음의 추가 처리 조건은 아래 배치 절을 따른다. 실패 시 컨테이너 상태와 실제 응답도 확인한다.

Jenkins 컨테이너는 가게 폴더를 같은 경로(`/home/ubuntu/axcore`)로 마운트한다. compose의 상대 경로(`./nginx/...`)가 호스트 기준으로 풀리므로 경로가 같아야 한다.

**손으로 롤백할 때** (자동 롤백이 안 됐거나, 배포 뒤 문제가 늦게 발견된 경우). frontend는 `backend`를 `frontend`로, `BACKEND_TAG`를 `FRONTEND_TAG`로 바꾼다. AI는 각각 `ai` · `AI_TAG`로 바꿔 같은 절차를 사용한다:

```bash
cd ~/axcore
docker images axcore-backend              # 남아 있는 태그 확인 (최근 3개)
nano .env                                 # BACKEND_TAG=<되돌릴 SHA>
docker compose up -d backend
docker compose ps                         # (healthy) 확인
```

DB 마이그레이션까지 되돌려야 하면 "DB 백업 · 복구"의 운영 DB 복구 절차로 배포 직전 백업(`backup/`)을 되살린다.

## 백엔드 수동 배포 (자동 배포가 안 될 때의 비상 절차)

1. Jenkins 작업 폴더에 develop이 받아져 있다: `~/axcore/jenkins-data/workspace/axcore-deploy`
2. 커밋 번호 확인: `git -C ~/axcore/jenkins-data/workspace/axcore-deploy rev-parse --short HEAD`
3. 이미지 빌드: `docker build -t axcore-backend:<sha> ~/axcore/jenkins-data/workspace/axcore-deploy/backend`
4. `.env`의 `BACKEND_TAG=<sha>` 갱신
5. `docker compose up -d backend` → `curl -s 127.0.0.1:18080/actuator/health` → `{"status":"UP"}`

- DB 접속은 compose 안에서 `postgres:5432` (컨테이너 이름 · 컨테이너 포트). 호스트 포트 15432는 SSH 터널용
- 표는 Flyway가 `platform` 스키마에 만든다: `\dt platform.*`
- batch 컨테이너는 같은 이미지를 `SPRING_PROFILES_ACTIVE=batch`로 띄운다. 실행 환경과 검증 범위는 아래 배치 절을 따른다.
- 헬스체크: `docker compose ps`에서 backend `(healthy)` 확인. 컨테이너 안에서 30초마다 `/actuator/health`를 두드리며, DB가 내려가면 약 90초 뒤 `(unhealthy)`로 바뀐다 (자동 재시작은 하지 않음. 5-7 배포 검증과 7-4 알림에서 사용)

## 배치 컨테이너 (54)

백엔드 담당자와 확인한 범위는 같은 backend 이미지를 batch 프로필로 실행하는 컨테이너 환경 구성이다. 정합성 검사·작업 실행 로직은 백엔드 작업 범위다. `batch`는 `axcore-backend:${BACKEND_TAG}`와 기존 플랫폼 PostgreSQL 연결 변수를 사용한다. 원천 MES·ERP 접속 정보나 CDC 소비 설정은 이번 변경에 추가하지 않는다. 이후 실행 코드가 요구하는 환경변수는 담당자와 확인해 별도 반영한다.

초기 자원 제한은 `mem_limit: 1g`, `cpus: 1.0`, `JAVA_TOOL_OPTIONS: -Xmx768m`이다. 힙 외 메모리 여유는 256MiB이며 충분한지는 실제 작업 부하에서 확인한다. batch 프로필은 웹 서버 없이 프로세스를 유지하므로 HTTP 포트를 추가하지 않는다. PostgreSQL의 healthy 상태를 기다린 후 실행하고 기존 `axcore-net`에 연결한다. 기존 Alloy 설정은 이 네트워크의 Compose `service="batch"` 로그를 수집 대상으로 포함하지만 실제 서버 수집 여부는 배포 후 확인해야 한다.

### 배포 준비 완료 기준

백엔드 담당자가 전달한 현재 기준에 따라 batch에는 healthcheck나 하트비트 코드를 추가하지 않는다. Jenkins가 현재 프로세스 시작 이후의 `Started PlatformApplication` 로그, RestartCount=0, OOMKilled=false와 running 상태를 확인하고 60초간 같은 컨테이너 ID·StartedAt이 유지되는지 검사한다. 로그 대기는 최대 90초이며 안정화 기간에는 5초마다 다시 검사한다. HTTP API health는 기존 backend·AI·frontend 기준을 유지한다.

담당자는 현재 배치 코드의 마이그레이션·작업 처리기 빈·스케줄 등록이 기동 중 완료되며, CDC 소비자와 작업 처리기 구현체는 아직 없다고 설명했다. 이번 기준은 기동과 짧은 안정화 확인이며 작업 성공이나 운영 중 DB 단절 감지를 보장하지 않는다. 기동 후 비동기 연결 구성요소 또는 실제 처리기가 추가되면 준비 상태와 운영 heartbeat를 다시 검토한다. 실제 도메인 작업 처리 준비를 Started 로그만으로 보증하지 않는다.

`stop_grace_period: 40s`를 두어 담당자가 안내한 앱 종료 대기 30초보다 Docker 강제 종료 유예를 길게 설정한다. 처리 중인 작업이 실제로 안전하게 종료·복구되는지는 작업 처리기 구현 후 검증한다.

현재 Compose의 메모리 상한이 있는 서비스 합계는 batch 포함 10.875GiB다(Jenkins 4, backend 2, PostgreSQL 1, AI 1, batch 1, frontend 0.125, Prometheus 0.5, Alloy 0.25, Grafana 0.5, Loki 0.5). 이 합계는 전체 서버 메모리 보장을 뜻하지 않는다. nginx에는 상한이 없고 별도 CDC 프로젝트의 Kafka·Connect·Console, OS 등의 사용량도 별도로 포함해야 한다. 16GB 서버의 실제 여유·피크를 확인한 뒤 초기 예산을 확정한다.

### 로컬 검증 결과

2026-11-02 사용자 실행 결과: Compose config 종료 코드 0과 diff 공백 검사 통과. `axcore-backend:batch-54-check`를 Dockerfile로 빌드했다. Dockerfile의 `bootJar -x test` 경로이므로 애플리케이션 테스트 통과를 의미하지 않는다. 외부 포트와 기존 볼륨이 없는 별도 네트워크·tmpfs PostgreSQL 18.6으로 검증했다. batch 프로필·`-Xmx768m`, DB 연결과 Flyway V1~V5 적용 후 `Started PlatformApplication`을 확인했다(약 9.9초). 메모리 260.6MiB/1GiB, CPU 0.09%는 유휴 상태의 단일 측정값이다. Docker inspect에서 Memory=1073741824, NanoCpus=1000000000, PortBindings={}를 확인했다. 재시작 후 약 9.5초에 다시 기동했고 running·OOM=false였다. 임시 앱·DB 컨테이너와 전용 네트워크는 정리했으며 검사 이미지는 남겼다.

후속 사용자 실행 결과: 별도 Windows Gradle project cache로 build 통과. 최신 develop 79ddbef(215 배치 실행 기반·270 인증 포함) 반영 후 backend 전체 build 재검사와 검사 이미지 재빌드도 성공했다. 새 tmpfs PostgreSQL에서 Flyway V1~V6 적용 성공, batch 프로필에서 약 10.52초에 Started 로그와 running·OOM=false·RestartCount=0을 확인했다. 이어서 5초 간격 12회 동안 컨테이너 ID·StartedAt 유지, running·RestartCount=0·OOM=false 조건을 확인해 60초 안정화 검사를 통과했다. 이전 버전으로 수행한 잘못된 DB 비밀번호 검사에서는 기동 완료 없이 Application run failed, 최종 exited·RestartCount=2·ExitCode=1이었다. 이 실패 사례는 최신 코드로 다시 실행한 결과는 아니다.

`infra/tests/jenkins-deploy-tests.groovy`는 Jenkinsfile의 실제 배포 함수를 로드하되 Jenkins 단계와 준비 판정 결과를 대체한다. 사용자가 Gradle에 포함된 Groovy로 실행해 8개 분기(묶음 갱신, 묶음 롤백, 첫 batch 실패 복구, 복구 실패, batch 부재, 기존 이미지 불일치, Compose 해석 오류, 이전 태그 부재) 통과를 확인했다. 이는 실제 Docker 배포나 Jenkins 서버 실행을 대신하지 않는다. 최신 Compose config 종료 코드 0도 확인했다.

컨테이너 기동 검증은 직접 `docker run`으로 지정한 실행 옵션의 확인이다. 서버 Compose batch 서비스의 실제 실행·제한 적용은 배포 후 확인해야 한다. 정합성 검사, 실제 도메인 작업, MES·ERP 연결, 장기 부하·로그 수집은 이번 로컬 검사에서 실행하지 않았다. 최신 기동 검사 컨테이너·임시 DB·네트워크는 사용자 확인으로 최종 정리 완료했다.

### 배포 및 이미지 자동 갱신

태그 정책은 BACKEND_TAG 공유와 backend·batch 묶음 배포·롤백이다. 독립적인 BATCH_TAG는 추가하지 않는다. Jenkins는 backend 이미지를 한 번 빌드하고, 서버 Compose에 batch가 있으면 두 서비스를 함께 재생성한다. HTTP health 확인 후 batch 전용 기동·안정화 검사를 수행하고 HTTP health도 재확인한다. `disableConcurrentBuilds()`로 같은 Jenkins Job 실행을 직렬화해 태그 갱신과 롤백이 겹치지 않게 한다. 이 옵션은 이 Jenkinsfile을 사용하는 MR 검사 Job에도 적용된다. 별도 Job이나 수동 서버 작업까지 잠그는 것은 아니므로 배포 중 수동 태그 변경은 하지 않는다. 서버 Compose에 batch가 아직 없으면 backend만 배포하고 건너뛴 사실을 출력한다. Compose 해석 오류는 서비스 부재로 간주하지 않고 배포를 중단한다. 서버 설정 파일은 여전히 자동 복사되지 않으므로 수동 반영이 필요하다.

기존 batch가 실행 중이면 이전 BACKEND_TAG의 이미지와 같은지 먼저 확인하며, 버전 불일치 시 자동 덮어쓰기 대신 중단한다. 배포 실패 시 이전 태그로 backend와 기존 실행 중 batch를 함께 복구하고 각 서비스의 준비 기준을 재확인한다. 첫 batch 배포처럼 이전에 실행 중이지 않았다면 신규 batch를 정지한 뒤 backend만 복구한다. 이전 태그 자체가 없으면 실패한 신규 서비스를 정지하고 수동 복구 필요를 기록한다. 롤백 검증이 실패하면 성공한 롤백으로 표시하지 않는다. 이미지 롤백은 DB 마이그레이션을 되돌리지 않으므로 이전 코드의 DB 호환성도 검토한다.

Jenkinsfile 변경은 develop 병합 시 서버에서 실행된다. Compose를 반영하기 전의 backend 배포에서는 batch가 추가되지 않는다. 서버 반영은 리뷰·병합 후 기존 설정 백업·비교와 Compose 검사를 거쳐 수행한다. 첫 실행은 이미지 태그 확인 후 `docker compose up -d batch`로 진행하며 기동 로그·60초간 running 및 StartedAt 유지·RestartCount=0·OOM=false·자원 제한·포트·Loki 로그를 확인한다. 이후 실제 자동 갱신과 실패 롤백 검증은 남아 있다. 위 로컬 기동 검증은 Jenkins 안정화 검사나 실제 작업 처리 검증을 대신하지 않는다.

## PostgreSQL 메이저 업그레이드 절차 (17.11 → 18.6, 2026-10-27 수행)

메이저 버전(17 → 18)은 데이터 파일 형식이 달라 `image:` 숫자만 바꾸면 컨테이너가 켜지지 않습니다. 백업 → 새 볼륨으로 새 버전 기동 → 복구 순서로 진행합니다. 데이터가 적을 때 하는 것이 가장 쉽습니다.

```bash
cd ~/axcore
# 1. 새 버전 숫자 확인, 이전용 백업 확보
docker run --rm postgres:18 postgres --version
./scripts/pg-backup.sh
# 2. 옛 컨테이너 삭제 (볼륨은 남음), compose 수정 전 사본
docker compose rm -sf postgres
cp compose.yaml compose.yaml.bak-17
# 3. compose.yaml: image 숫자, 새 볼륨 이름(pg-data-18 / axcore-pg-data-18), 마운트 경로 /var/lib/postgresql, 호스트 포트
# 4. 새 버전 기동 → 초기화 후 비어 있는 DB에 백업 복구
docker compose up -d postgres
docker compose exec -T postgres pg_restore -U axcore -d axcore < backup/<이전용 백업>.dump
# 5. 표 개수 · 줄 수 비교 (DB 백업 · 복구 절 참고), 포트 확인
sudo ss -tlnp | grep 15432
```

- 되돌리기: `cp compose.yaml.bak-17 compose.yaml && docker compose up -d postgres` (옛 볼륨이 그대로라 이전 버전으로 복귀)
- 옛 볼륨은 2~3일 뒤 문제 없으면 삭제: `docker volume rm axcore-pg-data`
- `pg-backup.sh`는 컨테이너 이름으로 접속하므로 버전 · 포트 변경에 영향 없음

## 관련 문서

- 노션 Infra > 할 일 리스트
- 노션 Infra > Jenkins 설치 가이드
- 노션 Infra > 할 일 리스트 5-0 (MR 검증 파이프라인)
