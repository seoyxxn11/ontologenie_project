import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, expect, test, vi } from 'vitest'
import { resetAnalysisMocks } from '../../mocks/analysisHandlers'
import { server } from '../../mocks/node'
import { resetProposalMocks } from '../../mocks/proposalHandlers'
import { resetSourceMocks } from '../../mocks/sourceHandlers'
import ProposalsPage from '../review/ProposalsPage'
import SourcesPage from '../sources/SourcesPage'
import AnalysisTargetDialog from './AnalysisTargetDialog'

afterEach(() => {
  resetSourceMocks()
  resetAnalysisMocks()
  resetProposalMocks()
  server.events.removeAllListeners()
})

function renderAt(path: string) {
  const router = createMemoryRouter(
    [
      { path: '/admin/sources', element: <SourcesPage /> },
      { path: '/admin/rules', element: <ProposalsPage /> },
    ],
    { initialEntries: [path] },
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
      name: 'AI 관계 제안 받기',
    }),
  )
  return screen.getByRole('dialog', { name: '분석 대상 선택' })
}

test('데이터 연결에서 열면 선택 원천의 표와 첫 표의 컬럼을 보여준다', async () => {
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

test('목업처럼 원천별 자료 수·표별 컬럼 수·컬럼 설명을 보여 주고 설명으로도 찾는다', async () => {
  const user = userEvent.setup()
  const dialog = await openFromSource(user)

  const select = within(dialog).getByLabelText('등록된 원천')
  expect(
    await within(select).findByRole('option', { name: /· 15개 자료$/ }),
  ).toBeInTheDocument()
  const list = await within(dialog).findByRole('list', { name: '표·자료 목록' })
  expect(
    await within(list).findByText(/^테이블 · 8개 컬럼$/),
  ).toBeInTheDocument()
  const table = await within(dialog).findByRole('table', {
    name: 'mes.equipment 컬럼 선택',
  })
  expect(within(table).getByText('컬럼 · 설명')).toBeInTheDocument()
  expect(within(table).getByText('설비 이름')).toBeInTheDocument()

  await user.type(
    within(dialog).getByLabelText('컬럼 이름·설명 검색'),
    '설비 이름',
  )
  expect(within(table).getAllByRole('row')).toHaveLength(2)
  expect(within(table).getByText('name')).toBeInTheDocument()
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

test('등록된 원천이 없으면 불러오는 중 대신 원천 등록 안내를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.get('/api/v1/sources', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 100, totalElements: 0, hasNext: false },
      }),
    ),
  )
  renderAt('/admin/rules')

  await user.click(screen.getByRole('button', { name: 'AI 관계 제안 받기' }))
  const dialog = screen.getByRole('dialog', { name: '분석 대상 선택' })

  expect(
    await within(dialog).findByText(
      '등록된 원천이 없습니다. 먼저 원천을 등록해 주세요.',
    ),
  ).toBeInTheDocument()
  expect(
    within(dialog).queryByText('표를 불러오는 중…'),
  ).not.toBeInTheDocument()
  expect(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  ).toBeDisabled()
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

test('목록에 없는 원천 번호로 열어도 선택칸과 표 목록이 같은 원천을 가리킨다', async () => {
  const requested: string[] = []
  server.events.on('request:start', ({ request }) => {
    requested.push(new URL(request.url).pathname)
  })
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AnalysisTargetDialog
        initialSourceId={999}
        onClose={() => {}}
        onStarted={() => {}}
        onEditScope={() => {}}
        onRegister={() => {}}
      />
    </QueryClientProvider>,
  )
  const dialog = screen.getByRole('dialog', { name: '분석 대상 선택' })

  const list = await within(dialog).findByRole('list', { name: '표·자료 목록' })
  expect(within(dialog).getByLabelText('등록된 원천')).toHaveValue('1')
  expect(
    within(dialog).getByText(
      '열려던 원천을 찾을 수 없어 첫 원천을 보여 줍니다.',
    ),
  ).toBeInTheDocument()
  for (const item of within(list).getAllByRole('button')) {
    expect(item).toHaveTextContent(/^erp\./)
  }
  expect(requested).not.toContain('/api/v1/sources/999/resources')
})

test('컬럼이 없는 문서·자료는 자료 전체를 골라 컬럼 없이 분석을 맡긴다', async () => {
  const user = userEvent.setup()
  const sent = captureAnalysisRequest()
  server.use(
    http.get('/api/v1/sources/:id/resources', () =>
      HttpResponse.json({
        data: [
          {
            id: 9001,
            dataSourceId: 2,
            resourceKey: 'docs.handover_files',
            resourceKind: 'DOCUMENT_SET',
            isReadable: true,
            sourceMetadata: { description: '교대 인계 문서' },
          },
        ],
        meta: { page: 0, size: 100, totalElements: 1, hasNext: false },
      }),
    ),
    http.get('/api/v1/sources/:id/resources/:resourceId/columns', () =>
      HttpResponse.json({ data: [] }),
    ),
  )
  const dialog = await openFromSource(user)

  const include = await within(dialog).findByRole('checkbox', {
    name: '이 자료를 분석에 포함',
  })
  // 다시 끄면 선택에서 빠진다
  await user.click(include)
  await user.click(include)
  expect(
    within(dialog).getByText(
      '0개 원천 · 0개 테이블 · 0개 컬럼 · 0개 문서·자료',
    ),
  ).toBeInTheDocument()
  await user.click(include)
  expect(
    within(dialog).getByText(
      '1개 원천 · 0개 테이블 · 0개 컬럼 · 1개 문서·자료',
    ),
  ).toBeInTheDocument()
  expect(within(dialog).getByText(/자료 전체 선택/)).toBeInTheDocument()
  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )

  await screen.findByRole('region', { name: '이번 분석' })
  expect(sent.body).toEqual({
    runKind: 'INITIAL',
    targets: [{ sourceResourceId: 9001 }],
  })
})

function renderDialog() {
  const onClose = vi.fn()
  const onStarted = vi.fn()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <AnalysisTargetDialog
        initialSourceId={1}
        onClose={onClose}
        onStarted={onStarted}
        onEditScope={() => {}}
        onRegister={() => {}}
      />
    </QueryClientProvider>,
  )
  return {
    onClose,
    onStarted,
    dialog: screen.getByRole('dialog', { name: '분석 대상 선택' }),
  }
}

test('고른 것이 있으면 배경을 눌러도 닫히지 않고 닫기·Esc로는 닫힌다', async () => {
  const user = userEvent.setup()
  const { onClose, dialog } = renderDialog()
  const backdrop = dialog.parentElement!

  await user.click(backdrop)
  expect(onClose).toHaveBeenCalledTimes(1)
  onClose.mockClear()

  await user.click(
    await within(dialog).findByRole('checkbox', { name: 'item_code 선택' }),
  )
  await user.click(backdrop)
  expect(onClose).not.toHaveBeenCalled()
  expect(
    within(dialog).getByRole('checkbox', { name: 'item_code 선택' }),
  ).toBeChecked()

  await user.keyboard('{Escape}')
  expect(onClose).toHaveBeenCalledTimes(1)
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  expect(onClose).toHaveBeenCalledTimes(2)
})

test('분석 접수 중에는 닫히지 않고 접수가 끝나면 화면을 넘긴다', async () => {
  let release: () => void = () => {}
  server.use(
    http.post('/api/v1/analyses', async () => {
      await new Promise<void>((resolve) => {
        release = resolve
      })
      return HttpResponse.json(
        {
          data: {
            id: 70,
            runKind: 'INITIAL',
            status: 'QUEUED',
            targets: [{ sourceResourceId: 11 }],
            requestedAt: '2026-11-11T01:00:00Z',
            joined: false,
          },
        },
        { status: 202 },
      )
    }),
  )
  const user = userEvent.setup()
  const { onClose, onStarted, dialog } = renderDialog()
  await user.click(
    await within(dialog).findByRole('checkbox', { name: 'item_code 선택' }),
  )
  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )

  await user.keyboard('{Escape}')
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  await user.click(dialog.parentElement!)
  expect(within(dialog).getByRole('button', { name: '취소' })).toBeDisabled()
  expect(onClose).not.toHaveBeenCalled()

  release()
  await waitFor(() => expect(onStarted).toHaveBeenCalledTimes(1))
  expect(onClose).not.toHaveBeenCalled()
})

test('표가 200개를 넘는 원천도 모든 표를 고를 수 있게 다음 쪽까지 받는다', async () => {
  const many = Array.from({ length: 205 }, (_, i) => ({
    id: 5000 + i,
    dataSourceId: 2,
    resourceKey: `mes.t${String(i).padStart(3, '0')}`,
    resourceKind: 'TABLE',
    isReadable: true,
    sourceMetadata: {},
  }))
  server.use(
    http.get('/api/v1/sources/:id/resources', ({ params, request }) => {
      const url = new URL(request.url)
      const page = Number(url.searchParams.get('page') ?? 0)
      const size = Number(url.searchParams.get('size') ?? 20)
      const rows = Number(params.id) === 2 ? many : []
      return HttpResponse.json({
        data: rows.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalElements: rows.length,
          hasNext: (page + 1) * size < rows.length,
        },
      })
    }),
  )
  const user = userEvent.setup()
  const dialog = await openFromSource(user)

  await user.type(
    within(dialog).getByLabelText('테이블·자료 이름 검색'),
    'mes.t204',
  )
  const list = await within(dialog).findByRole('list', { name: '표·자료 목록' })
  expect(within(list).getByText('mes.t204')).toBeInTheDocument()
})
