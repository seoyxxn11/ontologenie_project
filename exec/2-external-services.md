# 2. 외부 서비스

프로젝트가 쓰는 외부 서비스와 가입·설정에 필요한 정보입니다. 키·Webhook 주소 같은 비밀값은 Jenkins Credentials와 서버 `.env`에만 두고 저장소에는 넣지 않습니다(🔒).

## 2.1 실행 중에 쓰는 서비스

| 서비스 | 용도 | 설정 | 가입·발급 |
| --- | --- | --- | --- |
| **SSAFY GMS** (OpenAI 호환 API, `https://gms.ssafy.io/gmsapi/api.openai.com/v1`, Bearer 인증) | ① AI 분석 서버의 원천 분석·후보 제안(Responses API, 기본 모델 `gpt-6.1-sol`, 크레딧이 부족하면 `gpt-6-luna`) ② Agent 답변(Spring AI chat, 기본 `gpt-5.6-luna`) | 🔒 Jenkins `axcore-gms-api-key` → AI의 `AI_ONTOLOGY_LLM_API_KEY`와 backend의 `AXCORE_AGENT_LLM_API_KEY`. 현재 Compose의 Agent 주소는 GMS로 고정 | SSAFY 팀별 발급 키와 사용 가능한 크레딧을 준비 |
| **Mattermost** (Incoming Webhook) | ① Jenkins MR 검사 실패·배포 결과 DM ② 새 Action의 Handler 구현 요청을 팀 채널에 알림 ③ Grafana 알림 | 🔒 Jenkins `mm-webhook`, `axcore-action-notify-webhook-url` → `AXCORE_ACTION_NOTIFY_WEBHOOK_URL`. 알림 링크 주소 `AXCORE_ACTION_NOTIFY_SCREEN_BASE_URL`. Grafana는 UI에서 연락처 `axcore-mattermost`로 설정 | SSAFY Mattermost에서 Incoming Webhook 생성(Webhook 주소 자체가 비밀값) |
| **Let's Encrypt** (certbot webroot) | HTTPS 인증서. `k15c106.p.ssafy.io`(SAN `ontologenie.com`), `sonar.ontologenie.com` | `docker compose run --rm certbot certonly --webroot ...`(1장 1.5), 갱신 `scripts/renew-cert.sh`(cron 03:00) | 가입 불필요. 80 포트로 `/.well-known/acme-challenge/`에 접근할 수 있어야 함 |
| **도메인** | `ontologenie.com`(서비스 주소), `sonar.ontologenie.com`, `k15c106.p.ssafy.io`(SSAFY 제공) | 세 호스트가 메인 서버를 가리켜야 함. `ontologenie.com`·`sonar.ontologenie.com` A 레코드와 SSAFY 제공 호스트의 해석 결과 확인 | `ontologenie.com`은 가비아에서 구매·DNS 관리. `k15c106.p.ssafy.io`는 SSAFY 제공 |
| **AWS** | 메인 서버(Lightsail, Ubuntu 16GB), 원천 ERP·MES 서버(EC2 Amazon Linux 2023, t3a.medium 2대) | 원천 서버 보안 그룹과 `pg_hba.conf`는 메인 서버 IP만 허용 | SSAFY 제공 계정·서버 |

## 2.2 개발·배포에 쓰는 서비스

| 서비스 | 용도 | 설정 |
| --- | --- | --- |
| **SSAFY GitLab** (`https://lab.ssafy.com/s15-final/S15P31C106.git`) | 저장소, MR, Webhook으로 Jenkins 실행, 커밋·MR 상태 표시 | 🔒 Jenkins `gitlab-clone`(클론 토큰), GitLab Connection `ssafy-gitlab` |
| **Jenkins** (자체 호스팅, `/jenkins/`) | MR 검사 `axcore-mr-check`, 배포 `axcore-deploy`, `axcore-sonarqube` | `infra/jenkins/`, `Jenkinsfile` |
| **SonarQube** (자체 호스팅, `sonar.ontologenie.com`) | 코드 품질 분석 | 🔒 `axcore-sonarqube-*` 토큰 |
| **Docker Hub, quay.io** | 이미지 내려받기(postgres, nginx, kafka, debezium, grafana 등) | 가입 불필요 |
| **Maven Central, npm registry, PyPI** | 빌드 때 라이브러리 내려받기 | 가입 불필요 |
| **Jira** (`ssafy.atlassian.net`, 프로젝트 `S15P31C106`) | 작업 관리 | 실행에는 쓰지 않음 |
| **Notion** | 기능 명세, API 명세, 회의록 | 실행에는 쓰지 않음 |
| **Figma** | 화면 디자인 참고 | 실행에는 쓰지 않음 |

## 2.3 최초 가입·발급 후 연결 순서

1. **GMS**: 팀 계정으로 발급받은 키를 운영 담당자가 Jenkins Secret text `axcore-gms-api-key`에 등록합니다. 최초 환경 파일과 Credentials 값이 일치해야 배포 준비가 통과합니다. 분석은 `AI_ONTOLOGY_LLM_PROVIDER=gms`, Agent는 `AXCORE_AGENT_LLM=openai`를 사용합니다. 실제 분석·질문 전에 잔여 크레딧과 모델 접근 권한을 확인합니다. 원본·일반 설정·컨테이너 전달 관계는 [환경 변수](1-build-deploy.md#14-빌드실행-환경-변수)를 따릅니다.
2. **다른 LLM 서버**: 로컬 AI 서버는 `AI_ONTOLOGY_LLM_PROVIDER=openai-chat`과 주소·모델을 설정할 수 있습니다. 현재 운영 배포 준비 코드는 GMS 키가 `gms` 제공사·기본 GMS 주소에만 전달되도록 검사하므로, 운영 `.env.config`의 주소만 바꿔 다른 서버로 전환할 수 없습니다. 운영 전환은 별도 자격 증명·Compose·배포 검토 대상입니다.
3. **Mattermost**: 알림을 받을 팀·채널에서 Incoming Webhook을 발급합니다. Jenkins 결과용은 `mm-webhook`, Action 구현 요청용은 `axcore-action-notify-webhook-url`에 등록합니다. Jenkins DM의 대상 사용자 이름이 Mattermost 계정과 맞는지 시험하고, Grafana는 연락처 `axcore-mattermost`에 별도로 연결합니다. Webhook URL은 비밀값이므로 문서에 붙이지 않습니다.
4. **GitLab·Jenkins**: 저장소 읽기용 `gitlab-clone`, 상태 기록용 GitLab 연결, 세 Pipeline Job, Job별 Webhook 이벤트·Secret Token을 등록합니다. [Job과 Webhook 등록](1-build-deploy.md#jenkins-job과-webhook-등록)에 공통 SCM 값과 MR/배포 이벤트 구분이 있습니다. GitLab에 접근할 계정·토큰은 팀의 저장소 권한 안에서 발급합니다.
5. **DNS·Let's Encrypt**: DNS를 먼저 연결하고 외부에서 각 호스트의 80 포트 인증 경로가 보이는지 확인합니다. [최초 설치](1-build-deploy.md#처음-세팅-메인-서버)는 임시 HTTP 컨테이너로 메인용·Sonar용 인증서를 발급한 후 정식 nginx를 시작합니다. 기존 서버의 SAN 확장·갱신은 [서비스 도메인 추가](../infra/README.md#서비스-도메인-추가-278)를 따릅니다.
6. **SonarQube**: [최초 설치](1-build-deploy.md#sonarqube-최초-설치)에서 전용 DB 비밀번호, 관리자 계정, Private 프로젝트 3개, 분석 토큰, Jenkins `ENABLE_SONARQUBE`·`SONARQUBE_DASHBOARD_URL`을 연결합니다. 기존 설치 편입은 [SonarQube 운영 안내](../infra/sonarqube/README.md)를 따릅니다.
7. **AWS 원천 서버**: 새 호스트로 옮길 때는 `infra/source-db/pg_hba.conf`의 허용 메인 IP, 원천 보안 그룹, `infra/cdc/connectors/*.json`의 원천 주소를 새 환경에 맞춰 검토합니다. SSH는 관리자 접속 경로를 유지하고, 원천 DB 15432는 메인 서버에서 오는 지정 계정의 SSL 접속만 허용합니다. 계정 생성·합성 데이터 적재·publication 적용은 [원천 서버 절차](1-build-deploy.md#원천-erpmes-서버)를 따릅니다.

신규 외부 계정 가입, 도메인 구매, 토큰·인증서 발급은 운영 담당자가 각 서비스의 관리 화면에서 수행합니다. 저장소에는 발급값 대신 변수명과 Credentials ID만 남깁니다.

## 2.4 쓰지 않는 것

소셜 로그인, 결제, 지도, 푸시, 외부 스토리지 같은 서비스는 쓰지 않습니다. 로그인은 플랫폼 자체 계정(세션 기반)입니다.
