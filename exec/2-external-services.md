# 2. 외부 서비스

프로젝트가 쓰는 외부 서비스와 가입·설정에 필요한 정보입니다. 키·Webhook 주소 같은 비밀값은 Jenkins Credentials와 서버 `.env`에만 두고 저장소에는 넣지 않습니다(🔒).

## 2.1 실행 중에 쓰는 서비스

| 서비스 | 용도 | 설정 | 가입·발급 |
| --- | --- | --- | --- |
| **SSAFY GMS** (OpenAI 호환 API, `https://gms.ssafy.io/gmsapi/api.openai.com/v1`, Bearer 인증) | ① AI 분석 서버의 원천 분석·후보 제안(Responses API, 기본 모델 `gpt-6.1-sol`, 크레딧이 부족하면 `gpt-6-luna`) ② Agent 답변(Spring AI chat, 기본 `gpt-5.6-luna`) | 🔒 Jenkins `axcore-gms-api-key` → `AI_ONTOLOGY_LLM_API_KEY`, `AXCORE_AGENT_LLM_API_KEY`. 주소 `AI_ONTOLOGY_LLM_BASE_URL`, `AXCORE_AGENT_LLM_BASE_URL`. 모델 `AI_ONTOLOGY_LLM_MODEL`, `AXCORE_AGENT_LLM_MODEL` | SSAFY에서 팀별로 발급(크레딧 단위 과금). 다른 OpenAI 호환 서버(vLLM, Ollama 등)를 쓰려면 `AI_ONTOLOGY_LLM_PROVIDER=openai-chat`과 주소·모델을 바꿉니다 |
| **Mattermost** (Incoming Webhook) | ① Jenkins MR 검사 실패·배포 결과 DM ② 새 Action의 Handler 구현 요청을 팀 채널에 알림 ③ Grafana 알림 | 🔒 Jenkins `mm-webhook`, `axcore-action-notify-webhook-url` → `AXCORE_ACTION_NOTIFY_WEBHOOK_URL`. 알림 링크 주소 `AXCORE_ACTION_NOTIFY_SCREEN_BASE_URL`. Grafana는 UI에서 연락처 `axcore-mattermost`로 설정 | SSAFY Mattermost에서 Incoming Webhook 생성(Webhook 주소 자체가 비밀값) |
| **Let's Encrypt** (certbot webroot) | HTTPS 인증서. `k15c106.p.ssafy.io`(SAN `ontologenie.com`), `sonar.ontologenie.com` | `docker compose run --rm -T certbot certonly --webroot ...`(1장 1.5), 갱신 `scripts/renew-cert.sh`(cron 03:00) | 가입 불필요. 80 포트로 `/.well-known/acme-challenge/`에 접근할 수 있어야 함 |
| **도메인** | `ontologenie.com`(서비스 주소), `sonar.ontologenie.com`, `k15c106.p.ssafy.io`(SSAFY 제공) | `ontologenie.com` A 레코드 → 메인 서버 IP | `ontologenie.com`은 가비아에서 구매·DNS 관리. `k15c106.p.ssafy.io`는 SSAFY 제공 |
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

## 2.3 쓰지 않는 것

소셜 로그인, 결제, 지도, 푸시, 외부 스토리지 같은 서비스는 쓰지 않습니다. 로그인은 플랫폼 자체 계정(세션 기반)입니다.
