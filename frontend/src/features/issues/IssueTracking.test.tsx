import { formatKstDateTime } from '../../utils/dateTime'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { issueFixture } from '../../mocks/issueHandlers'
import { server } from '../../mocks/node'
import IssuesPanel from './IssuesPanel'
import type { Issue, IssueHistoryEntry } from './api'

const initialEntry: IssueHistoryEntry = {
  id: 1,
  issueId: 1,
  beforeStatus: null,
  afterStatus: 'NEW',
  changedAt: '2026-11-12T01:00:00Z',
  changedBy: null,
  checkRunId: 1,
  note: null,
}

function open(
  overrides: Partial<Issue> = {},
  entries: IssueHistoryEntry[] = [],
) {
  const state = { issue: { ...issueFixture, ...overrides }, entries }
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const detail = vi.fn(() => HttpResponse.json({ data: state.issue }))
  const list = vi.fn(() => HttpResponse.json({ data: [state.issue] }))
  const history = vi.fn(({ request }: { request: Request }) => {
    const params = new URL(request.url).searchParams
    const page = Number(params.get('page'))
    expect(params.get('size')).toBe('20')
    return HttpResponse.json({
      data: state.entries.slice(page * 20, (page + 1) * 20),
      meta: {
        page,
        size: 20,
        totalElements: state.entries.length,
        hasNext: (page + 1) * 20 < state.entries.length,
      },
    })
  })
  server.use(
    http.get('/api/v1/issues', list),
    http.get('/api/v1/issues/1', detail),
    http.get('/api/v1/issues/1/history', history),
  )
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/rules?view=issues&issue=1']}>
        <IssuesPanel />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { state, client, detail, list, history }
}

test('전달 기록은 최신 버전을 보내고 연속 클릭을 막으며 목록·상세·이력을 갱신한다', async () => {
  const user = userEvent.setup()
  const { state, detail, list, history } = open()
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({ rowVersion: 0 })
    await delay(100)
    state.issue = {
      ...state.issue,
      forwardedAt: '2026-11-12T01:02:00Z',
      forwardedBy: 5,
      rowVersion: 1,
    }
    return HttpResponse.json({ data: state.issue })
  })
  server.use(http.post('/api/v1/issues/1/forward', post))
  await user.dblClick(
    await screen.findByRole('button', { name: '전달함으로 기록' }),
  )
  await screen.findByText('전달한 사실을 기록했습니다.')
  const region = within(screen.getByRole('region', { name: '원천 전달 기록' }))
  expect(region.getByText('전달됨')).toBeInTheDocument()
  expect(region.getByText('계정 #5')).toBeInTheDocument()
  expect(
    region.getByText(formatKstDateTime(state.issue.forwardedAt!)),
  ).toBeInTheDocument()
  expect(post).toHaveBeenCalledTimes(1)
  expect(detail.mock.calls.length).toBeGreaterThan(1)
  expect(list.mock.calls.length).toBeGreaterThan(1)
  expect(history.mock.calls.length).toBeGreaterThan(1)
  expect(state.issue.status).toBe('NEW')
  expect(screen.getByText('상태 변경 이력이 없습니다.')).toBeInTheDocument()
})

test('재전달은 갱신된 버전으로 마지막 전달 시각·처리자를 덮어쓴다', async () => {
  const user = userEvent.setup()
  const { state } = open({
    forwardedAt: '2026-11-11T01:00:00Z',
    forwardedBy: 5,
    rowVersion: 1,
  })
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({ rowVersion: 1 })
    state.issue = {
      ...state.issue,
      forwardedAt: '2026-11-12T02:00:00Z',
      forwardedBy: 7,
      rowVersion: 2,
    }
    return HttpResponse.json({ data: state.issue })
  })
  server.use(http.post('/api/v1/issues/1/forward', post))
  await user.click(
    await screen.findByRole('button', { name: '다시 전달함으로 기록' }),
  )
  await screen.findByText('전달한 사실을 기록했습니다.')
  expect(
    within(screen.getByRole('region', { name: '원천 전달 기록' })).getByText(
      '계정 #7',
    ),
  ).toBeInTheDocument()
  expect(post).toHaveBeenCalledTimes(1)
})

test('이력은 시스템·관리자·근거 검사를 표시하고 서버 순서와 페이지를 유지한다', async () => {
  const user = userEvent.setup()
  const entries = [
    initialEntry,
    ...Array.from({ length: 20 }, (_, i) => ({
      ...initialEntry,
      id: i + 2,
      beforeStatus: 'NEW',
      afterStatus: 'CHECKING',
      changedBy: 5,
      checkRunId: null,
      note: `확인 사유 ${i + 1}`,
    })),
  ]
  open({}, entries)
  const region = within(
    await screen.findByRole('region', { name: '상태 변경 이력' }),
  )
  await region.findByText('시스템 ·', { exact: false })
  expect(region.getByText('근거 검사 #1')).toBeInTheDocument()
  expect(region.getAllByRole('listitem')[0]).toHaveTextContent('신규')
  expect(region.getAllByRole('listitem')[1]).toHaveTextContent('신규 → 확인중')
  await user.click(region.getByRole('button', { name: '다음 페이지' }))
  await region.findByText('확인 사유 20')
  expect(region.queryByText('근거 검사 #1')).not.toBeInTheDocument()
  await user.click(region.getByRole('button', { name: '이전 페이지' }))
  await region.findByText('근거 검사 #1')
})

test('067 상태 변경 성공 후 069 이력도 다시 조회한다', async () => {
  const user = userEvent.setup()
  const { state } = open({}, [initialEntry])
  server.use(
    http.patch('/api/v1/issues/1/status', async ({ request }) => {
      expect(await request.json()).toEqual({
        status: 'CHECKING',
        reason: '확인 시작',
        rowVersion: 0,
      })
      state.issue = { ...state.issue, status: 'CHECKING', rowVersion: 1 }
      state.entries.push({
        ...initialEntry,
        id: 2,
        beforeStatus: 'NEW',
        afterStatus: 'CHECKING',
        changedBy: 5,
        checkRunId: null,
        note: '확인 시작',
      })
      return HttpResponse.json({ data: state.issue })
    }),
  )
  await user.selectOptions(
    await screen.findByLabelText('변경할 상태'),
    'CHECKING',
  )
  await user.type(screen.getByLabelText('변경 사유'), '확인 시작')
  await user.click(screen.getByRole('button', { name: '상태 변경' }))
  const region = within(screen.getByRole('region', { name: '상태 변경 이력' }))
  await region.findByText('확인 시작')
  expect(region.getByText('신규 → 확인중')).toBeInTheDocument()
})

test.each(['VERSION_CONFLICT', 'ISSUE_ALREADY_RESOLVED'])(
  '전달 기록 %s는 재전송 없이 최신 정보를 조회한다',
  async (code) => {
    const user = userEvent.setup()
    const { state, detail } = open()
    const post = vi.fn(() => {
      state.issue = { ...state.issue, status: 'RESOLVED', rowVersion: 1 }
      return HttpResponse.json(
        {
          error: {
            code,
            message: '최신 문제 상태를 확인해 주세요.',
            details: {},
          },
        },
        { status: 409 },
      )
    })
    server.use(http.post('/api/v1/issues/1/forward', post))
    await user.click(
      await screen.findByRole('button', { name: '전달함으로 기록' }),
    )
    await screen.findByText('최신 문제 상태를 확인해 주세요.')
    await screen.findByText('해결된 문제는 전달 기록을 변경할 수 없습니다.')
    expect(post).toHaveBeenCalledTimes(1)
    expect(detail.mock.calls.length).toBeGreaterThan(1)
    expect(
      screen.queryByText('전달한 사실을 기록했습니다.'),
    ).not.toBeInTheDocument()
  },
)

test('해결된 문제는 전달 기록 버튼을 제공하지 않는다', async () => {
  open({ status: 'RESOLVED' })
  await screen.findByText('해결된 문제는 전달 기록을 변경할 수 없습니다.')
  expect(
    screen.queryByRole('button', { name: '전달함으로 기록' }),
  ).not.toBeInTheDocument()
})

test.each([400, 401, 403, 404])(
  '전달 기록 HTTP %s 오류를 표시하고 성공으로 처리하지 않는다',
  async (status) => {
    const user = userEvent.setup()
    open()
    const post = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code: 'TEST_ERROR',
            message: '전달 기록 요청 실패',
            details: {},
          },
        },
        { status },
      ),
    )
    server.use(http.post('/api/v1/issues/1/forward', post))
    await user.click(
      await screen.findByRole('button', { name: '전달함으로 기록' }),
    )
    await screen.findByText('전달 기록 요청 실패')
    expect(
      screen.queryByText('전달한 사실을 기록했습니다.'),
    ).not.toBeInTheDocument()
    expect(post).toHaveBeenCalledTimes(1)
  },
)

test('네트워크 실패 시 자동 재전송 없이 조회로 전달 여부를 확인한다', async () => {
  const user = userEvent.setup()
  const { state } = open()
  const post = vi.fn(() => {
    state.issue = {
      ...state.issue,
      forwardedAt: '2026-11-12T03:00:00Z',
      forwardedBy: 5,
      rowVersion: 1,
    }
    return HttpResponse.error()
  })
  server.use(http.post('/api/v1/issues/1/forward', post))
  await user.click(
    await screen.findByRole('button', { name: '전달함으로 기록' }),
  )
  await screen.findByText(
    '접수 여부가 불확실하므로 다시 기록하기 전에 최신 기록을 확인해 주세요.',
  )
  await user.click(
    screen.getByRole('button', { name: '최신 기록 다시 불러오기' }),
  )
  await screen.findByText('전달됨')
  expect(post).toHaveBeenCalledTimes(1)
})

test.each([
  null,
  { ...issueFixture, id: 2 },
  { ...issueFixture, rowVersion: 1 },
])('잘못된 저장 응답을 성공으로 표시하지 않는다 (%j)', async (data) => {
  const user = userEvent.setup()
  open()
  server.use(
    http.post('/api/v1/issues/1/forward', () => HttpResponse.json({ data })),
  )
  await user.click(
    await screen.findByRole('button', { name: '전달함으로 기록' }),
  )
  await screen.findByText(
    '전달 기록 저장 결과를 확인할 수 없습니다. 최신 기록을 다시 불러와 주세요.',
  )
  expect(
    screen.queryByText('전달한 사실을 기록했습니다.'),
  ).not.toBeInTheDocument()
})

test('늦은 전달 응답이 더 최신 상세를 덮어쓰지 않는다', async () => {
  const user = userEvent.setup()
  const { state, client } = open()
  let resolve!: (response: Response) => void
  server.use(
    http.post(
      '/api/v1/issues/1/forward',
      () =>
        new Promise<Response>((done) => {
          resolve = done
        }),
    ),
  )
  await user.click(
    await screen.findByRole('button', { name: '전달함으로 기록' }),
  )
  await waitFor(() => expect(resolve).toBeDefined())
  state.issue = {
    ...state.issue,
    status: 'CHECKING',
    rowVersion: 2,
    forwardedAt: '2026-11-12T04:00:00Z',
    forwardedBy: 7,
  }
  act(() => {
    client.setQueryData(['issues', 'detail', 1], { data: state.issue })
  })
  resolve(
    HttpResponse.json({
      data: {
        ...issueFixture,
        rowVersion: 1,
        forwardedAt: '2026-11-12T03:00:00Z',
        forwardedBy: 5,
      },
    }),
  )
  await screen.findByText('전달한 사실을 기록했습니다.')
  const region = within(screen.getByRole('region', { name: '원천 전달 기록' }))
  expect(region.getByText('계정 #7')).toBeInTheDocument()
  expect(region.queryByText('계정 #5')).not.toBeInTheDocument()
})

test.each([403, 404, 500])(
  '이력 HTTP %s 오류는 빈 이력으로 숨기지 않는다',
  async (status) => {
    const user = userEvent.setup()
    open()
    const get = vi.fn(() =>
      HttpResponse.json(
        {
          error: { code: 'TEST_ERROR', message: '이력 조회 실패', details: {} },
        },
        { status },
      ),
    )
    server.use(http.get('/api/v1/issues/1/history', get))
    const region = within(
      await screen.findByRole('region', { name: '상태 변경 이력' }),
    )
    await region.findByText('이력 조회 실패')
    expect(
      region.queryByText('상태 변경 이력이 없습니다.'),
    ).not.toBeInTheDocument()
    expect(get).toHaveBeenCalledTimes(1)
    if (status !== 500)
      expect(
        region.queryByRole('button', { name: '다시 불러오기' }),
      ).not.toBeInTheDocument()
    else {
      server.use(
        http.get('/api/v1/issues/1/history', () =>
          HttpResponse.json({
            data: [],
            meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
          }),
        ),
      )
      await user.click(region.getByRole('button', { name: '다시 불러오기' }))
      await region.findByText('상태 변경 이력이 없습니다.')
    }
  },
)

test('다른 문제의 이력이 섞인 응답은 표시하지 않는다', async () => {
  open()
  server.use(
    http.get('/api/v1/issues/1/history', () =>
      HttpResponse.json({
        data: [{ ...initialEntry, issueId: 2 }],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
  )
  await screen.findByText('상태 변경 이력 응답을 확인할 수 없습니다.')
})
