# AI 기반 온톨로지 레이어 구축 및 운영 플랫폼

> 서로 다른 업무 시스템의 데이터를 하나의 의미 체계로 연결하고, AI가 근거를 바탕으로 정보를 조회하고 승인된 업무 Action을 실행할 수 있도록 하는 플랫폼

**SSAFY 기업연계 프로젝트 | 협력기업: 에이엑스코어 | MVP 구현·배포 완료, 통합 검증 중**

## 1. 프로젝트 소개

ERP, MES, 현장 메모처럼 서로 다른 시스템에 저장된 데이터는 식별자와 표현 방식이 달라 하나의 업무 맥락으로 파악하기 어렵습니다. 기존의 단순 조회나 대시보드에서 나아가, 데이터 간 의미적 관계를 정의하고 실제 업무 실행까지 연결하는 **온톨로지 기반 운영 플랫폼**을 개발합니다.

제조 현장(프레스 공정)의 ERP·MES와 현장 메모를 대상으로 합니다. 시연용 원천 데이터는 기업 예시 데이터의 구조를 참고해 팀이 새로 만들었습니다([ai/data](ai/data)).

### 핵심 목표

- **데이터 통합:** 원천 시스템의 스키마와 식별자 차이를 분석하고 업무 객체 간 관계를 연결합니다.
- **온톨로지 관리:** 객체 유형, 속성, 관계 유형, 원천 매핑, 연결 규칙, Action 정의를 메타데이터로 관리합니다.
- **AI 제안과 사람의 승인:** AI가 객체·속성·관계·연결 규칙 후보를 근거 및 신뢰도와 함께 제안하고, 관리자가 검토한 후 반영합니다.
- **안전한 업무 실행:** AI Agent는 허용된 조회 Tool과 Action만 사용하며, 데이터 변경은 Action Handler의 유효성·권한 검증 및 감사 기록을 거칩니다.

### 범위

- AI는 원천 데이터를 분석해 객체·속성·관계 후보와 ERP·MES 사이의 연결 규칙 후보를 제안합니다. 과거 데이터를 바탕으로 한 추론 제안도 포함합니다.
- 정형 데이터뿐 아니라 현장 메모 같은 비정형 자료에서도 정보를 추출합니다.
- 원천 데이터의 정합성 오류는 플랫폼이 찾아서 원천 시스템 측에 전달합니다. 실제 수정은 원천 시스템 담당 업체가 합니다.
- 원천 시스템은 읽기만 합니다. 플랫폼에서 만든 결과(예: 재배정 계획)는 플랫폼 안에 저장합니다.
- 실제 운영 중인 ERP·MES 환경을 가정해 설계합니다. 원천 DB 부하와 개인정보 보호를 고려합니다.

## 2. 주요 기능

기능은 최종 시나리오 기준으로 묶었습니다. 기능별 상세와 담당은 [기능 명세서](https://app.notion.com/p/3ffbdf14d2d88041be0ef1898eda8a60)에서 관리합니다.

| 시나리오 | 내용 | 주요 기능 | 범위 |
| --- | --- | --- | --- |
| S1 | 원천 데이터 연결과 AI 분석 | 원천 DB 연결 등록·확인, 분석 범위 지정, 정형·비정형 분석, 후보와 근거·신뢰도 생성 | MVP |
| S2 | 제안 검토·정합성 확인·온톨로지 적용 | 후보 검토, 규칙 시험 적용, 승인·거절·보류, 승인 즉시 반영, 정의 직접 편집 | MVP |
| S3 | 객체 탐색과 Agent 업무 조회 | 객체 검색·상세·관계 탐색, 자연어 질문, 출처와 기준 시점을 담은 답변 | MVP |
| S4 | 작업지시 재배정 계획 생성 | 재배정 대상 조회, 대체 설비 검토, Action 검증 후 계획 저장 | MVP |
| S5 | 정합성 문제 확인과 재검증 | 문제 목록·근거, 원천 측 전달 기록, 수정 자료 수집, 변경분 재분석·재검증 | MVP |
| S6 | 원천 변경에 따른 모델 확장 | 원천에 새로 생긴 테이블·컬럼을 모델에 반영 | 보류 |
| S7 | 계정·권한 관리 | 로그인, 계정·역할 확인과 변경, 전체 실행 이력 조회 | MVP |
| 공통 | 수집·감사 | 원천 수집 배치(주기·수동 실행), Action 정의 관리, 동작·감사 기록 | MVP |

사용자 역할은 `ADMIN`, `PLANNER`, `MAINTENANCE`, `VIEWER` 4개입니다.

| 역할 | 하는 일 | 조회 범위 |
| --- | --- | --- |
| `ADMIN` | 원천·수집 주기·온톨로지 정의·제안 검토·미연결 문제 수동 연결·계정·Action 정의 관리, 재배정 계획 생성 | 운영 그룹 전체. 민감 값은 보지 않음 |
| `PLANNER` | 객체 조회, Agent 질문, 재배정 계획 생성 | 운영 그룹 전체와 민감 값 |
| `VIEWER` | 객체 조회, Agent 질문 | 운영 그룹 전체. 민감 값은 보지 않음 |
| `MAINTENANCE` | 미구성 역할. 권한과 조회 범위가 없음 | 없음 |

조회 범위는 객체 유형의 그룹과 역할별 허용 행(`role_data_scope`)으로 정합니다. 그룹이 없는 객체 유형은 관리자에게만 보이고, 다른 역할과 AI 업무 도우미에는 그룹을 지정해야 보입니다. 외부 LLM으로 보내는 값은 역할과 관계없이 민감 값을 가립니다. 권한 코드와 역할별 부여는 Flyway 초기 데이터(V13·V21·V28)에 있고, 자세한 기준은 [docs/data-access.md](docs/data-access.md)에 있습니다.

## 3. 시스템 구성

```text
                     사용자 (브라우저)
                            | HTTPS
                            v
              nginx ---- React (사용자 화면 · 관리자 화면)
                            | /api
                            v
   +------------------ Spring Boot ------------------+
   |  원천 연결 · 온톨로지 정의 · 원천 매핑            |   분석 요청
   |  제안 검토 · 승인 반영 · 정합성 검사              | -------------> FastAPI (AI 분석·제안) --> LLM
   |  Action Dispatcher / Handler · 감사 기록         | <-------------   후보 + 근거 + 신뢰도
   |  인증 · 권한                                     |
   |  AI Agent (Spring AI) ---------------------------+---> LLM
   +--------------------------------------------------+
            | 읽기·쓰기                    | 읽기 전용 (연결 확인 · 표본)
            v                              v
   플랫폼 DB (PostgreSQL)             원천 ERP · MES (PostgreSQL)
            ^                              ^
            | upsert                       | WAL (로그 기반 CDC)
            |                              v
            +-- 수집 배치 <-- Kafka <-- Kafka Connect(Debezium)
```

- **원천 연결:** 원천 ERP·MES의 PostgreSQL에 직접 접속합니다. 플랫폼은 조회 전용 계정, Debezium은 CDC 복제 계정을 씁니다. 원천에는 변경 이력 테이블·트리거를 만들지 않습니다. 별도의 REST API는 두지 않습니다.
- **수집 배치:** 별도 컨테이너에서 Kafka 토픽의 원천 변경 이벤트를 계속 읽어 반영합니다. 매핑이 바뀐 테이블과 뷰는 전체를 읽어 저장된 값과 비교합니다.
- **AI 분석:** FastAPI는 분석·제안 결과를 Spring Boot에 돌려줄 뿐 플랫폼 DB에 직접 쓰지 않습니다.
- **AI Agent:** Spring Boot 안에서 Spring AI로 구현합니다. Agent가 쓸 수 있는 Tool은 조회와 재배정 계획 생성으로 제한합니다.

### 설계 원칙

1. **메타모델과 인스턴스를 구분합니다.** 예를 들어 `설비`는 객체 유형이고, 원천에서 읽어 온 개별 설비는 그 유형의 객체 인스턴스입니다.
2. **AI의 제안은 자동 반영하지 않습니다.** 후보는 검토 대상으로 저장하고, 관리자가 승인하면 즉시 온톨로지에 반영합니다.
3. **Action 정의와 실행 코드를 분리합니다.** `ActionDefinition`은 실행 가능한 업무의 메타데이터이고, `ActionHandler`는 실제 비즈니스 로직을 수행합니다.
4. **Agent가 데이터 변경 로직을 우회하지 않도록 합니다.** 변경성 요청은 Action Dispatcher와 Handler를 통해 실행하고 실행 결과를 감사 기록으로 남깁니다.
5. **원천 시스템은 바꾸지 않습니다.** 원천 DB는 읽기만 하고, 원천에서 사라진 행도 지우지 않고 상태로 표시합니다.
6. **플랫폼 DB에 쓰는 곳을 제한합니다.** 쓰기는 Spring Boot(Handler)와 수집 배치만 합니다.

### CI/CD

```text
기능 브랜치 → MR → develop 병합 → GitLab Webhook → Jenkins
  → 바뀐 서비스만 빌드 → Docker 이미지 생성 → Docker Compose 배포 → 헬스체크
```

Jenkins Job 3개가 같은 `Jenkinsfile`을 씁니다.

- `axcore-mr-check`: MR을 검사합니다.
- `axcore-deploy`: develop에 병합되면 바뀐 서비스(backend·AI·frontend)만 배포합니다.
- `axcore-sonarqube`: develop의 backend·AI·frontend를 메인 서버의 SonarQube로 분석합니다([infra/sonarqube](infra/sonarqube/README.md)).

결과는 GitLab 커밋 상태와 Mattermost DM으로 알립니다.

## 4. 주요 데이터 모델

[온톨로지 MVP ERD (v2)](https://app.notion.com/p/402bdf14d2d880048f4fd8ee1f1d540b) 기준입니다(2026-10-26 확정). 실제 테이블의 원본은 [Flyway migration](backend/src/main/resources/db/migration)입니다. 모든 테이블은 PostgreSQL 스키마 `platform` 하나에 두고, 단일 ID PK는 `bigint`를 사용합니다. Spring Session·Spring Batch 테이블은 아래 목록에 넣지 않습니다.

**온톨로지 테이블 (22개)**

| 구분 | 테이블 | 설명 |
| --- | --- | --- |
| 정의 | `object_type`, `property_definition`, `link_type` | 객체 유형, 속성, 관계 유형 |
| 원천 | `data_source`, `data_source_credential`, `source_resource`, `source_column` | 원천 시스템, 암호화된 접속 정보, 원천의 표·문서, 컬럼 목록 |
| 원천 작업 | `source_connection_check`, `source_preview` | 연결 검사, 표본 조회 |
| 매핑 | `object_source_mapping`, `property_source_mapping` | 원천 행·컬럼을 객체·속성으로 바꾸는 방법 |
| 연결 규칙 | `link_mapping`, `link_mapping_condition` | 객체끼리 연결하는 규칙. 바뀔 때마다 새 개정본으로 보관 |
| 인스턴스 | `object_instance`, `object_link` | 실제 객체와 객체 간 연결 |
| Action | `action_definition` | 실행 가능한 업무, 입력 형식, 권한, Handler, 확인 창 여부 |
| 수집 | `sync_run`, `sync_run_resource`, `cdc_offset` | 원천 수집 실행 기록, 표별 진행, CDC 소비 위치 |
| 수집 보조 | `source_delete_mark`, `source_load_failure`, `link_check_pending` | 객체가 없는 원천 행의 삭제 흔적, 객체로 만들지 못한 원천 행·값 변환 실패, 원천 사이 미연결을 문제로 올리기 전 확인 대기 |

**운영 테이블 (23개)**

| 영역 | 내용 |
| --- | --- |
| 계정·권한 | 계정, 역할, 권한, 역할별 데이터 범위 |
| 분석 실행 | AI 분석 실행과 대상 |
| 제안·검토 | 제안 항목, 개정, 후보, 검토 결정 |
| 정합성 | 검사 실행·결과, 정합성 문제와 재검증·이력 |
| 실행·감사 | Action·Agent 조회·작업 실행 기록, 변경 전후 기록, 민감에서 내린 속성 기록 |
| Agent | 대화, 메시지 |
| 재배정 계획 | 재배정 계획과 계획 항목 |

> 메모 원문 저장 방식, AI별 Action 구분, API의 ID 표현은 아직 정하지 않았습니다. ERD 문서의 미정 항목에서 관리합니다.

## 5. 기술 스택

| 영역 | 기술 | 상태 |
| --- | --- | --- |
| Backend | Java 21, Spring Boot 4.1, Spring Data JPA, Flyway, Spring Security(세션 인증), Spring Session JDBC | 확정 |
| Batch | Spring Batch 6 (별도 컨테이너) | 확정 |
| 원천 CDC | Debezium 3.6 (Kafka Connect), Kafka 4.2 (KRaft 단일 노드), Redpanda Console | 확정 (2026-10-30). 인프라 기동 2026-11-02 |
| AI Agent | Spring AI 2.x | 확정 |
| AI 분석·제안 | Python, FastAPI, Pydantic, pytest, Ruff | 확정 |
| LLM | SSAFY GMS 제공 모델 | AI 분석·제안과 Agent에 사용. Agent 기본 모델은 `gpt-5.6-luna`([선정 근거](docs/agent-model-evaluation.md)) |
| Frontend | React 19, TypeScript, Vite, React Router, TanStack Query, Tailwind CSS, ESLint, Prettier | 확정 |
| Database | PostgreSQL 18 | 확정 |
| API 문서 | OpenAPI 3.1, Swagger | 확정 |
| Infra | AWS Lightsail, Docker Compose, nginx, Let's Encrypt, Jenkins, SonarQube | 확정 |
| 협업 | SSAFY GitLab(모노레포), Jira, Notion | 확정 |
| 모니터링 | Spring Boot Actuator, Prometheus, Grafana Alloy, Loki, Grafana | 확정 |
| 배포 알림 | Mattermost DM | 확정 |

캐시(Redis)는 쓰지 않습니다. Kafka는 원천 변경 수집(CDC)에만 쓰고, 오래 걸리는 작업은 지금처럼 DB의 실행 기록 테이블로 넘깁니다. 백엔드 세부 스택은 [개발 가이드 6.2](docs/development-guide.md#62-백엔드-기술-스택)에 있습니다.

## 6. 사용자 시나리오 (예시)

설비가 고장 나 작업이 멈췄을 때, 생산 계획 담당자가 Agent에게 재배정을 요청하는 흐름입니다(시나리오 S4).

**질문:** “고장 난 설비에 걸려 있는 작업지시를 다른 설비로 옮길 수 있는지 확인하고, 가능하면 재배정 계획을 만들어 줘.”

1. AI Agent가 온톨로지 조회 Tool로 해당 설비의 작업지시와 관련 수주·공정을 찾습니다.
2. 공정 조건을 만족하는 대체 설비를 찾아, 근거와 함께 재배정 계획을 제안합니다.
3. 사용자가 요청하면 Agent가 재배정 계획 생성 Action을 호출합니다.
4. Backend가 실행 권한, 설비 조건, 작업지시 상태를 검증합니다.
5. Action Handler가 계획을 플랫폼 DB에 저장하고 감사 기록을 남깁니다. 원천 MES의 배정은 바꾸지 않습니다.

## 7. 팀 구성

| 이름 | 영역 | 주요 책임 |
| --- | --- | --- |
| 김산이 | Backend | Action 실행·검증, 권한, 감사 기록, 수집 배치 |
| 이주헌 | Backend | 온톨로지 정의, 원천 매핑, 연결 규칙, 정합성 검사·문제 관리 |
| 정유림 | Backend | 제안 검토·승인·반영, 규칙 시험 적용·재검증, 객체 탐색 조회 |
| 김서연 | AI | 원천 연결, AI 분석·제안, 시연 데이터·평가 |
| 장민화 | AI Agent · Frontend | AI Agent, 사용자 화면 |
| 손주연 | Frontend · Infra | 관리자 화면, 서버·배포 환경 |

## 8. 개발 계획

- [x] 최종 시나리오와 기능 명세 정리
- [x] 온톨로지 ERD 확정 (v2)
- [x] 팀 개발 가이드 확정
- [x] 메인 서버 HTTPS·Jenkins 구성
- [x] 공통 개발 기반 구축
- [x] 인증·계정·권한 관리
- [x] 원천 연결·AI 분석 및 제안 생성
- [x] 온톨로지 정의·원천 매핑 관리
- [x] 제안 검토·정합성 검사·승인 반영
- [x] 원천 수집(CDC)·정합성 문제·재검증
- [x] 객체 탐색·상세 조회
- [x] 업무 Agent·대화 관리
- [x] Action 실행·작업지시 재배정 계획
- [x] 감사·실행 이력 추적
- [ ] 시연 데이터·통합 검증·산출물

체크는 develop에 구현되어 메인 서버에 배포된 기준입니다(2026-11-16). 남은 개선과 세부 작업은 Jira 프로젝트 `S15P31C106`의 에픽 아래에서 관리합니다.

## 9. 저장소 구조와 실행 방법

```text
/
├── backend/       # Spring Boot 백엔드 (API 서버·수집 배치 공용)
├── ai/            # FastAPI AI 분석 서비스, 시연 데이터·정답셋
├── frontend/      # React 웹 화면
├── docs/          # 개발 가이드, 기획 자료, 기능별 설계 메모
├── infra/         # 배포 설정 (nginx, certbot, Jenkins, PostgreSQL, 모니터링, 원천 DB·CDC)
├── .gitlab/       # MR 템플릿
├── .ai/           # AI 도구 공통 규칙
└── Jenkinsfile    # Jenkins 파이프라인
```

AI 서비스와 프론트엔드 실행 방법은 [ai/README.md](ai/README.md), [frontend/README.md](frontend/README.md)에 있습니다.

**Backend 실행** (`backend/`, JDK 21·Docker 필요)

```bash
./gradlew build                                                   # 빌드·테스트·포맷 검사
./gradlew bootTestRun --args='--spring.profiles.active=api'       # 로컬 DB 없이 실행 (Testcontainers)
```

실행 모드는 프로필로 고릅니다. `api`는 웹 서버, `batch`는 수집 배치 컨테이너입니다. 필요한 환경변수는 [backend/.env.example](backend/.env.example)에 있습니다.

LLM API Key, DB 비밀번호 같은 민감 정보는 저장소에 커밋하지 않습니다. 서버 `.env`와 Jenkins Credentials로만 관리하고, `.env.example`에는 변수 이름과 설명만 둡니다.

## 10. 문서 안내

| 문서 | 위치 |
| --- | --- |
| 개발 가이드 (브랜치·Commit·MR·코드 규칙) | [docs/development-guide.md](docs/development-guide.md) |
| 인프라 설정 | [infra/README.md](infra/README.md) |
| AI 서비스·데이터셋 | [ai/README.md](ai/README.md) |
| 프론트엔드 | [frontend/README.md](frontend/README.md) |
| 시스템 구조 | [Notion](https://app.notion.com/p/3f1bdf14d2d88111b3f1d420ead2dce1) |
| 최종 시나리오 | [Notion](https://app.notion.com/p/3ffbdf14d2d88054aba3d559f0f989d6) |
| 기능 명세서 (최종본) | [Notion](https://app.notion.com/p/3ffbdf14d2d88041be0ef1898eda8a60) |
| ERD | [Notion](https://app.notion.com/p/3ffbdf14d2d880fc95bafcb1c85df893) |
| API 명세서 | [Notion](https://app.notion.com/p/3ffbdf14d2d88087a083ec2d51922bcc) |

작업을 시작하기 전에 개발 가이드의 브랜치·Commit·MR 규칙(2~4절)을 확인합니다.

---

> 본 저장소의 README는 개발 진행에 따라 실제 구현 범위, 실행 방법 및 시연 결과를 지속적으로 갱신합니다.
