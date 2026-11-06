# 131 결정 실행 연결 구현

이 문서는 2026-11-05 반영 계약 뼈대 수령 후 구현한 131 코드와 검증 범위를 설명한다. 반영 서비스와의 호출·트랜잭션 계약은 [연동 계약](review-decision-flow.md)을 따른다.

## 구현 위치와 제공 코드

- worktree: `C:/Users/SSAFY/.codex/worktrees/43cf/S15P31C106`
- 브랜치: `feature/131-review-decision-flow`, 기존 Task 1·2 커밋: `733c851`
- 사용자 허용으로 주헌님의 `115f6ce`에서 ontology 서비스·반영기 인터페이스·DTO·enum 7개 원본 파일만 추가했다. 원본 blob과 파일 해시를 대조했다.
- 실제 종류별 반영기는 추가하지 않았다. 운영 코드에는 성공 대역이 없다.
- 기본 저장소 `C:/S15P31C106`의 develop 및 기존 미커밋 변경은 이 작업에서 수정하지 않았다.

## API 054

`POST /api/v1/proposals/{id}/decisions`를 기존 ProposalController에 연결했다. 요청은 기존 ProposalDecisionRequest(revision, decision, selectedOptionId, reason), 결정자는 세션 LoginAccount다. 기존 인증·CSRF·임시 비밀번호 제한을 유지한다.

성공은 HTTP 200의 `ApiResponse<ProposalDecisionResult>`다. API 055의 이력 DTO와 구분한다.

| 필드 | 값 |
| --- | --- |
| decisionId | 이번 review_decision ID |
| decision | APPROVE / REJECT / HOLD |
| reviewStatus | APPROVED / REJECTED / HELD |
| applyStatus | APPLIED / NOT_APPLIED |
| appliedEntityIds | 실제 ApplyResult.targetId의 숫자 배열. 비승인은 빈 배열 |
| heldItemIds | ApplyResult의 목록 그대로. 비승인은 빈 배열 |
| actionExecutionId | 실제 성공 감사 ID |

targetTable·reloadResourceIds·summary는 내부 감사 result에 남기며 공개 응답 필드로 추가하지 않는다.

### 명세 원문 대조와 오류 경계

사용자 허용 후 로그인된 브라우저에서 [API 054 원문](https://app.notion.com/p/405bdf14d2d8813dbde3d11097ddd5df)을 읽었다. 연결 도구의 404와 브라우저 조회 성공을 구분한다. 원문은 appliedEntityIds·heldItemIds를 int64[]로, 반영 업무 오류의 공개 응답을 422 APPLY_FAILED로 정의한다.

- 서비스 내부에서는 제공된 ApplyErrorCode의 400·409·422 코드와 details를 보존한다.
- Controller는 ApplyErrorCode 업무 예외만 명세의 422 APPLY_FAILED로 변환한다. reason과 실제 저장된 실패 감사 ID를 연결한다.
- 129 검증 오류(권한·후보 없음·revision·선택지·성공 검사)는 기존 코드와 상태를 유지한다.
- 시스템 오류는 500 INTERNAL_ERROR로 처리한다. 실패 감사가 저장됐을 때만 실제 감사 ID를 연결한다.
- 명세에 이미 있는 공개 형식과 오류를 구현했다. 원격 Notion/Jira/API 문서는 수정하지 않았다.

## 트랜잭션과 감사

ProposalDecisionService.decide는 외부 업무 트랜잭션 참여를 NEVER로 거절하고, 내부 TransactionTemplate으로 실제 커밋까지 관리한다.

1. 기존 ADMIN 조건과 PROPOSAL_DECIDE 권한을 검사한다.
2. 130 recordValidated를 한 번 호출한다. 내부 129가 검증·잠근 같은 관리 대상 후보를 사용한다.
3. APPROVE만 기존 Resolver의 불변 revision/선택지 payload를 실제 반영 서비스에 전달한다. 종류·출처는 잠긴 후보, 결정자는 세션에서 읽는다.
4. 반영 결과가 null이면 오류로 거절하고, 유효한 결과를 받은 뒤에만 approve한다. REJECT/HOLD는 Resolver·반영 서비스를 호출하지 않는다.
5. flush 후 DB snapshot으로 결정 이력 INSERT·실제 후보 UPDATE 변경을 만들고, ApplyResult.changes를 그대로 합친다. 반복 HOLD처럼 후보 행이 바뀌지 않으면 UPDATE 변경을 만들지 않는다.
6. DECIDE_PROPOSAL / PROPOSAL_DECIDE 및 ACTION / USER / WEB 계약으로 성공 감사를 같은 트랜잭션에 저장하고 커밋 후 응답한다.

예외는 TransactionTemplate 밖으로 전파해 업무 롤백을 마친 다음 recordSeparately로 차단·실패 감사만 저장한다. 검증 차단은 BLOCKED, 검증 이후 반영·저장·커밋 오류는 FAILED다. 실패 changes는 비운다. 감사 저장 실패가 원래 업무 오류를 가리지 않게 한다.

실패 뒤 후보의 apply_status=FAILED/apply_error를 별도 저장하지 않는다. 이 처리는 사용자 지시와 API 054의 동기 계약을 따른다. 189 원본 주석의 별도 FAILED 저장 설명과는 다르며 제공 파일을 임의 수정하지 않았다. 실패 전 HELD 후보도 HELD로 남는다.

현재 상태·결정·성공/실패 실행의 조회 방법은 [133 승인 반영 결과 조회](review-apply-result-query.md)를 따른다. 133은 이 실행·롤백 코드를 변경하지 않고 기존 049·055·096·097을 재사용한다.

## 원천 검사

CandidateCheckRepository는 속성 매핑의 sourceColumn.sourceId + sourceColumn.resourceKey를 직접 비교한다. table 또는 sourceId.table로 추측하지 않는다. 기본 저장소에 이미 작성된 같은 최소 변경과 회귀 테스트를 131 worktree에서 재사용했다.

revision·선택지·같은 분석의 매핑 후보를 각각 검증하며, 다른 매핑·analysis_run_target이 잘못된 직접 조회를 가리지 않게 했다. resourceKey 없는 과거 payload를 수정하거나 삭제하지 않는다.

서연님의 AI 변경 `a566769`은 이 작업에서 가져오지 않았다. 실제 분석 생성부터 승인까지 연동할 때 그 변경과 sourceColumn.resourceKey 전달을 함께 확인해야 한다.

## 검증의 의미와 남은 범위

새 테스트 클래스는 ProposalDecisionServiceTests와 ProposalDecisionApiTests 두 개다. 기존 129·130·Task 1·2와 공용 fixture 테스트를 재사용한다. 테스트 메서드의 자동 rollback에 의존하지 않고, 요청/서비스가 종료된 뒤 실제 PostgreSQL 상태를 조회한다.

테스트 전용 RecordingApplier는 실제 DB 행을 쓰는 트랜잭션 probe다. 입력 전달·결과/감사 연결·롤백 경계를 검사하며, 제공된 보류/다시 읽기 ID의 전달도 확인한다. 실제 OBJECT_TYPE·LINK_RULE 등의 도메인 저장 구현이나 보류 생성 성공을 의미하지 않는다.

현재 운영 반영 서비스에는 종류별 반영기가 없으므로 유효한 승인도 미지원 오류로 거절한다. 주헌님 반영기 수령 후 종류별 실제 저장·코드→ID 조회·관계 재조정·보류 생성·다시 읽기 표시의 성공과 전체 롤백을 통합 검증해야 한다. 매핑 APPLIED를 객체 수집 완료로 해석하지 않는다.

DB Schema·Migration·공용 인증 구조·새 의존성은 변경하지 않았다. Commit·Push·원격 수정·Merge·배포는 수행하지 않는다. 팀원 리뷰와 병합 후 검증은 별도 완료 조건이다.

## 실행 검증 결과 — 2026-11-05

- JDK: `C:/Users/SSAFY/.jdks/ms-21.0.13.0.2` (Java 21), 로컬 Docker/Testcontainers PostgreSQL 사용.
- 실행 위치: 131 worktree 루트.
- 최종 실행: `.\backend\gradlew.bat -p backend spotlessApply build --console=plain`.
- 결과: 종료 코드 0, BUILD SUCCESSFUL(4분 12초). spotlessCheck 포함.
- 최종 XML 집계: 81개 suite, 799건, 실패 0·오류 0·Skip 0.
- 새 결정 검증: ProposalDecisionServiceTests 14건, ProposalDecisionApiTests 11건.
- 주요 회귀: ProposalItemTests 15건, ProposalApplyPayloadResolverTests 12건, ProposalWriteApiTests 27건, ProposalDecisionHistoryApiTests 23건. 전체 build에 기존 공용 fixture 사용 클래스도 포함됐다.
- `git diff --check` 및 새 Java 파일 공백 검사 통과. 제공 원본 7개는 포맷 후에도 115f6ce blob 해시와 동일.
- 제공 브랜치의 별도 서비스 단위 테스트 2개 파일은 이번 7개 원본 수령에 포함하지 않았다. 131의 실제 서비스 호출 테스트로 MANDATORY와 미지원 종류 거절을 확인했다.
- JVM class-sharing 및 테스트 코드의 deprecated API 안내가 출력됐으며 빌드 실패는 아니다.

실패를 먼저 확인한 경계:
- API POST 미구현: REJECT/HOLD 2건이 405로 실패한 뒤 연결했다.
- 원천 키: 잘못된 table 조합 조회를 4건에서 재현한 뒤 수정했다.
- 반복 HOLD: 실제 후보 변경 없이 UPDATE 감사를 남기는 경로를 재현하고, 전후 snapshot이 같은 경우 제외하도록 고쳤다.
- null 반영 결과: 예외 없이 승인되던 경로를 재현하고, approve 전에 결과 누락을 오류로 처리하도록 고쳤다.
- 실제 DB 커밋 실패: 기존 fk_proposal_item_current_revision의 INITIALLY DEFERRED 제약을 테스트 데이터에서 위반해, 실제 커밋 오류 후 업무 전체 롤백과 별도 실패 감사를 확인했다. 스키마는 변경하지 않았다.

코드 검토는 작성자 자체 리뷰로 수행했다. 이 대화에서는 다른 에이전트를 사용하지 않았으며 팀원 독립 리뷰를 대신하지 않는다. 실제 종류별 반영·AI 생성부터의 전체 연동·운영 배포는 검증하지 않았다.
## BLOCKED 감사 회귀 검증 보강 — 2026-11-05

- 기존 ProposalDecisionApiTests의 VIEWER 권한 부족, 후보 없음, revision 충돌, 잘못된 선택지, 성공 검사 미충족, 최종 결정 후 재결정에 감사 단언을 추가했다.
- 오류 응답의 actionExecutionId가 실제 BLOCKED 감사 행을 가리키고, 대상 후보·오류 코드가 일치하며 차단 사유가 비어 있지 않은지 확인한다. 해당 감사의 entity_change는 0건이어야 한다.
- 기존 결정 API 테스트 11건을 먼저 실행한 뒤 `backend/`에서 `./gradlew.bat cleanTest build --console=plain`로 전체 검증했다. 결과는 BUILD SUCCESSFUL, 81개 suite·799건, 실패·오류·Skip 0이다. spotlessCheck 포함.
- CLI 인계 문서와 구현 계획 문서 2개를 삭제하고, 연동 계약의 지난 구현 상태 및 삭제 문서 참조를 정리했다. 이번 보강에서 운영 Java 코드·API·DB 스키마는 변경하지 않았다. Commit·Push·develop 반영은 수행하지 않았다.
