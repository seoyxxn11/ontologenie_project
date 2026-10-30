# infra

메인 서버(Lightsail, `k15c106.p.ssafy.io`)의 배포 설정 파일 사본입니다. 서버의 `/home/ubuntu/axcore/` 폴더와 같은 구조이며, 서버가 사라져도 같은 구성을 다시 만들 수 있도록 보관합니다.

## 구성

| 파일 | 역할 |
| --- | --- |
| `compose.yaml` | nginx · certbot · Jenkins · PostgreSQL 18 컨테이너 정의 (이미지 버전 고정, 내부망 `axcore-net`) |
| `.env.example` | `compose.yaml`이 읽는 환경 변수 이름 목록 (실제 값은 서버의 `.env`에만) |
| `nginx/conf.d/default.conf` | HTTP → HTTPS 리다이렉트, TLS 설정, `/jenkins/` 리버스 프록시, `/api/` → backend (스트리밍 대비 buffering off) |
| `nginx/html/index.html` | 기본 페이지 (nginx 동작 확인용) |
| `jenkins/Dockerfile` | Jenkins 공식 이미지 + docker CLI · compose 플러그인 |
| `scripts/renew-cert.sh` | 인증서 자동 갱신 + nginx reload (cron에서 매일 03:00 실행) |
| `scripts/pg-backup.sh` | 플랫폼 DB `pg_dump` 백업 + 7일 지난 파일 삭제 (cron에서 매일 04:00 실행) |
| backend | Spring Boot 백엔드 (api 모드) · `backend/Dockerfile` · 호스트 `127.0.0.1:18080` · 이미지 태그 `.env`의 `BACKEND_TAG` |

## 포트 규칙

SSAFY 클라우드 방화벽은 tcp · udp 1024~65535를 열어 두므로, 서버의 ufw와 `127.0.0.1` 바인딩이 유일한 방어선입니다. Docker의 `ports:`는 ufw를 우회하므로 아래 규칙을 지킵니다.

- 바깥(`0.0.0.0`)에 여는 포트는 nginx의 80 · 443뿐입니다.
- 나머지 서비스는 호스트 포트를 열지 않거나 `127.0.0.1:1xxxx`(비기본값)로만 엽니다. 컨테이너 안 포트는 기본값을 유지합니다.
- 현재 할당: PostgreSQL `127.0.0.1:15432` (컨테이너 안 5432). 앞으로 backend 18080 · ai 18000 · grafana 13000 예정.

## nginx 경로 (바깥에서 보이는 주소)

| 경로 | 가는 곳 | 비고 |
| --- | --- | --- |
| `/` | nginx 기본 페이지 | 5단계 frontend 배포 시 React로 교체 |
| `/api/` | backend:8080 (경로 그대로) | 로그인 전 요청은 backend가 401로 응답. `X-Forwarded-Proto` 전달(세션 Secure 쿠키), `proxy_buffering off` + `proxy_read_timeout 300s`(Agent 스트리밍), 업로드 상한 20m |
| `/jenkins/` | jenkins:8080 | 팀원 Read 권한 |
| `/swagger-ui.html` · `/swagger-ui/` · `/v3/api-docs` | backend:8080 | nginx Basic Auth (아이디 · 비밀번호는 서버 `nginx/htpasswd`, 팀 DM으로 공유). 백엔드 로그인과 별개 |

바깥에 열지 않는 것: backend `/actuator/*`(헬스체크·지표는 내부망만). `/api/`로 시작하지 않으므로 nginx가 넘기지 않는다.

공통 보호: 모든 HTTPS 응답에 보안 헤더 3개(HSTS 1주 · `X-Content-Type-Options nosniff` · `X-Frame-Options SAMEORIGIN`). 로그인 `/api/v1/auth/login`만 IP별 요청 제한(20회 즉시 통과, 이후 분당 30회, 초과 시 429). 교육장은 팀이 공인 IP 하나를 같이 쓰므로 느슨하게 둔다.

Swagger 열쇠 파일 만들기 (서버): `echo "swagger:$(openssl passwd -apr1)" > ~/axcore/nginx/htpasswd && chmod 644 ~/axcore/nginx/htpasswd` (비밀번호 두 번 입력. 파일에는 해시만 저장되고 저장소에는 올리지 않음. nginx 일꾼 사용자가 읽어야 하므로 644)

## 저장소에 올리지 않는 것

`.gitignore`로 제외합니다. 서버에만 존재합니다.

- `certbot/` — Let's Encrypt 인증서와 개인키
- `jenkins-data/` — Jenkins 설정, 계정, 자격 증명
- `logs/` — 갱신 · 백업 로그
- `backup/` — DB 백업 파일 (7일 보관, 서버에만)
- `.env`, `*.pem` — 비밀값과 접속 키

## 서버에 적용하는 방법

아직 Jenkins 자동 배포 전이라 사람이 직접 반영합니다. 노트북에서 저장소 루트 기준으로 실행합니다.

```bash
# 1. 바뀐 파일을 서버로 복사 (예: nginx 설정)
scp -i ~/.ssh/K15C106T.pem infra/nginx/conf.d/default.conf ubuntu@k15c106.p.ssafy.io:axcore/nginx/conf.d/

# 2. 서버에서 적용
ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io
cd ~/axcore
docker compose exec nginx nginx -t        # nginx 설정 문법 검사
docker compose exec nginx nginx -s reload # 설정만 바꿨을 때
docker compose up -d                      # compose.yaml을 바꿨을 때
```

반대로 서버에서 먼저 고친 경우에는 같은 방법으로 노트북에 받아 커밋합니다. 서버와 저장소의 내용이 같은지 `diff`로 확인합니다.

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

MR을 올리거나 MR 브랜치에 push하면 Job `axcore-mr-check`가 MR 브랜치를 받아 `backend/`에서 `./gradlew --no-daemon spotlessCheck build`를 돌린다 (스타일 검사 → 컴파일 → 테스트). 결과는 GitLab MR 화면 ✅/❌로 보고, 실패했을 때만 MR 작성자에게 Mattermost DM이 간다(성공 DM은 배포 Job만). 깨진 테스트 목록은 Jenkins 빌드 화면(Test Result)으로 본다. develop과 갈라진 뒤 `backend/`가 바뀌지 않은 MR(문서·인프라만 고친 MR)은 gradle을 건너뛰고 바로 ✅다. 수동 실행(Build with Parameters)은 develop을 받아 항상 전체를 돌린다 (develop 재검사 용도).

- 테스트는 Testcontainers로 테스트용 PostgreSQL 컨테이너를 띄운다. Jenkins 컨테이너의 docker.sock을 쓰며, 그 컨테이너는 기본 bridge에 뜨고 서버 포트로 열리므로 Jenkins 컨테이너에서는 axcore-net 게이트웨이(=서버)로 닿는다. 그 주소가 compose의 `TESTCONTAINERS_HOST_OVERRIDE`다. axcore-net을 지웠다 다시 만들면 게이트웨이가 바뀔 수 있으니 `docker network inspect axcore-net -f '{{(index .IPAM.Config 0).Gateway}}'`로 다시 확인한다.
- gradle 캐시는 `jenkins-data/.gradle`에 남아 두 번째부터 빠르다. 첫 실행은 Gradle 배포판과 의존성을 받느라 몇 분 걸린다.
- 메모리: Jenkins 2g + gradle 1g(`GRADLE_OPTS`) + 테스트 JVM 몫으로 compose `mem_limit: 5g`. 빌드 중 `docker stats --no-stream jenkins`로 상한 대비 사용량을 본다.
- `junit` 단계는 Jenkins의 JUnit 플러그인이 있어야 돈다 (초기 추천 플러그인에 포함).
- 검사 대상은 GitLab이 알려 준 MR 커밋(`gitlabMergeRequestLastCommit`) 자체를 받는다. 빌드 로그 첫머리의 `gitlab*` 변수 목록과 "검사 대상:" 줄로 어떤 코드를 검사했는지 확인한다. MR 검사인데 develop을 받으면 빌드가 실패하도록 안전망이 있다 (2026-10-30, Jenkinsfile 파라미터 `gitlabSourceBranch`의 기본값이 플러그인 변수를 덮어써 develop을 검사하던 문제).
- 보안 메모: MR 브랜치의 `build.gradle`이 docker.sock이 있는 Jenkins 컨테이너 안에서 실행된다. 팀원 GitLab 계정을 신뢰하는 구성이다. 분리(docker.sock 없는 일회용 컨테이너 + 외부 테스트 DB)는 보안 후속 과제로 둔다.

## 자동 배포 (develop 머지 → Jenkins axcore-deploy)

`Jenkinsfile`의 배포 단계는 Job `axcore-deploy`에서만 켜진다 (MR 검증 Job은 배포하지 않는다).

1. develop 머지 → GitLab Webhook → `axcore-deploy` 실행
2. 서버 `.env`의 `BACKEND_TAG`(지금 배포된 커밋)와 develop 사이에 `backend/` 변경이 있는지 확인. 변경 없으면 배포 건너뜀 (`FORCE_DEPLOY` 파라미터로 강제 가능). 직전 커밋과 비교하지 않는 이유: 머지가 연달아 쌓이면 앞 머지의 backend 변경을 놓친다. 배포 Job은 GitLab이 어떤 변수를 보내든 항상 `origin/develop`만 받는다
3. `docker build -t axcore-backend:<커밋 SHA> backend`
4. `scripts/pg-backup.sh` 1회 (마이그레이션 대비)
5. `.env`의 `BACKEND_TAG`를 새 SHA로 바꾸고 `docker compose -f /home/ubuntu/axcore/compose.yaml up -d backend`
6. 최대 90초 동안 `(healthy)` 대기. 실패하면 **직전 태그로 자동 롤백**하고 빌드를 실패로 표시
7. backend 이미지는 최근 3개 태그만 보관, 3일 지난 빌드 캐시 삭제
8. 결과는 GitLab 커밋 상태 + Mattermost DM(머지한 사람). 배포가 있으면 `backend 배포 <이전> → <새 SHA>` 또는 `롤백됨`이 붙는다

Jenkins 컨테이너는 가게 폴더를 같은 경로(`/home/ubuntu/axcore`)로 마운트한다. compose의 상대 경로(`./nginx/...`)가 호스트 기준으로 풀리므로 경로가 같아야 한다.

**손으로 롤백할 때** (자동 롤백이 안 됐거나, 배포 뒤 문제가 늦게 발견된 경우):

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
- batch 컨테이너는 같은 이미지를 `SPRING_PROFILES_ACTIVE=batch`로 띄운다 (ERP·MES 접속 정보 확정 후 추가)
- 헬스체크: `docker compose ps`에서 backend `(healthy)` 확인. 컨테이너 안에서 30초마다 `/actuator/health`를 두드리며, DB가 내려가면 약 90초 뒤 `(unhealthy)`로 바뀐다 (자동 재시작은 하지 않음. 5-7 배포 검증과 7-4 알림에서 사용)

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
