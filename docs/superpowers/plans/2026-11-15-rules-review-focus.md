# 연결 규칙 검토 집중형 Implementation Plan

## 4단계 실행 결과 · 2026-11-15

후속 5단계도 사용자 요청으로 완료했다. 독립 디자인 검토(A)와 탐지/브라우저 검증(B), break-ui를 진행해 병렬 표본 표의 짧은 코드 잘림·유형 선택 글자 대비·검사 영역 간격을 보정했다. 같은40조건 재검에서 페이지 넘침/JavaScript 오류0, 관련82개·전체1,145개 테스트와 lint·format·build 통과. 기능 표 경계의 nested-cards 탐지는 해당 상세 파일에만 예외를 저장했다. 실제 승인/반영 계약은 변경하지 않았으며 결과/제약은 `output/rules-qa/report.md`에 기록했다. 6단계·커밋·푸시·MR·배포는 하지 않았다.

사용자가 4단계 구현과 테스트를 요청해 계획을 실행했다. 아래 계획 본문은 승인 당시 기록이다.

- 최신 `origin/develop @ c110b564`에서 `feature/rules-review-focus`를 만들고 기존 미추적 자료를 보존했다. 상단 행동 통합, 보조 목록/넓은 상세, 규칙 → 근거·검사 병렬 → 검토 결정 배치를 반영했다.
- 검사 집계·조회 오류·빈 결과는 기본 노출하고 표본과 검사 안내만 펼침으로 제공한다. 반영 실패 사유는 상세 상단에 남긴다. 기존 결정 mutation·검사 조건·대안 선택·revision·오류 복구 계약은 변경하지 않았다.
- 공통 토큰/컴포넌트를 재사용하고 검사 통계·사유 폼의 과도한 여백을 정리했다. 문서 후보의 내용·대상·근거가 기본 노출되는 것도 유지했다. 독립 코드 검토와 최종 변경 재검토에서 구체적인 결함은 발견하지 못했다.
- 관련 61개 테스트 통과 후 전체 검증에서 이전 탭/중복 안내를 가정한 문서 후보 테스트 2개가 실패했다. 새 배치에서도 실제 정보·검사 안내가 기본 노출되는 검증으로 맞춘 뒤 전체 81개 파일·1,145개 테스트가 통과했다. lint·format:check·build도 통과했다.
- 로컬 HeadlessChrome 157, 100% 배율에서 1440/1920/1280/960/720/320 CSS px를 확인했다. 페이지 가로 넘침/JavaScript 오류 0개, 좁은 표는 이름 있는 내부 스크롤 유지. 검색 적용 전 선택 유지, 상세 조건, 검사 새로고침 후 사유 유지, 키보드 펼침·Escape·포커스 복귀를 확인했다.
- 로컬 MSW에서 대안501 선택 → 사유 → 승인 → 반영 완료와 결정 기록을 확인했다. 기존 MSW는 선택 대안으로 payload까지 교체하지 않으므로 실제 데이터 반영의 증거로 사용하지 않는다. 운영 API·DB·실제 승인/반영, Edge, 실제 브라우저 200% 확대는 미검증이다.
- 증거/명령/제약: `output/rules-implementation/verification.md`, 최종 캡처 `output/rules-implementation/final-1440.png`. 별도 5단계 QA·커밋·푸시·MR·배포는 실행하지 않았다.

> **For agentic workers:** Use superpowers:executing-plans to implement this plan task-by-task in the current session. Steps use checkbox syntax. 사용자 요청 범위는 현재 3단계 계획이며 4단계 구현은 아직 시작하지 않는다.

**Goal:** AI 제안의 규칙·근거·검사를 함께 읽고 사람이 사유를 작성해 승인·보류·거절하는 검토 집중형을 적용한다.

**Architecture:** 기존 ProposalsPage의 목록·검색 상태와 ProposalDetail의 조회·개정·대안 선택을 유지하고 표시 구조만 재배치한다. CheckResults와 ProposalDecision의 실제 검사·결정 요청 및 오류 복구는 재사용한다. API·DB·권한·승인/반영 계약을 변경하지 않는다.

**Tech Stack:** 기존 React·TypeScript·React Query·공통 UI/CSS·Vitest·Testing Library.

**Spec:** 사용자 선택 검토 집중형. 시안 `C:/Users/SSAFY/.codex/visualizations/2026/11/13/01a1d295-595b-7400-aa19-3a4be9088942/ontologenie-ux/rules-design-comparison.html`, 조사 기록 `output/rules-design/brief.md`, 시각 기준 `docs/design-system.md`.

## Global Constraints

- 기존 기능과 승인·반영 흐름 유지. 새 제목·소개·진행 단계 UI를 추가하지 않는다.
- 현재 checkout은 이전 데이터 연결 작업 브랜치다. 구현 시 최신 origin/develop을 확인하고 별도 작업 브랜치에서 시작한다. 미추적 캡처·시안·기존 작업은 보존한다.
- 2단계 조사 target은 origin/develop이며 배포 ProposalsPage-B4mxWC8r.js와 Git SHA의 직접 대응은 미확인이다. 구현 직전 최신 target 차이를 재확인한다.
- 운영 API에 검사·승인·반영 요청을 실행하지 않는다. 브라우저 확인에는 비민감 fixture를 사용한다.
- 시안의 가상 데이터·가상 확인창·Tweak·CDN 폰트·로컬 가상 상태 전환은 제품 코드로 복사하지 않는다.
- Commit·Push·MR·Merge·배포는 이 계획의 실행에 포함하지 않는다.

## 디자인 시스템 대조와 보정

| 점검 | 판단·구현 기준 |
| --- | --- |
| 목록/상세 구조 | 좌측 목록·우측 상세는 기본 원칙과 일치한다. 고정 244px 시안 폭을 그대로 강제하지 않고 기존 전환점과 긴 이름 접근을 유지한다. |
| 근거/검사 병렬 | 넓은 상세에서 병렬, 공간이 부족하면 순서대로 쌓는다. 필수 경고·승인 차단/반영 실패 사유는 접지 않는다. |
| 규격 | 로컬 Pretendard·기존 --ui-* 사용. 상세 제목 24/36·700, 섹션 16/24·600, 일반 입력/버튼 최소40px·radius4, 큰 패널 radius8. |
| 선택 표시 | 시안의 inset box-shadow 선택선은 일반 그림자로 이식하지 않는다. 면·테두리·CSS 선과 aria-current/aria-pressed로 표시한다. |
| 상태 색 | 검토 대기 warning, 처리 중 info, 검사 완료 success, 반영 실패 danger를 별개로 표시한다. AI 신뢰도는 승인 가능/반영 성공을 의미하지 않는다. |
| 페이지네이션 | 공통 Pagination 유지. 업무 데이터의 32px 예외를 이 화면으로 확장하지 않는다. 현재 페이지 크기도 유지한다. |
| 검색 | 입력과 적용 분리·Enter/버튼·URL 선택 복원·기존 단일 필터 의미 유지. 필터 접기에서 입력이나 적용 조건을 소실시키지 않는다. |
| 결정/확인창 | 실제 ProposalDecision의 즉시 요청·오류/충돌 복구 계약을 유지한다. 시안의 승인 미리보기 확인창을 새 필수 단계로 추가하지 않는다. |
| 폼 상태 | 기존 사유 필수·1000자 제한·대안 선택·pending 중 중복 제출 방지 유지. 시안의 후보별 임시 사유 보관을 새 제품 요구로 확대하지 않는다. 상세 배치 변경만으로 컴포넌트를 재마운트하지 않는다. |

문서/코드 대조에서 위 보정 항목을 확인했다. 렌더링 대비·키보드·확대 검증 완료를 뜻하지 않는다. 시안 CSS의 모든 규격을 제품 규격으로 승인한 것으로 해석하지 않는다.

## 변경 파일과 책임

| 파일 | 범위 |
| --- | --- |
| frontend/src/features/review/ProposalsPage.tsx | 상단 행동·상태·검색 정돈, 기존 후보 목록/선택/페이지 유지 |
| frontend/src/features/review/ProposalDetail.tsx | 규칙 → 근거/검사 병렬 → 검토 결정, embedded 화면 배치 중심 변경 |
| frontend/src/features/review/rules.css | 화면 범위 레이아웃·줄바꿈·반응형, 공통 토큰 적용 |
| frontend/src/features/review/ProposalDecision.tsx | 반복 설명/표현만 필요한 만큼 정리. mutation·validation·복구 로직 보존 |
| frontend/src/features/review/CheckResults.tsx | 요약/상세 구분이 필요한 경우만 변경. 재검사·표본·문제 행동 보존 |
| frontend/src/features/review/ProposalsPage.test.tsx | 선택/검색/필터 및 새 상세 구조 회귀 |
| frontend/src/features/review/ProposalDetailPolling.test.tsx | polling·오류·개정 갱신 시 상태 회귀 |
| frontend/src/features/review/ProposalDecision.test.tsx, CheckResults.test.tsx | 기존 검사/결정 계약 검증 유지 및 실제 변경 경로 보강 |
| docs/design-system.md | 승인된 연결 규칙 전용 배치와 검증 범위 기록 |

## Task 1: 상단 조작과 목록 유지

**Interfaces:** 기존 fetchProposals/PROPOSAL_PAGE_SIZE와 URL candidate 선택, ProposalDetail embeddedId 계약을 그대로 소비한다. 새 API나 전역 상태를 만들지 않는다.

- [ ] 최신 target과 배포 화면 차이를 확인하고 이전 작업과 분리된 브랜치 준비.
- [ ] 기존 ProposalsPage 테스트를 읽고 검색 입력 중 선택 유지, 적용 후 페이지/선택, 상세 필터 및 상태/유형 조건의 회귀 항목 확인.
- [ ] 상단 행동을 정돈하고 목록 옆 상세의 가용 폭을 확보한다. 기존 AI 제안·분석 관리·직접 제안·수동 검사/실행 목록·적용 후 문제 경로를 모두 남긴다.
- [ ] 관련 테스트 실행: `npx vitest run src/features/review/ProposalsPage.test.tsx` (frontend에서 실행).

## Task 2: 상세를 검토 순서로 재배치

**Interfaces:** RuleContent는 현재 proposal을 받고, CheckResults/ProposalDecision의 기존 proposal·refresh·대안 선택 props를 유지한다. 독립 상세 경로도 유지한다.

- [ ] ProposalDetail의 embedded 분기에서 규칙, 근거, 검사, 결정을 확인한다. 기존 Bundle/Comparison/DocumentLink 등 후보 종류별 렌더링을 누락하지 않는다.
- [ ] 기존 근거와 CheckResults를 같은 의미의 section으로 묶어 넓은 화면에서 병렬 배치한다. 전체 원본·대안·세부 검사 표본·문제별 행동·활동 이력을 계속 제공한다.
- [ ] 반영 실패 사유와 승인 조건 안내를 기본 노출한다. 중복 요약만 줄이고 실제 값·조회 실패·권한 제한·미제공의 의미를 보존한다.
- [ ] 검사 없음/진행/실패/현재 개정 불일치와 검사가 불필요한 후보를 각각 확인한다. 표본 미연결 건수를 검사 작업 자체 실패 또는 전건 일치 필수로 바꾸지 않는다.
- [ ] `npx vitest run src/features/review/ProposalDetailPolling.test.tsx src/features/review/CheckResults.test.tsx src/features/review/ProposalDecision.test.tsx` 실행.

## Task 3: 승인·반영 회귀와 시각 검증

**Interfaces:** saveProposalDecision 입력의 revision/decision/reason/selectedOptionId와 서버 응답의 reviewStatus/applyStatus를 변경하지 않는다.

- [ ] 사유 누락·공백·상한, 대안 선택 승인, 중복 제출, 409 충돌 후 최신 조회, 네트워크 결과 불명확 시 활동 기록 확인, 401/403/404 차단 경로의 기존 테스트를 유지한다.
- [ ] 서버 승인 결과와 반영 상태를 분리 표시한다. 프론트에서 승인 성공을 임의 생성하거나 자동 재요청하지 않는다.
- [ ] 스타일 적용 후 기존 상태/동작 테스트를 실행하고 변경된 배치 때문에 사라진 접근 가능한 이름·오류·행동을 복원한다.
- [ ] Chrome/Edge 가능한 환경에서 1440·1920·960 CSS px, 200% 확대·키보드·긴 이름·0건·다량 후보·조회 실패·반영 실패를 확인한다. 브라우저/확대율/fixture/미검증 환경을 기록한다.
- [ ] 구현 완료 후 `/impeccable polish`로 마무리하고 변경 시 관련 검증을 재실행한다.
- [ ] frontend에서 `npm run lint`, `npm run format:check`, `npx vitest run`, `npm run build`를 실행한다. 실행하지 못한 명령은 이유와 함께 남긴다.
- [ ] 디자인 시스템에 배치를 기록하고 실제 구현 화면과 시안 차이·기능 대조 결과를 보고한다. 별도 5단계 QA는 사용자 요청 시 진행한다.

## 기능 보존 체크리스트

- [ ] 상태·종류·검색·상세 조건·페이지·URL 선택 복원
- [ ] AI 제안·분석 관리·직접 제안·규칙 수정·대안 비교/선택·후보 종류별 상세
- [ ] 원문/근거/신뢰도·검사 시작/재검사·표본/문제 행동·활동 기록
- [ ] 사유·승인/보류/거절·현재 개정 검증·권한/충돌 복구·중복 제출 방지
- [ ] 검토와 반영 상태 구분·반영 실패 사유·적용 후 문제·수동 검사/실행 경로

## 3단계 결과

계획과 디자인 시스템 대조를 작성했다. 제품 코드 변경·제품 테스트 실행·4단계 구현·커밋/푸시/MR 변경은 수행하지 않았다. 사용자 선택 시안의 미리보기 검증 결과는 2단계 기록이며 제품 검증 결과로 전용하지 않는다.
