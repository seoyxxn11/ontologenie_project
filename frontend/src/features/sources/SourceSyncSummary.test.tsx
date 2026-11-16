import { formatKstDateTime } from '../../utils/dateTime'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import SourceSyncSummary from './SourceSyncSummary'
import { fetchSourceSyncSummary, type SyncRun } from './syncApi'

const clients: QueryClient[] = []
afterEach(async () => {
  cleanup()
  await Promise.all(clients.map((client) => client.cancelQueries()))
  clients.forEach((client) => client.clear())
  clients.length = 0
})

const finished = '2026-11-12T01:00:00Z'
const cdc = '2026-11-12T02:00:00Z'
function run(sourceId = 1, status: SyncRun['status'] = 'SUCCEEDED'): SyncRun {
  return {
    id: 7,
    syncRunId: 7,
    dataSourceId: sourceId,
    triggerKind: 'RECOVERY',
    status,
    requestedBy: null,
    requestedAt: '2026-11-12T00:00:00Z',
    startedAt: null,
    finishedAt: status === 'SUCCEEDED' || status === 'FAILED' ? finished : null,
    errorMessage: null,
    resources: [],
  }
}
function page(data: SyncRun[] = [], cdcLastAppliedAt: string | null = null) {
  return {
    data,
    meta: {
      page: 0,
      size: 1,
      totalElements: data.length,
      hasNext: false,
      cdcLastAppliedAt,
    },
  }
}
function open(ids = [1]) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      {ids.map((id) => (
        <SourceSyncSummary
          key={id}
          sourceId={id}
          sourceName={'원천 ' + id}
          connectionStatus="CONNECTED"
        />
      ))}
    </QueryClientProvider>,
  )
  return client
}

test('074를 원천별 1건 조회하고 최근 전체 읽기 상태·완료와 CDC 반영 시각을 구분한다', async () => {
  const requests: string[] = []
  server.use(
    http.get('/api/v1/sources/:id/sync-runs', ({ params, request }) => {
      const url = new URL(request.url)
      expect(url.searchParams.toString()).toBe('page=0&size=1')
      requests.push(url.pathname)
      return HttpResponse.json(
        page(
          [run(Number(params.id), params.id === '1' ? 'SUCCEEDED' : 'FAILED')],
          params.id === '1' ? cdc : null,
        ),
      )
    }),
  )
  open([1, 2])
  const first = within(screen.getByRole('region', { name: '원천 1 상태 요약' }))
  const second = within(
    screen.getByRole('region', { name: '원천 2 상태 요약' }),
  )
  expect(await first.findByText('완료')).toBeInTheDocument()
  expect(await second.findByText('전체 읽기 · 실패')).toBeInTheDocument()
  expect(first.getByText(formatKstDateTime(finished))).toHaveAttribute(
    'dateTime',
    finished,
  )
  expect(first.getByText('CDC 마지막 반영').parentElement).toHaveTextContent(
    formatKstDateTime(cdc),
  )
  expect(second.getByText(/최근 실행 종료:/)).toHaveTextContent(
    formatKstDateTime(finished),
  )
  expect(first.queryByText(/전체 읽기 완료:/)).not.toBeInTheDocument()
  expect(second.queryByText('완료 시각:')).not.toBeInTheDocument()
  expect(requests.sort()).toEqual([
    '/api/v1/sources/1/sync-runs',
    '/api/v1/sources/2/sync-runs',
  ])
})

test('실패 안내를 유지하고 중복 버튼과 상태 기준 영역은 표시하지 않는다', async () => {
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(page([run(1, 'FAILED')])),
    ),
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <SourceSyncSummary
        sourceId={1}
        sourceName="ERP 운영 DB"
        connectionStatus="CONNECTED"
      />
    </QueryClientProvider>,
  )
  const alert = await screen.findByRole('alert')
  expect(alert).toBeVisible()
  expect(alert).toHaveTextContent('최근 전체 읽기가 실패했습니다.')
  expect(alert.closest('details')).toBeNull()
  expect(screen.queryByText('상태·시각 기준')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '수집 이력 보기' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: /수집 요약 새로고침/ }),
  ).not.toBeInTheDocument()
})

test('전체 읽기 이력이 없어도 CDC 반영 시각은 표시하고 미수집으로 단정하지 않는다', async () => {
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(page([], cdc)),
    ),
  )
  open()
  expect(await screen.findByText('전체 읽기 이력 없음')).toBeInTheDocument()
  expect(screen.getByText('CDC 마지막 반영').parentElement).toHaveTextContent(
    formatKstDateTime(cdc),
  )
  expect(screen.queryByText('미수집')).not.toBeInTheDocument()
})

test.each([500, 403, 401])(
  '%i 조회 오류를 수집 실패나 빈 이력으로 표시하지 않는다',
  async (status) => {
    server.use(
      http.get(
        '/api/v1/sources/1/sync-runs',
        () => new HttpResponse(null, { status }),
      ),
    )
    open()
    expect(
      await screen.findByText(
        status === 500 ? '요약 조회 실패' : '조회 권한 없음',
      ),
    ).toBeInTheDocument()
    expect(screen.getByText('확인 불가')).toBeInTheDocument()
    expect(screen.queryByText('전체 읽기 이력 없음')).not.toBeInTheDocument()
    expect(screen.queryByText('전체 읽기 · 실패')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '원천 1 수집 요약 다시 조회' }) !==
        null,
    ).toBe(status === 500)
  },
)

test('한 원천의 조회 실패가 다른 원천을 가리지 않고 다시 조회는 GET만 실행한다', async () => {
  let failed = true
  const post = vi.fn(() => new HttpResponse(null, { status: 500 }))
  server.use(
    http.get('/api/v1/sources/:id/sync-runs', ({ params }) =>
      params.id === '1' && failed
        ? new HttpResponse(null, { status: 500 })
        : HttpResponse.json(page([run(Number(params.id))])),
    ),
    http.post('/api/v1/sources/:id/sync-runs', post),
  )
  const user = userEvent.setup()
  open([1, 2])
  const first = within(screen.getByRole('region', { name: '원천 1 상태 요약' }))
  const second = within(
    screen.getByRole('region', { name: '원천 2 상태 요약' }),
  )
  await first.findByText('요약 조회 실패')
  expect(await second.findByText('완료')).toBeInTheDocument()
  failed = false
  await user.click(
    first.getByRole('button', { name: '원천 1 수집 요약 다시 조회' }),
  )
  expect(await first.findByText('완료')).toBeInTheDocument()
  expect(post).not.toHaveBeenCalled()
})

test('대기·실행 중인 최근 실행을 자동 조회해 완료 시각으로 갱신한다', async () => {
  const statuses = ['QUEUED', 'RUNNING', 'SUCCEEDED'] as const
  let reads = 0
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(page([run(1, statuses[Math.min(reads++, 2)])])),
    ),
  )
  open()
  expect(await screen.findByText('전체 읽기 · 대기')).toBeInTheDocument()
  const announcement = screen.getByRole('status')
  expect(announcement).toHaveAttribute('aria-live', 'polite')
  expect(announcement).toHaveAttribute('aria-atomic', 'true')
  await waitFor(
    () => expect(screen.getByText('전체 읽기 · 실행 중')).toBeInTheDocument(),
    { timeout: 10000 },
  )
  await waitFor(() => expect(screen.getByText('완료')).toBeInTheDocument(), {
    timeout: 10000,
  })
  expect(screen.getByRole('status')).toBe(announcement)
  expect(announcement).toHaveTextContent('완료')
  expect(screen.getByText(formatKstDateTime(finished))).toHaveAttribute(
    'dateTime',
    finished,
  )
}, 20000)

test('일시적인 갱신 실패에도 기존 수집 시각을 유지한다', async () => {
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(page([run()], cdc)),
    ),
  )
  const client = open()
  await screen.findByText('완료')
  server.use(
    http.get(
      '/api/v1/sources/1/sync-runs',
      () => new HttpResponse(null, { status: 500 }),
    ),
  )
  await client.invalidateQueries({ queryKey: ['sources', 1, 'sync-runs'] })
  expect(screen.queryByText('요약 조회 실패')).not.toBeInTheDocument()
  expect(screen.getByText(formatKstDateTime(cdc))).toBeInTheDocument()
})

test.each([
  { ...page([run()]), data: [run(2)] },
  { ...page([run()]), data: [{ ...run(), triggerKind: 'CDC' }] },
  { ...page([run()]), data: [{ ...run(), status: 'UNKNOWN' }] },
  { ...page([run()]), data: [{ ...run(), finishedAt: 'invalid' }] },
  { ...page(), meta: { ...page().meta, cdcLastAppliedAt: 'invalid' } },
  { ...page(), meta: undefined },
  { ...page(), meta: { ...page().meta, totalElements: 2 } },
  page([run(), run()]),
])('원천·상태·시각·페이지 계약이 다른 응답을 거부한다 (%#)', async (body) => {
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () => HttpResponse.json(body)),
  )
  await expect(fetchSourceSyncSummary(1)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})
