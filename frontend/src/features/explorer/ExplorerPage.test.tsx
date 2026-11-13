import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { beforeEach, expect, test } from 'vitest'
import { server } from '../../mocks/node'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import ExplorerPage from './ExplorerPage'
import type { AgentEntryState } from '../agent/types'

function renderExplorerPage() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter>
        <ExplorerPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}

beforeEach(() => {
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
})

test('객체 목록 API로 받은 객체를 보여준다', async () => {
  renderExplorerPage()

  expect(screen.getByText('불러오는 중…')).toBeInTheDocument()
  expect(await screen.findByText('프레스 3호기')).toBeInTheDocument()
  expect(screen.getByText('6건')).toBeInTheDocument()
  expect(screen.getByText('PR-03')).toBeInTheDocument()
  expect(
    within(screen.getByRole('region', { name: '객체 목록' })).getAllByText(
      '설비',
    ),
  ).toHaveLength(2)
  expect(
    await screen.findByText(
      '가운데에서 객체 유형을 고르거나 왼쪽 목록에서 객체를 선택해 주세요.',
    ),
  ).toBeInTheDocument()
})

test('검색 입력 중에는 선택을 유지하고 적용 조건이 바뀌면 상세 선택을 해제한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  const list = within(screen.getByRole('region', { name: '객체 목록' }))
  await user.click(await list.findByRole('button', { name: /프레스 3호기/ }))
  const details = within(screen.getByRole('region', { name: '상세 정보' }))
  expect(
    await details.findByRole('heading', { name: '프레스 3호기' }),
  ).toBeVisible()
  const search = screen.getByRole('searchbox', { name: 'ID · 이름 검색' })
  await user.type(search, '프레스 1호기')
  expect(details.getByRole('heading', { name: '프레스 3호기' })).toBeVisible()
  await user.keyboard('{Enter}')
  expect(
    await list.findByRole('button', { name: /프레스 1호기/ }),
  ).toBeVisible()
  expect(
    details.queryByRole('heading', { name: '프레스 3호기' }),
  ).not.toBeInTheDocument()
})

test('같은 검색 조건을 다시 적용해도 선택 객체를 유지하고 상세 시각을 KST로 표시한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  const list = within(screen.getByRole('region', { name: '객체 목록' }))
  await user.click(await list.findByRole('button', { name: /프레스 3호기/ }))
  const details = within(screen.getByRole('region', { name: '상세 정보' }))
  await details.findByRole('heading', { name: '프레스 3호기' })
  expect(details.getByText('2026-10-27 09:00:00 KST')).toBeVisible()
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(details.getByRole('heading', { name: '프레스 3호기' })).toBeVisible()
})

test('대표 속성이 없어 이름이 원천 키인 객체는 키를 한 번만 보여 준다', async () => {
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json({
        data: [
          {
            id: 1401,
            objectTypeId: 2,
            objectTypeName: '작업지시',
            displayName: 'JB2610-0141',
            properties: {},
            restrictedPropertyCodes: [],
            status: 'ACTIVE',
            sourceMappingId: 22,
            sourceKey: { wo_no: 'JB2610-0141' },
            firstCollectedAt: '2026-10-01T00:00:00Z',
          },
        ],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
  )
  renderExplorerPage()

  const list = within(await screen.findByRole('region', { name: '객체 목록' }))
  expect(await list.findByText('JB2610-0141')).toBeInTheDocument()
  expect(list.getAllByText('JB2610-0141')).toHaveLength(1)
  expect(list.queryByText('#1401')).not.toBeInTheDocument()
})

test('원천 키가 모두 가려진 객체는 null 대신 내부 번호를 한 번만 보여 준다', async () => {
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json({
        data: [
          {
            id: 1402,
            objectTypeId: 2,
            objectTypeName: '작업지시',
            displayName: '#1402',
            properties: {},
            restrictedPropertyCodes: [],
            status: 'ACTIVE',
            sourceMappingId: 22,
            sourceKey: { wo_no: null },
            firstCollectedAt: '2026-10-01T00:00:00Z',
          },
        ],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
  )
  renderExplorerPage()

  const list = within(await screen.findByRole('region', { name: '객체 목록' }))
  expect(await list.findByText('#1402')).toBeInTheDocument()
  expect(list.getAllByText('#1402')).toHaveLength(1)
  expect(list.queryByText(/null/)).not.toBeInTheDocument()
})

test('객체를 고르면 상세 API로 받은 정보를 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(
    await screen.findByRole('heading', { name: '프레스 3호기' }),
  ).toBeInTheDocument()
  expect(screen.getByText('설비 / PR-03')).toBeInTheDocument()
  expect(
    screen.getByText('MES · mes.equipment · 원천 레코드'),
  ).toBeInTheDocument()
  expect(screen.getByText('설비 코드')).toBeInTheDocument()

  const evidence = screen.getByRole('button', {
    name: '원본·연결 근거 보기 ↗',
  })
  const properties = screen.getByRole('heading', { name: '속성' })
  expect(
    evidence.compareDocumentPosition(properties) &
      Node.DOCUMENT_POSITION_FOLLOWING,
  ).toBeTruthy()
  expect(screen.getByText('원천에 있음')).toBeInTheDocument()
  expect(screen.queryByText('정상')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('heading', { name: '원천 근거' }),
  ).not.toBeInTheDocument()
})

test('권한으로 가린 속성은 권한 없음, 값이 없는 속성은 -로 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  const purchasePrice = await screen.findByText('구매 가격')
  expect(purchasePrice.nextElementSibling).toHaveTextContent('권한 없음')
  const maintenanceNote = screen.getByText('정비 메모')
  expect(maintenanceNote.nextElementSibling).toHaveTextContent('—')
})

test('원천이 없는 플랫폼 객체에는 원천에 있음 배지를 붙이지 않는다', async () => {
  server.use(
    http.get('/api/v1/objects/301', () =>
      HttpResponse.json({
        data: {
          id: 301,
          objectTypeId: 3,
          objectTypeName: '설비',
          displayName: '프레스 3호기',
          properties: {},
          propertyNames: {},
          restrictedPropertyCodes: [],
          status: 'ACTIVE',
          sourceSystemName: null,
          sourceResourceKey: null,
          firstCollectedAt: '2026-10-26T09:00:00+09:00',
        },
      }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(await screen.findByText('플랫폼 객체')).toBeInTheDocument()
  expect(screen.queryByText('원천에 있음')).not.toBeInTheDocument()
  expect(screen.getByText('기준 시점').nextElementSibling).toHaveTextContent(
    '—',
  )
  expect(screen.getByText('최근 동기화').nextElementSibling).toHaveTextContent(
    '—',
  )
})

test('속성 표시 이름이 없으면 속성 코드를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects/301', () =>
      HttpResponse.json({
        data: {
          id: 301,
          objectTypeId: 3,
          objectTypeName: '설비',
          displayName: '프레스 3호기',
          properties: { equipment_code: 'PR-03', vendor_code: 'V-01' },
          propertyNames: { equipment_code: '설비 코드' },
          restrictedPropertyCodes: [],
          status: 'ACTIVE',
          firstCollectedAt: '2026-10-26T09:00:00+09:00',
        },
      }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(await screen.findByText('설비 코드')).toBeInTheDocument()
  expect(screen.getByText('vendor_code')).toBeInTheDocument()
})

test('검색 결과가 없으면 안내 문구를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  renderExplorerPage()

  expect(
    await screen.findByText('조건에 맞는 객체가 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getByText('0건')).toBeInTheDocument()
})

test('검색어로 검색하면 맞는 객체만 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.type(await screen.findByLabelText('ID · 이름 검색'), '프레스')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('3건')).toBeInTheDocument()
  expect(screen.getByText('프레스 작업')).toBeInTheDocument()
  expect(screen.queryByText('야간 인수인계')).not.toBeInTheDocument()
})

test('객체 유형을 고르고 검색하면 그 유형의 객체만 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')

  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('2건')).toBeInTheDocument()
  expect(screen.getByText('프레스 1호기')).toBeInTheDocument()
  expect(screen.queryByText('프레스 작업')).not.toBeInTheDocument()
})

test('초기화하면 검색 조건을 비우고 전체 목록을 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')

  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  await user.type(screen.getByLabelText('ID · 이름 검색'), '3호기')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('1건')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '초기화' }))

  expect(await screen.findByText('6건')).toBeInTheDocument()
  expect(screen.getByLabelText('객체 유형')).toHaveValue('')
  expect(screen.getByLabelText('ID · 이름 검색')).toHaveValue('')
})

test('객체 유형을 선택하기 전에는 속성 조건을 막고 칸 안에서 이유를 알린다', () => {
  renderExplorerPage()

  const property = screen.getByLabelText('속성 조건')
  const value = screen.getByLabelText('속성 값 · 정확 일치')
  expect(property).toBeDisabled()
  expect(property).toHaveDisplayValue('-')
  expect(value).toBeDisabled()
  expect(value).toHaveAttribute('placeholder', '객체 유형을 먼저 선택하세요')
  expect(property).toHaveAccessibleDescription('객체 유형을 먼저 선택하세요')
  expect(value).toHaveAccessibleDescription('객체 유형을 먼저 선택하세요')
  expect(
    screen.queryByText(
      '객체 유형을 먼저 선택하면 속성 조건을 사용할 수 있습니다.',
    ),
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

test('유형의 속성 값으로 정확 일치 검색하고 초기화한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')
  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  await screen.findByRole('option', { name: '상태' })
  await user.selectOptions(screen.getByLabelText('속성 조건'), '상태')
  await user.type(screen.getByLabelText('속성 값 · 정확 일치'), '정')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('0건')).toBeInTheDocument()

  await user.type(screen.getByLabelText('속성 값 · 정확 일치'), '지')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('1건')).toBeInTheDocument()
  expect(screen.getByText('프레스 3호기')).toBeInTheDocument()
  expect(screen.queryByText('프레스 1호기')).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(await screen.findByText('6건')).toBeInTheDocument()
  expect(screen.getByLabelText('속성 조건')).toHaveValue('')
  expect(screen.getByLabelText('속성 값 · 정확 일치')).toHaveValue('')
  expect(screen.getByLabelText('속성 값 · 정확 일치')).toBeDisabled()
})

test('유형을 변경하면 이전 속성과 값을 제거한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')
  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  await screen.findByRole('option', { name: '설비 코드' })
  await user.selectOptions(screen.getByLabelText('속성 조건'), '설비 코드')
  await user.type(screen.getByLabelText('속성 값 · 정확 일치'), 'PR-03')
  await user.selectOptions(screen.getByLabelText('객체 유형'), '작업지시')
  await screen.findByRole('option', { name: '작업지시 번호' })
  expect(
    screen.queryByRole('option', { name: '설비 코드' }),
  ).not.toBeInTheDocument()
  expect(screen.getByLabelText('속성 조건')).toHaveValue('')
  expect(screen.getByLabelText('속성 값 · 정확 일치')).toHaveValue('')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('1건')).toBeInTheDocument()
  expect(screen.getByText('프레스 작업')).toBeInTheDocument()
})

test('유형 카드를 선택해도 이전 속성 조건을 제거한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')
  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  await screen.findByRole('option', { name: '상태' })
  await user.selectOptions(screen.getByLabelText('속성 조건'), '상태')
  await user.type(screen.getByLabelText('속성 값 · 정확 일치'), '정지')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('1건')).toBeInTheDocument()
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: /작업지시/ }))
  expect(await screen.findByText('프레스 작업')).toBeInTheDocument()
  expect(screen.getByLabelText('속성 조건')).toHaveValue('')
  expect(screen.getByLabelText('속성 값 · 정확 일치')).toHaveValue('')
})

test('속성 조회 권한이 없어도 유형과 검색어 검색은 사용할 수 있다', async () => {
  server.use(
    http.get('/api/v1/ontology/object-types/:id/properties', () =>
      HttpResponse.json({ error: { message: '권한 없음' } }, { status: 403 }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')
  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  expect(
    await screen.findByText('이 계정은 속성 목록 조회 권한이 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('속성 조건')).toBeDisabled()
  expect(screen.getByLabelText('속성 조건')).toHaveDisplayValue('-')
  expect(screen.getByLabelText('속성 값 · 정확 일치')).toHaveAttribute(
    'placeholder',
    '-',
  )
  await user.type(screen.getByLabelText('ID · 이름 검색'), '3호기')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('1건')).toBeInTheDocument()
})

test('속성 조회 실패 시 재시도로 복구하고 JSON 속성은 제외한다', async () => {
  let failed = true
  server.use(
    http.get('/api/v1/ontology/object-types/:id/properties', () =>
      failed
        ? HttpResponse.json({}, { status: 500 })
        : HttpResponse.json({
            data: [
              { id: 33, code: 'status', name: '상태', dataType: 'STRING' },
              { id: 34, code: 'payload', name: '원문 JSON', dataType: 'JSON' },
            ],
          }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')
  await user.selectOptions(screen.getByLabelText('객체 유형'), '설비')
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '속성 목록을 불러오지 못했습니다.',
  )
  expect(screen.getByLabelText('속성 조건')).toBeDisabled()
  failed = false
  await user.click(screen.getByRole('button', { name: '속성 다시 불러오기' }))
  expect(
    await screen.findByRole('option', { name: '상태' }),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('속성 조건')).toBeEnabled()
  expect(
    screen.queryByRole('option', { name: '원문 JSON' }),
  ).not.toBeInTheDocument()
})

test('검색이 실패해도 오류와 함께 검색창을 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')

  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: '서버 내부 오류',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 500 },
      ),
    ),
  )
  await user.type(screen.getByLabelText('ID · 이름 검색'), '프레스')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('서버 내부 오류')).toBeInTheDocument()
  expect(screen.getByLabelText('ID · 이름 검색')).toBeInTheDocument()
})

test('상세 조회 권한이 없으면 서버가 준 안내를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects/301', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '요청을 실행할 권한이 없습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 403 },
      ),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(
    await screen.findByText('요청을 실행할 권한이 없습니다.'),
  ).toBeInTheDocument()
})

async function openEvidenceDialog(objectName: RegExp) {
  const user = userEvent.setup()
  renderExplorerPage()
  await user.click(await screen.findByRole('button', { name: objectName }))
  await user.click(
    await screen.findByRole('button', { name: '원본·연결 근거 보기 ↗' }),
  )
  return user
}

test('근거 보기를 누르면 원천 위치와 연결 근거를 팝업으로 보여준다', async () => {
  const user = await openEvidenceDialog(/프레스 3호기/)

  expect(
    screen.getByRole('dialog', { name: '원본·연결 근거 · 프레스 3호기' }),
  ).toBeInTheDocument()
  const dialog = within(screen.getByRole('dialog'))
  expect(dialog.getByText('mes.equipment')).toBeInTheDocument()
  expect(dialog.getByText('equipment_code: PR-03')).toBeInTheDocument()
  expect(await dialog.findByText('현재 배정 설비')).toBeInTheDocument()
  expect(dialog.getAllByText('들어오는 연결')).toHaveLength(2)
  expect(dialog.getByText('비교 값 PR-03 일치')).toBeInTheDocument()
  expect(
    dialog.getByText('원문: “야간에 3호기 진동이 커졌다.”'),
  ).toBeInTheDocument()
  expect(dialog.getByText('추정')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '닫기' }))

  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('관리자가 직접 만든 연결은 만든 방법을 보여준다', async () => {
  await openEvidenceDialog(/프레스 작업/)
  const dialog = within(screen.getByRole('dialog'))

  expect(await dialog.findByText('대체 가능 설비')).toBeInTheDocument()
  expect(dialog.getAllByText('나가는 연결')).toHaveLength(3)
  expect(dialog.getByText('관리자가 직접 연결')).toBeInTheDocument()
})

function mockRuleEvidence(evidence: Record<string, unknown>) {
  server.use(
    http.get('/api/v1/objects/:id/links', () =>
      HttpResponse.json({
        data: [
          {
            id: 21,
            linkTypeId: 2,
            linkTypeName: '현재 배정 설비',
            sourceObjectId: 1101,
            targetObjectId: 301,
            relatedObject: {
              id: 1101,
              displayName: '프레스 작업',
              objectTypeId: 11,
              objectTypeName: '작업지시',
            },
            origin: 'RULE',
            status: 'ACTIVE',
            linkMappingId: 610,
            evidence,
            expressionKind: null,
            confirmedBy: null,
          },
        ],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
  )
}

test('변환 전 값이 없는 예전 관계는 목록에 표시하고 변환 결과는 생략한다', async () => {
  mockRuleEvidence({
    sourceKey: { work_order_no: 'WO-101' },
    transformedValue: 'LEGACY-CODE',
  })
  const user = await openEvidenceDialog(/프레스 3호기/)
  const dialog = within(screen.getByRole('dialog'))

  expect(await dialog.findByText('현재 배정 설비')).toBeInTheDocument()
  expect(dialog.getByText('프레스 작업')).toBeInTheDocument()
  expect(dialog.queryAllByText(/LEGACY-CODE/)).toHaveLength(0)
  await user.click(dialog.getByText('상세 근거'))
  expect(dialog.getByText('work_order_no: WO-101')).toBeVisible()
  expect(
    dialog.queryByRole('table', { name: '비교 조건' }),
  ).not.toBeInTheDocument()
})

test('들어오는 관계에서도 여러 조건의 출발과 도착 값을 같은 방향으로 보여준다', async () => {
  mockRuleEvidence({
    rawValue: { 1: 'pr03', 2: 'Plant A' },
    transformedValue: { 1: 'PR-03', 2: 'PLANT-A' },
    comparisons: [
      {
        sequenceNo: 1,
        sourceProperty: 'assigned_equipment',
        sourceColumn: 'equipment_id',
        sourceValue: 'pr03',
        targetProperty: 'equipment_code',
        targetValue: 'PR-03',
        matchedValue: 'PR-03',
      },
      {
        sequenceNo: 2,
        sourceProperty: 'plant_id',
        sourceValue: 'Plant A',
        targetProperty: 'plant_code',
        targetValue: 'PLANT-A',
        matchedValue: 'PLANT-A',
      },
    ],
  })
  const user = await openEvidenceDialog(/프레스 3호기/)
  const dialog = within(screen.getByRole('dialog'))
  await dialog.findByText('현재 배정 설비')
  await user.click(dialog.getByText('상세 근거'))

  const comparisons = within(dialog.getByRole('table', { name: '비교 조건' }))
  expect(
    comparisons.getByRole('columnheader', { name: '출발 원본' }),
  ).toBeInTheDocument()
  expect(
    comparisons.getByRole('columnheader', { name: '비교 대상' }),
  ).toBeInTheDocument()
  const rows = comparisons.getAllByRole('row')
  const firstCondition = within(rows[1])
  expect(firstCondition.getByText('assigned_equipment = pr03')).toBeVisible()
  expect(firstCondition.getByText('현재 원천 컬럼 equipment_id')).toBeVisible()
  expect(firstCondition.getByText('equipment_code = PR-03')).toBeVisible()
  expect(
    within(rows[2])
      .getAllByRole('cell')
      .map((cell) => cell.textContent),
  ).toEqual(['plant_id = Plant A', 'plant_code = PLANT-A'])
  expect(dialog.queryByText(/\[object Object\]/)).not.toBeInTheDocument()
})

test('권한으로 근거가 비어 있는 관계는 빈 상세 근거를 만들지 않는다', async () => {
  mockRuleEvidence({})
  await openEvidenceDialog(/프레스 3호기/)
  const dialog = within(screen.getByRole('dialog'))

  expect(await dialog.findByText('현재 배정 설비')).toBeInTheDocument()
  expect(dialog.queryByText('상세 근거')).not.toBeInTheDocument()
})

test('연결이 없으면 안내 문구를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects/:id/links', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  await openEvidenceDialog(/프레스 3호기/)

  expect(
    await screen.findByText('현재 연결된 정보가 없습니다.'),
  ).toBeInTheDocument()
})

test('연결 조회가 실패해도 원천 위치는 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects/:id/links', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '요청을 실행할 권한이 없습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 403 },
      ),
    ),
  )
  await openEvidenceDialog(/프레스 3호기/)
  const dialog = within(screen.getByRole('dialog'))

  expect(
    await dialog.findByText('요청을 실행할 권한이 없습니다.'),
  ).toBeInTheDocument()
  expect(dialog.getByText('mes.equipment')).toBeInTheDocument()
})

test('Esc 키를 누르면 근거 팝업이 닫힌다', async () => {
  const user = await openEvidenceDialog(/프레스 3호기/)

  await user.keyboard('{Escape}')

  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('팝업 바깥을 누르면 닫히고 안쪽을 누르면 닫히지 않는다', async () => {
  const user = await openEvidenceDialog(/프레스 3호기/)
  const dialog = screen.getByRole('dialog')

  await user.click(screen.getByText('원천 위치'))
  expect(dialog).toBeInTheDocument()

  await user.click(dialog.parentElement!)
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('객체를 고르기 전에는 객체 유형 관계도를 보여준다', async () => {
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  expect(
    await graph.findByRole('heading', { name: '객체 유형 간 관계' }),
  ).toBeInTheDocument()
  expect(graph.getByRole('button', { name: /작업지시/ })).toBeInTheDocument()
  // 이름표는 유형을 고른 뒤 그 유형의 선에만 보인다.
  expect(graph.queryByText('생산 대상 수주 · 1건')).not.toBeInTheDocument()
  expect(graph.getByText('객체 유형 6개 · 관계 유형 5개')).toBeInTheDocument()
  expect(
    graph.queryByText(/각 카드는 개별 객체가 아닌/),
  ).not.toBeInTheDocument()
  const heading = graph.getByRole('heading', { name: '객체 유형 간 관계' })
  const viewGroup = graph.getByRole('group', { name: '보기 방식' })
  expect(heading.parentElement).toContainElement(viewGroup)
  expect(
    within(viewGroup).getByRole('button', { name: '개요' }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(graph.queryByText('객체 유형')).not.toBeInTheDocument()
  expect(
    graph.getByRole('button', { name: '객체 유형 설비' }),
  ).toBeInTheDocument()
  expect(
    graph.getByText('점선: 현재 연결 0건 · 화살표: 관계 방향'),
  ).toBeInTheDocument()
})

test('목록 건수는 천 단위 쉼표로 표시한다', async () => {
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 5064, hasNext: true },
      }),
    ),
  )
  renderExplorerPage()
  expect(await screen.findByText('5,064건')).toBeInTheDocument()
})

test('유형 목록으로 바꾼 보기는 객체를 골랐다 돌아와도 유지한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: '유형 목록' }))
  expect(graph.getByLabelText('객체 유형 목록')).toBeInTheDocument()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  expect(
    await graph.findByRole('heading', { name: '프레스 3호기와 연결된 정보' }),
  ).toBeInTheDocument()
  expect(
    graph.queryByRole('group', { name: '보기 방식' }),
  ).not.toBeInTheDocument()

  await user.click(graph.getByRole('button', { name: '← 전체 구조로' }))
  expect(await graph.findByLabelText('객체 유형 목록')).toBeInTheDocument()
})

test('같은 두 유형 사이의 관계는 선 하나에 이름을 모아 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: '객체 유형 설비' }))
  const assigned = await graph.findByText('현재 배정 설비 · 1건')
  const alternative = graph.getByText('대체 가능 설비 · 1건')

  expect(assigned.parentElement).toBe(alternative.parentElement)
})

test('고른 유형의 선은 카드 아래에 두고 다른 선보다 위에 그린다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: '객체 유형 설비' }))
  await graph.findByText('현재 배정 설비 · 1건')

  const edges = [...document.querySelectorAll('.react-flow__edge')]
  for (const edge of edges) {
    expect(Number(edge.closest('svg')?.style.zIndex || 0)).toBeLessThan(1)
  }
  const lastPath = edges.at(-1)?.querySelector('.react-flow__edge-path')
  expect(lastPath?.getAttribute('style')).toMatch(/6f5adf|111, 90, 223/i)
})

test('유형을 고르면 그 유형의 관계 이름표만 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: '객체 유형 설비' }))

  expect(await graph.findByText('현재 배정 설비 · 1건')).toBeInTheDocument()
  expect(graph.queryByText('생산 대상 수주 · 1건')).not.toBeInTheDocument()
})

test('키보드로 관계도를 지날 때는 유형 카드에만 멈춘다', async () => {
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await graph.findByRole('button', { name: '객체 유형 설비' })

  // 카드를 감싼 칸과 선은 고를 수 없으므로 Tab 순서에 넣지 않는다.
  expect(
    document.querySelectorAll(
      '.react-flow__node[tabindex], .react-flow__edge[tabindex]',
    ),
  ).toHaveLength(0)
})

test('유형 카드를 누르면 그 유형의 객체 목록과 관계를 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: /설비/ }))

  expect(await screen.findByText('2건')).toBeInTheDocument()
  expect(screen.getByLabelText('객체 유형')).toHaveValue('3')
  expect(graph.getByRole('button', { name: /설비/ })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(screen.getByRole('heading', { name: '설비' })).toBeInTheDocument()
  expect(screen.getByText('들어오는 관계 4건')).toBeInTheDocument()
  expect(screen.getByText('작업지시 → 설비 · N:1')).toBeInTheDocument()
  expect(screen.getByText('활성 연결 1건 · 전체 2건')).toBeInTheDocument()
})

test('객체 수가 없는 ADMIN 응답으로 활성 연결과 전체 연결을 구분해 보여준다', async () => {
  server.use(
    http.get('/api/v1/ontology/model-graph', ({ request }) => {
      expect(new URL(request.url).search).toBe('')
      return HttpResponse.json({
        data: {
          nodes: [
            { id: 3, code: 'EQUIPMENT', name: '설비' },
            { id: 4, code: 'WORK_ORDER', name: '작업지시' },
          ],
          edges: [
            {
              id: 2,
              code: 'ASSIGNED_EQUIPMENT',
              name: '현재 배정 설비',
              sourceTypeId: 4,
              targetTypeId: 3,
              cardinality: 'MANY_TO_ONE',
              relationCount: 3,
              activeLinkCount: 1,
            },
          ],
        },
      })
    }),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: /설비/ }))

  expect(
    await screen.findByText('활성 연결 1건 · 전체 3건'),
  ).toBeInTheDocument()
  expect(graph.getByText('현재 배정 설비 · 1건')).toBeInTheDocument()
  expect(graph.queryByText(/객체 \d+개/)).not.toBeInTheDocument()
})

test('검색어가 있을 때 유형 카드를 누르면 검색어는 두고 유형만 바꾼다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await screen.findByText('6건')

  await user.type(screen.getByLabelText('ID · 이름 검색'), '프레스')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('3건')).toBeInTheDocument()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(graph.getByRole('button', { name: /설비/ }))

  expect(await screen.findByText('2건')).toBeInTheDocument()
  expect(screen.getByLabelText('ID · 이름 검색')).toHaveValue('프레스')
  expect(screen.getByLabelText('객체 유형')).toHaveValue('3')
})

test('객체 수를 받지 않은 유형도 선택해 빈 객체 목록을 조회할 수 있다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const emptyType = await graph.findByRole('button', { name: /점검 요청/ })

  expect(emptyType).toBeEnabled()
  expect(emptyType).not.toHaveTextContent('객체 0개')
  await user.click(emptyType)
  expect(
    await screen.findByText('조건에 맞는 객체가 없습니다.'),
  ).toBeInTheDocument()
})

test('객체를 고른 뒤 전체 구조로를 누르면 유형 관계도로 돌아간다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  await user.click(screen.getByRole('button', { name: '← 전체 구조로' }))

  expect(
    await screen.findByRole('heading', { name: '객체 유형 간 관계' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText(
      '가운데에서 객체 유형을 고르거나 왼쪽 목록에서 객체를 선택해 주세요.',
    ),
  ).toBeInTheDocument()
})

test.each(['PLANNER', 'MAINTENANCE', 'VIEWER'] as const)(
  '%s는 403 뒤 객체 선택 안내만 보여준다',
  async (roleCode) => {
    setMockAccount({ ...demoAccount, roleCode })
    renderExplorerPage()

    expect(
      await screen.findByText('객체를 선택하면 연결 관계를 표시합니다.'),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('heading', { name: '객체 유형 간 관계' }),
    ).not.toBeInTheDocument()
  },
)

test.each(['server', 'network'])(
  '%s 오류는 안내와 재시도를 제공하고 성공하면 관계도를 복구한다',
  async (kind) => {
    server.use(
      http.get('/api/v1/ontology/model-graph', () =>
        kind === 'network'
          ? HttpResponse.error()
          : HttpResponse.json(
              {
                error: {
                  code: 'INTERNAL_ERROR',
                  message: '일시적인 오류입니다.',
                  details: {},
                  requestId: 'test-042',
                },
              },
              { status: 500 },
            ),
      ),
    )
    const user = userEvent.setup()
    renderExplorerPage()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '유형 관계도를 불러오지 못했습니다.',
    )
    expect(
      screen.queryByText('객체를 선택하면 연결 관계를 표시합니다.'),
    ).not.toBeInTheDocument()
    server.resetHandlers()
    await user.click(screen.getByRole('button', { name: '다시 시도' }))
    expect(
      await screen.findByRole('heading', { name: '객체 유형 간 관계' }),
    ).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  },
)

test('이미 받은 관계도는 새로고침 실패 시 유지하고 실패 안내를 보여준다', async () => {
  const client = renderExplorerPage()
  await screen.findByRole('heading', { name: '객체 유형 간 관계' })
  server.use(
    http.get(
      '/api/v1/ontology/model-graph',
      () => new HttpResponse(null, { status: 500 }),
    ),
  )
  await client.invalidateQueries({ queryKey: ['ontology', 'model-graph'] })
  expect(await screen.findByRole('alert')).toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: '객체 유형 간 관계' }),
  ).toBeInTheDocument()
})

test('자기 유형 관계도 관계선과 유형 상세에 표시한다', async () => {
  server.use(
    http.get('/api/v1/ontology/model-graph', () =>
      HttpResponse.json({
        data: {
          nodes: [{ id: 4, code: 'WORK_ORDER', name: '작업지시' }],
          edges: [
            {
              id: 9,
              code: 'RELATED_ORDER',
              name: '연관 작업',
              sourceTypeId: 4,
              targetTypeId: 4,
              cardinality: 'MANY_TO_MANY',
              relationCount: 2,
              activeLinkCount: 0,
            },
          ],
        },
      }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await graph.findByRole('button', { name: /작업지시/ })
  const edge = document.querySelector('.react-flow__edge path')
  expect(edge?.getAttribute('d')).not.toMatch(/NaN/)
  expect(edge?.getAttribute('d')).toContain('C')
  await user.click(graph.getByRole('button', { name: /작업지시/ }))
  // 이름표는 유형을 고른 뒤에 보인다.
  expect(await graph.findByText('연관 작업 · 0건')).toBeInTheDocument()
  expect(
    await screen.findByText('작업지시 → 작업지시 · N:M'),
  ).toBeInTheDocument()
  expect(screen.getByText('활성 연결 0건 · 전체 2건')).toBeInTheDocument()
})

test('관계도에서 연결된 객체 카드를 누르면 그 객체로 이동한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))

  expect(await graph.findByText('연결된 정보 · 2건')).toBeInTheDocument()
  expect(graph.getByText('화살표: 관계 방향')).toBeInTheDocument()
  expect(
    graph.getByRole('heading', { name: '프레스 3호기와 연결된 정보' }),
  ).toBeInTheDocument()
  expect(await graph.findByText('#1101')).toBeInTheDocument()

  await user.click(graph.getByRole('button', { name: /프레스 작업/ }))

  expect(
    await screen.findByRole('heading', { name: '프레스 작업' }),
  ).toBeInTheDocument()
  expect(await graph.findByText('연결된 정보 · 3건')).toBeInTheDocument()
  expect(
    graph.getByRole('heading', { name: '프레스 작업과 연결된 정보' }),
  ).toBeInTheDocument()
})

test('연결이 없으면 관계도에 안내를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects/:id/link-groups', () =>
      HttpResponse.json({ data: [] }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(
    await screen.findByText('이 객체에는 연결된 관계가 없습니다.'),
  ).toBeInTheDocument()
})

function manyLink(index: number, maintenance = false) {
  return {
    id: 9000 + index,
    linkTypeId: maintenance ? 8 : 7,
    linkTypeName: maintenance ? '정비 대상 설비' : '불량 발생 설비',
    sourceObjectId: 5000 + index,
    targetObjectId: 301,
    relatedObject: {
      id: 5000 + index,
      displayName: maintenance ? `정비 ${index}` : `불량 ${index}`,
      objectTypeId: maintenance ? 24 : 19,
      objectTypeName: maintenance ? '정비 지시' : '불량 기록',
    },
    origin: 'RULE',
    status: 'ACTIVE',
    evidence: {},
  }
}

type ManyLink = ReturnType<typeof manyLink>

// 불량 기록 연결 뒤에 정비 지시 연결을 둔다. 전체를 100건씩 받으면 첫 쪽에 정비 지시가 없다.
function mockManyLinks(
  defects: number,
  {
    maintenance = 0,
    failPage,
    changeAfterFirstPage,
    staleDefectCount,
    failGroupRefetch = false,
  }: {
    maintenance?: number
    failPage?: number
    changeAfterFirstPage?: (links: ManyLink[]) => ManyLink[]
    // 첫 묶음 건수 응답만 불량 기록 건수를 이 값으로 준다. 그 사이 연결이 바뀐 상황이다.
    staleDefectCount?: number
    failGroupRefetch?: boolean
  } = {},
) {
  const searches: string[] = []
  const groupCalls = { count: 0 }
  const initial = [
    ...Array.from({ length: defects }, (_, index) => manyLink(index)),
    ...Array.from({ length: maintenance }, (_, index) =>
      manyLink(defects + index, true),
    ),
  ]
  const changed = changeAfterFirstPage?.(initial) ?? initial
  const groupsOf = (defectCount: number) =>
    [
      { ...manyLink(0), linkCount: defectCount },
      { ...manyLink(0, true), linkCount: maintenance },
    ]
      .map((link) => ({
        linkTypeId: link.linkTypeId,
        linkTypeName: link.linkTypeName,
        direction: 'IN',
        relatedObjectTypeId: link.relatedObject.objectTypeId,
        relatedObjectTypeName: link.relatedObject.objectTypeName,
        linkCount: link.linkCount,
      }))
      .filter((group) => group.linkCount > 0)
  server.use(
    http.get('/api/v1/objects/:id/link-groups', () => {
      groupCalls.count += 1
      if (groupCalls.count === 1) {
        return HttpResponse.json({
          data: groupsOf(staleDefectCount ?? defects),
        })
      }
      if (failGroupRefetch) {
        return HttpResponse.json(
          {
            error: {
              code: 'INTERNAL_ERROR',
              message: '관계 묶음을 다시 불러오지 못했습니다.',
              details: {},
              requestId: 'mock-request-id',
            },
          },
          { status: 500 },
        )
      }
      return HttpResponse.json({ data: groupsOf(defects) })
    }),
    http.get('/api/v1/objects/:id/links', ({ request }) => {
      const url = new URL(request.url)
      searches.push(url.search)
      const page = Number(url.searchParams.get('page'))
      const size = Number(url.searchParams.get('size'))
      const linkTypeId = url.searchParams.get('linkTypeId')
      const all = (page === 0 ? initial : changed).filter(
        (link) => linkTypeId === null || link.linkTypeId === Number(linkTypeId),
      )
      if (page === failPage) {
        return HttpResponse.json(
          {
            error: {
              code: 'INTERNAL_ERROR',
              message: '연결을 더 불러오지 못했습니다.',
              details: {},
              requestId: 'mock-request-id',
            },
          },
          { status: 500 },
        )
      }
      return HttpResponse.json({
        data: all.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalElements: all.length,
          hasNext: (page + 1) * size < all.length,
        },
      })
    }),
  )
  return { searches, groupCalls }
}

test('한 관계 유형의 연결이 100건을 넘어도 첫 화면에 모든 관계 유형 묶음과 건수를 보여준다', async () => {
  const { searches } = mockManyLinks(230, { maintenance: 3 })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))

  expect(await graph.findByText('연결된 정보 · 233건')).toBeInTheDocument()
  expect(
    graph.getByRole('button', { name: /불량 기록\s*230건/ }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(
    graph.getByRole('button', { name: /정비 지시\s*3건/ }),
  ).toHaveAttribute('aria-pressed', 'false')
  expect(graph.getAllByText('불량 발생 설비')).toHaveLength(1)
  expect(graph.getByText('정비 대상 설비')).toBeInTheDocument()

  const defectList = within(
    graph.getByRole('region', { name: '불량 기록 연결 목록' }),
  )
  expect(
    defectList.getByRole('heading', {
      name: '불량 발생 설비 · 불량 기록 230건',
    }),
  ).toBeInTheDocument()
  expect(await defectList.findByText('전체 230건 중 100건')).toBeInTheDocument()
  expect(defectList.getAllByRole('button', { name: /불량 \d+/ })).toHaveLength(
    100,
  )
  expect(searches).toEqual(['?linkTypeId=7&direction=IN&page=0&size=100'])
})

test('묶음 안에서 더 불러오기를 누르면 그 묶음의 다음 쪽을 이어 보여준다', async () => {
  const { searches } = mockManyLinks(230, { maintenance: 3 })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const defectList = within(
    await graph.findByRole('region', { name: '불량 기록 연결 목록' }),
  )
  await user.click(
    await defectList.findByRole('button', { name: '더 불러오기' }),
  )

  expect(await defectList.findByText('전체 230건 중 200건')).toBeInTheDocument()
  expect(searches).toContain('?linkTypeId=7&direction=IN&page=1&size=100')

  await user.click(defectList.getByRole('button', { name: '더 불러오기' }))

  expect(
    await defectList.findByRole('button', { name: /불량 229/ }),
  ).toBeInTheDocument()
  expect(defectList.getAllByRole('button', { name: /불량 \d+/ })).toHaveLength(
    230,
  )
  expect(
    defectList.queryByRole('button', { name: '더 불러오기' }),
  ).not.toBeInTheDocument()
  expect(graph.getByText('연결된 정보 · 233건')).toBeInTheDocument()
})

test('유형 카드를 고르면 그 유형의 객체 목록을 보여준다', async () => {
  const { searches } = mockManyLinks(230, { maintenance: 3 })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(
    await graph.findByRole('button', { name: /정비 지시\s*3건/ }),
  )

  expect(
    graph.queryByRole('region', { name: '불량 기록 연결 목록' }),
  ).not.toBeInTheDocument()
  const maintenanceList = within(
    graph.getByRole('region', { name: '정비 지시 연결 목록' }),
  )
  expect(
    maintenanceList.getByRole('heading', {
      name: '정비 대상 설비 · 정비 지시 3건',
    }),
  ).toBeInTheDocument()
  expect(
    await maintenanceList.findAllByRole('button', { name: /정비 \d+/ }),
  ).toHaveLength(3)
  expect(
    maintenanceList.queryByRole('button', { name: '더 불러오기' }),
  ).not.toBeInTheDocument()
  expect(searches).toContain('?linkTypeId=8&direction=IN&page=0&size=100')
})

test('묶음의 다음 쪽을 불러오지 못해도 이미 불러온 목록은 유지한다', async () => {
  mockManyLinks(230, { failPage: 1 })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const defectList = within(
    await graph.findByRole('region', { name: '불량 기록 연결 목록' }),
  )
  await user.click(
    await defectList.findByRole('button', { name: '더 불러오기' }),
  )

  expect(
    await defectList.findByText('연결을 더 불러오지 못했습니다.'),
  ).toBeInTheDocument()
  expect(defectList.getAllByRole('button', { name: /불량 \d+/ })).toHaveLength(
    100,
  )
  expect(
    graph.getByRole('button', { name: /불량 기록\s*230건/ }),
  ).toBeInTheDocument()
})

test('다음 쪽을 받는 사이 연결이 줄면 더 불러오기를 숨긴다', async () => {
  mockManyLinks(230, {
    changeAfterFirstPage: (links) => links.slice(0, 100),
  })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const defectList = within(
    await graph.findByRole('region', { name: '불량 기록 연결 목록' }),
  )
  await user.click(
    await defectList.findByRole('button', { name: '더 불러오기' }),
  )

  await waitFor(() =>
    expect(
      defectList.queryByRole('button', { name: '더 불러오기' }),
    ).not.toBeInTheDocument(),
  )
  expect(defectList.getAllByRole('button', { name: /불량 \d+/ })).toHaveLength(
    100,
  )
})

test('다음 쪽을 받는 사이 앞쪽 연결이 늘어도 같은 연결은 한 번만 보여준다', async () => {
  mockManyLinks(230, {
    changeAfterFirstPage: (links) => [
      ...Array.from({ length: 5 }, (_, index) => ({
        ...manyLink(1),
        id: 8000 + index,
      })),
      ...links,
    ],
  })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const defectList = within(
    await graph.findByRole('region', { name: '불량 기록 연결 목록' }),
  )
  await user.click(
    await defectList.findByRole('button', { name: '더 불러오기' }),
  )

  expect(await defectList.findByText('전체 235건 중 195건')).toBeInTheDocument()
  expect(defectList.getAllByRole('button', { name: /불량 \d+/ })).toHaveLength(
    195,
  )
})

test('묶음 건수와 목록 건수가 다르면 묶음 건수를 다시 받아 맞춘다', async () => {
  const { groupCalls } = mockManyLinks(228, {
    maintenance: 3,
    staleDefectCount: 230,
  })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))

  expect(
    await graph.findByRole('button', { name: /불량 기록\s*228건/ }),
  ).toBeInTheDocument()
  expect(
    graph.getByRole('heading', { name: '불량 발생 설비 · 불량 기록 228건' }),
  ).toBeInTheDocument()
  expect(graph.getByText('전체 228건 중 100건')).toBeInTheDocument()
  expect(graph.getByText('연결된 정보 · 231건')).toBeInTheDocument()
  expect(groupCalls.count).toBe(2)
})

test('묶음 건수를 다시 받지 못해도 이미 그린 관계도는 유지한다', async () => {
  const { groupCalls } = mockManyLinks(228, {
    maintenance: 3,
    staleDefectCount: 230,
    failGroupRefetch: true,
  })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const defectList = within(
    await graph.findByRole('region', { name: '불량 기록 연결 목록' }),
  )
  expect(await defectList.findByText('전체 228건 중 100건')).toBeInTheDocument()
  await waitFor(() => expect(groupCalls.count).toBe(2))

  expect(
    graph.getByRole('heading', { name: '프레스 3호기와 연결된 정보' }),
  ).toBeInTheDocument()
  expect(
    graph.getByRole('button', { name: /불량 기록\s*230건/ }),
  ).toBeInTheDocument()
  expect(
    graph.queryByText('관계 묶음을 다시 불러오지 못했습니다.'),
  ).not.toBeInTheDocument()
  expect(defectList.getAllByRole('button', { name: /불량 \d+/ })).toHaveLength(
    100,
  )
})

test('고른 묶음의 연결이 그 사이 없어졌으면 빈 목록 안내를 보여준다', async () => {
  mockManyLinks(0, {
    maintenance: 3,
    staleDefectCount: 5,
    failGroupRefetch: true,
  })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const defectList = within(
    await graph.findByRole('region', { name: '불량 기록 연결 목록' }),
  )

  expect(
    await defectList.findByText('지금은 이 묶음에 연결이 없습니다.'),
  ).toBeInTheDocument()
  expect(defectList.queryByRole('list')).not.toBeInTheDocument()
})

test('묶음 건수를 다시 받아 순서가 바뀌어도 보던 묶음을 그대로 보여준다', async () => {
  const { groupCalls } = mockManyLinks(2, {
    maintenance: 3,
    staleDefectCount: 5,
  })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))

  expect(
    await graph.findByRole('button', { name: /불량 기록\s*2건/ }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(groupCalls.count).toBe(2)
  expect(
    graph.getByRole('button', { name: /정비 지시\s*3건/ }),
  ).toHaveAttribute('aria-pressed', 'false')
  expect(
    graph.getByRole('region', { name: '불량 기록 연결 목록' }),
  ).toBeInTheDocument()
  expect(
    graph.queryByRole('region', { name: '정비 지시 연결 목록' }),
  ).not.toBeInTheDocument()
})

test('보던 묶음이 다시 받은 묶음 건수에서 빠지면 그 자리에 빈 묶음 안내를 남긴다', async () => {
  mockManyLinks(0, { maintenance: 3, staleDefectCount: 5 })
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))

  expect(
    await graph.findByRole('heading', {
      name: '불량 발생 설비 · 불량 기록 0건',
    }),
  ).toBeInTheDocument()
  expect(
    graph.queryByRole('button', { name: /불량 기록/ }),
  ).not.toBeInTheDocument()
  const defectList = within(
    graph.getByRole('region', { name: '불량 기록 연결 목록' }),
  )
  expect(
    defectList.getByText('지금은 이 묶음에 연결이 없습니다.'),
  ).toBeInTheDocument()
  expect(
    graph.getByRole('button', { name: /정비 지시\s*3건/ }),
  ).toHaveAttribute('aria-pressed', 'false')
  expect(
    graph.queryByRole('region', { name: '정비 지시 연결 목록' }),
  ).not.toBeInTheDocument()
})

test('근거 팝업도 전체 건수를 보여주고 더 불러올 수 있다', async () => {
  mockManyLinks(150)
  const user = await openEvidenceDialog(/프레스 3호기/)
  const dialog = within(screen.getByRole('dialog'))

  expect(await dialog.findByText('전체 150건 중 100건')).toBeInTheDocument()

  await user.click(dialog.getByRole('button', { name: '더 불러오기' }))

  expect(await dialog.findByText('전체 150건 중 150건')).toBeInTheDocument()
  expect(
    dialog.queryByRole('button', { name: '더 불러오기' }),
  ).not.toBeInTheDocument()
})

test('객체 상세의 이 객체 실행 이력은 그 객체의 실행 이력 주소로 연결된다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(
    await screen.findByRole('link', { name: '이 객체 실행 이력' }),
  ).toHaveAttribute('href', '/audit?objectId=301')
})

function manyObjects(count: number) {
  return Array.from({ length: count }, (_, index) => ({
    id: 1000 + index,
    objectTypeId: 1,
    objectTypeName: '설비',
    displayName: `설비 ${index + 1}`,
    properties: {},
    restrictedPropertyCodes: [],
    status: 'ACTIVE',
    firstCollectedAt: '2026-10-01T00:00:00Z',
  }))
}

function mockManyObjects(count: number) {
  const all = manyObjects(count)
  server.use(
    http.get('/api/v1/objects', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      return HttpResponse.json({
        data: all.slice(page * 20, page * 20 + 20),
        meta: {
          page,
          size: 20,
          totalElements: count,
          hasNext: (page + 1) * 20 < count,
        },
      })
    }),
  )
}

test('다음·이전 페이지로 객체 목록을 넘긴다', async () => {
  const user = userEvent.setup()
  mockManyObjects(25)
  renderExplorerPage()

  expect(await screen.findByText('1 / 2쪽 · 25건')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled()

  await user.click(screen.getByRole('button', { name: '다음 페이지' }))

  expect(await screen.findByText('2 / 2쪽 · 25건')).toBeInTheDocument()
  expect(screen.getByText('설비 21')).toBeInTheDocument()
  expect(screen.queryByText('설비 1')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled()
})

test('검색하면 첫 페이지로 돌아간다', async () => {
  const user = userEvent.setup()
  mockManyObjects(25)
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: '다음 페이지' }))
  await screen.findByText('2 / 2쪽 · 25건')

  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('1 / 2쪽 · 25건')).toBeInTheDocument()
})

function AgentEntryProbe() {
  const location = useLocation()
  const entry = location.state as AgentEntryState
  return (
    <div>
      <p>{location.search}</p>
      <p>{`${entry.referenceObjectId} · ${entry.referenceObjectName} · ${entry.referenceObjectTypeName} · ${entry.draft}`}</p>
    </div>
  )
}

test('검색 조건을 입력만 하면 현재 결과·페이지를 유지하고 적용할 때 첫 페이지로 돌아간다', async () => {
  const user = userEvent.setup()
  mockManyObjects(25)
  renderExplorerPage()
  await user.click(await screen.findByRole('button', { name: '다음 페이지' }))
  await screen.findByText('2 / 2쪽 · 25건')
  await screen.findByText('설비 21')

  await user.type(screen.getByLabelText('ID · 이름 검색'), '새 조건')
  expect(screen.getByText('2 / 2쪽 · 25건')).toBeInTheDocument()
  expect(screen.getByText('설비 21')).toBeInTheDocument()
  expect(screen.getByLabelText('적용된 검색 조건')).not.toHaveTextContent(
    '새 조건',
  )

  await user.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('1 / 2쪽 · 25건')
  expect(screen.getByLabelText('적용된 검색 조건')).toHaveTextContent('새 조건')
})

test('유형 목록에서도 유형을 선택해 객체 검색과 관계 상세를 연결한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: '유형 목록' }))
  await user.click(graph.getByRole('button', { name: '설비' }))
  await screen.findByText('2건')
  expect(screen.getByLabelText('객체 유형')).toHaveValue('3')
  expect(
    within(screen.getByRole('region', { name: '상세 정보' })).getByRole(
      'heading',
      { name: '설비' },
    ),
  ).toBeInTheDocument()
})

test('관계도를 확장해도 선택한 객체를 유지하고 분할 보기로 복원한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()
  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  await screen.findByRole('heading', { name: '프레스 3호기' })
  await user.click(screen.getByRole('button', { name: '관계도 확장' }))
  expect(
    screen.queryByRole('region', { name: '객체 목록' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('region', { name: '상세 정보' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '분할 보기' }))
  expect(
    within(screen.getByRole('region', { name: '상세 정보' })).getByRole(
      'heading',
      { name: '프레스 3호기' },
    ),
  ).toBeInTheDocument()
  expect(
    within(screen.getByRole('region', { name: '객체 목록' })).getByRole(
      'button',
      { name: /프레스 3호기/ },
    ),
  ).toHaveAttribute('aria-pressed', 'true')
})

test('객체 상세의 질문하기는 새 대화를 만들어 그 객체로 질문을 시작한다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/explorer']}>
        <Routes>
          <Route path="/explorer" element={<ExplorerPage />} />
          <Route path="/agent" element={<AgentEntryProbe />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  await user.click(await screen.findByRole('button', { name: '질문하기' }))

  expect(await screen.findByText(/^\?conversation=\d+$/)).toBeInTheDocument()
  expect(
    screen.getByText(
      '301 · 프레스 3호기 · 설비 · 프레스 3호기에 연결된 업무 정보를 알려줘',
    ),
  ).toBeInTheDocument()
})

test('주소에 객체 번호가 있으면 그 객체 상세를 연다', async () => {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/explorer?object=301']}>
        <ExplorerPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )

  expect(
    await screen.findByRole('heading', { name: '프레스 3호기' }),
  ).toBeInTheDocument()
  expect(screen.getByText('설비 / PR-03')).toBeInTheDocument()
})

function CurrentSearch() {
  const location = useLocation()
  return <p>{`주소: ${location.search}`}</p>
}

test('다른 객체를 고르거나 전체 구조로 돌아가면 주소의 객체 번호도 바뀐다', async () => {
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/explorer?object=301']}>
        <ExplorerPage />
        <CurrentSearch />
      </MemoryRouter>
    </QueryClientProvider>,
  )

  expect(
    await screen.findByRole('heading', { name: '프레스 3호기' }),
  ).toBeInTheDocument()

  const list = screen.getByRole('region', { name: '객체 목록' })
  await user.click(
    await within(list).findByRole('button', { name: /프레스 1호기/ }),
  )

  expect(
    await screen.findByRole('heading', { name: '프레스 1호기' }),
  ).toBeInTheDocument()
  expect(screen.getByText('주소: ?object=302')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '← 전체 구조로' }))

  expect(screen.getByText('주소:')).toBeInTheDocument()
})
