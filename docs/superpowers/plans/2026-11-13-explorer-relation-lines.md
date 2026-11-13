# 업무 데이터 유형 관계도 관계선 정리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 유형 관계도에서 관계선이 다른 카드 위를 지나지 않게 꺾어 그리고, 이름표는 고른 유형의 선에만 보이며, 고른 유형으로 확대되게 한다.

**Architecture:** 샘플(`[싸피]관계선_샘플코드/relation-lines-example`)의 순수 함수 `edgeRoute.ts`를 그대로 들여오고, `modelGraphLayout.ts`에 경로 계산(`routeRelationLines`)과 간격 값을 넣는다. `ModelGraph.tsx`는 계산한 경로로 선을 그리고, 고른 유형에 따라 선 강조·이름표·확대를 정한다. 관계도 확장일 때만 그림을 더 높게 한다.

**Tech Stack:** React 19 · TypeScript · @xyflow/react 12 · @dagrejs/dagre · Vitest + Testing Library

**Spec:** 대화에서 확정한 범위 + 시안 캔버스 https://claude.ai/artifact/N64aGNTeA9y67ibrNka3fs "관계선" 페이지(시안 B + 확장, 맨 아래 "간격 조절 16·120" 판). 샘플 README: `C:\Users\SSAFY\Downloads\[싸피]관계선_샘플코드\relation-lines-example\README.md`.

## Global Constraints

- 배치 값: `rankdir: 'LR'`, `nodesep: 16`, `ranksep: 120`, `marginx`·`marginy: 32`. 카드 크기(`TYPE_NODE_WIDTH 156`, `TYPE_NODE_HEIGHT 52`)는 그대로.
- 이름표 자리 확보(`labelSize()`)는 지운다. 이름표는 고른 유형에 붙은 선에만 보인다. 아무것도 고르지 않으면 이름표가 없다.
- 고른 유형에 붙은 선: 색 `#6f5adf`(`--ui-brand-strong` 값), 굵기 2, 화살표 같은 색, 다른 선 위(`zIndex: 1`). 나머지 선: 투명도 0.3. 고르지 않았을 때는 지금과 같은 회색(`#94a3b8`, 굵기 1.5). 점선은 지금처럼 "활성 연결 0건"만 뜻한다.
- 경로를 못 찾은 선(`route.fallback`)과 자기 유형 관계는 지금처럼 곡선으로 그린다.
- 유형을 고르거나 관계도 확장을 켜고 끌 때: 고른 유형과 연결된 유형이 모두 들어오게 맞춘다(`fitBounds`, padding 0.15). 고른 유형이 없으면 전체 맞춤.
- 관계도 확장일 때만 그림 높이 `max(360px, calc(100vh - 230px))`. 평소 높이는 지금 값 그대로.
- `edgeRoute.ts`는 샘플 내용을 바꾸지 않고 들여온다. 새 패키지는 없다.
- 커밋은 사용자가 요청할 때만, Co-Authored-By 없이.

## Review Focus

- 고른 유형이 연결 없는 유형(예: 고정자산)일 때 확대 범위가 카드 하나라 과하게 커지지 않는지 — 확대 상한(`maxZoom` 1.5)을 넘지 않는다.
- 유형 목록 보기에서 유형을 고른 뒤 개요로 돌아오면, 그림이 고른 유형에 맞춰져 있는지.
- 관계도 확장을 켜고 끌 때 고른 유형 확대가 다시 맞춰지는지(창 크기가 바뀌므로).
- 객체를 골랐다가 "← 전체 구조로" 돌아오면 그림을 다시 만들므로 확대·강조가 고른 유형 기준으로 다시 잡히는지.
- 관계도 데이터가 없거나 선이 하나도 없을 때 `routeRelationLines`가 빈 결과로 끝나고 오류가 나지 않는지.

---

### Task 1: 경로 계산 들여오기와 배치 값

**Files:**
- Create: `frontend/src/features/explorer/edgeRoute.ts` (샘플 파일 복사)
- Create: `frontend/src/features/explorer/edgeRoute.test.ts` (샘플 파일 복사)
- Modify: `frontend/src/features/explorer/modelGraphLayout.ts`
- Modify: `frontend/src/features/explorer/modelGraphLayout.test.ts`

**Interfaces:**
- Produces: `routeRelationLines(nodes: ModelNode[], lines: RelationLine[], points: Map<number, Point>): Map<string, Route>` (샘플과 같은 이름·모양). `Route`는 `edgeRoute.ts`에서 export.

- [ ] **Step 1: 실패하는 테스트** — 샘플 `modelGraphLayout.test.ts`의 테스트 `관계 선 경로는 다른 유형 카드를 지나가지 않고 자기 유형 관계는 계산에서 뺀다`를 이 저장소 테스트 파일 끝에 옮기고(import에 `routeRelationLines` 추가), `edgeRoute.test.ts`를 복사한다. 이 저장소 카드 크기에서도 통과해야 한다.
- [ ] **Step 2: 실패 확인** — `frontend/`에서 `npx vitest run src/features/explorer/modelGraphLayout.test.ts src/features/explorer/edgeRoute.test.ts` → `routeRelationLines`/`edgeRoute` 없음으로 FAIL.
- [ ] **Step 3: 구현** — `edgeRoute.ts` 복사. `modelGraphLayout.ts`: `labelSize` 삭제, `setEdge`는 라벨 크기 없이, `setGraph`에 Global Constraints의 배치 값, 샘플의 `routeRelationLines`를 옮긴다.
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS. 기존 배치 테스트 3개(겹침 없음)도 PASS.
- [ ] **Step 5: 커밋(사용자 요청 시)**

### Task 2: 선 그리기·강조·이름표·확대

**Files:**
- Modify: `frontend/src/features/explorer/ModelGraph.tsx`
- Modify: `frontend/src/features/explorer/ExplorerPage.tsx` (확장 상태 전달)
- Modify: `frontend/src/features/explorer/explorer.css`
- Test: `frontend/src/features/explorer/ExplorerPage.test.tsx`

**Interfaces:**
- Consumes: Task 1의 `routeRelationLines`, `Route`.
- Produces: `ModelGraph` props에 `expanded: boolean` 추가(ExplorerPage의 `graphExpanded`).

- [ ] **Step 1: 실패하는 테스트**
  - 기존 `객체를 고르기 전에는 객체 유형 관계도를 보여준다`의 `graph.getByText('생산 대상 수주 · 1건')`을 `expect(graph.queryByText('생산 대상 수주 · 1건')).not.toBeInTheDocument()`로 바꾼다.
  - 기존 `같은 두 유형 사이의 관계는 선 하나에 이름을 모아 보여준다` 맨 앞에서 `await user.click(await graph.findByRole('button', { name: '객체 유형 설비' }))`로 설비를 고른 뒤 같은 확인을 한다(`userEvent.setup()` 추가).
  - 새 테스트 `유형을 고르면 그 유형의 관계 이름표만 보여준다`: 설비를 고르면 `현재 배정 설비 · 1건`이 보이고, 설비와 무관한 `생산 대상 수주 · 1건`은 없다.
- [ ] **Step 2: 실패 확인** — `npx vitest run src/features/explorer/ExplorerPage.test.tsx` → 위 세 테스트 중 이름표가 처음부터 보이는 것 때문에 FAIL.
- [ ] **Step 3: 구현**
  - 배치·경로 계산은 `useMemo([graph])`로 한 번만.
  - `RelationEdge`: `data.route`가 있고 `fallback`이 아니면 `route.d`로 그리고 이름표 위치는 `route.labelAt`. 이름표는 `data.showLabel`일 때만.
  - 선 목록을 만들 때 Global Constraints의 강조·흐림 규칙을 적용한다.
  - 확대: `useEffect([selectedTypeId, expanded, view, graph])`에서 다음 프레임(`requestAnimationFrame`)에 고른 유형과 이웃 유형 카드 좌표(배치 결과 `points`)의 경계 상자로 `fitBounds(…, { padding: 0.15 })`, 고른 유형이 없으면 `fitView({ padding: 0.15 })`. 그래프 인스턴스가 아직 없으면(`onInit` 전) `onInit`에서 같은 맞춤을 한 번 한다.
  - `ExplorerPage`: `<ModelGraph expanded={graphExpanded} …/>`.
  - `explorer.css`: `.explorer-workbench.is-expanded .explorer-graph-canvas { height: max(360px, calc(100vh - 230px)); }`
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.
- [ ] **Step 5: 커밋(사용자 요청 시)**

### Task 3: 마무리

- [ ] **Step 1: `/impeccable polish 업무 데이터`** — 실제 유형 구조(배포 사이트 042 응답을 로컬 화면에만 주입)로 1440×900에서 처음 화면·설비 선택·확장+설비 선택을 캡처하고 배율을 잰다. 기대값: 평소 전체 약 0.42, 확장+설비 약 0.70, 경로 계산한 선 중 카드를 지나는 선 0개. Review Focus 항목도 화면으로 확인한다.
- [ ] **Step 2: 전체 검사** — `frontend/`에서 `npx prettier --check src/`, `npm run lint`, `npx vitest run`(종료 코드 0, `Errors` 없음), `npm run build`.
