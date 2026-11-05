# Codex 프로젝트 지침

공통 규칙과 Workflow의 유일한 원본은 `.ai/`이다. 모든 경로는 이 파일이 있는 프로젝트 루트를 기준으로 해석한다.

- 모든 작업 전에 `.ai/rules/ai-collaboration.md`를 읽고 작업 권한을 따른다.
- 코드 수정 시 `.ai/rules/code.md`, `.ai/rules/architecture.md`를 읽는다.
- API 추가·변경 시 `.ai/rules/api.md`를 읽는다.
- DB Schema·Entity·Migration·Repository·SQL 변경 시 `.ai/rules/database.md`를 읽는다.
- 테스트 작성·실행 시 `.ai/rules/testing.md`를 읽는다.
- Jira 이슈 작성·수정 시 `.ai/rules/jira.md`를 읽는다.
- 인프라·배포 설정(`infra/`, `Jenkinsfile`, `Dockerfile`, compose 파일) 변경 시 `.ai/rules/infra.md`를 읽는다.
- 기능 명세서, API 명세서, ERD 같은 팀 문서의 위치는 `.ai/references.md`에서 확인한다.
- 팀 컨벤션 전문은 `docs/development-guide.md`이다. `.ai/rules/`와 내용이 다르면 임의로 고르지 말고 차이를 보고한다.
- 프로젝트에서 확정하지 않은 규칙을 임의로 만들지 않는다.
- 실행하지 않은 테스트를 통과했다고 표현하지 않는다. 요청과 관련 없는 변경을 하지 않는다.
- 비밀번호, 토큰, 실제 민감 데이터를 외부 도구나 서비스에 보내지 않는다. API나 DB를 바꾸면 OpenAPI 명세와 ERD 수정을 함께 하거나 보고한다.
- 일반 수정 요청을 Commit, Push, 원격 MR 변경의 승인으로 해석하지 않는다. 사용자 승인 없는 위험한 작업은 실행하지 않는다. Merge와 운영 작업은 사람의 최종 통제를 따른다.

관련 요청이 발생하면 아래 **프로젝트 로컬 Skill의 `SKILL.md`를 실제로 읽고 사용**한다. 동일 이름의 다른 Skill 대신 아래 경로를 선택한다. 상세 Workflow를 시작할 때 모두 로드하지 않는다.

| 요청 | Skill 경로 | 공통 문서 |
| --- | --- | --- |
| Commit Message 작성·실제 Commit | `.agents/skills/commit/SKILL.md` | `.ai/workflows/commit.md`, `.ai/rules/git.md` |
| MR 설명·검토 준비·원격 생성 또는 수정 | `.agents/skills/merge-request/SKILL.md` | `.ai/workflows/merge-request.md`, `.ai/rules/git.md` |
| 코드·변경사항·MR 리뷰 | `.agents/skills/code-review/SKILL.md` | `.ai/workflows/code-review.md`, `.ai/rules/code.md`, `.ai/rules/architecture.md`, `.ai/rules/testing.md` |
| 기능 구현·버그 수정·코드 변경 | `.agents/skills/implement/SKILL.md` | `.ai/workflows/implement.md`, `.ai/rules/code.md`, `.ai/rules/architecture.md`, `.ai/rules/testing.md`, `.ai/references.md` |
| Jira 이슈 초안·생성·수정 | `.agents/skills/jira-issue/SKILL.md` | `.ai/workflows/jira-issue.md`, `.ai/rules/jira.md`, `.ai/references.md` |
