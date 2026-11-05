# 기준 문서

AI 작업에서 확인할 팀 문서의 위치다. 저장소 밖 문서는 Notion과 Jira에 있다.

- Notion이나 Jira를 읽을 도구가 없으면 내용을 추측하지 않는다. 필요한 내용을 사용자에게 요청한다.
- 문서끼리 내용이 다르면 임의로 고르지 않는다. 차이를 보고하고 사람의 결정을 받는다.
- API 계약은 API 명세서가 [API 규칙](rules/api.md)보다 우선한다.

## 저장소

| 문서 | 위치 | 쓰임 |
| --- | --- | --- |
| 개발 가이드 | [docs/development-guide.md](../docs/development-guide.md) | 팀 컨벤션 전문 |
| AI 규칙·절차 | [rules/](rules/), [workflows/](workflows/) | AI 작업 규칙과 실행 절차 |
| 인프라 설정 | [infra/README.md](../infra/README.md) | 메인 서버 배포 설정 사본과 적용 방법 |
| MR 템플릿 | [.gitlab/merge_request_templates/Default.md](../.gitlab/merge_request_templates/Default.md) | MR 본문 형식 |

## Notion (C106 - 에이엑스코어)

| 문서 | 링크 | 쓰임 |
| --- | --- | --- |
| 기능 명세서 (최종본) | [기능 명세서](https://app.notion.com/p/3ffbdf14d2d88041be0ef1898eda8a60) | 기능 ID(`FN-...`), 담당, MVP 범위 |
| 최종 시나리오 | [최종 시나리오](https://app.notion.com/p/3ffbdf14d2d88054aba3d559f0f989d6) | 시나리오 S1~S7의 업무 흐름 |
| API 명세서 | [API 명세서](https://app.notion.com/p/3ffbdf14d2d88087a083ec2d51922bcc) · [C106 API 관리 DB](https://app.notion.com/p/fb5c0ebaff7d446991d1c5adb0fdc5a8) | API별 경로·요청·응답·오류 |
| ERD | [ERD](https://app.notion.com/p/3ffbdf14d2d880fc95bafcb1c85df893) · [온톨로지 MVP ERD v2](https://app.notion.com/p/402bdf14d2d880048f4fd8ee1f1d540b) · [운영 ERD v1](https://app.notion.com/p/402bdf14d2d880d19014c967ea8f6fa3) | 테이블·제약 조건. DDL(`ddl.sql`, `ddl_ops.sql`)은 각 페이지에 첨부 |
| 시스템 구조 | [시스템 구조](https://app.notion.com/p/3f1bdf14d2d88111b3f1d420ead2dce1) | 서비스 구성, CI/CD 흐름 |
| 서버 작업 주의사항 | [서버 작업 주의사항](https://app.notion.com/p/401bdf14d2d88073bd5ed8af56a8f2b0) | 서버·Docker 금지 사항 ([인프라 규칙](rules/infra.md)의 원본) |
| Infra 할 일 | [할 일 리스트](https://app.notion.com/p/401bdf14d2d8808ba767ecbb8e0c9fec) | 인프라 작업 단계와 완료 기준 |
| 조회 쿼리 기준·분류표 | [조회 쿼리 작성 기준 초안과 JdbcClient 사용처 분류](https://app.notion.com/p/40fbdf14d2d881e9b6ebd7eb362699e9) | S15P31C106-294. JdbcClient 사용처 30개 클래스 분류(유지·개선·판단 필요) |
| 개발 가이드 (Notion 사본) | [C106 개발 가이드](https://app.notion.com/p/405bdf14d2d881229c95e18f0ceac2bc) | 저장소 가이드와 같은 내용 |

## Jira

사이트는 `ssafy.atlassian.net`, 프로젝트 키는 `S15P31C106`이다. 작성 규칙은 [Jira 규칙](rules/jira.md)을 따른다.

## 확인 필요

- OpenAPI 3.1 원본 파일의 위치. 지금은 Notion API 관리 DB에 API별로 정리되어 있다.
