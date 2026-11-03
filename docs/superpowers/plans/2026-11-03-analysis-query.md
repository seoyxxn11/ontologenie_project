# 분석 조회 API 017·019·020 구현 계획

> **For agentic workers:** superpowers:executing-plans로 현재 세션에서 구현한다. 사용자가 기존 명세대로 구현하도록 요청한 범위다.

**Goal:** Jira S15P31C106-277의 실행 목록·상태·분석별 후보 조회를 구현한다.

**Architecture:** Analysis의 읽기 전용 Service와 JDBC Repository가 실행 조회를 맡는다. 020은 Review Service의 후보 검색·응답 변환·latestCheck 일괄 조회를 재사용하고 analysisRunId 조건을 추가한다.

**Tech Stack:** 기존 Spring MVC, JdbcClient, JPA Specification, PostgreSQL, MockMvc/Testcontainers.

**Spec:** 2026-11-03 Notion 017·019·020 원문과 Jira 277 확인. 기존 105·018과 함께 작업 중인 feature/223-analysis-results에서 계속 구현한다.

## 제약과 선택

- ADMIN 세션 인증, 읽기 전용 트랜잭션, ApiResponse/PageMeta를 사용한다.
- 017: runKind/status, 020: itemKind/reviewStatus/applyStatus 필터를 AND로 적용한다.
- page=0, size=20, id DESC는 기존 Review 조회 방식에 맞춘 구현 기본값이다. 명세에 기본값·정렬 보완이 필요하다.
- 019는 상태와 무관하게 대상·시각·범위·요약·실패 사유를 조회한다. 접수 전용 joined는 GET 응답에 넣지 않는다.
- 020은 경로 analysisRunId와 AI origin으로 범위를 고정한다. 없는 실행은 404(details.resource=analysis_run), 기존 실행의 후보가 없으면 200 빈 페이지다.
- 기존 접수 합류 정책·AI 시작 알림·스키마·인증 구조는 변경하지 않는다. Commit/Push/원격 변경은 하지 않는다.
- 새 조회 통합 테스트 한 클래스에 대표 완료 조건을 모은다. GET에는 중복·상태 변경·롤백 테스트를 추가하지 않는다.

## 구현 순서

- [x] 대표 API 통합 테스트 작성 및 미구현 실패 확인.
- [x] AnalysisSearchRequest, AnalysisProposalSearchRequest, AnalysisResponse 추가. AnalysisQueryRepository/Service와 기존 AnalysisController의 GET 세 경로 연결.
- [x] ProposalService에 분석별 검색 추가. 기존 응답 변환 및 latestCheck 로딩 공유.
- [x] 새 테스트 및 기존 analysis/review 관련 검증, Spotless 적용과 최종 build 1회.
- [x] 명세 일치·남은 협의 사항·검증 결과 정리.

## 검토 초점

- 서로 다른 분석의 후보가 섞이지 않는지와 필터의 AND 적용.
- 최신 검사·AI 신뢰도 등이 기존 Review 응답과 일치하는지.
- QUEUED/RUNNING/SUCCEEDED/FAILED 시각·실패 사유 및 null 응답.
- 잘못된 enum/page/size/id, 없는 실행, 401/403.
- 빈 페이지에서도 전체 건수·hasNext가 맞는지.

## 협의할 내용

1. **산이 / AI 분석 계약:** analysisRange에서 실제 사용하는 기간·샘플링 필드와 검증 규칙을 정한다. 현재 원천·대상·컬럼이 같으면 진행 중인 실행에 합류하며 runKind·기간·샘플링·basedOnSyncRunId는 합류 키에 없다. 서로 다른 조건이 합류해도 되는지 확정한다. 이는 기존 후보 검사(check_run)의 후보 버전+데이터 기준과 별개다.
2. **산이 / 연동·배포:** 271은 이미 커밋 후 전송 계약이 있으므로 이를 재결정할 필요는 없다. 전송 실패 처리·재시도와 273 시간 초과 정리의 책임 및 함께 배포할 순서를 확인한다. 현재 접수·결과 저장·조회만으로 실제 FastAPI 분석이 시작되지는 않는다.
3. **산이 / 재분석 계약:** INITIAL에 basedOnSyncRunId를 허용할지, REANALYSIS에 필수로 할지, 성공한 최초 분석 없이 재분석을 허용할지 정한다. 현재는 참조 실행이 존재하는지만 확인한다.
4. **주연(FE) / 요청·조회 계약:** 알 수 없는 요청 필드 400, 중복 컬럼은 하나로 정규화하는 현재 동작을 공유한다. 목록의 기본 page=0/size=20 및 id DESC와 020의 기존 Review 응답 재사용(latestCheck 포함)을 명세에 보완한다.
5. **주헌 및 감사 기록 담당 / 기록 수준:** 수동 후보와 AI 후보의 검사 접수·변경 감사 기록 수준을 같게 할지 확인한다. AI 저장은 현재 ANALYSIS_RUN 감사 기록을 남긴다. 주헌과 기존에 맞춘 후보 검사 합류·재시도 규칙을 018에 그대로 적용하는 문제는 아니다.
6. **문서 정리:** Jira의 담당·조회 범위 분리는 223/276/277로 이미 정리됐다. Notion의 예전 담당·개발 상태 및 새 조회 기본값을 맞추고, 기존 docs/superpowers 문서의 커밋 포함 여부를 커밋 준비 때 정한다. 원격 문서 변경·커밋은 이번 작업 범위가 아니다.

## 실행 기록

- 준비: 기존 미커밋 105·018 변경을 보존하며 같은 브랜치에서 후속 조회를 구현한다.
- 구현: 017 필터·페이지와 대상 일괄 조회, 019 상태·결과 조회, 020 분석별 AI 후보 필터와 Review 응답 공유를 연결했다. 스키마·의존성·보안 설정 변경 없음.
- 검증: 미구현 GET 경로의 실패 확인 후 대표 통합 테스트 20건 통과. 같은 목록 테스트에 다중 대상 연결을 보완했다.
- 최종 `backend/`의 `./gradlew.bat spotlessApply build` 성공(2분 12초). XML 48개 기준 전체 453건, 실패·오류·건너뜀 0. 새 조회 20건 포함. 로그 `.gradle-query-final-277.log`.
- `git diff --check` 성공. 별도 읽기 전용 검토에서 구체적인 Critical/Major 결함 없음. 추가 보류 Minor 없음.
- 검토 한계: 실제 FastAPI·batch 기동·운영 배포·FE 화면은 검증하지 않았다. 내부 결과 API를 통한 저장·조회 연결만 검증했다. 사람 리뷰·MR 병합 후 동작 확인은 Jira 완료 조건으로 남아 있다.
- 판단: 조회 기본값·정렬은 기존 Review와 동일하게 구현해 문서 보완 사항으로 남긴다. 018 합류 정책과 271·272 연동은 기존 경계를 유지한다. 해당 계약이 달라지면 후속 합류 키/연동 변경이 필요하다.
- 원격 Notion·Jira 수정 및 Commit·Push·MR 작업은 수행하지 않았다.
