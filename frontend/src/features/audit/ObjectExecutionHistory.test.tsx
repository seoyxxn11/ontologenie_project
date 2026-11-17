import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { Link, MemoryRouter, Route, Routes } from 'react-router'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import AuditPage from './AuditPage'
import type { Execution } from './types'

function execution(id: number): Execution {
  return {
    id,
    requestId: `request-${id}`,
    eventKind: 'ACTION',
    category: 'OPERATION',
    actionName: `실행 ${id}`,
    actorType: 'USER',
    actorName: '계획 담당자',
    entryPoint: 'WEB',
    targetType: 'object_instance',
    targetId: 301,
    targetLabel: '프레스 3호기',
    status: 'SUCCEEDED',
    requestedAt: '2026-11-11T01:00:00Z',
    changeCount: 0,
  }
}

function renderHistory(path = '/audit?objectId=301') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Link to="/audit?objectId=302">다른 객체</Link>
        <Routes>
          <Route path="/audit" element={<AuditPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function mockPages(path = '/api/v1/objects/:id/executions') {
  const requests: URLSearchParams[] = []
  const items = Array.from({ length: 21 }, (_, index) => execution(index + 1))
  server.use(
    http.get(path, ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      const page = Number(params.get('page'))
      const filtered = params.has('status') || params.has('category')
      const matched = filtered ? items.slice(0, 1) : items
      return HttpResponse.json({
        data: matched.slice(page * 20, (page + 1) * 20),
        meta: {
          page,
          size: 20,
          totalElements: matched.length,
          hasNext: !filtered && page === 0,
        },
      })
    }),
  )
  return requests
}

test('첫 20건 이후의 이력과 이전 페이지를 조회한다', async () => {
  const requests = mockPages()
  const user = userEvent.setup()
  renderHistory()

  expect(await screen.findByText('1 / 2쪽 · 21건')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled()
  expect(within(screen.getByRole('table')).getAllByRole('row')).toHaveLength(21)
  expect(screen.queryByText('업무 실행 · 실행 21')).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  expect(await screen.findByText('2 / 2쪽 · 21건')).toBeInTheDocument()
  expect(screen.getByText('업무 실행 · 실행 21')).toBeInTheDocument()
  expect(screen.queryByText('업무 실행 · 실행 1')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled()
  expect(requests.at(-1)?.get('page')).toBe('1')

  await user.click(screen.getByRole('button', { name: '이전 페이지' }))
  expect(await screen.findByText('1 / 2쪽 · 21건')).toBeInTheDocument()
})

test('다음 페이지에서 기간·상태 검색과 초기화를 하면 첫 페이지를 조회한다', async () => {
  const requests = mockPages()
  const user = userEvent.setup()
  renderHistory()
  await screen.findByText('1 / 2쪽 · 21건')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('2 / 2쪽 · 21건')

  await user.type(screen.getByLabelText('시작일'), '2026-11-11')
  await user.type(screen.getByLabelText('종료일'), '2026-11-11')
  await user.selectOptions(screen.getByLabelText('결과'), 'SUCCEEDED')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('총 1건')).toBeInTheDocument()
  expect(Object.fromEntries(requests.at(-1)!)).toEqual({
    page: '0',
    size: '20',
    from: '2026-11-10T15:00:00.000Z',
    to: '2026-11-11T15:00:00.000Z',
    status: 'SUCCEEDED',
  })
  await user.click(screen.getByRole('button', { name: '초기화' }))
  await screen.findByText('1 / 2쪽 · 21건')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('2 / 2쪽 · 21건')
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(await screen.findByText('1 / 2쪽 · 21건')).toBeInTheDocument()
  expect(screen.getByLabelText('시작일')).toHaveValue('')
  expect(screen.getByLabelText('결과')).toHaveValue('')
})

test('공용 096 화면도 페이지 이동 후 종류를 바꾸면 첫 페이지를 조회한다', async () => {
  const requests = mockPages('/api/v1/audit/actions')
  const user = userEvent.setup()
  renderHistory('/audit')
  await screen.findByText('1 / 2쪽 · 21건')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('2 / 2쪽 · 21건')
  await user.click(screen.getByRole('button', { name: '업무 실행' }))
  expect(await screen.findByText('총 1건')).toBeInTheDocument()
  expect(requests.at(-1)?.get('page')).toBe('0')
  expect(requests.at(-1)?.get('category')).toBe('OPERATION')
})

test('다른 객체로 이동하면 이전 기록과 검색 조건·페이지가 남지 않는다', async () => {
  mockPages()
  let respond: (() => void) | undefined
  const nextRequests: URLSearchParams[] = []
  server.use(
    http.get('/api/v1/objects/302/executions', async ({ request }) => {
      nextRequests.push(new URL(request.url).searchParams)
      await new Promise<void>((resolve) => {
        respond = resolve
      })
      return HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      })
    }),
  )
  const user = userEvent.setup()
  renderHistory()
  await screen.findByText('1 / 2쪽 · 21건')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('2 / 2쪽 · 21건')
  await user.selectOptions(screen.getByLabelText('결과'), 'BLOCKED')
  await user.click(screen.getByRole('link', { name: '다른 객체' }))

  try {
    await waitFor(() => expect(respond).toBeDefined())
    expect(screen.getByText('실행 이력을 불러오는 중…')).toBeInTheDocument()
    expect(screen.queryByText('업무 실행 · 실행 21')).not.toBeInTheDocument()
    expect(screen.getByLabelText('결과')).toHaveValue('')
    expect(Object.fromEntries(nextRequests[0])).toEqual({
      page: '0',
      size: '20',
    })
  } finally {
    respond?.()
  }
  expect(
    await screen.findByText('조건에 맞는 이력이 없습니다.'),
  ).toBeInTheDocument()
})

test.each([
  [401, 'UNAUTHENTICATED', '로그인이 필요합니다.'],
  [403, 'FORBIDDEN', '요청이 허용되지 않았습니다.'],
  [404, 'RESOURCE_NOT_FOUND', '대상을 찾을 수 없습니다.'],
  [409, 'CONFLICT', '최신 상태를 확인해 주세요.'],
] as const)(
  '080의 %i 오류는 빈 이력과 구분하고 전체 이력으로 우회하지 않는다',
  async (status, code, title) => {
    let auditCalls = 0
    server.use(
      http.get('/api/v1/objects/:id/executions', () =>
        HttpResponse.json(
          {
            error: {
              code,
              message: '객체 이력 조회 거절',
              details: {},
              requestId: 'history-error',
            },
          },
          { status },
        ),
      ),
      http.get('/api/v1/audit/actions', () => {
        auditCalls += 1
        return HttpResponse.json({ data: [] })
      }),
    )
    renderHistory()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(title)
    expect(alert).toHaveTextContent('객체 이력 조회 거절')
    expect(
      screen.queryByText('조건에 맞는 이력이 없습니다.'),
    ).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '다시 불러오기' }),
    ).not.toBeInTheDocument()
    expect(auditCalls).toBe(0)
  },
)

test('080 서버 실패는 같은 객체 조회로 재시도할 수 있다', async () => {
  let calls = 0
  server.use(
    http.get('/api/v1/objects/:id/executions', () => {
      calls += 1
      return calls === 1
        ? HttpResponse.json(
            {
              error: {
                code: 'INTERNAL_ERROR',
                message: '조회 실패',
                details: {},
                requestId: 'failed',
              },
            },
            { status: 500 },
          )
        : HttpResponse.json({
            data: [execution(1)],
            meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
          })
    }),
  )
  const user = userEvent.setup()
  renderHistory()
  await screen.findByText('조회 실패')
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('업무 실행 · 실행 1')).toBeInTheDocument()
  expect(calls).toBe(2)
})

test.each(['', '0', '-1', '1.5', 'abc', '1e2', '9007199254740992'])(
  '잘못된 객체 번호 %s는 다른 조회로 바꾸지 않는다',
  async (id) => {
    const requests: string[] = []
    server.use(
      http.get('/api/v1/objects/:id/executions', ({ request }) => {
        requests.push(request.url)
        return HttpResponse.json({ data: [] })
      }),
      http.get('/api/v1/audit/actions', ({ request }) => {
        requests.push(request.url)
        return HttpResponse.json({ data: [] })
      }),
    )
    renderHistory(`/audit?objectId=${id}`)
    expect(screen.getByRole('alert')).toHaveTextContent('올바른 객체 번호')
    expect(requests).toEqual([])
    expect(screen.queryByLabelText('결과')).not.toBeInTheDocument()
  },
)

test('객체 실행 상세에서 성공 결과·차단 이유·실패 이유와 가린 값을 구분한다', async () => {
  server.use(
    http.get('/api/v1/objects/:id/executions', () =>
      HttpResponse.json({
        data: [
          execution(1),
          { ...execution(2), status: 'BLOCKED' },
          { ...execution(3), status: 'FAILED' },
        ],
        meta: { page: 0, size: 20, totalElements: 3, hasNext: false },
      }),
    ),
    http.get('/api/v1/audit/actions/:id', ({ params }) => {
      const id = Number(params.id)
      return HttpResponse.json({
        data: {
          id,
          status: id === 1 ? 'SUCCEEDED' : id === 2 ? 'BLOCKED' : 'FAILED',
          input: id === 3 ? { unitCost: null } : {},
          result: id === 1 ? { planId: 1 } : {},
          blockedReason: id === 2 ? '설비 조건 불일치' : null,
          failureReason: id === 3 ? '외부 응답 시간 초과' : null,
          restrictedPropertyCodes: id === 3 ? ['unitCost'] : [],
        },
      })
    }),
  )
  const user = userEvent.setup()
  renderHistory()
  await screen.findByText('총 3건')
  const rows = within(screen.getByRole('table')).getAllByRole('row').slice(1)
  for (const [index, row] of rows.entries()) {
    const trigger = within(row).getByText('내용 보기')
    await user.click(trigger)
    const dialog = within(await screen.findByRole('dialog'))
    if (index === 0) {
      expect(await dialog.findByText('재배정 계획 번호')).toBeVisible()
      expect(dialog.getByText('1', { exact: true })).toBeVisible()
      expect(dialog.getByText(/"planId": 1/)).not.toBeVisible()
      await user.click(dialog.getByText('기술 정보 · JSON'))
      expect(dialog.getByText(/"planId": 1/)).toBeVisible()
    } else if (index === 1)
      expect(await dialog.findByText('설비 조건 불일치')).toBeVisible()
    else {
      expect(await dialog.findByText('외부 응답 시간 초과')).toBeVisible()
      expect(dialog.getByText('권한으로 가림', { exact: true })).toBeVisible()
      expect(
        dialog.getByText(/"unitCost": "\(권한으로 가림\)"/),
      ).not.toBeVisible()
      await user.click(dialog.getByText('기술 정보 · JSON'))
      expect(dialog.getByText(/"unitCost": "\(권한으로 가림\)"/)).toBeVisible()
    }
    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  }
})
