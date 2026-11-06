# 승인 반영 결과 조회 (133) Implementation Plan

> 실행은 프로젝트 implement workflow에 따라 진행한다. Commit·Push·원격 변경은 이 계획의 실행 범위에 포함하지 않는다.

**Goal:** 프론트 수정 없이 049·055와 096·097 실행 이력으로 승인 반영·실패·보류 결과를 조회한다.

**Architecture:** 현재 상태는 proposal_item, 커밋된 결정은 review_decision, 실행 시도는 action_execution에서 기존 API로 읽는다. 131의 업무 전체 롤백과 별도 실패 감사 트랜잭션을 유지한다. 조회 누락이 검증될 때만 운영 코드를 보완한다.

**Tech Stack:** Java 21, Spring Boot, MockMvc, Testcontainers PostgreSQL, Gradle.

**Spec:** 사용자 확정 범위(2026-11-05), S15P31C106-133, docs/review-decision-execution.md.

## Constraints

- 프론트·DB 스키마·API 응답 계약·인증 구조를 변경하지 않는다.
- 실패 후 apply_status=FAILED 또는 apply_error를 별도 저장하지 않는다.
- 기존 화면처럼 후보 필터 없는 096 요청으로도 결과를 읽을 수 있어야 한다.
- 055는 커밋된 결정만 제공한다. 실패 기록을 승인 결정으로 만들지 않는다.
- 종류별 실제 반영기는 아직 없다. 테스트용 반영기는 트랜잭션·조회 연결만 검증한다.

## Tasks

- [x] 1. 기준 build 확인, 133 전용 worktree와 branch 준비.
- [x] 2. ProposalApplyResultQueryApiTests 추가: POST 후 049·055·096·097 조회를 실제 DB 커밋 이후 검증.
  - 사용자 추가 요청에 따라 성공과 실제 보류 행 참조 1건, 기존 HOLD 이후 반영 실패·커밋 실패 2건만 신규 검증한다.
  - 기본 화면용 096 조회에서 상태·input·result가 제공되는지 검증.
  - 권한·중복·revision·페이지·감사 저장 실패 등의 기존 테스트는 재사용한다.
- [x] 3. 재현된 조회 누락만 최소 수정. 기존 동작으로 충족되어 운영 코드는 추가하지 않았다.
- [x] 4. docs/review-apply-result-query.md에 화면별 조회 방법·상태 해석·실패 이력·검증 한계 기록. 189 문서의 과거 FAILED 별도 저장 설명은 사용자 확정 롤백 방식으로 바로잡았다.
- [x] 5. spotlessApply와 신규 핵심 테스트 3건 통과, diff 확인과 독립 리뷰. 변경 전 전체 backend build는 이미 통과했으므로 사용자 최소 검증 요청에 따라 반복하지 않았다.

## Review Focus

- FAILED·BLOCKED를 성공 결정 또는 현재 반영 상태로 잘못 해석하지 않는다.
- 과거 revision 실패와 현재 상태를 구분한다.
- 재시도 성공이 과거 실패 감사를 숨기지 않는다.
- 감사 조회가 후보·Action·사용자 범위를 누출하지 않는다.
- 테스트용 반영기를 실제 도메인 반영 완료 근거로 사용하지 않는다.

## Verification

- backend/에서 ./gradlew.bat test --tests '*ProposalApplyResultQueryApiTests' --console=plain.
- backend/에서 ./gradlew.bat spotlessApply test --tests '*ProposalApplyResultQueryApiTests' --console=plain.
- git diff --check, frontend·migration·131 실행 서비스 변경 여부 확인.

## Progress

- 131이 병합된 origin/develop(cd63546)에서 별도 worktree와 feature/133-review-apply-result-query를 준비했다.
- 원격 Notion 명세는 앞선 조회에서 404였으며, 이번 범위는 사용자 확정과 기존 API 계약 재사용을 기준으로 한다.
- 기준 build는 93개 suite·911건 통과. 최종 신규 테스트는 3건만 실행했고 종료 코드 0이다. 자세한 결과는 docs/review-apply-result-query.md를 따른다.
- 독립 코드 리뷰 결과 수정이 필요한 결함은 없었다. 사용자의 최소 검증 요청에 따라 핵심 경로 외 신규 테스트는 제거했다. 최초 작업에서는 Commit·Push·병합·운영 변경을 하지 않았다.
- 후속 사용자 요청: 필드 주입을 생성자 주입으로 변경한 뒤 133을 커밋하고, 최신 develop을 일반 merge로 반영한다. 커밋 전 컴파일·포맷을 확인하고 병합 후 핵심 3건만 실행한다. Push·MR 생성은 포함하지 않는다.
- 후속 실행: 생성자 주입 변경 및 컴파일·포맷 확인 후 `9e3eec0`으로 133을 커밋했다. `origin/develop`의 `8381018`을 충돌 없이 병합한 상태에서 spotlessCheck와 핵심 3건이 통과했다(실패·오류·Skip 0). 전체 build·Push·MR 생성은 하지 않았다.
