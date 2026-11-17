# Agent 최소 반영 범위 (2026-11-17)

사용자 요청에 따라 진행 범위 검사·자동 보충 후보를 분리하고, 현재 상태/과거 실적 구분과 FIND 5회만 남겼다. 기준 HEAD는 `1fb79dbe448d91ef5d1353dac978d2497fb27e87`, 작업 브랜치는 `fix/agent-current-progress`다. 커밋·푸시·머지·배포는 하지 않았다.

## 남긴 변경

- `AgentResponder`: 과거 실적의 종료를 전체 작업 완료로 해석하지 않는다. 진행 질문은 하위 현재 상태·잔량을 우선 확인하고, 중단이 있으면 연결된 자원·장애 기록을 확인하도록 안내한다. 단순 상태·과거 실적 질문에는 하위 탐색을 강제하지 않는다. 진행 현황만 요청했으면 대체설비·완료 계산·계획으로 확장하지 않도록 안내한다.
- `AgentModelSummaryTools`: 현재 상태와 과거 실적 유형을 구별할 설명이 필요할 때 모델 요약을 사용할 수 있도록 설명을 맞춘다.
- `AgentObjectSetTools`: MAX_FINDS 3→5. 전체 도구 25회 및 다른 한도는 유지한다.
- 기존 `AgentResponderTests`의 안내 문구 검사와 `AgentObjectSetToolsIntegrationTests`의 1~5회 허용·6번째 거절 검사를 남긴다.
- 응답 정책 3.9 및 한도 설명을 맞춘다. kind·권한·모델·REST API·DB 스키마 변경은 없다.

## 제외하고 보관한 변경

`AgentProgressCoverage`, `AgentProgressTools`, PROGRESS_SCOPE 도구·안내, 조회 증거 추적, 마스킹 결과 보관, 종료 검사·자동 보충·미확인 안내 및 전용 테스트를 제외했다. 추가했던 FIND 일괄 조회 설명도 제외했다. 기존 Session·마스킹·상세조회 구현은 기준 HEAD로 복원했다.

로컬 보관 위치: `C:/Users/SSAFY/.codex/archives/agent-current-progress-20261117-223803/`

- `before/`: 선별 전 미커밋·미추적 파일 원본. `manifest.json`의 SHA-256과 일치하는지 확인한 뒤 분리했다.
- `all-before.patch`: 기준 HEAD에 대한 전체 추적 파일 변경.
- `experiments/`: 한도 비교·진행 범위 검사·마지막 재측정 자료.
- 관련 실험 문서·계획도 `before/docs/`에 보관했다. 복원할 때 이후 변경 위에 무조건 덮어쓰지 말고 기준 HEAD와 대조한다.

## 기존 GMS 근거와 한계

앞선 작은 묶음의 P01은 중단 공정 20·30, 공정별 잔량 3,200개, P103의 진행 중 비가동·유압 누유를 설명했다. 31.07초, 누적 입력 195,073토큰이었다. 같은 묶음의 P02는 중단 작업 공정을 놓쳤고 P03은 원인을 놓쳤으므로 전체 질문 해결로 보지 않는다. [원본 측정 기록](agent-find-limit-2026-11-17.md)을 참고한다.

현재 선별본은 당시 GMS 측정본과 프롬프트 한 구절이 다르다. 기존 리뷰에서 확정한 “현재 진행 현황만 요청한 경우에는 확장하지 않고, 납기 가능·위험이나 대안을 함께 물으면 해당 판단 규칙을 따른다”는 구절을 유지했다. 이번에 새로운 의미 규칙을 추가한 것이 아니다. 따라서 이전 P01 수치를 현재 선별본의 새 측정 결과로 표시하지 않는다.

이번에는 GMS를 호출하지 않는다. 로컬 테스트는 실행 경로·한도·권한·마스킹의 회귀를 확인하며, 실제 모델의 탐색 경로나 반복 답변 정확성을 보장하지 않는다.

## 최종 로컬 검증

- `spotlessApply test --tests ...`: 관련 7개 클래스, 93건 통과, 실패·오류·건너뜀 0. 42초, BUILD SUCCESSFUL.
- 대상: AgentResponderTests, AgentModelSummaryToolsTests, AgentObjectSetToolsIntegrationTests, AgentObjectSetToolsTests, AgentQuerySessionTests, ModelMaskingToolCallbackTests, AgentObjectDetailToolsTests.
- 제품/테스트 전체 컴파일도 통과했다. 기존 deprecated API 경고가 있으며 컴파일 오류는 없다.
- GMS 실행 조건을 비활성화하고 지정 클래스만 실행했다. GMS 테스트·전체 테스트 묶음은 실행하지 않았다.
- 제거한 진행 범위/자동 보충 심볼의 소스 잔존 참조가 없고, `git diff --check`가 통과했다.
- 제품 변경은 3개 파일, 테스트 변경은 2개 파일이며 정책 문서와 검증 기록을 함께 남겼다.
- 보관 위치의 selected.patch, selected/ 및 test-results/에 선별 변경과 검증 결과를 보존했다. 커밋·푸시·머지·배포는 하지 않았다.
