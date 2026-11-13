# 업무 데이터 화면 정리 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 업무 데이터 화면(`/explorer`)에서 겹친 박스·반복 설명·꾸밈용 보라 장식을 걷어내 확정 시안 B와 같게 만든다.

**Architecture:** 동작은 그대로 두고 `explorer.css`의 테두리·여백과 세 컴포넌트의 문구만 바꾼다. 속성 조건 안내만 전체 폭 문장 대신 입력 칸 안 문구로 옮기며, 이 부분은 테스트로 고정한다.

**Tech Stack:** React 19 · TypeScript · Vite · Vitest + Testing Library + MSW(`frontend/`)

**Spec:** 1단계 인터뷰에서 확정한 범위와 확정 시안 B. 시안: https://claude.ai/artifact/N64aGNTeA9y67ibrNka3fs (위 줄 "시안 B", 아래 줄 "시안 A·B 공통 — 객체 선택"). 수정 전 캡처는 같은 캔버스의 "수정 전" 판이다.

## Global Constraints

- 범위는 업무 데이터 화면 하나. 다른 화면·상태 표시(배지)·페이지 제목 아래 설명은 바꾸지 않는다.
- 키컬러(`--ui-brand` `#8975f6`, `--ui-brand-strong` `#6f5adf`)와 목록 선택 행의 보라 세로줄·연보라 배경은 유지한다.
- 목록·관계도·상세 세 칸의 테두리(`.explorer-panel`)는 유지한다.
- 새 색을 만들지 않는다. 회색 면은 `var(--ui-surface-subtle)`(`#f7f7f7`)만 쓴다.
- 문구는 아래 값을 그대로 쓴다.
  - 막힌 속성 조건 칸의 첫 항목: `-`(하이픈 한 글자)
  - 속성 값 칸 안내: 유형 선택 전 `객체 유형을 먼저 선택하세요`, 속성 선택 전 `속성 조건을 먼저 선택하세요`
  - 객체 관계도 범례: `화살표: 관계 방향`
- 검색·선택·페이지 이동·URL 조건 복원 동작은 바꾸지 않는다.
- 커밋은 사용자가 요청할 때만 한다. 커밋 메시지에 Co-Authored-By를 넣지 않는다.

## Review Focus

- 유형은 골랐지만 속성 목록이 불러오는 중·권한 없음(403)·실패·검색 가능한 속성 없음일 때: 속성 조건은 `-`, 속성 값 안내는 `-`, 이유는 지금처럼 칸 아래 문장으로 보인다.
- 유형 선택 전에는 칸 아래 안내 문장이 없으므로 `aria-describedby`가 없는 id를 가리키면 안 된다.
- 참/거짓(`BOOLEAN`) 속성을 고르면 안내가 `true 또는 false`로 바뀐다(기존 동작 유지).
- 유형을 바꾸거나 초기화하면 안내가 다시 처음 단계 문구로 돌아간다.
- 1199px 이하(검색 줄 2열)와 760px 이하(1열)에서 박스를 걷어낸 검색 줄과 적용 조건 줄 사이 간격이 무너지지 않는다. 이 항목은 polish 단계에서 화면으로 확인한다.

---

### Task 1: 검색 줄 — 박스 제거와 칸 안 안내

**Files:**
- Modify: `frontend/src/features/explorer/ObjectSearchBar.tsx:42-50, 111-163, 175-200`
- Modify: `frontend/src/features/explorer/explorer.css:4-15, 40-47`
- Test: `frontend/src/features/explorer/ExplorerPage.test.tsx`

**Interfaces:**
- Produces: `ObjectSearchBar.tsx` 안의 모듈 함수 `getPropertyValuePlaceholder(state: { hasType: boolean; propertySelectDisabled: boolean; selectedDataType: string | undefined }): string`. 다른 파일은 쓰지 않는다.

- [ ] **Step 1: 실패하는 테스트 작성** — 기존 테스트 `객체 유형을 선택하기 전에는 속성 조건을 비활성화한다`(163행)를 아래로 바꾸고, 새 테스트 두 개를 그 아래에 둔다.

```tsx
test('객체 유형을 선택하기 전에는 속성 조건을 막고 칸 안에서 이유를 알린다', () => {
  renderExplorerPage()

  const property = screen.getByLabelText('속성 조건')
  const value = screen.getByLabelText('속성 값 · 정확 일치')
  expect(property).toBeDisabled()
  expect(property).toHaveDisplayValue('-')
  expect(value).toBeDisabled()
  expect(value).toHaveAttribute('placeholder', '객체 유형을 먼저 선택하세요')
  expect(value).not.toHaveAttribute('aria-describedby')
  expect(
    screen.queryByText('객체 유형을 먼저 선택하면 속성 조건을 사용할 수 있습니다.'),
  ).not.toBeInTheDocument()
})

test('유형을 고르면 속성 선택 전과 후의 안내를 바꾼다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')
  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  await screen.findByRole('option', { name: '상태' })

  const value = screen.getByLabelText('속성 값 · 정확 일치')
  expect(screen.getByLabelText('속성 조건')).toHaveDisplayValue('전체 속성')
  expect(value).toHaveAttribute('placeholder', '속성 조건을 먼저 선택하세요')

  await user.selectOptions(screen.getByLabelText('속성 조건'), '상태')
  expect(value).toHaveAttribute('placeholder', '일치하는 값을 입력하세요')

  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(value).toHaveAttribute('placeholder', '객체 유형을 먼저 선택하세요')
})
```

  그리고 기존 테스트 `속성 조회 권한이 없어도 유형과 검색어 검색은 사용할 수 있다`(231행)의 `expect(screen.getByLabelText('속성 조건')).toBeDisabled()` 다음 줄에 추가한다.

```tsx
  expect(screen.getByLabelText('속성 조건')).toHaveDisplayValue('-')
  expect(screen.getByLabelText('속성 값 · 정확 일치')).toHaveAttribute('placeholder', '-')
```

- [ ] **Step 2: 실패 확인** — `frontend/`에서

  Run: `npx vitest run src/features/explorer/ExplorerPage.test.tsx`
  Expected: 위 세 테스트가 FAIL(`toHaveDisplayValue('-')`에서 "전체 속성" 수신 등). 나머지는 PASS.

- [ ] **Step 3: `ObjectSearchBar.tsx` 구현**
  - 48행 `showPropertyHelp`와 같은 조건을 `propertySelectDisabled`로 이름 붙이고, 속성 조건 `select`의 `disabled`에 그대로 쓴다.
  - 칸 아래 안내는 `typeId`가 있을 때만 보인다: `const showPropertyHelp = Boolean(typeId) && propertySelectDisabled`. 안내 블록 안의 `!typeId` 분기(`'객체 유형을 먼저 선택하면…'`)는 지운다. 두 칸의 `aria-describedby`도 이 값을 따른다.
  - 속성 조건의 첫 `<option value="">` 글자: `propertySelectDisabled ? '-' : '전체 속성'`.
  - 모듈 함수 `getPropertyValuePlaceholder`를 추가하고 속성 값 `input`의 `placeholder`에 쓴다. 순서대로 판단한다: 유형 없음 → `객체 유형을 먼저 선택하세요`, `propertySelectDisabled` → `-`, `selectedDataType` 없음 → `속성 조건을 먼저 선택하세요`, `'BOOLEAN'` → `true 또는 false`, 그 밖 → `일치하는 값을 입력하세요`.

- [ ] **Step 4: `explorer.css` 구현**
  - `.explorer-search`에서 `border`, `border-radius`, `padding`, `background` 네 줄을 지운다.
  - `.explorer-applied-filters`의 `margin: 16px 0`을 `margin: 24px 0 16px`로 바꾼다.

- [ ] **Step 5: 통과 확인**

  Run: `npx vitest run src/features/explorer/ExplorerPage.test.tsx`
  Expected: 전부 PASS, 마지막 요약에 `Errors` 줄이 없다.

- [ ] **Step 6: 커밋(사용자 요청 시)**

```bash
git add frontend/src/features/explorer/ObjectSearchBar.tsx frontend/src/features/explorer/explorer.css frontend/src/features/explorer/ExplorerPage.test.tsx
git commit -m "refactor: 업무 데이터 검색 줄의 박스를 걷어내고 속성 안내를 칸 안으로 옮김"
```

### Task 2: 관계도와 상세 칸 — 안쪽 테두리·사용법·보라 세로줄 제거

**Files:**
- Modify: `frontend/src/features/explorer/ModelGraph.tsx:272, 297-302`
- Modify: `frontend/src/features/explorer/ObjectGraph.tsx:162-166`
- Modify: `frontend/src/features/explorer/explorer.css:151-156, 218-222`
- Test: `frontend/src/features/explorer/ExplorerPage.test.tsx`

**Interfaces:**
- Consumes: 없음(Task 1과 파일만 겹치고 이름은 공유하지 않음).

- [ ] **Step 1: 실패하는 테스트 작성**
  - 기존 `객체를 고르기 전에는 객체 유형 관계도를 보여준다`(544행) 끝에 추가:

```tsx
  expect(graph.queryByText(/각 카드는 개별 객체가 아닌/)).not.toBeInTheDocument()
```

  - 기존 테스트 중 `연결된 정보 · 2건`을 확인하는 테스트(786행) 그 줄 다음에 추가:

```tsx
  expect(graph.getByText('화살표: 관계 방향')).toBeInTheDocument()
```

- [ ] **Step 2: 실패 확인**

  Run: `npx vitest run src/features/explorer/ExplorerPage.test.tsx`
  Expected: 위 두 줄이 있는 테스트 2개가 FAIL.

- [ ] **Step 3: 구현**
  - `ModelGraph.tsx`: 두 번째 `model-graph-caption` 문단("각 카드는 개별 객체가…")을 지운다. 건수 문단(`객체 유형 N개 · 관계 유형 N개`)은 둔다. 272행 그림 영역의 `bg-white`를 지운다(바탕은 CSS에서 준다).
  - `ObjectGraph.tsx`: 연결이 있을 때 문구를 `'화살표: 관계 방향'`으로 바꾼다. 연결이 없을 때 문구는 그대로 둔다.
  - `explorer.css`: `.explorer-graph-canvas`의 `border` 줄을 지우고 `background: var(--ui-surface-subtle);`를 넣는다. `.explorer-detail-hint`의 `border-left`와 `padding` 줄을 지운다.

- [ ] **Step 4: 통과 확인**

  Run: `npx vitest run src/features/explorer/ExplorerPage.test.tsx`
  Expected: 전부 PASS, `Errors` 줄 없음.

- [ ] **Step 5: 커밋(사용자 요청 시)**

```bash
git add frontend/src/features/explorer/ModelGraph.tsx frontend/src/features/explorer/ObjectGraph.tsx frontend/src/features/explorer/explorer.css frontend/src/features/explorer/ExplorerPage.test.tsx
git commit -m "refactor: 업무 데이터 관계도 안쪽 테두리와 반복 사용법 문구 정리"
```

### Task 3: 마무리 점검(UI/UX 4단계 끝)

**Files:** 위 파일만. 새 파일 없음.

- [ ] **Step 1: `/impeccable polish 업무 데이터` 실행** — 로컬(`npm run dev`, `demo-admin`)에서 1440px·1280px·760px로 열어 Review Focus의 간격 항목을 확인한다. 고칠 것이 나오면 사용자에게 보여 주고 허락받은 뒤 고친다.
- [ ] **Step 2: 전체 검사** — `frontend/`에서 차례로

  Run: `npm run format:check` → Expected: `All matched files use Prettier code style!`
  Run: `npm run lint` → Expected: 오류 0
  Run: `npx vitest run` → Expected: 전부 PASS, 종료 코드 0, `Errors` 줄 없음
  Run: `npm run build` → Expected: 종료 코드 0
