import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { resetAnalysisMocks } from '../../mocks/analysisHandlers'
import { server } from '../../mocks/node'
import { resetProposalMocks } from '../../mocks/proposalHandlers'
import { resetSourceMocks } from '../../mocks/sourceHandlers'
import ProposalsPage from '../review/ProposalsPage'
import SourcesPage from '../sources/SourcesPage'

afterEach(() => {
  resetSourceMocks()
  resetAnalysisMocks()
  resetProposalMocks()
  server.events.removeAllListeners()
})

function renderAt(path: string) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/sources" element={<SourcesPage />} />
          <Route path="/admin/rules" element={<ProposalsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function captureAnalysisRequest() {
  const sent: { body?: unknown } = {}
  server.events.on('request:start', async ({ request }) => {
    if (
      request.method === 'POST' &&
      new URL(request.url).pathname === '/api/v1/analyses'
    ) {
      sent.body = await request.clone().json()
    }
  })
  return sent
}

async function openFromSource(user: ReturnType<typeof userEvent.setup>) {
  renderAt('/admin/sources?source=2')
  await user.click(
    await screen.findByRole('button', {
      name: '이 원천으로 AI 관계 제안 받기',
    }),
  )
  return screen.getByRole('dialog', { name: '분석 대상 선택' })
}

test('원천 상세에서 열면 그 원천의 표와 첫 표의 컬럼을 보여준다', async () => {
  const user = userEvent.setup()
  const dialog = await openFromSource(user)

  expect(within(dialog).getByLabelText('등록된 원천')).toHaveValue('2')
  const list = await within(dialog).findByRole('list', { name: '표·자료 목록' })
  // 목업처럼 6개씩 나눠 보여 준다
  expect(within(list).getAllByRole('button')).toHaveLength(6)
  expect(within(dialog).getByText('1 / 3쪽 · 15건')).toBeInTheDocument()
  expect(
    await within(dialog).findByRole('table', {
      name: 'mes.equipment 컬럼 선택',
    }),
  ).toBeInTheDocument()
  expect(within(dialog).getByText('전체 8개 중')).toBeInTheDocument()
  expect(
    within(dialog).getByText(
      '0개 원천 · 0개 테이블 · 0개 컬럼 · 0개 문서·자료',
    ),
  ).toBeInTheDocument()
  expect(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  ).toBeDisabled()
})

test('고른 컬럼만 분석을 맡기고 연결 규칙에서 이번 분석을 보여준다', async () => {
  const user = userEvent.setup()
  const sent = captureAnalysisRequest()
  const dialog = await openFromSource(user)
  await within(dialog).findByRole('list', { name: '표·자료 목록' })

  await user.type(
    within(dialog).getByLabelText('테이블·자료 이름 검색'),
    'work_order',
  )
  await user.click(
    within(dialog).getByRole('button', { name: /^mes\.work_order · 작업지시/ }),
  )
  await user.click(
    await within(dialog).findByRole('checkbox', { name: 'wo_no 선택' }),
  )
  await user.click(
    within(dialog).getByRole('checkbox', { name: 'product_code 선택' }),
  )
  expect(
    within(dialog).getByText(
      '1개 원천 · 1개 테이블 · 2개 컬럼 · 0개 문서·자료',
    ),
  ).toBeInTheDocument()

  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )

  // 목업처럼 연결 규칙 화면으로 넘어가 이번 분석을 보여 준다.
  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(
    await within(current).findByText(/ANALYSIS-052/, {}, { timeout: 3000 }),
  ).toBeInTheDocument()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(sent.body).toEqual({
    runKind: 'INITIAL',
    targets: [{ sourceResourceId: 32, columns: ['wo_no', 'product_code'] }],
  })
})

test('표의 컬럼을 모두 고르면 표 전체를 분석하도록 컬럼 목록을 빼서 보낸다', async () => {
  const user = userEvent.setup()
  const sent = captureAnalysisRequest()
  const dialog = await openFromSource(user)

  const all = await within(dialog).findByRole('checkbox', { name: '전체' })
  await user.click(all)
  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )

  await screen.findByRole('region', { name: '이번 분석' })
  expect(sent.body).toEqual({
    runKind: 'INITIAL',
    targets: [{ sourceResourceId: 29 }],
  })
})

test('고른 자료만 보기를 켜면 선택한 표만 남는다', async () => {
  const user = userEvent.setup()
  const dialog = await openFromSource(user)

  await user.click(
    await within(dialog).findByRole('checkbox', { name: 'code 선택' }),
  )
  await user.click(within(dialog).getByLabelText('이 원천에서 선택한 자료만'))

  const list = within(dialog).getByRole('list', { name: '표·자료 목록' })
  expect(within(list).getAllByRole('button')).toHaveLength(1)
  expect(list).toHaveTextContent('mes.equipment · 설비')
  expect(list).toHaveTextContent('1개 컬럼 선택')
})

test('분석 요청이 거절되면 창 안에서 서버 안내를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.post('/api/v1/analyses', () =>
      HttpResponse.json(
        {
          error: {
            code: 'VALIDATION_FAILED',
            message: '요청 필드를 확인해 주세요.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 400 },
      ),
    ),
  )
  const dialog = await openFromSource(user)

  await user.click(
    await within(dialog).findByRole('checkbox', { name: 'code 선택' }),
  )
  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )

  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    '요청 필드를 확인해 주세요.',
  )
})

test('표 목록이 많으면 다음 페이지로 넘겨 볼 수 있다', async () => {
  const user = userEvent.setup()
  const dialog = await openFromSource(user)
  await within(dialog).findByRole('list', { name: '표·자료 목록' })

  const finder = within(dialog).getByRole('region', {
    name: '테이블·자료 찾기',
  })
  await user.click(within(finder).getByRole('button', { name: '다음 페이지' }))

  expect(within(finder).getByText('2 / 3쪽 · 15건')).toBeInTheDocument()
  expect(
    within(dialog).getByRole('list', { name: '표·자료 목록' }),
  ).not.toHaveTextContent('mes.equipment')
})

test('컬럼이 많으면 나눠 보여 주고 전체 선택은 모든 페이지에 적용한다', async () => {
  const user = userEvent.setup()
  const sent = captureAnalysisRequest()
  const dialog = await openFromSource(user)
  await within(dialog).findByRole('list', { name: '표·자료 목록' })
  await user.type(
    within(dialog).getByLabelText('테이블·자료 이름 검색'),
    'work_order',
  )
  await user.click(
    within(dialog).getByRole('button', { name: /^mes\.work_order · 작업지시/ }),
  )
  const picker = within(dialog).getByRole('region', { name: '컬럼 선택' })
  const table = await within(picker).findByRole('table', {
    name: 'mes.work_order 컬럼 선택',
  })

  expect(within(table).getAllByRole('row')).toHaveLength(9)
  expect(within(picker).getByText('1 / 2쪽 · 14건')).toBeInTheDocument()

  await user.click(within(picker).getByRole('checkbox', { name: '전체' }))
  expect(within(picker).getByText('14개 선택')).toBeInTheDocument()
  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )
  await screen.findByRole('region', { name: '이번 분석' })
  expect(sent.body).toEqual({
    runKind: 'INITIAL',
    targets: [{ sourceResourceId: 32 }],
  })
})

test('수집 대상 변경을 누르면 창을 닫고 그 원천의 수집 대상 탭을 연다', async () => {
  const user = userEvent.setup()
  const dialog = await openFromSource(user)

  await user.click(
    within(dialog).getByRole('button', { name: '이 원천의 수집 대상 변경' }),
  )

  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '수집 대상' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})

test('원천 등록을 누르면 선택 창 대신 등록 창을 연다', async () => {
  const user = userEvent.setup()
  const dialog = await openFromSource(user)

  await user.click(within(dialog).getByRole('button', { name: '+ 원천 등록' }))

  expect(
    screen.queryByRole('dialog', { name: '분석 대상 선택' }),
  ).not.toBeInTheDocument()
  expect(screen.getByRole('dialog')).toBeInTheDocument()
})
