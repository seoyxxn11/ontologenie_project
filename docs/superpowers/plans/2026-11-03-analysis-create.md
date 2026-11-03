# Analysis Create Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans. Follow the approved 018-first scope and representative acceptance tests; do not expand the test matrix.

**Goal:** ADMIN이 선택한 원천 자원·컬럼의 분석을 접수하거나 같은 활성 실행에 합류한다.

**Jira:** S15P31C106-276(API 018, 3포인트). 2026-11-03 기존 S15P31C106-223에서 분리됐으며 기존 브랜치와 공유 MR로 검토한다. API 017·019·020은 S15P31C106-277이다.

**Architecture:** 기존 세션·CSRF 인증과 JDBC·트랜잭션 패턴을 따른다. 기존 `uq_analysis_run_active`와 INSERT ON CONFLICT DO NOTHING을 이용해 동시 접수를 합류시키고, 합류할 행은 FOR UPDATE로 확인한다. 새 실행일 때만 대상을 넣고 모든 접수의 USER/WEB 감사 기록을 같은 트랜잭션에 남긴다.

**Tech Stack:** Java 21, Spring Boot, JdbcClient, PostgreSQL, MockMvc, Testcontainers.

**Spec:** [018 선택 범위 AI 분석 실행](https://app.notion.com/p/405bdf14d2d881128b30ebe8700524f8), 2026-11-03 브라우저에서 요청·응답·검증 규칙 확인. 이 채팅에서 사용자가 018 구현 및 기존 검증 절차를 승인했다.

## Global Constraints

- POST /api/v1/analyses, ADMIN, HTTP 202 + ApiResponse.
- runKind INITIAL/REANALYSIS, targets 최소 1개, 대상 ID 중복 불가. 없는 자원·참고 수집은 404.
- 선택 컬럼은 비어 있지 않고 현재 source_column 목록에 있어야 한다. 요청 analysisRange.columns 직접 지정 불가.
- REANALYSIS도 변경 구간을 받지 않는다. targets[].columns에서 선택 범위를 만들고 analysis_range.columns에 보관한다.
- work_key는 명세대로 대상 자원과 컬럼 집합만으로 정규화한다. 생략한 컬럼은 전체를 뜻하며 명시한 전체 컬럼 목록과 구분한다. 합류 시 기존 실행의 runKind·요청자·범위를 보존한다.
- 271 AI 전송, 017·019·020 조회는 이번 범위 밖이다. DB·인증 구조·의존성을 바꾸지 않고 Commit/Push/Jira 변경도 하지 않는다.

## Review Focus

- 같은 대상·컬럼의 순서가 달라도 QUEUED/RUNNING 하나에 합류한다.
- 종료된 실행에는 합류하지 않고 새 실행을 만든다.
- source_resource.is_readable은 SYNC 기준이므로 AI 접수를 차단하는 조건으로 쓰지 않는다.
- 존재하지 않는 컬럼, 범위 덮어쓰기, 구형 변경 구간은 400으로 거절한다.
- 대상 저장·감사 실패는 새 실행까지 롤백한다. 합류 접수도 별도 감사 줄을 남긴다.

### Task 1: 분석 접수 API

**Files:** Create under `backend/src/main/java/com/axcore/platform/analysis/`: `controller/AnalysisController.java`, `dto/AnalysisCreateRequest.java`, `dto/AnalysisRunResponse.java`, `repository/AnalysisCreateRepository.java`, `service/AnalysisCreateService.java`. Add `backend/src/test/java/com/axcore/platform/analysis/controller/AnalysisCreateApiTests.java`.

**Interfaces:** `AnalysisCreateService.create(AnalysisCreateRequest, LoginAccount) -> AnalysisRunResponse`; repository insert-if-absent, active-for-update, target insertion, response loading. Existing 105 API is used to verify that created runs can start/finish.

- [x] 작성: 정상 선택 접수, 순서 정규화와 QUEUED/RUNNING 합류, 다른 컬럼·종료 후 새 접수, 재분석, 대표 입력 오류·404·인증, 동시 접수, 감사 실패 롤백.
- [x] RED: `./gradlew.bat test --tests '*AnalysisCreateApiTests'`, 미구현 404 확인.
- [x] 구현: 입력 검증 → 대상·컬럼 정규화 → 활성 실행 합류 또는 QUEUED 생성·대상 저장 → USER/WEB 감사 → 원본 실행 정보와 joined 반환.
- [x] GREEN: `./gradlew.bat test --tests '*AnalysisCreateApiTests'`.
- [x] 독립 리뷰의 중요한 문제만 재현·수정한다.
- [x] 최종 `./gradlew.bat spotlessApply build` 및 `git diff --check` 확인. 실제 AI 시작 미연동과 남은 조회 API를 보고한다.

## 검증 결과

- 미구현 상태에서 정상 접수의 202 예상이 404로 실패하는 것을 확인했다.
- `./gradlew.bat spotlessApply test --tests '*AnalysisCreateApiTests'`: 대표 테스트 14건 통과.
- 독립 읽기 전용 리뷰: Critical/Major 발견 없음. 동시 접수·105 종료 처리와의 잠금·재시도, 권한, 원자성, 원본 정보 보존을 확인했다.
- 최종 `./gradlew.bat spotlessApply build`: 전체 433건 통과, 실패·오류·건너뜀 0건. 포맷 검사와 `git diff --check`도 통과했다.
- 기존 105 작업과 같은 `feature/223-analysis-results` 브랜치에서 진행했다. Commit·Push·MR·Jira 변경은 하지 않았다.
- 018은 접수·합류까지 구현했다. 271 실제 AI 시작 전송, 017·019·020 조회, 운영 부하·시간 초과 검증은 범위 밖이다. 합류 키에서 runKind·참고 메타데이터를 제외하는 것은 명세의 동일 범위 합류 규칙을 따른다.
