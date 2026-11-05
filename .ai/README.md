# C106 AI 협업 문서

Claude Code와 Codex가 같은 규칙과 Workflow를 쓰도록 만든 프로젝트 로컬 문서다. 공통 템플릿에 [C106 개발 가이드](../docs/development-guide.md)의 팀 결정을 반영했다.

## 구성

| 위치 | 역할 |
| --- | --- |
| `AGENTS.md` | Codex 진입점과 작업별 Skill 연결 |
| `CLAUDE.md` | Claude Code 진입점과 작업별 Skill 연결 |
| `.ai/rules/` | 공통 개발 규칙과 AI 작업 권한 (원본) |
| `.ai/workflows/` | Commit, MR, Code Review, 기능 구현, Jira 이슈 실행 절차 (원본) |
| `.ai/references.md` | 기능 명세서, API 명세서, ERD 등 팀 문서의 위치 |
| `.claude/skills/` | Claude Code용 Skill. `.ai/` 문서만 가리킨다. |
| `.agents/skills/` | Codex용 Skill. `.ai/` 문서만 가리킨다. |
| `.gitlab/merge_request_templates/` | GitLab MR 본문 형식 |
| `docs/development-guide.md` | 팀 개발 가이드 전문. Notion에도 같은 내용을 둔다. |

## 원본 관리

- 팀 컨벤션의 결정은 개발 가이드에 기록한다. Notion과 저장소의 가이드를 함께 수정한다.
- `.ai/rules/`에는 가이드 중 AI 작업에 필요한 규칙만 옮긴다. 가이드를 바꾸면 관련 규칙 문서도 같은 MR에서 수정한다.
- [인프라 규칙](rules/infra.md)은 개발 가이드와 Notion 「서버 작업 주의사항」을 함께 원본으로 둔다. 주의사항이 바뀌면 인프라 규칙도 확인한다.
- Notion·Jira 문서의 위치가 바뀌면 [기준 문서](references.md)를 고친다.
- AI 규칙과 Workflow는 `.ai/`에서만 수정한다.
- 진입점과 Skill에는 상세 규칙을 복사하지 않는다.
- Claude Code는 `CLAUDE.md`에서 [AI 협업 규칙](rules/ai-collaboration.md)만 import한다. 나머지 문서는 관련 작업 시 읽는다.
- Codex는 import를 지원하지 않는다. 그래서 `AGENTS.md`에 핵심 원칙 요약을 둔다. AI 협업 규칙의 핵심 원칙을 바꾸면 이 요약도 함께 확인한다.
- GitLab Template은 MR 출력 형식이며 승인 정책의 원본이 아니다.

## 사용 방법

1. 새 프로젝트에 숨김 폴더를 포함해 전체 구조를 복사하고 팀 Git 저장소에 포함한다.
2. 프로젝트 루트에서 Claude Code 또는 Codex를 시작한다. 별도 Skill 설치는 필요 없다.
3. 작업을 요청한다. 관련 요청이 있을 때만 Skill과 Workflow를 읽는다.
4. Skill이 자동으로 선택되지 않으면 명시적으로 호출한다.

| 작업 | Claude Code | Codex CLI·IDE |
| --- | --- | --- |
| Commit | `/commit 현재 변경사항의 Commit Message를 추천해줘.` | `$commit 현재 변경사항의 Commit Message를 추천해줘.` |
| MR | `/merge-request 현재 Branch의 MR 설명 초안을 작성해줘.` | `$merge-request 현재 Branch의 MR 설명 초안을 작성해줘.` |
| 리뷰 | `/code-review 지정한 Target Branch와의 변경사항을 리뷰해줘.` | `$code-review 지정한 Target Branch와의 변경사항을 리뷰해줘.` |
| 구현 | `/implement S15P31C106-123 이슈를 구현해줘.` | `$implement S15P31C106-123 이슈를 구현해줘.` |
| Jira 이슈 | `/jira-issue FN-ACT-04 작업의 이슈 초안을 작성해줘.` | `$jira-issue FN-ACT-04 작업의 이슈 초안을 작성해줘.` |

Codex에서는 `/skills`로도 선택할 수 있다. 같은 이름의 개인·관리자·플러그인 Skill이 있으면 이 저장소의 Skill이 선택됐는지 확인한다. Skill이 목록에 없으면 프로젝트 경로를 확인하고 새 세션을 시작한다.

Skill 호출은 Commit·Push·원격 MR 작업의 승인이 아니다. 요청 문장에 따라 수행 범위가 달라진다.

| 요청 예시 | 수행 범위 |
| --- | --- |
| "Commit Message 추천해줘." | 메시지 초안만 작성 |
| "프로젝트 규칙에 따라 커밋해줘." | 실제 Commit. Push는 별도 요청 필요 |
| "현재 Branch의 MR 설명을 작성해줘." | 코드 리뷰 후 본문 초안 작성. 원격 MR은 만들지 않음 |
| "이 초안으로 원격 MR을 생성해줘." | 저장소와 Source/Target Branch 확인 후 원격 생성. Critical·Major 리뷰 항목이 있으면 생성 전에 확인 요청 |
| "Target Branch와 비교해서 리뷰해줘." | 리뷰 결과 보고. 코드 수정과 원격 댓글 게시는 하지 않음 |
| "이 이슈 구현 계획만 세워줘." | 계획만 보고. 코드 수정은 하지 않음 |
| "이 이슈를 구현해줘." | Branch 확인·구현·테스트·결과 보고. Commit·Push·MR은 별도 요청 필요 |
| "이슈 초안 작성해줘." | 초안만 작성. Jira에 반영하지 않음 |
| "이슈 만들어줘." | 중복 검색과 보고자·담당자 확인 후 Jira에 생성 |

## 팀 초기 설정

각 규칙 문서의 `Team Decision Needed`에 남은 미결정 항목을 팀 회의에서 정한다. 결정 내용은 개발 가이드와 해당 규칙 문서에 함께 기록한다. 합의 전까지는 미결정으로 둔다.

- [Git 규칙](rules/git.md)
- [코드 규칙](rules/code.md)
- [아키텍처 규칙](rules/architecture.md)
- [API 규칙](rules/api.md)
- [DB 규칙](rules/database.md)
- [테스트 규칙](rules/testing.md)
- [Jira 규칙](rules/jira.md)
- [인프라 규칙](rules/infra.md) (`확인 필요` 항목)
- [기준 문서](references.md) (`확인 필요` 항목)

서비스 골격을 만들면 실제 테스트 명령과 실행 환경을 테스트 규칙에 기록한다.

## 제한

- 문서의 승인 기준은 AI 작업 지침이다. 도구나 서버가 강제하는 접근 통제가 아니다.
- 파일만으로 CI, MR 자동 리뷰, Merge 차단, 배포가 실행되지 않는다. 별도 설정이 필요하다.
- 애플리케이션 코드, Dependency, DB 설정, CI/CD 파일, Git Hooks는 포함하지 않는다.

## 참고 문서

- [Codex 공식 Skill 문서](https://developers.openai.com/codex/skills)
- [Claude Code 공식 Skill 문서](https://code.claude.com/docs/en/skills)
- [Claude Code 메모리 문서](https://code.claude.com/docs/en/memory) (`CLAUDE.md` import)

도구 버전이나 조직 설정에 따라 Skill 발견·호출 동작이 달라질 수 있다.
