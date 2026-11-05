# Implement Workflow

먼저 [AI 협업 규칙](../rules/ai-collaboration.md), [코드 규칙](../rules/code.md), [아키텍처 규칙](../rules/architecture.md), [테스트 규칙](../rules/testing.md), [기준 문서](../references.md)를 읽는다. API 변경이 있으면 [API 규칙](../rules/api.md)을, DB Schema·Entity·Migration·Repository·SQL 변경이 있으면 [DB 규칙](../rules/database.md)을, 인프라·배포 설정 변경이 있으면 [인프라 규칙](../rules/infra.md)을 함께 읽는다.

## 실행 순서

1. 작업 대상을 확인한다. Jira 이슈 키와 기능 ID를 사용자 요청, Branch 이름, 이슈에서 찾는다. Jira를 읽을 수 있으면 이슈의 목적, 작업 내용, 완료 기준, 선행 작업을 확인한다. 이슈 번호를 확인할 수 없으면 추측하지 않고 묻는다. 이슈 없는 작업이면 그대로 진행한다.
2. 기준 문서를 확인한다. 기능 명세서에서 기능 ID의 범위와 담당을, API 명세서에서 경로·요청·응답·오류를, ERD에서 테이블과 제약 조건을 읽는다. 이슈, 명세, 기존 코드가 서로 다르면 임의로 고르지 않는다. 차이를 보고하고 결정을 받는다.
3. 작업 Branch를 확인한다. `git branch --show-current`와 `git status --short`로 현재 Branch와 미커밋 변경을 본다.
   - 새 작업이면 원격 `develop`의 최신 상태에서 [Git 규칙](../rules/git.md)의 이름 형식으로 Branch를 만든다.
   - 미커밋 변경이 있으면 옮기거나 버리지 않는다. 처리 방법을 먼저 확인한다.
   - `develop`과 `main`에서 직접 작업하지 않는다.
4. 구현 계획을 세운다. 바꿀 파일, 계층, 추가할 테스트를 정리한다.
   - 의존성 추가, DB Schema 변경, 기존 API 호환성을 깨는 변경, 인증·인가 구조 변경, 핵심 아키텍처 변경이 포함되면 실행 전에 멈춘다. 필요한 작업, 대상, 영향을 설명하고 승인을 받는다.
   - 사용자가 계획만 요청했으면 여기서 멈추고 계획을 보고한다.
5. 구현한다. 기존 구조와 코드 스타일을 우선한다. 요청 범위 밖의 리팩터링을 섞지 않는다.
6. 테스트를 작성한다. [테스트 규칙](../rules/testing.md)의 필수 범위와 C106 핵심 테스트 중 해당하는 항목을 확인한다. 버그 수정이면 가능하면 재현 테스트를 먼저 만든다.
7. 문서를 맞춘다.
   - API를 바꿨으면 API 명세서 수정이 필요하다고 보고하거나 함께 수정한다.
   - DB를 바꿨으면 ERD 수정이 필요하다고 보고하거나 함께 수정한다. Migration은 새 버전 파일로 추가한다.
   - 개발 가이드에 영향이 있으면 저장소·Notion 가이드와 관련 `.ai/rules/`를 함께 고친다.
8. 검증한다. 포맷 적용과 테스트는 [테스트 규칙](../rules/testing.md)과 프로젝트 설정에 있는 명령으로 실행한다. 실패하면 원인을 확인하고 고친다. 검증을 약화하거나 테스트를 Skip해서 통과시키지 않는다.
9. 결과를 보고한다. [AI 협업 규칙](../rules/ai-collaboration.md)의 결과 보고 항목에 더해, Jira 완료 기준 중 충족한 항목과 남은 항목을 구분한다.

## 작업 경계

구현 요청은 Commit, Push, MR, Jira 상태 변경의 승인이 아니다. 이 작업은 각각 요청이 있을 때 [Commit Workflow](commit.md), [Merge Request Workflow](merge-request.md), [Jira Issue Workflow](jira-issue.md)를 따른다.
