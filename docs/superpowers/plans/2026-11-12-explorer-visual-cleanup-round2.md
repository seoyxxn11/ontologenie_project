# 업무 데이터 화면 2차 정리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 업무 데이터 화면에서 관계도 그림과 근거 버튼이 1440×900 첫 화면에 보이게 하고, critique·break-ui에서 나온 표시 문제를 정리한다.

**Architecture:** 관계도 칸의 보기 전환 상태를 `ExplorerPage`로 올려 칸 제목 줄에 두고, 배율 버튼은 React Flow `Panel`로 그림 위에 겹친다. 상세 칸은 근거 버튼 위치만 옮긴다. 나머지는 CSS와 표시 함수(`formatCount`) 수정이다.

**Tech Stack:** React 19 · TypeScript · Vite · @xyflow/react · Vitest + Testing Library + MSW(`frontend/`)

**Spec:** 2차 인터뷰에서 확정한 범위(대화) + 시안 캔버스 https://claude.ai/artifact/N64aGNTeA9y67ibrNka3fs 의 "2차" 페이지(처음 화면, C1). 시안은 develop `7864209` 이전 레이아웃(제목 줄·메뉴 두 줄)으로 그려졌다. 머리·메뉴는 지금 develop 레이아웃을 따르고, 세 칸 안쪽만 시안을 따른다.

## Global Constraints

- 범위는 업무 데이터 화면(`frontend/src/features/explorer/`)만. 공용 컴포넌트(`Feedback.tsx`, `Pagination.tsx`, `StatusBadge`)는 고치지 않는다. 빈 결과 박스는 `explorer.css`에서 이 화면만 덮어쓴다.
- 새 색 값을 만들지 않는다. 기존 토큰(`--ui-brand`, `--ui-brand-strong`, `--ui-brand-subtle`, `--ui-surface`, `--ui-surface-subtle`, `--ui-border`, `--ui-text`, `--ui-text-secondary`)만 쓴다.
- 문구는 그대로 쓴다: 배지 `원천에 있음`, 범례 `점선: 현재 연결 0건 · 화살표: 관계 방향`, 근거 버튼 `원본·연결 근거 보기 ↗`(기존 문구 유지), 보기 전환 묶음 이름 `보기 방식`, 배율 묶음 이름 `관계도 배율 조절`(기존).
- 그림 높이: `max(360px, calc(100vh - 360px))`.
- 건수 표시는 `Intl.NumberFormat('ko-KR')` 쉼표 형식. 단 공용 `Pagination`의 "N쪽 · N건"은 이번에 바꾸지 않는다.
- 상태 배지의 모양·색(`tone`)은 바꾸지 않는다.
- 커밋은 사용자가 요청할 때만, Co-Authored-By 없이.

## Review Focus

- 유형 목록 보기로 바꾼 뒤 객체를 골랐다가 "← 전체 구조로"로 돌아오면 보기 방식이 어떻게 되는지(지금은 ModelGraph가 다시 마운트돼 개요로 돌아감 — 위로 올린 상태는 유지되는 게 자연스러움).
- 객체를 선택한 직후 객체 상세가 아직 불러오는 중일 때 칸 제목(이름을 모름)과 그 객체를 볼 권한이 없을 때(403) 칸 제목.
- 유형 관계도를 불러오지 못했거나 권한이 없을 때(`isModelGraphForbidden`) 제목 줄에 보기 전환 버튼이 나오지 않아야 함.
- 이름이 긴 객체(300자 한도)를 고르면 칸 제목과 가운데 노드가 넘치지 않음 — 제목은 줄바꿈, 가운데 노드는 2줄.
- 760px 이하에서 칸 제목 줄(제목 + 전환 버튼 + 관계도 확장)이 줄바꿈되어도 버튼이 잘리지 않음.

---

### Task 1: 관계도 칸 — 제목 줄로 버튼 올리기, 배율 버튼 겹치기, 그림 높이, 범례

**Files:**
- Modify: `frontend/src/features/explorer/ExplorerPage.tsx` (관계도 칸 220-281행)
- Modify: `frontend/src/features/explorer/ModelGraph.tsx` (TypeNode 52-75행, 툴바 215-271행, 그림 272행, 캡션 294행)
- Modify: `frontend/src/features/explorer/ObjectGraph.tsx` (제목 80-82행, 그림 84-85행)
- Modify: `frontend/src/features/explorer/modelGraphLayout.ts:5` (`TYPE_NODE_HEIGHT` 76 → 52)
- Modify: `frontend/src/features/explorer/explorer.css`
- Test: `frontend/src/features/explorer/ExplorerPage.test.tsx`

**Interfaces:**
- Produces: `ModelGraph` props에 `view: ModelGraphView` 추가(`export type ModelGraphView = 'graph' | 'list'`, ModelGraph.tsx에서 export). ModelGraph 안의 `view` useState는 지운다.

- [ ] **Step 1: 실패하는 테스트**
  - 기존 `객체를 고르기 전에는 객체 유형 관계도를 보여준다` 끝에 추가:

```tsx
  const heading = graph.getByRole('heading', { name: '객체 유형 간 관계' })
  const viewGroup = graph.getByRole('group', { name: '보기 방식' })
  expect(heading.parentElement).toContainElement(viewGroup)
  expect(within(viewGroup).getByRole('button', { name: '개요' })).toHaveAttribute('aria-pressed', 'true')
  expect(graph.queryByText('객체 유형')).not.toBeInTheDocument()
  expect(graph.getByText('점선: 현재 연결 0건 · 화살표: 관계 방향')).toBeInTheDocument()
```

  - 새 테스트:

```tsx
test('유형 목록으로 바꾼 보기는 객체를 골랐다 돌아와도 유지한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: '유형 목록' }))
  expect(graph.getByLabelText('객체 유형 목록')).toBeInTheDocument()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  expect(await graph.findByRole('heading', { name: '프레스 3호기와 연결된 정보' })).toBeInTheDocument()
  expect(graph.queryByRole('group', { name: '보기 방식' })).not.toBeInTheDocument()

  await user.click(graph.getByRole('button', { name: '← 전체 구조로' }))
  expect(await graph.findByLabelText('객체 유형 목록')).toBeInTheDocument()
})
```

- [ ] **Step 2: 실패 확인** — `frontend/`에서 `npx vitest run src/features/explorer/ExplorerPage.test.tsx` → 위 두 테스트 FAIL(보기 방식 묶음 없음).
- [ ] **Step 3: 구현**
  - `ExplorerPage`: `const [modelView, setModelView] = useState<ModelGraphView>('graph')`. 관계도 칸 제목 줄의 오른쪽 묶음(`explorer-panel-actions`)에 차례로: (유형 관계도를 보여 줄 때만) `role="group" aria-label="보기 방식"` 안에 `개요`/`유형 목록` 버튼(`aria-pressed`), (객체를 골랐을 때만) `← 전체 구조로` text 버튼, 그리고 기존 `관계도 확장`. 본문의 `← 전체 구조로` 버튼은 지운다. `<ModelGraph view={modelView} …/>`.
  - 객체를 골랐을 때 칸 제목은 `${withAndParticle(displayName)} 연결된 정보`. 이름은 `useQuery({ queryKey: ['objects', selectedId], queryFn: () => fetchObject(selectedId), enabled: selectedId !== null })`로 같은 캐시에서 읽는다. 불러오는 중이거나 오류면 `관계 탐색`.
  - `ObjectGraph`: 80-82행 `h2`를 지운다. 85행 그림 높이 클래스 `h-[440px]`를 `h-full`로.
  - `ModelGraph`: 툴바 두 개를 지운다. 배율 버튼 넷은 `<ReactFlow>` 안 `<Panel position="top-right">`에 `role="group" aria-label="관계도 배율 조절"`로 둔다(`@xyflow/react`의 `Panel`). 272행 `h-[460px]`를 지운다. TypeNode의 "객체 유형" `span`을 지운다. 캡션 아래, 개요 보기일 때만 `<p className="model-graph-caption">점선: 현재 연결 0건 · 화살표: 관계 방향</p>`.
  - `explorer.css`: `.explorer-graph-canvas { height: max(360px, calc(100vh - 360px)); }`. `.model-graph-toolbar` 규칙을 지우고 `.explorer-view-toggle`(테두리 1px `--ui-input-border`, 버튼 높이 36px, 눌림 상태는 `color: var(--ui-text)` + `background: var(--ui-brand-subtle)` + `box-shadow: inset 0 -2px 0 var(--ui-brand-strong)`)와 `.model-graph-zoom`(배경 `--ui-surface`, 테두리 `--ui-border`, 높이 32px 버튼)을 추가한다. 패널 제목 줄 오른쪽 묶음은 `display:flex; flex-wrap:wrap; gap:12px`.
- [ ] **Step 4: 통과 확인** — 같은 명령 → 전부 PASS, `Errors` 줄 없음. `npx vitest run src/features/explorer/modelGraphLayout.test.ts`도 PASS.
- [ ] **Step 5: 커밋(사용자 요청 시)**

### Task 2: 상세 칸 — 근거 버튼 위로, 배지 문구

**Files:**
- Modify: `frontend/src/features/explorer/ObjectDetail.tsx` (90-139행)
- Modify: `frontend/src/features/explorer/labels.ts:4`
- Modify: `frontend/src/features/explorer/explorer.css`
- Test: `frontend/src/features/explorer/ExplorerPage.test.tsx`

- [ ] **Step 1: 실패하는 테스트** — 기존 `객체를 고르면 상세 API로 받은 정보를 보여준다`에 추가:

```tsx
  const evidence = screen.getByRole('button', { name: '원본·연결 근거 보기 ↗' })
  const properties = screen.getByRole('heading', { name: '속성' })
  expect(evidence.compareDocumentPosition(properties) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  expect(screen.getByText('원천에 있음')).toBeInTheDocument()
  expect(screen.queryByText('정상')).not.toBeInTheDocument()
  expect(screen.queryByRole('heading', { name: '원천 근거' })).not.toBeInTheDocument()
```

- [ ] **Step 2: 실패 확인** — `npx vitest run src/features/explorer/ExplorerPage.test.tsx` → 위 테스트 FAIL.
- [ ] **Step 3: 구현**
  - `labels.ts`: `ACTIVE: '원천에 있음'`.
  - `ObjectDetail`: 출처 줄(`explorer-source-caption`) 바로 아래에 `explorer-detail-buttons` 줄을 두고 `원본·연결 근거 보기 ↗`(secondary)와 `질문하기`(secondary)를 이 순서로 둔다. 맨 위 `explorer-detail-actions` 줄에서 질문하기를 빼고 `유형 / ID`만 남긴다. `원천 근거` 섹션(129-140행)을 지운다. `이 객체 실행 이력` 링크는 그대로 맨 아래.
  - `explorer.css`: `.explorer-detail-buttons { display:flex; flex-wrap:wrap; gap:8px; margin-bottom:16px }`.
- [ ] **Step 4: 통과 확인** — 같은 명령 → 전부 PASS. 근거 팝업 테스트(375행 근처)도 PASS.
- [ ] **Step 5: 커밋(사용자 요청 시)**

### Task 3: 객체 관계도 노드·이름표, 박스 안 박스, 단어 끊김, 건수 쉼표

**Files:**
- Modify: `frontend/src/features/explorer/format.ts` (함수 추가)
- Modify: `frontend/src/features/explorer/ObjectGraph.tsx` (이름표 118-125행, 가운데 128-134행, 노드 151행, 160행, 182행, 221행, 235행)
- Modify: `frontend/src/features/explorer/ExplorerPage.tsx` (목록 건수), `ModelGraph.tsx:132`, `TypeRelations.tsx:14, 27`
- Modify: `frontend/src/features/explorer/explorer.css`
- Test: `frontend/src/features/explorer/format.test.ts`, `ExplorerPage.test.tsx`

**Interfaces:**
- Produces: `formatCount(value: number): string` in `format.ts` — `new Intl.NumberFormat('ko-KR').format(value)`.

- [ ] **Step 1: 실패하는 테스트**
  - `format.test.ts`:

```ts
test('건수는 천 단위 쉼표로 표시한다', () => {
  expect(formatCount(0)).toBe('0')
  expect(formatCount(1284)).toBe('1,284')
  expect(formatCount(5064)).toBe('5,064')
})
```

  - `ExplorerPage.test.tsx` 새 테스트:

```tsx
test('목록 건수는 천 단위 쉼표로 표시한다', async () => {
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json({ data: [], meta: { page: 0, size: 20, totalElements: 5064, hasNext: true } }),
    ),
  )
  renderExplorerPage()
  expect(await screen.findByText('5,064건')).toBeInTheDocument()
})
```

- [ ] **Step 2: 실패 확인** — `npx vitest run src/features/explorer/format.test.ts src/features/explorer/ExplorerPage.test.tsx` → 위 두 테스트 FAIL.
- [ ] **Step 3: 구현**
  - `formatCount`를 추가하고, 이 화면의 `N건` 표시(목록 머리 건수, 객체 관계도 노드·합계·묶음 제목, 유형 관계도 선 이름표, 유형 상세의 관계 건수)에 쓴다.
  - `ObjectGraph`: 이름표 `max-w-32 truncate`를 `object-graph-label`(최대 폭 10rem, 2줄 `line-clamp`, `title` 유지)로. 가운데 이름 `strong`에 `object-graph-center-name`(2줄 `line-clamp`)과 `title={object.displayName}`. 235행 목록의 `rounded-lg border border-slate-200`을 지운다.
  - `explorer.css`:
    - `.object-graph-node { border-color: var(--ui-border); word-break: keep-all; }`
    - `.object-graph-node[aria-pressed='true'] { border: 2px solid var(--ui-brand-strong); background: linear-gradient(var(--ui-brand-subtle), var(--ui-brand-subtle)), var(--ui-surface); }` — 반투명 면을 흰 바탕 위에 겹쳐 불투명하게(새 색 없음)
    - `.object-graph-center, .explorer-object-name, .explorer-panel-heading h2, .explorer-detail-title, .model-graph-node { word-break: keep-all; }`
    - `.explorer-object-list .ui-feedback--empty { border: 0; }`
- [ ] **Step 4: 통과 확인** — 같은 명령 → 전부 PASS.
- [ ] **Step 5: 커밋(사용자 요청 시)**

### Task 4: 마무리(UI/UX 4단계 끝)

- [ ] **Step 1: `/impeccable polish 업무 데이터`** — 로컬(`demo-admin`)에서 1440·1210·1100·760px, 처음/유형 선택/객체 선택 상태와 break-ui 최악 데이터(`scratchpad/breakui/install.js`)로 확인. 근거 버튼과 관계도 그림이 1440×900 첫 화면에 보이는지, "개요" 버튼 대비가 4.5:1 이상인지 잰다. 고칠 것이 나오면 사용자에게 보이고 허락받는다.
- [ ] **Step 2: 전체 검사** — `frontend/`에서 `npx prettier --check src/`, `npm run lint`, `npx vitest run`(종료 코드 0, `Errors` 없음), `npm run build`.
