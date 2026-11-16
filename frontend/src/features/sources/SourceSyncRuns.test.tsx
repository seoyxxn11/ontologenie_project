import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  act,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import SourceSyncRuns from './SourceSyncRuns'
import {
  sourceSyncApi,
  type SyncRun,
  type SyncAccepted,
  type SyncPage,
} from './syncApi'

const resource = {
  syncRunId: 21,
  dataSourceId: 1,
  sourceResourceId: 10,
  syncStrategy: 'CDC' as const,
  mode: 'FULL' as const,
  status: 'SUCCEEDED' as const,
  fromLsn: null,
  toLsn: 100,
  rowsRead: 12,
  rowsUpserted: 10,
  rowsDeleted: 2,
  startedAt: null,
  finishedAt: null,
  errorMessage: null,
}
function run(overrides: Partial<SyncRun> = {}): SyncRun {
  return {
    id: 21,
    syncRunId: 21,
    dataSourceId: 1,
    triggerKind: 'MANUAL',
    status: 'SUCCEEDED',
    requestedBy: 1,
    requestedAt: '2026-11-07T01:00:00Z',
    startedAt: null,
    finishedAt: null,
    errorMessage: null,
    resources: [resource],
    ...overrides,
  }
}
function accepted(overrides: Partial<SyncAccepted> = {}): SyncAccepted {
  return {
    syncRunId: 21,
    dataSourceId: 1,
    triggerKind: 'MANUAL',
    status: 'QUEUED',
    requestedBy: 1,
    requestedAt: '2026-11-07T01:00:00Z',
    joinedExisting: false,
    resources: [{ ...resource, joined: false }],
    ...overrides,
  }
}
function mocks() {
  server.use(
    http.get('/api/v1/sources/1/resources', () =>
      HttpResponse.json({
        data: [
          {
            id: 10,
            dataSourceId: 1,
            resourceKey: 'public.orders',
            resourceKind: 'TABLE',
            isReadable: true,
            sourceMetadata: {},
          },
        ],
      }),
    ),
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json({
        data: [run()],
        meta: {
          page: 0,
          size: 10,
          totalElements: 1,
          hasNext: false,
          cdcLastAppliedAt: null,
        },
      }),
    ),
    http.get('/api/v1/sync-runs/:id', ({ params }) =>
      HttpResponse.json({
        data: run({ id: Number(params.id), syncRunId: Number(params.id) }),
      }),
    ),
    http.post('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json({ data: accepted() }, { status: 202 }),
    ),
  )
}
function open(api = true) {
  mocks()
  return render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SourceSyncRuns sourceId={1} api={api ? sourceSyncApi : undefined} />
    </QueryClientProvider>,
  )
}

test('API 미제공 상태에서는 요청과 조회를 실행하지 않는다', async () => {
  const post = vi.fn()
  open(false)
  server.use(http.post('/api/v1/sources/1/sync-runs', post))
  expect(screen.getByRole('button', { name: '수동 수집 요청' })).toBeDisabled()
  expect(
    screen.queryByRole('button', { name: '이력 새로고침' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText('수동 수집과 실행 이력 조회는 아직 사용할 수 없습니다.'),
  ).toBeInTheDocument()
  expect(post).not.toHaveBeenCalled()
})

test('전체 요청은 테이블 ID를 생략하고 연속 클릭은 한 번만 접수한다', async () => {
  const user = userEvent.setup()
  open()
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({})
    await delay(100)
    return HttpResponse.json({ data: accepted() }, { status: 202 })
  })
  server.use(http.post('/api/v1/sources/1/sync-runs', post))
  await user.dblClick(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText('수집 실행 #21을 접수했습니다.')
  expect(post).toHaveBeenCalledTimes(1)
  const detail = within(
    await screen.findByRole('region', { name: '수집 실행 #21 상세' }),
  )
  expect(
    detail.getByText('읽음 12 · 생성·갱신 10 · 삭제 2'),
  ).toBeInTheDocument()
})

test('선택 요청은 필수 선택을 검사하고 테이블별 기존 실행 여러 건을 안내한다', async () => {
  const user = userEvent.setup()
  open()
  server.use(
    http.post('/api/v1/sources/1/sync-runs', async ({ request }) => {
      expect(await request.json()).toEqual({ sourceResourceIds: [10] })
      return HttpResponse.json(
        {
          data: accepted({
            syncRunId: null,
            status: null,
            joinedExisting: true,
            resources: [
              { ...resource, syncRunId: 31, joined: true },
              {
                ...resource,
                sourceResourceId: 11,
                syncRunId: 32,
                joined: true,
              },
            ],
          }),
        },
        { status: 202 },
      )
    }),
  )
  await user.click(
    screen.getByRole('checkbox', { name: '활성 매핑이 있는 모든 테이블 수집' }),
  )
  expect(screen.getByRole('button', { name: '수동 수집 요청' })).toBeDisabled()
  await user.click(
    await screen.findByRole('checkbox', { name: 'public.orders' }),
  )
  await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText('새 실행 없이 기존 수집에 합류했습니다.')
  expect(
    screen.getByRole('button', { name: '실행 #31 보기' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '실행 #32 보기' }))
  await screen.findByRole('region', { name: '수집 실행 #32 상세' })
})

test.each([
  [400, 'VALIDATION_FAILED'],
  [401, 'UNAUTHENTICATED'],
  [403, 'FORBIDDEN'],
  [409, 'CONFLICT'],
  [422, 'BUSINESS_RULE_VIOLATION'],
  [404, 'RESOURCE_NOT_FOUND'],
])(
  '요청 실패 %s는 오류를 알리고 자동으로 재접수하지 않는다',
  async (status, code) => {
    const user = userEvent.setup()
    open()
    const post = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code,
            message: '수집 요청을 처리할 수 없습니다.',
            details: {},
            requestId: 'test-120',
          },
        },
        { status },
      ),
    )
    server.use(http.post('/api/v1/sources/1/sync-runs', post))
    await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
    await screen.findByText('수집 요청을 처리할 수 없습니다.')
    expect(post).toHaveBeenCalledTimes(1)
    expect(
      screen.queryByText('수집 실행 #21을 접수했습니다.'),
    ).not.toBeInTheDocument()
  },
)

test.each([400, 422, 409])(
  '%s 오류의 테이블 이름·없는 ID를 표시하고 CDC 재연결 사유를 안내한다',
  async (status) => {
    const user = userEvent.setup()
    open()
    await screen.findByRole('button', { name: '수집 실행 #21' })
    server.use(
      http.post('/api/v1/sources/1/sync-runs', () =>
        HttpResponse.json(
          {
            error: {
              code:
                status === 422
                  ? 'BUSINESS_RULE_VIOLATION'
                  : status === 409
                    ? 'CONFLICT'
                    : 'VALIDATION_FAILED',
              message: '요청 대상 테이블을 확인해 주세요.',
              details: {
                field: 'sourceResourceIds',
                reason:
                  status === 422
                    ? '켜진 매핑이 없는 테이블은 수집할 수 없습니다.'
                    : '수집 대상 확인이 필요합니다.',
                ids: [10, 999],
                ...(status === 409 ? { recoveryReason: 'SOURCE_CHANGED' } : {}),
              },
            },
          },
          { status },
        ),
      ),
    )
    await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
    await screen.findByText('확인이 필요한 테이블: public.orders, 테이블 #999')
    if (status === 409) {
      expect(
        screen.getByText(
          '원천 접속 정보가 변경되었습니다. CDC 재연결 후 다시 시도해 주세요.',
        ),
      ).toBeInTheDocument()
    } else {
      expect(
        screen.queryByText(/CDC 재연결 후 다시 시도/),
      ).not.toBeInTheDocument()
    }
    expect(screen.queryByText(/접수 여부가 불확실하면/)).not.toBeInTheDocument()
  },
)

test('일부 테이블은 새 실행으로 접수하고 나머지는 기존 실행 상세로 연결한다', async () => {
  const user = userEvent.setup()
  open()
  server.use(
    http.post('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(
        {
          data: accepted({
            joinedExisting: true,
            resources: [
              { ...resource, joined: false },
              {
                ...resource,
                sourceResourceId: 11,
                syncRunId: 31,
                status: 'QUEUED',
                joined: true,
              },
            ],
          }),
        },
        { status: 202 },
      ),
    ),
  )
  await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText('수집 실행 #21을 접수했습니다.')
  expect(
    screen.getByText(
      '일부 또는 모든 테이블은 기존 실행 결과를 함께 확인합니다.',
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '실행 #21 보기' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '실행 #31 보기' }))
  await screen.findByRole('region', { name: '수집 실행 #31 상세' })
})

test('전체 합류한 실행의 테이블이 QUEUED여도 실행 상태로 진행·완료를 판단한다', async () => {
  const user = userEvent.setup()
  open()
  let calls = 0
  server.use(
    http.post('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(
        {
          data: accepted({
            syncRunId: null,
            status: null,
            joinedExisting: true,
            resources: [
              { ...resource, syncRunId: 31, status: 'QUEUED', joined: true },
            ],
          }),
        },
        { status: 202 },
      ),
    ),
    http.get('/api/v1/sync-runs/31', () =>
      HttpResponse.json({
        data: run({
          id: 31,
          syncRunId: 31,
          status: ++calls === 1 ? 'RUNNING' : 'SUCCEEDED',
          resources: [
            {
              ...resource,
              syncRunId: 31,
              status: calls === 1 ? 'QUEUED' : 'SUCCEEDED',
            },
          ],
        }),
      }),
    ),
  )
  await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText('새 실행 없이 기존 수집에 합류했습니다.')
  await screen.findByRole('heading', { name: '수집 실행 #31 · 실행 중' })
  await screen.findByRole('heading', { name: '수집 실행 #31 · 완료' })
  expect(calls).toBe(2)
})

test('네트워크 실패는 재접수하지 않고 이력 확인을 안내하며 null 접수 응답도 오류로 처리한다', async () => {
  const user = userEvent.setup()
  open()
  const post = vi.fn(() => HttpResponse.error())
  server.use(http.post('/api/v1/sources/1/sync-runs', post))
  await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText(
    '접수 여부가 불확실하면 자동 갱신되는 실행 이력에서 확인해 주세요.',
  )
  expect(post).toHaveBeenCalledTimes(1)
  server.use(
    http.post('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json({ data: null }, { status: 202 }),
    ),
  )
  await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText(
    '수집 접수 결과를 확인할 수 없습니다. 이력을 확인해 주세요.',
  )
  expect(
    screen.queryByText('수집 실행 #21을 접수했습니다.'),
  ).not.toBeInTheDocument()
})

test('상태·CDC·기간 필터와 페이지 조건을 서버로 보내고 잘못된 기간은 막는다', async () => {
  const user = userEvent.setup()
  open()
  const queries: string[] = []
  server.use(
    http.get('/api/v1/sources/1/sync-runs', ({ request }) => {
      queries.push(request.url)
      return HttpResponse.json({
        data: [],
        meta: {
          page: 0,
          size: 10,
          totalElements: 21,
          hasNext: true,
          cdcLastAppliedAt: null,
        },
      })
    }),
  )
  await user.selectOptions(screen.getByLabelText('실행 상태'), 'FAILED')
  await user.selectOptions(screen.getByLabelText('시작 원인'), 'CDC')
  await waitFor(() =>
    expect(
      queries.some(
        (url) =>
          url.includes('status=FAILED') && url.includes('triggerKind=CDC'),
      ),
    ).toBe(true),
  )
  const from = screen.getByLabelText('접수 시작(포함)')
  const to = screen.getByLabelText('접수 종료(미포함)')
  // datetime-local의 브라우저 입력 이벤트를 사용한다.
  const { fireEvent } = await import('@testing-library/react')
  fireEvent.change(from, { target: { value: '2026-11-07T12:00' } })
  fireEvent.change(to, { target: { value: '2026-11-07T11:00' } })
  expect(screen.getByRole('button', { name: '기간 적용' })).toBeDisabled()
  fireEvent.change(to, { target: { value: '2026-11-07T13:00' } })
  await user.click(screen.getByRole('button', { name: '기간 적용' }))
  await waitFor(() =>
    expect(queries.some((url) => new URL(url).searchParams.has('from'))).toBe(
      true,
    ),
  )
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await waitFor(() =>
    expect(
      queries.some((url) => new URL(url).searchParams.get('page') === '1'),
    ).toBe(true),
  )
  expect(
    queries.every((url) => new URL(url).searchParams.get('size') === '10'),
  ).toBe(true)
  await user.click(screen.getByRole('button', { name: '마지막 페이지' }))
  await waitFor(() =>
    expect(
      queries.some((url) => new URL(url).searchParams.get('page') === '2'),
    ).toBe(true),
  )
})

test('실행 중 상세는 완료까지 갱신하고 실패 사유와 테이블 처리 건수를 보여준다', async () => {
  const user = userEvent.setup()
  open()
  let calls = 0
  server.use(
    http.get('/api/v1/sync-runs/21', () =>
      HttpResponse.json({
        data: run(
          ++calls === 1
            ? { status: 'RUNNING' }
            : {
                status: 'FAILED',
                errorMessage: 'heartbeat 시간 초과',
                resources: [
                  {
                    ...resource,
                    status: 'FAILED',
                    errorMessage: '테이블 수집 중단',
                  },
                ],
              },
        ),
      }),
    ),
  )
  await user.click(await screen.findByRole('button', { name: '수집 실행 #21' }))
  const detail = within(
    await screen.findByRole('region', { name: '수집 실행 #21 상세' }),
  )
  await detail.findByText('heartbeat 시간 초과')
  expect(detail.getByText('테이블 수집 중단')).toBeInTheDocument()
  expect(calls).toBe(2)
})

test('실행 중은 5초, 유휴·일시 오류는 30초마다 갱신하고 연속 실패만 알린다', async () => {
  const list = vi
    .fn()
    .mockResolvedValueOnce({ data: [] })
    .mockResolvedValueOnce({ data: [run({ status: 'RUNNING' })] })
    .mockResolvedValueOnce({ data: [run()] })
    .mockRejectedValueOnce(new Error('이력 조회 실패'))
    .mockRejectedValueOnce(new Error('이력 조회 실패'))
    .mockRejectedValueOnce(new Error('이력 조회 실패'))
    .mockResolvedValueOnce({ data: [run()] })
    .mockRejectedValueOnce(new Error('복구 후 일시 오류'))
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  client.setQueryData(['sources', 1, 'resources', 'all'], [])
  vi.useFakeTimers()
  const view = render(
    <QueryClientProvider client={client}>
      <SourceSyncRuns sourceId={1} api={{ ...sourceSyncApi, list }} />
    </QueryClientProvider>,
  )
  try {
    await act(() => vi.advanceTimersByTimeAsync(0))
    expect(list).toHaveBeenCalledTimes(1)
    expect(
      screen.queryByRole('button', { name: '이력 새로고침' }),
    ).not.toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(29999))
    expect(list).toHaveBeenCalledTimes(1)
    await act(() => vi.advanceTimersByTimeAsync(1))
    expect(list).toHaveBeenCalledTimes(2)
    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(list).toHaveBeenCalledTimes(3)
    await act(() => vi.advanceTimersByTimeAsync(30000))
    expect(list).toHaveBeenCalledTimes(4)
    expect(
      screen.queryByText(/이력 갱신이 지연되고 있습니다/),
    ).not.toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(30000))
    expect(list).toHaveBeenCalledTimes(5)
    expect(
      screen.queryByText(/이력 갱신이 지연되고 있습니다/),
    ).not.toBeInTheDocument()
    const row = screen.getByRole('button', { name: '수집 실행 #21' })
    await act(() => vi.advanceTimersByTimeAsync(30000))
    expect(list).toHaveBeenCalledTimes(6)
    expect(
      screen.getByText(/이력 갱신이 지연되고 있습니다/),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '수집 실행 #21' })).toBe(row)
    await act(() => vi.advanceTimersByTimeAsync(30000))
    expect(list).toHaveBeenCalledTimes(7)
    expect(
      screen.queryByText(/이력 갱신이 지연되고 있습니다/),
    ).not.toBeInTheDocument()
    await act(() => vi.advanceTimersByTimeAsync(30000))
    expect(list).toHaveBeenCalledTimes(8)
    expect(
      screen.queryByText(/이력 갱신이 지연되고 있습니다/),
    ).not.toBeInTheDocument()
    expect(list).toHaveBeenLastCalledWith(1, { page: 0 })
  } finally {
    view.unmount()
    client.clear()
    vi.useRealTimers()
  }
})

test('백그라운드 갱신 대기·성공·실패에도 목록 DOM과 필터·페이지를 유지한다', async () => {
  const user = userEvent.setup()
  const result: SyncPage = {
    data: [run({ status: 'FAILED' })],
    meta: { page: 1, size: 10, totalElements: 21, hasNext: true },
  }
  const list = vi.fn().mockResolvedValue(result)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  client.setQueryData(['sources', 1, 'resources', 'all'], [])
  const view = render(
    <QueryClientProvider client={client}>
      <SourceSyncRuns sourceId={1} api={{ ...sourceSyncApi, list }} />
    </QueryClientProvider>,
  )
  try {
    await screen.findByRole('button', { name: '수집 실행 #21' })
    await user.selectOptions(screen.getByLabelText('실행 상태'), 'FAILED')
    await user.selectOptions(screen.getByLabelText('시작 원인'), 'MANUAL')
    fireEvent.change(screen.getByLabelText('접수 시작(포함)'), {
      target: { value: '2026-11-07T12:00' },
    })
    await user.click(screen.getByRole('button', { name: '다음 페이지' }))
    await waitFor(() =>
      expect(list).toHaveBeenLastCalledWith(1, {
        page: 1,
        status: 'FAILED',
        triggerKind: 'MANUAL',
      }),
    )
    const row = screen.getByRole('button', { name: '수집 실행 #21' })
    const pagination = screen.getByRole('navigation', { name: '페이지 이동' })
    let resolve!: (value: SyncPage) => void
    list.mockImplementationOnce(
      () =>
        new Promise<SyncPage>((done) => {
          resolve = done
        }),
    )
    let refresh!: Promise<void>
    act(() => {
      refresh = client.refetchQueries({
        queryKey: ['sources', 1, 'sync-runs'],
        type: 'active',
      })
    })
    expect(screen.getByRole('button', { name: '수집 실행 #21' })).toBe(row)
    expect(
      screen.queryByText('수집 이력을 불러오는 중…'),
    ).not.toBeInTheDocument()
    await act(async () => {
      resolve({
        ...result,
        data: [
          run({ status: 'FAILED', resources: [{ ...resource, rowsRead: 99 }] }),
        ],
      })
      await refresh
    })
    expect(screen.getByRole('button', { name: '수집 실행 #21' })).toBe(row)
    list.mockRejectedValueOnce(new Error('일시적인 갱신 실패'))
    await act(async () => {
      await client.refetchQueries({
        queryKey: ['sources', 1, 'sync-runs'],
        type: 'active',
      })
    })
    expect(screen.queryByText('일시적인 갱신 실패')).not.toBeInTheDocument()
    expect(
      screen.queryByText(/이력 갱신이 지연되고 있습니다/),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '수집 실행 #21' })).toBe(row)
    expect(screen.getByRole('navigation', { name: '페이지 이동' })).toBe(
      pagination,
    )
    expect(
      screen.getByRole('button', { name: '2페이지로 이동' }),
    ).toHaveAttribute('aria-current', 'page')
    expect(screen.getByLabelText('실행 상태')).toHaveValue('FAILED')
    expect(screen.getByLabelText('시작 원인')).toHaveValue('MANUAL')
    expect(screen.getByLabelText('접수 시작(포함)')).toHaveValue(
      '2026-11-07T12:00',
    )
    expect(list).toHaveBeenLastCalledWith(1, {
      page: 1,
      status: 'FAILED',
      triggerKind: 'MANUAL',
    })
  } finally {
    view.unmount()
    client.clear()
  }
})

test('이력 조회 실패는 빈 목록으로 숨기지 않고 재조회한다', async () => {
  const user = userEvent.setup()
  open()
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: '이력 조회 실패',
            details: {},
            requestId: 'test',
          },
        },
        { status: 500 },
      ),
    ),
  )
  await screen.findByText('이력 조회 실패')
  expect(
    screen.queryByText('수집 실행 이력이 없습니다.'),
  ).not.toBeInTheDocument()
  server.use(
    http.get('/api/v1/sources/1/sync-runs', () =>
      HttpResponse.json({ data: [] }),
    ),
  )
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await screen.findByText('수집 실행 이력이 없습니다.')
})
