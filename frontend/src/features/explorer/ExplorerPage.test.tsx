import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import ExplorerPage from './ExplorerPage'

function renderExplorerPage() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <ExplorerPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('객체 목록 API로 받은 객체를 보여준다', async () => {
  renderExplorerPage()

  expect(screen.getByText('불러오는 중…')).toBeInTheDocument()
  expect(await screen.findByText('프레스 3호기')).toBeInTheDocument()
  expect(screen.getByText('6건')).toBeInTheDocument()
  expect(screen.getByText('#301')).toBeInTheDocument()
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

test('객체를 고르면 상세 API로 받은 정보를 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(
    await screen.findByRole('heading', { name: '프레스 3호기' }),
  ).toBeInTheDocument()
  expect(screen.getByText('설비 / #301')).toBeInTheDocument()
  expect(
    screen.getByText('MES · mes.equipment · 원천 레코드'),
  ).toBeInTheDocument()
  expect(screen.getByText('설비 코드')).toBeInTheDocument()
})

test('권한으로 가린 속성은 권한 없음, 값이 없는 속성은 -로 보여준다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  const purchasePrice = await screen.findByText('구매 가격')
  expect(purchasePrice.nextElementSibling).toHaveTextContent('권한 없음')
  const maintenanceNote = screen.getByText('정비 메모')
  expect(maintenanceNote.nextElementSibling).toHaveTextContent('-')
})

test('속성 표시 이름이 없으면 속성 코드를 보여준다', async () => {
  server.use(
    http.get('/api/v1/objects/:id', () =>
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

test('속성 조건 검색은 백엔드 지원 전이라 막아 둔다', () => {
  renderExplorerPage()

  expect(screen.getByLabelText('속성 조건')).toBeDisabled()
  expect(screen.getByLabelText('속성 값 · 포함 검색')).toBeDisabled()
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
    http.get('/api/v1/objects/:id', () =>
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
  expect(dialog.getByText('값 PR03 → PR-03')).toBeInTheDocument()
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
  expect(graph.getByText('생산 대상 수주 · 1건')).toBeInTheDocument()
  expect(graph.getByText('객체 유형 6개 · 관계 유형 5개')).toBeInTheDocument()
})

test('같은 두 유형 사이의 관계는 선 하나에 이름을 모아 보여준다', async () => {
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const assigned = await graph.findByText('현재 배정 설비 · 1건')
  const alternative = graph.getByText('대체 가능 설비 · 1건')

  expect(assigned.parentElement).toBe(alternative.parentElement)
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

test('관리자가 아니라 전체 연결 수가 없으면 활성 연결 수만 보여준다', async () => {
  server.use(
    http.get('/api/v1/ontology/model-graph', () =>
      HttpResponse.json({
        data: {
          nodes: [
            { id: 3, code: 'EQUIPMENT', name: '설비', objectCount: 2 },
            { id: 4, code: 'WORK_ORDER', name: '작업지시', objectCount: 1 },
          ],
          edges: [
            {
              id: 2,
              code: 'ASSIGNED_EQUIPMENT',
              name: '현재 배정 설비',
              sourceTypeId: 4,
              targetTypeId: 3,
              cardinality: 'MANY_TO_ONE',
              relationCount: null,
              activeLinkCount: 1,
            },
          ],
        },
      }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  await user.click(await graph.findByRole('button', { name: /설비/ }))

  expect(await screen.findByText('활성 연결 1건')).toBeInTheDocument()
  expect(screen.queryByText(/null/)).not.toBeInTheDocument()
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

test('객체가 없는 유형은 건수를 보여주고 누를 수 없다', async () => {
  renderExplorerPage()

  const graph = within(screen.getByRole('region', { name: '관계도' }))
  const emptyType = await graph.findByRole('button', { name: /점검 요청/ })

  expect(emptyType).toBeDisabled()
  expect(emptyType).toHaveTextContent('객체 0개')
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

test('유형 관계도를 볼 권한이 없으면 객체 선택 안내만 보여준다', async () => {
  server.use(
    http.get('/api/v1/ontology/model-graph', () =>
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
  renderExplorerPage()

  expect(
    await screen.findByText('객체를 선택하면 연결 관계를 표시합니다.'),
  ).toBeInTheDocument()
})

test('관계도에서 연결된 객체 카드를 누르면 그 객체로 이동한다', async () => {
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))
  const graph = within(screen.getByRole('region', { name: '관계도' }))

  expect(await graph.findByText('연결된 정보 · 2건')).toBeInTheDocument()
  expect(
    graph.getByRole('heading', { name: '프레스 3호기와 연결된 정보' }),
  ).toBeInTheDocument()
  expect(graph.getByText('#1101')).toBeInTheDocument()

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
    http.get('/api/v1/objects/:id/links', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  const user = userEvent.setup()
  renderExplorerPage()

  await user.click(await screen.findByRole('button', { name: /프레스 3호기/ }))

  expect(
    await screen.findByText('이 객체에는 연결된 관계가 없습니다.'),
  ).toBeInTheDocument()
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
