import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { checkRunFixture } from '../../mocks/checkHandlers'
import { server } from '../../mocks/node'
import type { CheckResult } from '../review/checkApi'
import CheckRunsPage from './CheckRunsPage'
import type { CheckRun } from './api'

const clients: QueryClient[] = []
beforeEach(() => setMockAccount({ ...demoAccount, roleCode: 'ADMIN' }))
afterEach(async () => {
  cleanup()
  await Promise.all(clients.map((client) => client.cancelQueries()))
  clients.forEach((client) => client.clear())
  clients.length = 0
  vi.useRealTimers()
})
function open(search = '', state?: { historyReturnTo: string }) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter
        initialEntries={[{ pathname: '/admin/checks', search, state }]}
      >
        <CheckRunsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}
const manual: CheckRun = {
  ...checkRunFixture,
  id: 802,
  triggerKind: 'MANUAL',
  status: 'QUEUED',
  startedAt: null,
  finishedAt: null,
  requestedBy: 1,
}
function list(rows: unknown[], totalElements = rows.length, page = 0) {
  return HttpResponse.json({
    data: rows,
    meta: {
      page,
      size: 20,
      totalElements,
      hasNext: (page + 1) * 20 < totalElements,
    },
  })
}
const result: CheckResult = {
  id: 912,
  checkRunId: 802,
  linkMappingId: 12,
  proposalItemId: null,
  proposalItemRevision: null,
  previousResultId: null,
  totalCount: 5,
  matchedCount: 2,
  unmatchedCount: 1,
  duplicateCount: 1,
  violationCount: 1,
  matchRate: 0.4,
  sampleRows: [
    {
      sourceKey: { id: 'WO-102' },
      rawValue: 'W23',
      transformedValue: 'E023',
      targetObjectIds: [99],
      classification: 'DUPLICATE',
      reason: '일치하는 도착 객체가 여러 개입니다.',
    },
  ],
}

test('059 목록에서 종류·상태·접수 기간을 전달하고 서버 페이지를 이동한다', async () => {
  const searches: URLSearchParams[] = []
  server.use(
    http.get('/api/v1/checks/runs', ({ request }) => {
      const params = new URL(request.url).searchParams
      searches.push(params)
      return list(
        [{ ...checkRunFixture, id: params.get('page') === '1' ? 800 : 801 }],
        21,
        Number(params.get('page')),
      )
    }),
  )
  const user = open()
  await screen.findByRole('button', { name: '검사 #801 상세 보기' })
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByRole('button', { name: '검사 #800 상세 보기' })
  expect(
    screen.queryByRole('button', { name: '검사 #801 상세 보기' }),
  ).not.toBeInTheDocument()
  await user.selectOptions(screen.getByLabelText('검사 종류'), 'MANUAL')
  await user.selectOptions(screen.getByLabelText('실행 상태'), 'FAILED')
  // datetime-local은 브라우저 현지 시각을 시간대가 붙은 ISO 시각으로 변환한다.
  await user.type(screen.getByLabelText('접수 시작 시각'), '2026-11-10T09:00')
  await user.type(screen.getByLabelText('접수 종료 시각'), '2026-11-12T18:00')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await waitFor(() =>
    expect(searches.at(-1)?.get('triggerKind')).toBe('MANUAL'),
  )
  const params = searches.at(-1)!
  expect(params.get('status')).toBe('FAILED')
  expect(params.get('page')).toBe('0')
  expect(params.get('size')).toBe('20')
  expect(params.get('from')).toBe(new Date('2026-11-10T09:00').toISOString())
  expect(params.get('to')).toBe(new Date('2026-11-12T18:00').toISOString())
  await user.click(screen.getByRole('button', { name: '초기화' }))
  await waitFor(() => expect(searches.at(-1)?.has('triggerKind')).toBe(false))
})

test('역전된 접수 기간은 조회 요청을 보내지 않는다', async () => {
  const get = vi.fn(() => list([]))
  server.use(http.get('/api/v1/checks/runs', get))
  const user = open()
  await screen.findByText('조건에 맞는 검사 실행이 없습니다.')
  await user.type(screen.getByLabelText('접수 시작 시각'), '2026-11-12T18:00')
  await user.type(screen.getByLabelText('접수 종료 시각'), '2026-11-10T09:00')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '종료 시각은 시작 시각 이후',
  )
  expect(get).toHaveBeenCalledOnce()
})

test.each([
  [false, false, '검사 실행 #802을 접수했습니다.'],
  [true, false, '이미 대기·실행 중인 검사 #802에 합류했습니다.'],
  [false, true, '같은 기준의 완료 결과 #802을 재사용합니다.'],
] as const)(
  '060 전체 검사 접수·합류·재사용을 구분한다 (%s, %s)',
  async (joined, reused, message) => {
    const post = vi.fn(async ({ request }: { request: Request }) => {
      expect(await request.json()).toEqual({
        triggerKind: 'MANUAL',
        scope: { kind: 'ALL' },
      })
      await delay(40)
      return HttpResponse.json(
        {
          data: {
            ...manual,
            status: reused ? 'SUCCEEDED' : 'QUEUED',
            joined,
            reused,
          },
        },
        { status: reused ? 200 : 202 },
      )
    })
    server.use(
      http.post('/api/v1/checks/runs', post),
      http.get('/api/v1/checks/runs/:id', () =>
        HttpResponse.json({
          data: {
            ...manual,
            status: 'FAILED',
            errorMessage: '검사 가능한 규칙이 없습니다.',
          },
        }),
      ),
    )
    const returnTo = '/admin/rules?reviewStatus=PENDING'
    const user = open('', { historyReturnTo: returnTo })
    await user.click(screen.getByRole('button', { name: '수동 검사 열기' }))
    await user.dblClick(screen.getByRole('button', { name: '수동 검사 실행' }))
    await screen.findByText(message)
    await screen.findByRole('region', { name: '검사 실행 #802 상세' })
    expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
      'href',
      returnTo,
    )
    expect(post).toHaveBeenCalledOnce()
  },
)

test('046 두 번째 페이지의 ACTIVE 규칙을 찾아 LINK_MAPPINGS로 보낸다', async () => {
  const pages: number[] = []
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      triggerKind: 'MANUAL',
      scope: { kind: 'LINK_MAPPINGS', linkMappingIds: [12] },
    })
    return HttpResponse.json(
      {
        data: {
          ...manual,
          scope: { kind: 'LINK_MAPPINGS', linkMappingIds: [12] },
          joined: false,
          reused: false,
        },
      },
      { status: 202 },
    )
  })
  server.use(
    http.get('/api/v1/ontology/rules/:code/revisions', ({ request }) => {
      const params = new URL(request.url).searchParams
      const page = Number(params.get('page') ?? 0)
      const size = Number(params.get('size') ?? 20)
      pages.push(page)
      const revisions = [
        ...Array.from({ length: 20 }, (_, index) => ({
          id: 1000 + index,
          code: 'WO_EQ',
          revision: index + 1,
          status: 'RETIRED',
        })),
        { id: 12, code: 'WO_EQ', revision: 21, status: 'ACTIVE' },
      ]
      return HttpResponse.json({
        data: revisions.slice(page * size, (page + 1) * size),
        meta: { page, size, totalElements: 21, hasNext: page === 0 },
      })
    }),
    http.post('/api/v1/checks/runs', post),
    http.get('/api/v1/checks/runs/:id', () =>
      HttpResponse.json({ data: manual }),
    ),
  )
  const user = open()
  await user.click(screen.getByRole('button', { name: '수동 검사 열기' }))
  await user.click(screen.getByLabelText('선택한 규칙'))
  expect(screen.getByRole('button', { name: '수동 검사 실행' })).toBeDisabled()
  await user.type(screen.getByLabelText('연결 규칙 코드'), 'WO_EQ')
  await user.click(screen.getByRole('button', { name: '규칙 찾기' }))
  await user.click(
    await screen.findByRole('button', { name: '검사 대상에 추가' }),
  )
  expect(screen.queryByText('WO_EQ · 개정 1')).not.toBeInTheDocument()
  expect(pages).toEqual([0, 1])
  expect(
    screen.getByRole('button', { name: '검사 대상에 추가' }),
  ).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '수동 검사 실행' }))
  await screen.findByText('검사 실행 #802을 접수했습니다.')
  expect(post).toHaveBeenCalledOnce()
})

test.each([403, 409, 422])(
  '060의 %s 오류를 표시하고 자동 재요청하지 않는다',
  async (status) => {
    const post = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code: 'REJECTED',
            message: '검사 요청이 거절되었습니다.',
            details: {},
          },
        },
        { status },
      ),
    )
    server.use(http.post('/api/v1/checks/runs', post))
    const user = open()
    await user.click(screen.getByRole('button', { name: '수동 검사 열기' }))
    await user.click(screen.getByRole('button', { name: '수동 검사 실행' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '검사 요청이 거절되었습니다.',
    )
    expect(screen.queryByText(/접수 여부가 불확실/)).not.toBeInTheDocument()
    expect(post).toHaveBeenCalledOnce()
  },
)

test('접수 통신 실패 시 목록 확인을 안내하고 목록 새로고침은 GET만 수행한다', async () => {
  const post = vi.fn(() => HttpResponse.error())
  const get = vi.fn(() => list([]))
  server.use(
    http.post('/api/v1/checks/runs', post),
    http.get('/api/v1/checks/runs', get),
  )
  const user = open()
  await screen.findByText('조건에 맞는 검사 실행이 없습니다.')
  await user.click(screen.getByRole('button', { name: '수동 검사 열기' }))
  await user.click(screen.getByRole('button', { name: '수동 검사 실행' }))
  await screen.findByText(/접수 여부가 불확실/)
  await user.click(screen.getByRole('button', { name: '닫기' }))
  await user.click(screen.getByRole('button', { name: '실행 목록 새로고침' }))
  await waitFor(() => expect(get).toHaveBeenCalledTimes(2))
  expect(post).toHaveBeenCalledOnce()
})

test('URL의 실행을 061로 조회하고 완료 후 062의 집계·표본을 읽으며 상태 조회를 멈춘다', async () => {
  let calls = 0
  const get = vi.fn(() =>
    HttpResponse.json({
      data: { ...manual, status: ++calls === 1 ? 'RUNNING' : 'SUCCEEDED' },
    }),
  )
  const results = vi.fn(() => list([result]))
  server.use(
    http.get('/api/v1/checks/runs/:id', get),
    http.get('/api/v1/checks/runs/:id/results', results),
  )
  const user = open('?run=802')
  await screen.findByText(/2초마다 상태를 확인/)
  expect(results).not.toHaveBeenCalled()
  await user.click(await screen.findByRole('button', { name: '규칙 #12' }))
  await screen.findByText('매칭률: 40.0%')
  expect(get).toHaveBeenCalledTimes(2)
  const table = screen.getByRole('table', { name: '검사 결과 912 판정 근거' })
  expect(within(table).getByText('[99]')).toBeInTheDocument()
  expect(
    within(table).getByText('일치하는 도착 객체가 여러 개입니다.'),
  ).toBeInTheDocument()
  vi.useFakeTimers()
  await act(async () => {
    await vi.advanceTimersByTimeAsync(6000)
  })
  expect(get).toHaveBeenCalledTimes(2)
})

test.each(['QUEUED', 'RUNNING', 'FAILED'] as const)(
  '%s 실행의 부분 결과는 요청하지 않는다',
  async (status) => {
    const results = vi.fn(() => list([]))
    server.use(
      http.get('/api/v1/checks/runs/:id', () =>
        HttpResponse.json({
          data: {
            ...manual,
            status,
            errorMessage: status === 'FAILED' ? '원천 데이터 기준 오류' : null,
          },
        }),
      ),
      http.get('/api/v1/checks/runs/:id/results', results),
    )
    open('?run=802')
    await screen.findByRole('region', { name: '검사 실행 #802 상세' })
    if (status === 'FAILED')
      expect(await screen.findByRole('alert')).toHaveTextContent(
        '원천 데이터 기준 오류',
      )
    expect(results).not.toHaveBeenCalled()
  },
)

test('061 조회 실패 후 다시 불러오기는 GET만 수행한다', async () => {
  const get = vi.fn(() => HttpResponse.error())
  const post = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(
    http.get('/api/v1/checks/runs/:id', get),
    http.post('/api/v1/checks/runs', post),
  )
  const user = open('?run=802')
  await screen.findByRole('alert')
  expect(get).toHaveBeenCalledOnce()
  server.use(
    http.get('/api/v1/checks/runs/:id', () =>
      HttpResponse.json({
        data: { ...manual, status: 'FAILED', errorMessage: '검사 실패' },
      }),
    ),
  )
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await screen.findByRole('region', { name: '검사 실행 #802 상세' })
  expect(post).not.toHaveBeenCalled()
})

test('다른 실행의 061 응답은 상세나 결과로 표시하지 않는다', async () => {
  server.use(
    http.get('/api/v1/checks/runs/:id', () =>
      HttpResponse.json({ data: checkRunFixture }),
    ),
  )
  open('?run=802')
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '검사 실행 응답을 확인할 수 없습니다.',
  )
  expect(
    screen.queryByRole('region', { name: '검사 결과' }),
  ).not.toBeInTheDocument()
})

test('규칙별 집계 선택과 결과 페이지 이동에 따라 해당 표본만 표시한다', async () => {
  const pages: number[] = []
  server.use(
    http.get('/api/v1/checks/runs/:id', () =>
      HttpResponse.json({ data: { ...manual, status: 'SUCCEEDED' } }),
    ),
    http.get('/api/v1/checks/runs/:id/results', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      pages.push(page)
      return list(
        page === 0
          ? [result, { ...result, id: 913, linkMappingId: 13, sampleRows: [] }]
          : [
              {
                ...result,
                id: 914,
                linkMappingId: 14,
                sampleRows: [{ rawValue: '다음 페이지 근거' }],
              },
            ],
        21,
        page,
      )
    }),
  )
  const user = open('?run=802')
  const summary = await screen.findByRole('table', { name: '규칙별 판정 집계' })
  expect(within(summary).getAllByRole('row')).toHaveLength(3)
  expect(
    screen.queryByRole('table', { name: '검사 결과 912 판정 근거' }),
  ).not.toBeInTheDocument()
  await user.click(within(summary).getByRole('button', { name: '규칙 #13' }))
  expect(
    screen.queryByRole('table', { name: '검사 결과 912 판정 근거' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText('제공된 판정 근거 표본이 없습니다.'),
  ).toBeInTheDocument()
  const dialog = screen.getByRole('dialog', { name: '규칙 판정 근거' })
  await user.click(within(dialog).getByRole('button', { name: '이전 규칙' }))
  expect(
    screen.getByRole('table', { name: '검사 결과 912 판정 근거' }),
  ).toBeInTheDocument()
  expect(
    within(dialog).getByRole('button', { name: '이전 규칙' }),
  ).toBeDisabled()
  await user.click(within(dialog).getByRole('button', { name: '다음 규칙' }))
  await user.click(within(dialog).getByRole('button', { name: '다음 규칙' }))
  await screen.findByText('다음 페이지 근거')
  expect(
    within(dialog).getByRole('button', { name: '다음 규칙' }),
  ).toBeDisabled()
  await user.click(within(dialog).getByRole('button', { name: '이전 규칙' }))
  expect(
    screen.getByText('제공된 판정 근거 표본이 없습니다.'),
  ).toBeInTheDocument()
  await user.click(within(dialog).getByRole('button', { name: '이전 규칙' }))
  expect(
    within(dialog).getByRole('button', { name: '이전 규칙' }),
  ).toBeDisabled()
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(
    within(summary).getByRole('button', { name: '규칙 #13' }),
  ).toHaveFocus()
  expect(
    within(summary).getByRole('button', { name: '규칙 #12' }),
  ).toBeInTheDocument()
  const results = screen.getByRole('region', { name: '검사 결과' })
  await user.click(within(results).getByRole('button', { name: '다음 페이지' }))
  await user.click(
    await within(results).findByRole('button', { name: '규칙 #14' }),
  )
  await screen.findByText('다음 페이지 근거')
  expect(screen.queryByText('규칙 #13')).not.toBeInTheDocument()
  expect(pages).toEqual([0, 1])
})

test('수동 검사 창을 취소하면 요청하지 않고 선택한 실행을 유지한다', async () => {
  const post = vi.fn(() => HttpResponse.json({ data: manual }))
  server.use(http.post('/api/v1/checks/runs', post))
  const user = open('?run=801')
  await screen.findByRole('region', { name: '검사 실행 #801 상세' })
  await user.click(screen.getByRole('button', { name: '수동 검사 열기' }))
  expect(
    screen.getByRole('dialog', { name: '수동 검사 실행' }),
  ).toBeInTheDocument()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(
    screen.getByRole('region', { name: '검사 실행 #801 상세' }),
  ).toBeInTheDocument()
  expect(post).not.toHaveBeenCalled()
})

test('43개 결과의 실제 20개 페이지 경계와 마지막 결과를 양방향 탐색한다', async () => {
  const rows = Array.from({ length: 43 }, (_, index) => ({
    ...result,
    id: 12000 + index,
    linkMappingId: 2000 + index,
    sampleRows: [{ rawValue: `근거 ${index + 1}` }],
  }))
  const pages: number[] = []
  server.use(
    http.get('/api/v1/checks/runs/:id', () =>
      HttpResponse.json({ data: { ...manual, status: 'SUCCEEDED' } }),
    ),
    http.get('/api/v1/checks/runs/:id/results', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      pages.push(page)
      return list(rows.slice(page * 20, (page + 1) * 20), 43, page)
    }),
  )
  const user = open('?run=802')
  await user.click(await screen.findByRole('button', { name: '규칙 #2019' }))
  const dialog = screen.getByRole('dialog', { name: '규칙 판정 근거' })
  expect(within(dialog).getByText('20 / 43개 결과')).toBeInTheDocument()
  await user.click(within(dialog).getByRole('button', { name: '다음 규칙' }))
  await within(dialog).findByText('근거 21')
  expect(within(dialog).getByText('21 / 43개 결과')).toBeInTheDocument()
  await user.click(within(dialog).getByRole('button', { name: '이전 규칙' }))
  expect(within(dialog).getByText('근거 20')).toBeInTheDocument()
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  expect(screen.getByRole('button', { name: '규칙 #2019' })).toHaveFocus()
  const results = screen.getByRole('region', { name: '검사 결과' })
  await user.click(
    within(results).getByRole('button', { name: '3페이지로 이동' }),
  )
  await user.click(await screen.findByRole('button', { name: '규칙 #2042' }))
  const last = screen.getByRole('dialog', { name: '규칙 판정 근거' })
  expect(within(last).getByText('43 / 43개 결과')).toBeInTheDocument()
  expect(within(last).getByRole('button', { name: '다음 규칙' })).toBeDisabled()
  for (const number of [42, 41, 40]) {
    await user.click(within(last).getByRole('button', { name: '이전 규칙' }))
    await within(last).findByText(`근거 ${number}`)
  }
  await user.keyboard('{Escape}')
  expect(screen.getByRole('button', { name: '규칙 #2042' })).toHaveFocus()
  expect(
    screen.queryByRole('button', { name: '규칙 #2039' }),
  ).not.toBeInTheDocument()
  expect(pages).toEqual([0, 1, 2])
})

test('모달의 다음 페이지 조회 실패는 오류를 표시하고 재시도 후 이동하며 본문을 유지한다', async () => {
  let failed = false
  const rows = Array.from({ length: 21 }, (_, index) => ({
    ...result,
    id: 12000 + index,
    linkMappingId: 2000 + index,
    sampleRows: [{ rawValue: `실패 복구 근거 ${index}` }],
  }))
  server.use(
    http.get('/api/v1/checks/runs/:id', () =>
      HttpResponse.json({ data: { ...manual, status: 'SUCCEEDED' } }),
    ),
    http.get('/api/v1/checks/runs/:id/results', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      if (page === 1 && !failed) {
        failed = true
        return HttpResponse.error()
      }
      return list(rows.slice(page * 20, (page + 1) * 20), 21, page)
    }),
  )
  const user = open('?run=802')
  await user.click(await screen.findByRole('button', { name: '규칙 #2019' }))
  const dialog = screen.getByRole('dialog', { name: '규칙 판정 근거' })
  await user.click(within(dialog).getByRole('button', { name: '다음 규칙' }))
  await within(dialog).findByRole('alert')
  expect(
    within(dialog).queryByText('실패 복구 근거 19'),
  ).not.toBeInTheDocument()
  await user.click(
    within(dialog).getByRole('button', { name: '다시 불러오기' }),
  )
  await within(dialog).findByText('실패 복구 근거 20')
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  expect(screen.getByRole('button', { name: '규칙 #2019' })).toHaveFocus()
})

test('직접 진입하면 이전 화면 버튼을 표시하지 않는다', async () => {
  open('?run=801')
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('link', { name: '← 연결 규칙으로 돌아가기' }),
  ).not.toBeInTheDocument()
})
test('진입 화면이 있으면 해당 화면의 검색 조건을 유지해 복귀한다', () => {
  open('', {
    historyReturnTo: '/admin/rules?reviewStatus=PENDING&proposal=303',
  })
  expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
    'href',
    '/admin/rules?reviewStatus=PENDING&proposal=303',
  )
})
test('외부 주소를 복귀 경로로 사용하지 않는다', () => {
  open('', { historyReturnTo: 'https://example.com' })
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
})

test('검사 선택·검색·초기화 후에도 진입 화면 복귀 경로를 유지한다', async () => {
  const returnTo = '/admin/rules?reviewStatus=PENDING&proposal=303'
  const user = open('', { historyReturnTo: returnTo })
  await user.click(await screen.findByRole('button', { name: /#801/ }))
  expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
    'href',
    returnTo,
  )
  await user.selectOptions(screen.getByLabelText('검사 종류'), 'MANUAL')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
    'href',
    returnTo,
  )
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
    'href',
    returnTo,
  )
})
