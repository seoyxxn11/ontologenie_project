import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { Link, MemoryRouter, useLocation } from 'react-router'
import { expect, test, vi } from 'vitest'
import { issueFixture } from '../../mocks/issueHandlers'
import { server } from '../../mocks/node'
import IssuesPanel from './IssuesPanel'
import type { Issue } from './api'
import {
  fetchReverificationRun,
  requestReverification,
} from './reverificationApi'

const accepted = {
  id: 20,
  triggerKind: 'REVERIFY',
  status: 'QUEUED',
  scope: { kind: 'ISSUES', issueIds: [1], linkMappingIds: [99] },
  comparedToRunId: 1,
  requestedAt: '2026-11-12T01:00:00Z',
  startedAt: null,
  finishedAt: null,
  errorMessage: null,
  dataBasis: { resources: [{ sourceResourceId: 3, lastSyncRunId: 4 }] },
  joined: false,
  reused: false,
}

function Location() {
  const location = useLocation()
  return (
    <>
      <output aria-label="현재 경로">
        {location.pathname}
        {location.search}
      </output>
      <Link to="/explorer">다른 화면으로 이동</Link>
    </>
  )
}

function open(overrides: Partial<Issue> = {}, search = '?view=issues&issue=1') {
  const state = {
    issue: { ...issueFixture, status: 'FIXED_PENDING_REVERIFY', ...overrides },
  }
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const detail = vi.fn(() => HttpResponse.json({ data: state.issue }))
  const list = vi.fn(() => HttpResponse.json({ data: [state.issue] }))
  const history = vi.fn(() =>
    HttpResponse.json({
      data: [],
      meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
    }),
  )
  server.use(
    http.get('/api/v1/issues', list),
    http.get('/api/v1/issues/1', detail),
    http.get('/api/v1/issues/1/history', history),
  )
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[`/admin/rules${search}`]}>
        <Location />
        <IssuesPanel />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return { state, client, detail, list, history, ...view }
}

test('한 번 접수하고 대기·실행·완료를 조회하며 실제 개정본 결과와 이전 결과를 비교한다', async ({
  onTestFinished,
}) => {
  const errors = vi.spyOn(console, 'error')
  onTestFinished(() => errors.mockRestore())
  const user = userEvent.setup()
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      triggerKind: 'REVERIFY',
      scope: { kind: 'ISSUES', issueIds: [1] },
      comparedToRunId: 1,
    })
    await delay(80)
    return HttpResponse.json({ data: accepted }, { status: 202 })
  })
  let gets = 0
  const get = vi.fn(() => {
    gets += 1
    return HttpResponse.json({
      data: {
        ...accepted,
        status: gets === 1 ? 'RUNNING' : 'SUCCEEDED',
        startedAt: '2026-11-12T01:01:00Z',
        finishedAt: gets === 1 ? null : '2026-11-12T01:02:00Z',
        dataBasis: { resources: [{ sourceResourceId: 3, lastSyncRunId: 5 }] },
      },
    })
  })
  const result = vi.fn(
    ({ params, request }: { params: { id?: string }; request: Request }) => {
      const runId = Number(params.id)
      const linkMappingId = runId === 1 ? 12 : 99
      expect(new URL(request.url).searchParams.get('linkMappingId')).toBe(
        String(linkMappingId),
      )
      return HttpResponse.json({
        data: [
          {
            id: runId,
            checkRunId: runId,
            linkMappingId,
            proposalItemId: null,
            totalCount: 2,
            matchedCount: 1,
            unmatchedCount: 1,
            duplicateCount: 0,
            violationCount: 0,
            matchRate: 0.5,
            sampleRows: [{ rawValue: runId === 1 ? '이전 근거' : '현재 근거' }],
          },
        ],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      })
    },
  )
  const change = vi.fn(() => HttpResponse.json({}))
  const verifications = vi.fn(() =>
    HttpResponse.json({
      data: [
        {
          issueId: 1,
          checkRunId: 20,
          triggerKind: 'REVERIFY',
          status: 'SUCCEEDED',
          outcome: 'PASSED',
          detail: { classification: 'MATCHED' },
          checkedAt: '2026-11-12T01:02:00Z',
        },
      ],
      meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
    }),
  )
  const comparison = vi.fn(() =>
    HttpResponse.json({
      data: {
        comparedToRunId: 1,
        coverage: 'UNAVAILABLE',
        hasBaselinePassEvidence: false,
        rows: [],
      },
      meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
    }),
  )
  server.use(
    http.post('/api/v1/checks/runs', post),
    http.get('/api/v1/checks/runs/20', get),
    http.get('/api/v1/checks/runs/:id/results', result),
    http.patch('/api/v1/issues/1/status', change),
    http.get('/api/v1/issues/1/verifications', verifications),
    http.get('/api/v1/checks/runs/20/comparison', comparison),
  )
  const { detail, list, history } = open()
  await user.dblClick(
    await screen.findByRole('button', { name: '재검증 실행' }),
  )
  await screen.findByText('재검증 실행 #20 · 실행 중')
  expect(screen.getByRole('button', { name: '재검증 실행' })).toBeDisabled()
  await screen.findByText('재검증 실행 #20 · 완료', {}, { timeout: 4000 })
  await waitFor(() => {
    expect(detail.mock.calls.length).toBeGreaterThan(1)
    expect(list.mock.calls.length).toBeGreaterThan(1)
    expect(history.mock.calls.length).toBeGreaterThan(1)
  })
  expect(screen.getByLabelText('현재 경로')).toHaveTextContent(
    'reverifyIssue=1&reverifyRun=20',
  )
  await user.click(screen.getByText('검사 데이터 기준'))
  expect(screen.getByText('원천 자원 #3 · 성공 수집 #5')).toBeInTheDocument()
  expect(verifications).not.toHaveBeenCalled()
  expect(comparison).not.toHaveBeenCalled()
  await user.click(screen.getByText('검사 결과 비교'))
  await within(
    await screen.findByRole('region', { name: '발견 당시 검사 결과' }),
  ).findByText('이전 근거')
  await within(
    await screen.findByRole('region', { name: '이번 재검증 검사 결과' }),
  ).findByText('현재 근거')
  expect(
    screen.getByText(/실행 완료는 개별 문제의 통과·해결 판정이 아닙니다/),
  ).toBeInTheDocument()
  const historyRegion = within(
    await screen.findByRole('region', { name: '문제 재검증 이력' }),
  )
  expect(await historyRegion.findByText('실행 #20 · 통과')).toBeInTheDocument()
  expect(
    await screen.findByRole('region', { name: '재검증 비교 결과' }),
  ).toBeInTheDocument()
  expect(post).toHaveBeenCalledTimes(1)
  expect(get).toHaveBeenCalledTimes(2)
  expect(change).not.toHaveBeenCalled()
  expect(verifications).toHaveBeenCalledTimes(1)
  expect(comparison).toHaveBeenCalledTimes(1)
  expect(
    errors.mock.calls.filter(([message]) =>
      String(message).includes('Encountered two children with the same key'),
    ),
  ).toEqual([])
})

test.each([false, true])(
  '접수 중 상세를 닫거나 이동한 후 늦은 응답이 와도 경로를 복원하지 않는다 (이동=%s)',
  async (navigate) => {
    const user = userEvent.setup()
    let resolve!: (response: Response) => void
    const post = vi.fn(
      () =>
        new Promise<Response>((done) => {
          resolve = done
        }),
    )
    const get = vi.fn(() => HttpResponse.json({ data: accepted }))
    server.use(
      http.post('/api/v1/checks/runs', post),
      http.get('/api/v1/checks/runs/20', get),
    )
    const { client } = open()
    await user.click(await screen.findByRole('button', { name: '재검증 실행' }))
    await waitFor(() => expect(resolve).toBeDefined())
    await user.click(screen.getByRole('button', { name: '닫기' }))
    if (navigate)
      await user.click(screen.getByRole('link', { name: '다른 화면으로 이동' }))
    const path = screen.getByLabelText('현재 경로').textContent
    resolve(HttpResponse.json({ data: accepted }, { status: 202 }))
    await waitFor(() =>
      expect(
        client.getQueryData(['issues', 'reverification', 1, 1, 20]),
      ).toEqual(accepted),
    )
    expect(screen.getByLabelText('현재 경로')).toHaveTextContent(path!)
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(post).toHaveBeenCalledTimes(1)
    expect(get).not.toHaveBeenCalled()
  },
)

test.each([
  {
    joined: true,
    reused: false,
    status: 'RUNNING',
    message: '이미 대기·실행 중인 재검증에 합류했습니다.',
    httpStatus: 202,
  },
  {
    joined: false,
    reused: true,
    status: 'SUCCEEDED',
    message: '같은 수집 기준의 완료 결과를 재사용합니다.',
    httpStatus: 200,
  },
])('접수 결과 joined=$joined reused=$reused를 구분한다', async (receipt) => {
  const user = userEvent.setup()
  const data = { ...accepted, ...receipt }
  server.use(
    http.post('/api/v1/checks/runs', () =>
      HttpResponse.json({ data }, { status: receipt.httpStatus }),
    ),
    http.get('/api/v1/checks/runs/20', () => HttpResponse.json({ data })),
  )
  open()
  await user.click(await screen.findByRole('button', { name: '재검증 실행' }))
  await screen.findByText(receipt.message)
})

test('URL에 보관한 실행을 다시 조회하고 실패 이유를 표시하며 완료 결과를 조회하지 않는다', async () => {
  const post = vi.fn(() => HttpResponse.json({ data: accepted }))
  const get = vi.fn(() =>
    HttpResponse.json({
      data: {
        ...accepted,
        status: 'FAILED',
        errorMessage: '검사 데이터 읽기 실패',
      },
    }),
  )
  const results = vi.fn(() => HttpResponse.json({ data: [] }))
  server.use(
    http.post('/api/v1/checks/runs', post),
    http.get('/api/v1/checks/runs/20', get),
    http.get('/api/v1/checks/runs/20/results', results),
  )
  open({}, '?view=issues&issue=1&reverifyIssue=1&reverifyRun=20')
  await screen.findByText('재검증 실행 #20 · 실패')
  expect(screen.getByRole('alert')).toHaveTextContent('검사 데이터 읽기 실패')
  expect(screen.getByRole('button', { name: '재검증 실행' })).toBeEnabled()
  expect(get).toHaveBeenCalledTimes(1)
  expect(post).not.toHaveBeenCalled()
  expect(results).not.toHaveBeenCalled()
})

test('상태 조회 오류의 재시도는 POST 대신 GET만 다시 수행한다', async () => {
  const user = userEvent.setup()
  let failed = true
  const post = vi.fn(() => HttpResponse.json({ data: accepted }))
  const get = vi.fn(() =>
    failed
      ? HttpResponse.json(
          { error: { code: 'TEST_ERROR', message: '상태 조회 실패' } },
          { status: 500 },
        )
      : HttpResponse.json({ data: { ...accepted, status: 'SUCCEEDED' } }),
  )
  server.use(
    http.post('/api/v1/checks/runs', post),
    http.get('/api/v1/checks/runs/20', get),
  )
  open({}, '?view=issues&issue=1&reverifyIssue=1&reverifyRun=20')
  await screen.findByText('상태 조회 실패')
  expect(screen.getByRole('button', { name: '재검증 실행' })).toBeDisabled()
  failed = false
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await screen.findByText('재검증 실행 #20 · 완료')
  expect(get).toHaveBeenCalledTimes(2)
  expect(post).not.toHaveBeenCalled()
})

test.each([400, 401, 403, 404, 409, 422])(
  '접수 HTTP %s 오류를 표시하고 자동 재전송하지 않는다',
  async (status) => {
    const user = userEvent.setup()
    const { state, detail } = open()
    const post = vi.fn(() => {
      if (status === 409 || status === 422)
        state.issue = { ...state.issue, status: 'RESOLVED', rowVersion: 1 }
      return HttpResponse.json(
        { error: { code: 'TEST_ERROR', message: '재검증 접수 실패' } },
        { status },
      )
    })
    server.use(http.post('/api/v1/checks/runs', post))
    await user.click(await screen.findByRole('button', { name: '재검증 실행' }))
    await screen.findByText('재검증 접수 실패')
    expect(
      screen.queryByText('재검증 실행 #20을 접수했습니다.'),
    ).not.toBeInTheDocument()
    expect(post).toHaveBeenCalledTimes(1)
    if (status === 409 || status === 422) {
      await waitFor(() => expect(detail.mock.calls.length).toBeGreaterThan(1))
      expect(
        screen.queryByRole('button', { name: '재검증 실행' }),
      ).not.toBeInTheDocument()
    }
  },
)

test('통신 실패는 접수 불확실과 실행 이력 링크를 표시한다', async () => {
  const user = userEvent.setup()
  const post = vi.fn(() => HttpResponse.error())
  server.use(http.post('/api/v1/checks/runs', post))
  open()
  await user.click(await screen.findByRole('button', { name: '재검증 실행' }))
  await screen.findByText(/접수 여부가 불확실합니다/)
  expect(screen.getByRole('link', { name: '실행 이력' })).toHaveAttribute(
    'href',
    '/audit',
  )
  expect(post).toHaveBeenCalledTimes(1)
})

test.each(['NEW', 'CHECKING', 'RESOLVED'])(
  '%s 상태는 재검증 실행 버튼을 제공하지 않는다',
  async (status) => {
    open({ status })
    await screen.findByRole('region', { name: '재검증' })
    expect(
      screen.queryByRole('button', { name: '재검증 실행' }),
    ).not.toBeInTheDocument()
  },
)

test.each([
  { linkMappingId: null, proposalItemId: 4 },
  { detectedCheckRunId: 0 },
])('검사 기준이 없는 대상은 실행을 막는다 (%j)', async (overrides) => {
  open(overrides)
  expect(
    await screen.findByRole('button', { name: '재검증 실행' }),
  ).toBeDisabled()
})

test('다른 문제의 URL 실행 선택을 현재 문제에 적용하지 않는다', async () => {
  const get = vi.fn(() => HttpResponse.json({ data: accepted }))
  server.use(http.get('/api/v1/checks/runs/20', get))
  open({}, '?view=issues&issue=1&reverifyIssue=2&reverifyRun=20')
  expect(
    await screen.findByRole('button', { name: '재검증 실행' }),
  ).toBeEnabled()
  expect(get).not.toHaveBeenCalled()
})

test.each([
  null,
  { ...accepted, scope: { ...accepted.scope, issueIds: [2] } },
  { ...accepted, comparedToRunId: 3 },
  { ...accepted, scope: { ...accepted.scope, linkMappingIds: [] } },
  { ...accepted, joined: undefined },
  { ...accepted, reused: true },
  { ...accepted, status: 'SUCCEEDED', reused: true, joined: true },
  { ...accepted, dataBasis: null },
])('잘못된 접수 응답은 성공으로 처리하지 않는다 (%j)', async (data) => {
  server.use(
    http.post('/api/v1/checks/runs', () => HttpResponse.json({ data })),
  )
  await expect(requestReverification(1, 1)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})

test.each([
  { ...accepted, id: 21 },
  { ...accepted, triggerKind: 'MANUAL' },
  { ...accepted, status: 'UNKNOWN' },
])('조회 실행의 ID·종류·상태 불일치를 거절한다 (%j)', async (data) => {
  server.use(
    http.get('/api/v1/checks/runs/20', () => HttpResponse.json({ data })),
  )
  await expect(fetchReverificationRun(20, 1, 1)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})
