import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import {
  createMemoryRouter,
  MemoryRouter,
  Outlet,
  Route,
  RouterProvider,
  Routes,
  useLocation,
} from 'react-router'
import { afterEach, expect, test, vi } from 'vitest'
import { resetAnalysisMocks } from '../../mocks/analysisHandlers'
import { server } from '../../mocks/node'
import { resetProposalMocks } from '../../mocks/proposalHandlers'
import { resetSourceMocks } from '../../mocks/sourceHandlers'
import ProposalsPage from '../review/ProposalsPage'
import SourcesPage from '../sources/SourcesPage'
import { ANALYSIS_ERROR_LIMIT_MS, ANALYSIS_POLL_MS } from './AnalysisPanel'

afterEach(() => {
  resetSourceMocks()
  resetAnalysisMocks()
  resetProposalMocks()
})

function LocationProbe() {
  const { search } = useLocation()
  return <output aria-label="조회 주소">{search}</output>
}

function renderRules(path = '/admin/rules') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <LocationProbe />
        <Routes>
          <Route path="/admin/rules" element={<ProposalsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function startFromRules(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'AI 관계 제안 받기' }))
  const dialog = screen.getByRole('dialog', { name: '분석 대상 선택' })
  await user.click(
    await within(dialog).findByRole('checkbox', { name: 'item_code 선택' }),
  )
  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )
}

async function chooseReanalysis(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByText('분석 관리', { selector: 'summary' }))
  await user.click(screen.getByRole('button', { name: '변경 데이터 재분석' }))
  const dialog = within(
    screen.getByRole('dialog', { name: '변경 데이터 재분석' }),
  )
  expect(
    dialog.getByRole('button', { name: '재분석하고 후보 받기' }),
  ).toBeDisabled()
  await user.click(
    await dialog.findByRole('checkbox', { name: 'item_code 선택' }),
  )
  expect(
    within(dialog.getByRole('region', { name: '재분석 범위 확인' })).getByText(
      'item_code',
    ),
  ).toBeInTheDocument()
  return dialog
}

test.each([
  { label: '직접 추가 출처', search: 'origin=MANUAL', joined: false },
  { label: '반영 완료 상태', search: 'applyStatus=APPLIED', joined: false },
  {
    label: '적용 후 문제 보기',
    search: 'view=issues&issueStatus=NEW&issueKind=UNMATCHED&issuePage=2',
    joined: false,
  },
  {
    label: '모든 기존 조건과 실행 합류',
    search:
      'analysis=50&origin=MANUAL&applyStatus=APPLIED&reviewStatus=REJECTED&itemKind=OBJECT_TYPE&group=objects&q=old&page=4&candidate=301&view=issues&issueStatus=NEW&issueKind=UNMATCHED&issuePage=2',
    joined: true,
  },
])(
  '새 분석 접수 후 $label 조건을 초기화하고 AI 후보를 보여준다',
  async ({ search, joined }) => {
    server.use(
      http.post('/api/v1/analyses', () =>
        HttpResponse.json(
          {
            data: {
              id: 51,
              runKind: 'INITIAL',
              status: 'QUEUED',
              targets: [{ sourceResourceId: 11 }],
              requestedAt: '2026-11-11T01:00:00Z',
              joined,
            },
          },
          { status: 202 },
        ),
      ),
    )
    const user = userEvent.setup()
    renderRules(`/admin/rules?${search}`)
    await startFromRules(user)
    await waitFor(() =>
      expect(screen.getByLabelText('조회 주소').textContent).toBe(
        joined ? '?analysis=51&joined=1' : '?analysis=51',
      ),
    )
    const list = within(
      await screen.findByRole('complementary', { name: '검토 목록' }),
    )
    expect(
      await list.findByRole('button', { name: /작업지시 설비 코드 연결/ }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('table', { name: '정합성 문제 목록' }),
    ).not.toBeInTheDocument()
    if (joined)
      expect(
        await screen.findByText(
          '같은 대상의 분석이 이미 진행 중이라 그 분석에 합류했습니다.',
        ),
      ).toBeInTheDocument()
  },
)

test('새 분석 접수 실패 시 기존 조회 조건과 문제 보기를 유지한다', async () => {
  server.use(
    http.post('/api/v1/analyses', () =>
      HttpResponse.json(
        {
          error: {
            code: 'TEST_ERROR',
            message: '분석 요청 실패',
            details: {},
            requestId: 'test-346',
          },
        },
        { status: 422 },
      ),
    ),
  )
  const search =
    '?origin=MANUAL&applyStatus=APPLIED&view=issues&issueStatus=NEW'
  const user = userEvent.setup()
  renderRules(`/admin/rules${search}`)
  await startFromRules(user)
  expect(
    await within(screen.getByRole('dialog')).findByText('분석 요청 실패'),
  ).toBeInTheDocument()
  expect(screen.getByLabelText('조회 주소').textContent).toBe(search)
})

test('선택 범위를 확인해 REANALYSIS로 접수하고 진행·결과와 실제 컬럼 범위를 표시한다', async () => {
  const user = userEvent.setup()
  renderRules()
  const dialog = await chooseReanalysis(user)
  await user.click(dialog.getByRole('button', { name: '재분석하고 후보 받기' }))
  const current = within(
    await screen.findByRole('region', { name: '이번 분석' }),
  )
  await current.findByRole('region', { name: '요청한 재분석 범위' })
  expect(current.getByText(/자료 #11 · item_code/)).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '변경 데이터 재분석' }),
  ).toBeDisabled()
  await current.findByText('이번 분석 후보 1건', {}, { timeout: 8000 })
  expect(
    screen.getByRole('button', { name: '변경 데이터 재분석' }),
  ).toBeEnabled()
  if (
    !screen
      .getByText('분석 관리', { selector: 'summary' })
      .parentElement?.hasAttribute('open')
  )
    await user.click(screen.getByText('분석 관리', { selector: 'summary' }))
  await user.click(screen.getByRole('button', { name: '최근 분석 보기' }))
  expect(
    await screen.findByText('재분석', { selector: 'td' }),
  ).toBeInTheDocument()
}, 15000)

test('재분석은 선택 컬럼을 전송하고 기존 INITIAL 실행에 합류하면 실제 실행 종류를 보존한다', async () => {
  const user = userEvent.setup()
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      runKind: 'REANALYSIS',
      targets: [{ sourceResourceId: 11, columns: ['item_code'] }],
    })
    return HttpResponse.json(
      {
        data: {
          id: 50,
          runKind: 'INITIAL',
          status: 'QUEUED',
          targets: [{ sourceResourceId: 11 }],
          requestedAt: '2026-11-07T01:00:00Z',
          joined: true,
        },
      },
      { status: 202 },
    )
  })
  server.use(http.post('/api/v1/analyses', post))
  renderRules()
  const dialog = await chooseReanalysis(user)
  await user.dblClick(
    dialog.getByRole('button', { name: '재분석하고 후보 받기' }),
  )
  await screen.findByText(
    '같은 대상의 분석이 이미 진행 중이라 그 분석에 합류했습니다.',
  )
  expect(post).toHaveBeenCalledTimes(1)
  expect(
    screen.queryByRole('region', { name: '요청한 재분석 범위' }),
  ).not.toBeInTheDocument()
})

test.each([403, 404, 409, 422])(
  '재분석 접수 %s 오류를 표시하고 자동 재접수하지 않는다',
  async (status) => {
    const user = userEvent.setup()
    const post = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code: 'TEST_ERROR',
            message: '재분석 요청 실패',
            details: {},
            requestId: 'test-121',
          },
        },
        { status },
      ),
    )
    server.use(http.post('/api/v1/analyses', post))
    renderRules()
    const dialog = await chooseReanalysis(user)
    await user.click(
      dialog.getByRole('button', { name: '재분석하고 후보 받기' }),
    )
    await dialog.findByText('재분석 요청 실패')
    expect(post).toHaveBeenCalledTimes(1)
    expect(
      screen.queryByRole('region', { name: '이번 분석' }),
    ).not.toBeInTheDocument()
  },
)

test('분석을 맡기면 진행 상태를 다시 조회하고 끝나면 이번 분석 후보만 보여준다', async () => {
  const user = userEvent.setup()
  renderRules()
  await startFromRules(user)

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(
    await within(current).findByText('분석 대상: 자료 1개'),
  ).toBeInTheDocument()
  expect(current).toHaveTextContent('ANALYSIS-052')
  // 끝나기 전에는 같은 요청을 다시 보내지 않는다
  expect(
    screen.getByRole('button', { name: 'AI 관계 제안 받기' }),
  ).toBeDisabled()

  expect(
    await within(current).findByText(
      '이번 분석 후보 1건',
      {},
      { timeout: 8000 },
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: 'AI 관계 제안 받기' }),
  ).toBeEnabled()
  const table = await screen.findByRole('complementary', { name: '검토 목록' })
  expect(
    within(table).getAllByRole('button', { name: /검토 상태:/ }),
  ).toHaveLength(1)
}, 15000)

test('전체 후보 보기를 누르면 분석 조건 없이 모든 제안을 보여준다', async () => {
  const user = userEvent.setup()
  renderRules('/admin/rules?analysis=50')

  const table = await screen.findByRole('complementary', { name: '검토 목록' })
  expect(
    within(table).getAllByRole('button', { name: /검토 상태:/ }),
  ).toHaveLength(1)

  await user.click(screen.getByRole('button', { name: '전체 후보 보기' }))

  expect(
    screen.queryByRole('region', { name: '이번 분석' }),
  ).not.toBeInTheDocument()
  // expect.poll은 기본 1초만 기다려(setup.ts의 대기 설정을 따르지 않음) 바쁠 때 실패한다. waitFor로 기다린다
  await waitFor(() =>
    expect(
      within(
        screen.getByRole('complementary', { name: '검토 목록' }),
      ).getAllByRole('button', { name: /검토 상태:/ }),
    ).toHaveLength(3),
  )
})

test('실패한 분석은 실패 사유를 보여준다', async () => {
  renderRules('/admin/rules?analysis=49')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(await within(current).findByRole('alert')).toHaveTextContent(
    'AI 계정이 이 테이블을 읽을 수 없습니다: erp.employee',
  )
  // 실패 사유(alert)가 먼저 뜨고 상태 문구는 그 뒤에 바뀔 수 있어 기다려서 본다
  await waitFor(() => expect(current).toHaveTextContent('분석에 실패했습니다'))
  expect(
    await screen.findByText(
      '선택한 상태·유형·검색어에 해당하는 후보가 없습니다.',
    ),
  ).toBeInTheDocument()
})

test('같은 대상의 분석에 합류했으면 알려준다', async () => {
  renderRules('/admin/rules?analysis=51&joined=1')

  expect(
    await screen.findByText(
      '같은 대상의 분석이 이미 진행 중이라 그 분석에 합류했습니다.',
    ),
  ).toBeInTheDocument()
  await screen.findByRole('complementary', { name: '검토 목록' })
})

test('최근 분석 목록에서 과거 실행을 골라 그 후보를 본다', async () => {
  const user = userEvent.setup()
  renderRules()
  await screen.findByRole('complementary', { name: '검토 목록' })

  if (
    !screen
      .getByText('분석 관리', { selector: 'summary' })
      .parentElement?.hasAttribute('open')
  )
    await user.click(screen.getByText('분석 관리', { selector: 'summary' }))
  await user.click(screen.getByRole('button', { name: '최근 분석 보기' }))
  const recent = await screen.findByRole(
    'table',
    { name: '최근 분석' },
    { timeout: 3000 },
  )
  const rows = within(recent).getAllByRole('row')
  expect(rows[1]).toHaveTextContent('ANALYSIS-051')
  expect(rows[1]).toHaveTextContent('완료')
  expect(rows[1]).toHaveTextContent('후보 1건')
  expect(rows[3]).toHaveTextContent('실패')

  await user.click(
    within(recent).getByRole('button', { name: 'ANALYSIS-050 후보 보기' }),
  )

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(
    await within(current).findByText(/ANALYSIS-050/, {}, { timeout: 3000 }),
  ).toBeInTheDocument()
})

test('없는 분석을 열면 서버 안내를 보여준다', async () => {
  renderRules('/admin/rules?analysis=999')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(await within(current).findByRole('alert')).toHaveTextContent(
    '요청한 항목을 찾을 수 없습니다.',
  )
})

test('분석 요청 권한이 없으면 창 안에서 서버 안내를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.post('/api/v1/analyses', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '이 작업을 할 권한이 없습니다.',
            details: {},
            requestId: 'mock-analysis',
          },
        },
        { status: 403 },
      ),
    ),
  )
  renderRules()
  await startFromRules(user)

  const dialog = screen.getByRole('dialog', { name: '분석 대상 선택' })
  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    '이 작업을 할 권한이 없습니다.',
  )
})

test('고른 범위와 다르게 분석한 부분은 안내로 보여준다', async () => {
  renderRules('/admin/rules?analysis=48')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  const notices = await within(current).findByRole('list', {
    name: '분석 범위 안내',
  })
  expect(notices).toHaveTextContent(
    'mes.work_order: 고른 컬럼 old_note이(가) 원천에서 사라져 빼고 분석했습니다.',
  )
  expect(notices).toHaveTextContent(
    'mes.sensor_reading: 행이 많아 앞에서 100,000행만 분석했습니다.',
  )
  expect(current).toHaveTextContent('문서 연결 2건')
  await screen.findByText('선택한 상태·유형·검색어에 해당하는 후보가 없습니다.')
})

test('안내할 것이 없으면 안내 목록을 그리지 않는다', async () => {
  renderRules('/admin/rules?analysis=50')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  await within(current).findByText(/이번 분석 후보/)
  expect(
    within(current).queryByRole('list', { name: '분석 범위 안내' }),
  ).not.toBeInTheDocument()
  await screen.findByRole('complementary', { name: '검토 목록' })
})

function analysisRun(status: 'RUNNING' | 'SUCCEEDED') {
  return {
    id: 60,
    runKind: 'INITIAL',
    status,
    targets: [{ sourceResourceId: 11 }],
    analysisRange: null,
    requestedAt: '2026-11-11T01:00:00Z',
    startedAt: '2026-11-11T01:00:01Z',
    finishedAt: status === 'SUCCEEDED' ? '2026-11-11T01:00:31Z' : null,
    resultSummary: status === 'SUCCEEDED' ? { proposals: 0 } : null,
    errorMessage: null,
    requestedBy: 1,
  }
}

function badGateway() {
  return HttpResponse.json(
    {
      error: {
        code: 'BAD_GATEWAY',
        message: '잠시 연결이 끊겼습니다.',
        details: {},
        requestId: 'mock-analysis',
      },
    },
    { status: 502 },
  )
}

test('진행 확인 중 502가 한 번 나도 오류 화면으로 바꾸지 않고 계속 확인한다', async () => {
  let calls = 0
  server.use(
    http.get('/api/v1/analyses/60', () => {
      calls += 1
      if (calls === 1)
        return HttpResponse.json({ data: analysisRun('RUNNING') })
      if (calls === 2) return badGateway()
      return HttpResponse.json({ data: analysisRun('SUCCEEDED') })
    }),
  )
  renderRules('/admin/rules?analysis=60')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(
    await within(current).findByText('AI가 고른 범위를 분석하고 있습니다'),
  ).toBeInTheDocument()
  expect(
    await within(current).findByText(
      '이번 분석 후보 0건',
      {},
      { timeout: 8000 },
    ),
  ).toBeInTheDocument()
  expect(calls).toBe(3)
  expect(within(current).queryByRole('alert')).not.toBeInTheDocument()
}, 15000)

test('진행 확인이 계속 실패해도 받은 결과는 두고 안내 줄만 보이며 다시 확인한다', async () => {
  let calls = 0
  server.use(
    http.get('/api/v1/analyses/60', () => {
      calls += 1
      return calls === 1
        ? HttpResponse.json({ data: analysisRun('RUNNING') })
        : badGateway()
    }),
  )
  renderRules('/admin/rules?analysis=60')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(
    await within(current).findByText(
      /진행 상태를 새로 확인하지 못했습니다\. 잠시 연결이 끊겼습니다\. 잠시 뒤 다시 확인합니다\./,
      {},
      { timeout: 10000 },
    ),
  ).toBeInTheDocument()
  expect(current).toHaveTextContent('AI가 고른 범위를 분석하고 있습니다')
  expect(within(current).queryByRole('alert')).not.toBeInTheDocument()
  const failed = calls
  await waitFor(() => expect(calls).toBeGreaterThan(failed), {
    timeout: 5000,
  })
}, 20000)

test('서버가 오래 계속 실패하면 진행 확인을 멈춘다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    let calls = 0
    server.use(
      http.get('/api/v1/analyses/61', () => {
        calls += 1
        return calls === 1
          ? HttpResponse.json({ data: analysisRun('RUNNING') })
          : badGateway()
      }),
    )
    renderRules('/admin/rules?analysis=61')
    await screen.findByRole('region', { name: '이번 분석' })

    // 받은 진행 상태 뒤로 실패만 이 시간 넘게 이어지면 멈추고 다시 확인 버튼을 둔다
    await act(() =>
      vi.advanceTimersByTimeAsync(ANALYSIS_ERROR_LIMIT_MS + 30_000),
    )
    const stopped = calls
    expect(stopped).toBeGreaterThan(10)
    await act(() => vi.advanceTimersByTimeAsync(ANALYSIS_POLL_MS * 10))
    expect(calls).toBe(stopped)
    const current = screen.getByRole('region', { name: '이번 분석' })
    expect(current).toHaveTextContent('계속 실패해 자동 확인을 멈췄습니다.')
    expect(current).not.toHaveTextContent('잠시 뒤 다시 확인합니다.')
    // 다시 확인을 누르면 한 번 더 묻는다
    await act(async () => {
      within(current).getByRole('button', { name: '다시 확인' }).click()
    })
    await act(() => vi.advanceTimersByTimeAsync(5_000))
    expect(calls).toBeGreaterThan(stopped)
  } finally {
    vi.useRealTimers()
  }
}, 30000)

test('이미 끝난 분석을 열면 불러오던 후보 목록을 다시 요청하지 않는다', async () => {
  const requested: string[] = []
  server.events.on('request:start', ({ request }) => {
    requested.push(new URL(request.url).pathname)
  })
  renderRules('/admin/rules?analysis=50')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  await within(current).findByText('이번 분석 후보 1건')
  await screen.findByRole('complementary', { name: '검토 목록' })
  await new Promise((resolve) => setTimeout(resolve, 500))
  expect(
    requested.filter((path) => path === '/api/v1/analyses/50/proposals'),
  ).toHaveLength(1)
  server.events.removeAllListeners()
})

test('분석 대상 선택의 원천 등록은 데이터 연결 화면에서 등록 창을 연다', async () => {
  const user = userEvent.setup()
  const router = createMemoryRouter(
    [
      {
        element: (
          <>
            <LocationProbe />
            <Outlet />
          </>
        ),
        children: [
          { path: '/admin/rules', element: <ProposalsPage /> },
          { path: '/admin/sources', element: <SourcesPage /> },
        ],
      },
    ],
    { initialEntries: ['/admin/rules'] },
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  await user.click(screen.getByRole('button', { name: 'AI 관계 제안 받기' }))
  const dialog = screen.getByRole('dialog', { name: '분석 대상 선택' })
  await user.click(
    await within(dialog).findByRole('button', { name: '+ 원천 등록' }),
  )

  expect(
    await screen.findByRole('dialog', { name: '원천 등록' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '취소' }))
  expect(
    screen.queryByRole('dialog', { name: '원천 등록' }),
  ).not.toBeInTheDocument()
  expect(screen.getByLabelText('조회 주소').textContent).toBe('')
})
