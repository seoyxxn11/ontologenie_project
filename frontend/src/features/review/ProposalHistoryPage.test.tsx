import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../../App'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import ProposalHistoryPage from './ProposalHistoryPage'
import ProposalDetail from './ProposalDetail'
import type { ProposalDecisionRecord } from './decisionApi'
import type { BusinessApprovalSource } from './businessApproval'

const clients: QueryClient[] = []
afterEach(async () => {
  cleanup()
  await Promise.all(clients.map((client) => client.cancelQueries()))
  clients.forEach((client) => client.clear())
  clients.length = 0
})

function open(
  path = '/admin/rules/303/history?view=activity',
  businessApprovalSource?: BusinessApprovalSource,
) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route
            path="/admin/rules/:id/history"
            element={
              <ProposalHistoryPage
                businessApprovalSource={businessApprovalSource}
              />
            }
          />
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}

test('기본 모의 이력의 과거 개정 원본을 팝업으로 조회한다', async () => {
  open()
  await userEvent.click(
    (await screen.findAllByRole('button', { name: '개정 1' }))[0],
  )
  expect(
    await within(screen.getByRole('dialog')).findByText(
      '작업지시 설비 코드 원문 연결',
    ),
  ).toBeInTheDocument()
})

test('현업 승인 상태는 별도 탭에서 준비 중으로 표시하고 기존 승인 결과를 사용하지 않는다', async () => {
  open('/admin/rules/301/history?view=business&reviewStatus=APPROVED')
  expect(
    await screen.findByRole('region', { name: '현업 승인 후 적용 상태' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '현업 승인 상태' }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: '승인 상태' })).toHaveAttribute(
    'aria-pressed',
    'false',
  )
  expect(screen.getAllByText('미확인')).toHaveLength(4)
  expect(screen.queryByText('반영 실패')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('link', { name: '제안 상세로 돌아가기' }),
  ).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '승인 상태' }))
  expect(
    await screen.findByRole('region', { name: '승인·반영 상태' }),
  ).toHaveTextContent('반영 실패')
})

test('현업 상태 조회는 탭 선택 후 현재 제안 개정으로만 실행한다', async () => {
  const load = vi.fn().mockResolvedValue({
    proposalId: 303,
    revision: 2,
    approvalStatus: 'PENDING',
    request: null,
    decisions: [],
    application: null,
  })
  open('/admin/rules/303/history', { load })
  await screen.findByRole('region', { name: '승인·반영 상태' })
  expect(load).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '현업 승인 상태' }))
  expect(await screen.findByText('승인 대기')).toBeInTheDocument()
  expect(load).toHaveBeenCalledWith(303, 2, expect.any(AbortSignal))
  expect(load).toHaveBeenCalledOnce()
})

function record(
  overrides: Partial<ProposalDecisionRecord> = {},
): ProposalDecisionRecord {
  return {
    id: 702,
    proposalItemId: 303,
    itemRevision: 1,
    decision: 'HOLD',
    selectedOptionId: null,
    reason: '설비 확인 대기',
    decidedBy: 7,
    decidedAt: '2026-11-01T03:00:00Z',
    ...overrides,
  }
}

function pageResponse(
  data: ProposalDecisionRecord[],
  page = 0,
  totalElements = data.length,
) {
  return HttpResponse.json({
    data,
    meta: {
      page,
      size: 20,
      totalElements,
      hasNext: (page + 1) * 20 < totalElements,
    },
  })
}

test('현재 승인 상태와 실제 반영 상태·실패 사유를 구분하고 이력은 선택할 때 조회한다', async () => {
  const decisions = vi.fn(() => pageResponse([]))
  server.use(http.get('/api/v1/proposals/301/decisions', decisions))
  open('/admin/rules/301/history')
  const status = await screen.findByRole('region', { name: '승인·반영 상태' })
  expect(within(status).getByText('승인')).toBeInTheDocument()
  expect(within(status).getByText('반영 실패')).toBeInTheDocument()
  expect(within(status).getByText(/대상 설비가 변경되어/)).toBeInTheDocument()
  expect(decisions).not.toHaveBeenCalled()
  await userEvent.click(screen.getByRole('button', { name: '활동 기록' }))
  expect(
    await screen.findByText('이 페이지에 결정 이력이 없습니다.'),
  ).toBeInTheDocument()
  expect(decisions).toHaveBeenCalledTimes(1)
})

test('보류 건 탭은 없고 예전 보류 건 주소는 승인 상태를 보여 준다', async () => {
  open('/admin/rules/303/history?view=held')
  expect(
    await screen.findByRole('region', { name: '승인·반영 상태' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '기존 보류 이력' }),
  ).not.toBeInTheDocument()
})

test('055를 0페이지·20개로 호출하고 과거 개정·대안 ID·실행자·사유를 서버 순서대로 표시한다', async () => {
  const requests: string[] = []
  server.use(
    http.get('/api/v1/proposals/303/decisions', ({ request }) => {
      requests.push(request.url)
      return pageResponse([
        record({
          id: 703,
          decision: 'APPROVE',
          reason: '승인 이유',
          selectedOptionId: 501,
          selectedOptionNo: 3,
          decidedByName: '김민수',
        }),
        record({ decision: 'REJECT', reason: '거절 이유' }),
      ])
    }),
    http.get('/api/v1/proposals/303/options', () =>
      HttpResponse.json({
        data: [
          {
            id: 501,
            optionNo: 3,
            revision: 1,
            payload: {},
            evidence: {},
            aiConfidence: null,
          },
        ],
      }),
    ),
  )
  open()
  const table = await screen.findByRole('table', { name: '검토 결정 이력' })
  const params = new URL(requests[0]).searchParams
  expect(params.get('page')).toBe('0')
  expect(params.get('size')).toBe('20')
  expect(params.has('revision')).toBe(false)
  expect(within(table).getAllByRole('row')[1]).toHaveTextContent('승인 이유')
  expect(within(table).getAllByRole('row')[2]).toHaveTextContent('거절 이유')
  expect(
    await within(table).findByRole('button', { name: '대안 3' }),
  ).toBeInTheDocument()
  expect(within(table).getByText('김민수')).toBeInTheDocument()
  expect(within(table).queryByText(/결정 #/)).not.toBeInTheDocument()
  expect(within(table).getAllByText('개정 1')).toHaveLength(2)
  expect(screen.getByText('제안 #303 · 현재 개정 2')).toBeInTheDocument()
})

test('서버 페이지를 이동하고 승인 상태로 돌아오면 결정 페이지를 초기화한다', async () => {
  const user = userEvent.setup()
  const pages: number[] = []
  server.use(
    http.get('/api/v1/proposals/303/decisions', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      pages.push(page)
      return pageResponse(
        [record({ id: page + 700, reason: `페이지 ${page}` })],
        page,
        21,
      )
    }),
  )
  open()
  await screen.findByText('페이지 0')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('페이지 1')
  expect(screen.queryByText('페이지 0')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '승인 상태' }))
  await user.click(screen.getByRole('button', { name: '활동 기록' }))
  await screen.findByText('페이지 0')
  expect(pages).toEqual([0, 1, 0])
})

test('로딩·빈 결과를 구분하고 쓰기 요청이나 처리 버튼을 만들지 않는다', async () => {
  const write = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(
    http.get('/api/v1/proposals/303/decisions', async () => {
      await delay(100)
      return pageResponse([])
    }),
    http.post('/api/v1/*', write),
  )
  open()
  expect(
    await screen.findByText('결정 이력을 불러오는 중…'),
  ).toBeInTheDocument()
  await screen.findByText('이 페이지에 결정 이력이 없습니다.')
  expect(screen.queryByRole('button', { name: '승인' })).not.toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
})

test.each([401, 403, 404])(
  '결정 조회 %s 오류를 빈 목록으로 위장하거나 자동 재요청하지 않는다',
  async (status) => {
    const handler = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code:
              status === 401
                ? 'UNAUTHENTICATED'
                : status === 403
                  ? 'FORBIDDEN'
                  : 'RESOURCE_NOT_FOUND',
            message: `조회 실패 ${status}`,
            details: {},
            requestId: 'history-test',
          },
        },
        { status },
      ),
    )
    server.use(http.get('/api/v1/proposals/303/decisions', handler))
    open()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      `조회 실패 ${status}`,
    )
    expect(
      screen.queryByText('이 페이지에 결정 이력이 없습니다.'),
    ).not.toBeInTheDocument()
    expect(handler).toHaveBeenCalledTimes(1)
  },
)

test('새로 조회하다 실패하면 캐시에 남은 결정 이력을 현재 결과처럼 표시하지 않는다', async () => {
  const user = userEvent.setup()
  open()
  await screen.findByRole('table', { name: '검토 결정 이력' })
  server.use(
    http.get('/api/v1/proposals/303/decisions', () => HttpResponse.error()),
  )
  await user.click(screen.getByRole('button', { name: '이력 새로고침' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '서버에 연결하지 못했습니다.',
  )
  expect(
    screen.queryByRole('table', { name: '검토 결정 이력' }),
  ).not.toBeInTheDocument()
})

test.each(['다른 제안', '페이지 누락'])(
  '%s 응답은 표시하지 않는다',
  async (kind) => {
    server.use(
      http.get('/api/v1/proposals/303/decisions', () =>
        kind === '다른 제안'
          ? pageResponse([record({ proposalItemId: 999 })])
          : HttpResponse.json({ data: [record()] }),
      ),
    )
    open()
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '결정 이력의 제안·페이지 정보를 확인할 수 없습니다.',
    )
    expect(screen.queryByText('설비 확인 대기')).not.toBeInTheDocument()
  },
)

test('잘못된 제안 주소는 상세·결정 API를 호출하지 않는다', async () => {
  const request = vi.fn(() => pageResponse([]))
  server.use(
    http.get('/api/v1/proposals/:id', request),
    http.get('/api/v1/proposals/:id/decisions', request),
  )
  open('/admin/rules/invalid/history?view=activity')
  await screen.findByText('올바른 제안 주소가 아닙니다.')
  expect(request).not.toHaveBeenCalled()
})

test('이력 링크로 이동하고 돌아올 때 제안 목록의 필터를 보존한다', async () => {
  const user = userEvent.setup()
  open('/admin/rules/303?reviewStatus=PENDING&page=2')
  await user.click(await screen.findByRole('link', { name: '이력' }))
  await screen.findByRole('heading', { name: '규칙 이력' })
  await user.click(screen.getByRole('button', { name: '활동 기록' }))
  expect(
    screen.getByRole('link', { name: '제안 상세로 돌아가기' }),
  ).toHaveAttribute('href', '/admin/rules/303?reviewStatus=PENDING&page=2')
  await user.click(screen.getByRole('link', { name: '제안 상세로 돌아가기' }))
  expect(
    await screen.findByRole('link', { name: '제안 목록으로 돌아가기' }),
  ).toHaveAttribute('href', '/admin/rules?reviewStatus=PENDING&page=2')
})

test('실제 관리 경로에서 VIEWER는 이력 화면과 API를 열지 않는다', async () => {
  setMockAccount({ ...demoAccount, roleCode: 'VIEWER' })
  window.history.pushState({}, '', '/admin/rules/303/history?view=activity')
  const request = vi.fn(() => pageResponse([]))
  server.use(
    http.get('/api/v1/proposals/:id', request),
    http.get('/api/v1/proposals/:id/decisions', request),
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })
  await waitFor(() => expect(client.isFetching()).toBe(0))
  expect(request).not.toHaveBeenCalled()
})

test('승인 상태 조회 실패 시 오래된 반영 정보를 표시하지 않는다', async () => {
  server.use(
    http.get('/api/v1/proposals/303', () =>
      HttpResponse.json(
        {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: '없는 제안',
            details: {},
            requestId: 'history-test',
          },
        },
        { status: 404 },
      ),
    ),
  )
  const client = new QueryClient()
  client.setQueryData(['proposals', 'detail', 303], proposalFixtures[0])
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/rules/303/history']}>
        <Routes>
          <Route
            path="/admin/rules/:id/history"
            element={<ProposalHistoryPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(await screen.findByRole('alert')).toHaveTextContent('없는 제안')
  expect(
    screen.queryByRole('region', { name: '승인·반영 상태' }),
  ).not.toBeInTheDocument()
})

test('과거 개정 팝업은 개정 목록 다음 페이지까지 조회하고 현재 내용으로 대체하지 않는다', async () => {
  const requests: number[] = []
  server.use(
    http.get('/api/v1/proposals/303/decisions', () =>
      pageResponse([record({ itemRevision: 21, decidedByName: '이서연' })]),
    ),
    http.get('/api/v1/proposals/303/revisions', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      requests.push(page)
      return HttpResponse.json({
        data: Array.from({ length: page === 0 ? 20 : 1 }, (_, i) => ({
          revision: page * 20 + i + 1,
          payload: {
            name: page === 0 ? '이전 기록' : '당시 규칙 내용 확인',
            conditions: [
              {
                sourceProperty: '과거 설비 코드',
                targetProperty: '과거 대상 코드',
                operator: 'EQ',
              },
            ],
          },
          evidence: {},
          aiConfidence: null,
          createdBy: 7,
          createdAt: '2026-11-01T03:00:00Z',
        })),
        meta: { page, size: 20, totalElements: 21, hasNext: page === 0 },
      })
    }),
  )
  const user = userEvent.setup()
  open()
  const trigger = await screen.findByRole('button', { name: '개정 21' })
  await user.click(trigger)
  const dialog = within(await screen.findByRole('dialog'))
  expect(await dialog.findByText('당시 규칙 내용 확인')).toBeVisible()
  expect(dialog.getByText('과거 설비 코드')).toBeVisible()
  expect(requests).toEqual([0, 1])
  await user.keyboard('{Escape}')
  expect(trigger).toHaveFocus()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('선택 대안은 당시 개정과 ID를 대조하고 번호는 optionNo를 표시한다', async () => {
  const revisions: string[] = []
  server.use(
    http.get('/api/v1/proposals/303/decisions', () =>
      pageResponse([record({ selectedOptionId: 501, selectedOptionNo: 3 })]),
    ),
    http.get('/api/v1/proposals/303/options', ({ request }) => {
      revisions.push(new URL(request.url).searchParams.get('revision') ?? '')
      return HttpResponse.json({
        data: [
          {
            id: 501,
            optionNo: 3,
            revision: 1,
            payload: {
              name: '과거 선택 후보',
              conditions: [
                {
                  sourceProperty: '옛 출발',
                  targetProperty: '옛 대상',
                  operator: 'EQ',
                },
              ],
            },
            evidence: {},
            aiConfidence: null,
          },
        ],
      })
    }),
  )
  const user = userEvent.setup()
  open()
  const trigger = await screen.findByRole('button', { name: '대안 3' })
  await user.click(trigger)
  const dialog = within(await screen.findByRole('dialog'))
  expect(await dialog.findByText('과거 선택 후보')).toBeVisible()
  expect(revisions.every((x) => x === '1')).toBe(true)
  await user.click(dialog.getByRole('button', { name: '닫기' }))
  expect(trigger).toHaveFocus()
})

test('해당 개정에 선택 대안 ID가 없으면 다른 대안을 대신 보여주지 않는다', async () => {
  server.use(
    http.get('/api/v1/proposals/303/decisions', () =>
      pageResponse([record({ selectedOptionId: 501 })]),
    ),
    http.get('/api/v1/proposals/303/options', () =>
      HttpResponse.json({
        data: [
          {
            id: 502,
            optionNo: 3,
            revision: 1,
            payload: { name: '다른 후보' },
            evidence: {},
            aiConfidence: null,
          },
        ],
      }),
    ),
  )
  open()
  await userEvent.click(
    await screen.findByRole('button', { name: '대안 보기' }),
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '해당 개정의 선택 대안을 찾을 수 없습니다.',
  )
  expect(screen.queryByText('다른 후보')).not.toBeInTheDocument()
})
