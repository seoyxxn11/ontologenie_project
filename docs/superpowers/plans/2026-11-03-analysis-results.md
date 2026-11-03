# Analysis Results Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** API 105로 분석 진행·완료·실패와 AI 후보를 원자적으로 저장한다.

**Architecture:** JDBC 행 잠금으로 실행 상태를 직렬화한다. Analysis Service의 트랜잭션 안에서 Review Service, CandidateCheckService, AuditRecorder를 호출한다.

**Tech Stack:** Java 21, Spring Boot, JdbcClient, PostgreSQL, JUnit, MockMvc, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-11-03-analysis-results-design.md`

**Jira 범위 정리 (2026-11-03):** S15P31C106-223은 API 105(5포인트), S15P31C106-276은 API 018(3포인트), S15P31C106-277은 API 017·019·020(2포인트)으로 분리됐다. 105·018은 기존 작업 브랜치와 공유 MR로 검토 가능하다.

## Global Constraints

- 기존 스키마·내부 인증·의존성을 유지한다. Commit·Push·Jira 변경은 하지 않는다.
- API 응답은 ApiResponse, 입력 오류는 400, 대상 없음은 404, 상태 충돌은 409.
- 현재 시각은 주입한 Clock으로 구한다. 도메인 간 쓰기는 Service를 거친다.

## Review Focus

- 동시에 들어온 완료 알림은 한 번만 반영한다.
- 잘못된 선택지나 중첩 JSON 형식은 일부 후보를 남기지 않는다.
- 후보 전체를 저장한 후 자동 검사를 접수한다.
- 내부 요청은 사용자 생성자를 만들거나 원천 값을 감사 기록에 복제하지 않는다.
- 감사 기록 실패 시 분석 상태·후보·검사 접수가 모두 롤백된다.

### Task 1: 상태 전이와 오류 계약

**Files:** Create `analysis/controller/AnalysisResultController.java`, `analysis/dto/AnalysisResultRequest.java`, `analysis/dto/AnalysisResultAck.java`, `analysis/domain/AnalysisRun.java`, `analysis/repository/AnalysisRunRepository.java`, `analysis/service/AnalysisResultService.java` under `backend/src/main/java/com/axcore/platform/`. Test: `backend/src/test/java/com/axcore/platform/analysis/controller/AnalysisResultApiTests.java`.

**Interfaces:** `AnalysisResultService.accept(long id, AnalysisResultRequest request) -> AnalysisResultAck`; repository `findForUpdate(long id) -> Optional<AnalysisRun>`.

- [x] 작성: RUNNING 시작 시각, FAILED 사유·종료·감사 기록, 상태 충돌, 없는 대상, 잘못된 본문·인증 테스트.
- [x] 실행: `./gradlew.bat test --tests '*AnalysisResultApiTests'`; 미구현 경로 404로 실패하는지 확인.
- [x] 구현: DTO 검증, JDBC 행 잠금, 상태 변경, 실패 결과 감사 기록.
- [x] 검증: 동일 명령에서 해당 상태 전이 테스트 통과.

### Task 2: 성공 결과 저장과 원자성

**Files:** Add `review/dto/AiProposalRequest.java`, `review/service/AiProposalService.java`, `review/repository/AiProposalWriteRepository.java`; modify `check/service/CandidateCheckService.java`, `check/repository/CandidateCheckRepository.java` to accept nullable automatic requester. Extend Task 1 tests and service.

**Interfaces:** `AiProposalService.save(long analysisRunId, List<AiProposalRequest> proposals) -> List<Long>`; all participating services join the caller transaction.

- [x] 작성: 선택지·신뢰도·개정본 저장, 자동 검사, 기본 요약, 후보 검증, 중복·동시 완료, 감사 실패 롤백 테스트.
- [x] 실행: 성공 저장 미구현으로 실패하는지 확인.
- [x] 구현: 전체 후보를 저장한 뒤 검사를 접수하고 SUCCEEDED와 감사 기록을 저장.
- [x] 검증: `./gradlew.bat spotlessApply build`; 테스트 결과와 변경 파일 확인.
- [x] 문서: 105 구현 범위와 나머지 223 작업을 구분해 기록. 독립 코드 리뷰 후 중요한 문제를 고친다.

## 검증 결과

- 2026-11-03: 변경 전 `./gradlew.bat build` 성공.
- 105 통합 테스트는 미구현 경로의 404를 먼저 확인한 뒤 구현했다.
- 독립 리뷰의 선택지 소수 번호 절삭과 신규 관계 후보 참조의 검사 기준 누락은 대표 테스트에서 각각 200/400 불일치, 기준 건수 2/3 불일치를 재현한 뒤 수정했다.
- 사용자의 요청에 따라 반복 입력 조합을 줄이고 완료 기준의 대표 사례만 유지했다.
- 최종 `./gradlew.bat spotlessApply build` 성공: 105 테스트 27건 포함 전체 419건, 실패·오류·건너뜀 0건. `git diff --check` 통과.
- Commit·Push·MR·Jira 변경은 하지 않았다. 실제 AI 호출, 후속 검사 실행·승인까지의 통합 동작은 이번 검증 범위 밖이다.

## 구현 판단

- 내부 서비스가 자동 접수한 후보 검사의 요청자는 NULL로 둔다. 기존 DDL의 CANDIDATE 허용 조건과 내부 API의 사용자 없음 규칙을 따른다.
- FAILED 분석 알림을 정상 저장한 감사 기록은 상태 변경과 같은 트랜잭션에 둔다. 기록만 따로 커밋해 상태와 이력이 어긋나는 것을 방지한다.
- 105는 명세의 DDL 수준 검증을 담당하고, 후보의 업무상 유효성·실제 시험 적용 결과·승인 여부는 후속 검사 및 검토 기능에서 판단한다.

## 명세 대조 후 보정

- [x] 404 details.resource를 analysis_run으로 명시한다.
- [x] 409 메시지와 details를 API 105 명세에 맞춘다.
- [x] FAILED는 기존 result_summary를 유지하고 상태·종료 시각·실패 사유만 바꾼다.
- [x] 내부 결과 컨트롤러도 Servlet 웹 애플리케이션에서만 생성한다.
- 기존 대표 테스트의 관련 단언만 보강한다. 유형 수집 공통화, 018 합류 정책 변경, 추가 감사 정책은 이번 보정 범위 밖이다.
- 기존 5개 테스트 실행에서 명세 차이를 먼저 재현한 뒤 보정했다. 테스트 수는 추가하지 않았다.
- 보정 후 독립 리뷰에서 중대한 문제 없음. `./gradlew.bat spotlessApply build` 전체 433건 통과(실패·오류·건너뜀 0), `git diff --check` 통과.
