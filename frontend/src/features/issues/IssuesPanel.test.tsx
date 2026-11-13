import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, useNavigate } from 'react-router'
import { expect, test, vi } from 'vitest'
import { issueFixture } from '../../mocks/issueHandlers'
import { server } from '../../mocks/node'
import ProposalsPage from '../review/ProposalsPage'

test.each(['linkMappingId', 'proposalItemId'] as const)(
  '문제의 %s로 두 검사 실행의 결과와 근거를 비교한다',
  async (filter) => {
    const scope = { linkMappingId: null, proposalItemId: null, [filter]: 12 }
    const results = vi.fn(
      ({
        params,
        request,
      }: {
        params: { runId?: string }
        request: Request
      }) => {
        const runId = Number(params.runId)
        const expectedFilter = runId === 2 ? 'linkMappingId' : filter
        expect(new URL(request.url).searchParams.get(expectedFilter)).toBe(
          runId === 2 ? '24' : '12',
        )
        return HttpResponse.json({
          data: [
            {
              id: runId,
              checkRunId: runId,
              ...(runId === 2
                ? { linkMappingId: 24, proposalItemId: null }
                : scope),
              totalCount: 1,
              matchedCount: runId === 2 ? 1 : 0,
              unmatchedCount: runId === 2 ? 0 : 1,
              duplicateCount: 0,
              violationCount: 0,
              matchRate: runId === 2 ? 1 : 0,
              sampleRows: [{ rawValue: runId === 2 ? '수정된 값' : '최초 값' }],
            },
          ],
          meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
        })
      },
    )
    server.use(
      http.get('/api/v1/issues/1', () =>
        HttpResponse.json({
          data: {
            ...issueFixture,
            ...scope,
            status: 'RESOLVED',
            resolvedCheckRunId: 2,
          },
        }),
      ),
      http.get('/api/v1/issues/1/verifications', () =>
        HttpResponse.json({
          data: [
            {
              issueId: 1,
              checkRunId: 2,
              triggerKind: 'REVERIFY',
              status: 'SUCCEEDED',
              outcome: 'PASSED',
              detail: { linkMappingId: 24 },
              checkedAt: '2026-11-11T06:00:00Z',
            },
          ],
          meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
        }),
      ),
      http.get('/api/v1/checks/runs/2/comparison', () =>
        HttpResponse.json({
          data: {
            comparedToRunId: 1,
            coverage: 'UNAVAILABLE',
            hasBaselinePassEvidence: false,
            rows: [],
          },
          meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
        }),
      ),
      http.get('/api/v1/checks/runs/:runId/results', results),
    )
    const user = userEvent.setup()
    open('?view=issues&issue=1')
    const dialog = within(await screen.findByRole('dialog'))
    const toggle = await dialog.findByText('검사 결과 비교')
    expect(results).not.toHaveBeenCalled()
    await user.click(toggle)
    expect(
      await within(
        await dialog.findByRole('region', { name: '발견 당시 검사 결과' }),
      ).findByText('최초 값'),
    ).toBeInTheDocument()
    expect(
      await within(
        await dialog.findByRole('region', {
          name: '해결 처리에 사용한 재검증 결과',
        }),
      ).findByText('수정된 값'),
    ).toBeInTheDocument()
    expect(results).toHaveBeenCalledTimes(2)
  },
)

function HistoryControl() {
  const navigate = useNavigate()
  return (
    <button type="button" onClick={() => navigate(-1)}>
      브라우저 뒤로 가기
    </button>
  )
}

test('재검증 결과와 상태 변경 이력을 함께 표시하고 해결 후 갱신한다', async ({
  onTestFinished,
}) => {
  const errors = vi.spyOn(console, 'error')
  onTestFinished(() => errors.mockRestore())
  let requested = false
  let resolved = false
  const run = {
    id: 42,
    triggerKind: 'REVERIFY',
    scope: { kind: 'ISSUES', issueIds: [1], linkMappingIds: [12] },
    comparedToRunId: issueFixture.detectedCheckRunId,
    requestedAt: '2026-11-11T05:59:00Z',
    startedAt: '2026-11-11T05:59:30Z',
    finishedAt: '2026-11-11T06:00:00Z',
    errorMessage: null,
    dataBasis: { resources: [] },
  }
  const stateHistory = vi.fn(() => {
    const entries = [
      {
        id: 1,
        issueId: 1,
        beforeStatus: 'CHECKING',
        afterStatus: 'FIXED_PENDING_REVERIFY',
        changedAt: '2026-11-11T05:00:00Z',
        changedBy: 5,
        checkRunId: null,
        note: '원천 수정 완료',
      },
    ]
    if (resolved) {
      entries.push({
        ...entries[0],
        id: 2,
        beforeStatus: 'FIXED_PENDING_REVERIFY',
        afterStatus: 'RESOLVED',
        changedAt: '2026-11-11T06:01:00Z',
        note: '해결 근거 확인',
      })
    }
    return HttpResponse.json({
      data: entries,
      meta: {
        page: 0,
        size: 20,
        totalElements: entries.length,
        hasNext: false,
      },
    })
  })
  server.use(
    http.get('/api/v1/issues/1/history', stateHistory),
    http.get('/api/v1/issues/1', () =>
      HttpResponse.json({
        data: {
          ...issueFixture,
          status: resolved ? 'RESOLVED' : 'FIXED_PENDING_REVERIFY',
          resolvedCheckRunId: resolved ? 42 : null,
        },
      }),
    ),
    http.post('/api/v1/checks/runs', async ({ request }) => {
      expect(await request.json()).toEqual({
        triggerKind: 'REVERIFY',
        scope: { kind: 'ISSUES', issueIds: [1] },
        comparedToRunId: issueFixture.detectedCheckRunId,
      })
      requested = true
      return HttpResponse.json(
        {
          data: {
            ...run,
            status: 'QUEUED',
            startedAt: null,
            finishedAt: null,
            joined: false,
            reused: false,
          },
        },
        { status: 202 },
      )
    }),
    http.get('/api/v1/checks/runs/42', () =>
      HttpResponse.json({ data: { ...run, status: 'SUCCEEDED' } }),
    ),
    http.get('/api/v1/issues/1/verifications', () =>
      HttpResponse.json({
        data: requested
          ? [
              {
                issueId: 1,
                checkRunId: 42,
                triggerKind: 'REVERIFY',
                status: 'SUCCEEDED',
                outcome: 'PASSED',
                detail: { rawValue: 'E023', linkMappingId: 12 },
                checkedAt: '2026-11-11T06:00:00Z',
              },
            ]
          : [],
        meta: {
          page: 0,
          size: 20,
          totalElements: requested ? 1 : 0,
          hasNext: false,
        },
      }),
    ),
    http.get('/api/v1/checks/runs/42/comparison', () =>
      HttpResponse.json({
        data: {
          comparedToRunId: issueFixture.detectedCheckRunId,
          coverage: 'PARTIAL',
          hasBaselinePassEvidence: false,
          rows: [
            {
              category: 'RESOLVED',
              ruleCode: 'W2E',
              objectInstanceId: 12,
              before: { classification: 'UNMATCHED', reason: '수정 전 근거' },
              after: { classification: 'MATCHED' },
            },
          ],
        },
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
    http.patch('/api/v1/issues/1/status', async ({ request }) => {
      expect(await request.json()).toEqual({
        status: 'RESOLVED',
        reason: '통과 확인',
        rowVersion: issueFixture.rowVersion,
        resolvedCheckRunId: 42,
      })
      resolved = true
      return HttpResponse.json({
        data: {
          ...issueFixture,
          status: 'RESOLVED',
          rowVersion: issueFixture.rowVersion + 1,
          resolvedCheckRunId: 42,
        },
      })
    }),
  )
  const user = userEvent.setup()
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  const stateHistoryRegion = within(
    await dialog.findByRole('region', { name: '상태 변경 이력' }),
  )
  expect(
    await stateHistoryRegion.findByText('원천 수정 완료'),
  ).toBeInTheDocument()
  const initialHistoryRequests = stateHistory.mock.calls.length
  await user.click(await dialog.findByRole('button', { name: '재검증 실행' }))
  expect(await dialog.findByText('재검증 실행 #42 · 완료')).toBeInTheDocument()
  await user.click(dialog.getByText('검사 결과 비교'))
  const verificationHistoryRegion = within(
    await dialog.findByRole('region', { name: '문제 재검증 이력' }),
  )
  expect(
    await verificationHistoryRegion.findByText('실행 #42 · 통과'),
  ).toBeInTheDocument()
  await waitFor(() =>
    expect(stateHistory.mock.calls.length).toBeGreaterThan(
      initialHistoryRequests,
    ),
  )
  expect(stateHistoryRegion.getByText('원천 수정 완료')).toBeInTheDocument()
  expect(await dialog.findByText(/수정 전 근거/)).toBeInTheDocument()
  expect(
    dialog.getByRole('heading', { name: '신규 문제 확인 불가' }),
  ).toBeInTheDocument()
  await user.selectOptions(dialog.getByLabelText('변경할 상태'), 'RESOLVED')
  await user.type(dialog.getByLabelText('변경 사유'), '통과 확인')
  await user.type(dialog.getByLabelText('통과한 재검증 실행 ID'), '42')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  expect(
    await dialog.findByText('처리 상태를 변경했습니다.'),
  ).toBeInTheDocument()
  expect(
    await stateHistoryRegion.findByText('해결 근거 확인'),
  ).toBeInTheDocument()
  expect(
    verificationHistoryRegion.getByText('실행 #42 · 통과'),
  ).toBeInTheDocument()
  expect(
    errors.mock.calls.filter(([message]) =>
      String(message).includes('Encountered two children with the same key'),
    ),
  ).toEqual([])
})

function open(
  search = '',
  previousSearch?: string,
  client = new QueryClient(),
) {
  const entries = [`/admin/rules${search}`]
  if (previousSearch !== undefined)
    entries.unshift(`/admin/rules${previousSearch}`)
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={entries}>
        <HistoryControl />
        <ProposalsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('연결 규칙에서 문제를 열어 별도 상세 응답의 원천 근거와 원인 미확정을 표시한다', async () => {
  const detail = vi.fn(() =>
    HttpResponse.json({
      data: { ...issueFixture, rawValue: '57', transformedValue: 'GP0057' },
    }),
  )
  server.use(
    http.get('/api/v1/issues/1', detail),
    http.get('/api/v1/ontology/rules', () =>
      HttpResponse.json({
        data: [
          {
            code: 'W2E',
            description: '작업자 번호로 직원 연결',
            linkTypeName: '작업 담당자',
          },
        ],
      }),
    ),
  )
  const user = userEvent.setup()
  open()
  await user.click(screen.getByRole('link', { name: '적용 후 문제' }))
  const table = await screen.findByRole('table', { name: '정합성 문제 목록' })
  expect(
    await within(table).findByText('작업자 번호로 직원 연결 (연결 매핑 #12)'),
  ).toBeInTheDocument()
  await user.click(within(table).getByRole('button', { name: '#1 · 미연결' }))
  const dialog = within(await screen.findByRole('dialog'))
  expect(await dialog.findByText('GP0057')).toBeInTheDocument()
  expect(
    await dialog.findByText('작업자 번호로 직원 연결 (연결 매핑 #12)'),
  ).toBeVisible()
  expect(dialog.getByText('57')).toBeInTheDocument()
  expect(dialog.getByText('대응 직원 없음')).toBeInTheDocument()
  expect(dialog.getByText('MES')).toBeInTheDocument()
  expect(dialog.getByText('원인 미확정')).toBeInTheDocument()
  expect(detail).toHaveBeenCalledTimes(1)
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '브라우저 뒤로 가기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: '연결 규칙', level: 1 }),
  ).toBeInTheDocument()
})

test('문제 탭은 해결 포함 전체 건수이고 목록은 현재 필터 결과 건수임을 구분한다', async () => {
  server.use(
    http.get('/api/v1/issues', ({ request }) => {
      const status = new URL(request.url).searchParams.get('status')
      const data = Array.from({ length: 6 }, (_, index) => ({
        ...issueFixture,
        id: index + 1,
        status: index === 5 ? 'RESOLVED' : 'NEW',
      })).filter((row) => !status || row.status === status)
      return HttpResponse.json({
        data,
        meta: { page: 0, size: 20, totalElements: data.length, hasNext: false },
      })
    }),
  )
  const user = userEvent.setup()
  open('?view=issues')
  expect(
    await screen.findByText('적용 후 문제 · 전체 6건 (해결 포함)'),
  ).toBeVisible()
  expect(
    await screen.findByText('조회 결과 6건 · 전체 상태(해결 포함)'),
  ).toBeVisible()
  await user.click(screen.getByRole('button', { name: '해결' }))
  expect(await screen.findByText('조회 결과 1건 · 해결')).toBeVisible()
  expect(screen.getByText('적용 후 문제 · 전체 6건 (해결 포함)')).toBeVisible()
  expect(
    within(
      screen.getByRole('table', { name: '정합성 문제 목록' }),
    ).getAllByRole('row'),
  ).toHaveLength(2)
})

test('문제 집계 실패를 전체 0건이나 조회 중으로 표시하지 않는다', async () => {
  server.use(
    http.get('/api/v1/issues', () =>
      HttpResponse.json(
        { error: { code: 'SERVER_ERROR', message: '조회 실패' } },
        { status: 500 },
      ),
    ),
  )
  open()
  expect(
    await screen.findByText('적용 후 문제 · 전체 건수 조회 실패'),
  ).toBeVisible()
  expect(
    screen.queryByText('적용 후 문제 · 전체 0건 (해결 포함)'),
  ).not.toBeInTheDocument()
})

test('문제 집계의 전체 메타 정보가 없으면 페이지 행 수를 전체 건수로 오인하지 않는다', async () => {
  server.use(
    http.get('/api/v1/issues', () =>
      HttpResponse.json({ data: [issueFixture] }),
    ),
  )
  open()
  expect(
    await screen.findByText('적용 후 문제 · 전체 건수 확인 불가'),
  ).toBeVisible()
})

test('지연된 상태 변경 응답이 더 최신 상세를 덮어쓰지 않는다', async () => {
  const client = new QueryClient()
  let resolveResponse!: (response: Response) => void
  const response = new Promise<Response>((resolve) => {
    resolveResponse = resolve
  })
  const change = vi.fn(() => response)
  server.use(http.patch('/api/v1/issues/1/status', change))
  const user = userEvent.setup()
  open('?view=issues&issue=1', undefined, client)
  const dialog = within(await screen.findByRole('dialog'))
  await user.selectOptions(
    await dialog.findByLabelText('변경할 상태'),
    'CHECKING',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '확인 시작')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  expect(change).toHaveBeenCalledTimes(1)
  act(() => {
    client.setQueryData(['issues', 'detail', 1], {
      data: {
        ...issueFixture,
        status: 'FIXED_PENDING_REVERIFY',
        rowVersion: 2,
      },
    })
  })
  resolveResponse(
    HttpResponse.json({
      data: { ...issueFixture, status: 'CHECKING', rowVersion: 1 },
    }),
  )
  expect(
    await dialog.findByText('처리 상태를 변경했습니다.'),
  ).toBeInTheDocument()
  expect(dialog.getByText('조치완료·재검증대기')).toBeInTheDocument()
})

test('상세 URL로 직접 들어오면 닫아도 목록 필터가 유지되고 뒤로 가기가 상세를 다시 열지 않는다', async () => {
  const user = userEvent.setup()
  open('?view=issues&issueStatus=NEW&issue=1', '')
  const dialog = within(await screen.findByRole('dialog'))
  await user.click(dialog.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByLabelText('처리 상태')).toHaveValue('NEW')

  await user.click(screen.getByRole('button', { name: '브라우저 뒤로 가기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(
    screen.getByRole('heading', { name: '연결 규칙', level: 1 }),
  ).toBeInTheDocument()
})

test('상태 필터를 서버에 보내고 페이지를 첫 쪽으로 되돌린다', async () => {
  const requests: URLSearchParams[] = []
  server.use(
    http.get('/api/v1/issues', ({ request }) => {
      const params = new URL(request.url).searchParams
      requests.push(params)
      return HttpResponse.json({ data: [] })
    }),
  )
  const user = userEvent.setup()
  open('?view=issues&issuePage=2&issueKind=UNMATCHED')
  await screen.findByText('조건에 맞는 정합성 문제가 없습니다.')
  await user.selectOptions(screen.getByLabelText('처리 상태'), 'CHECKING')
  await screen.findByText('조건에 맞는 정합성 문제가 없습니다.')
  expect(
    requests
      .filter((params) => params.has('issueKind'))
      .map((params) => [
        params.get('page'),
        params.get('status'),
        params.get('issueKind'),
      ]),
  ).toEqual([
    ['2', null, 'UNMATCHED'],
    ['0', 'CHECKING', 'UNMATCHED'],
  ])
})

test('문제 상세의 권한 오류를 표시하고 재시도 버튼을 숨긴다', async () => {
  server.use(
    http.get('/api/v1/issues/1', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '관리자 권한이 필요합니다.',
            details: {},
          },
        },
        { status: 403 },
      ),
    ),
  )
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  expect(await dialog.findByRole('alert')).toHaveTextContent(
    '관리자 권한이 필요합니다.',
  )
  expect(
    dialog.queryByRole('button', { name: '다시 불러오기' }),
  ).not.toBeInTheDocument()
})

test('해결 요청에 최신 버전과 재검증 ID를 보내고 상세와 목록을 갱신한다', async () => {
  let issue = {
    ...issueFixture,
    status: 'FIXED_PENDING_REVERIFY',
    rowVersion: 2,
  }
  const change = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      status: 'RESOLVED',
      reason: '재검증 통과',
      resolvedCheckRunId: 12,
      rowVersion: 2,
    })
    issue = { ...issue, status: 'RESOLVED', rowVersion: 3 }
    return HttpResponse.json({ data: issue })
  })
  server.use(
    http.get('/api/v1/issues', () => HttpResponse.json({ data: [issue] })),
    http.get('/api/v1/issues/1', () => HttpResponse.json({ data: issue })),
    http.patch('/api/v1/issues/1/status', change),
  )
  const user = userEvent.setup()
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  await user.selectOptions(
    await dialog.findByLabelText('변경할 상태'),
    'RESOLVED',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '재검증 통과')
  const submit = dialog.getByRole('button', { name: '상태 변경' })
  expect(submit).toBeDisabled()
  await user.type(dialog.getByLabelText('통과한 재검증 실행 ID'), '12')
  await user.dblClick(submit)
  expect(
    await dialog.findByText('처리 상태를 변경했습니다.'),
  ).toBeInTheDocument()
  expect(dialog.getByText('해결')).toBeInTheDocument()
  const table = within(screen.getByRole('table', { name: '정합성 문제 목록' }))
  expect(await table.findByText('해결')).toBeInTheDocument()
  expect(change).toHaveBeenCalledTimes(1)
})

test('상태 변경 충돌의 원인을 표시하고 재전송 없이 최신 상태를 조회한다', async () => {
  let issue = { ...issueFixture }
  const change = vi.fn(() => {
    issue = { ...issue, status: 'CHECKING', rowVersion: 1 }
    return HttpResponse.json(
      {
        error: {
          code: 'VERSION_CONFLICT',
          message: '다른 사용자가 먼저 변경했습니다.',
        },
      },
      { status: 409 },
    )
  })
  server.use(
    http.get('/api/v1/issues/1', () => HttpResponse.json({ data: issue })),
    http.patch('/api/v1/issues/1/status', change),
  )
  const user = userEvent.setup()
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  await user.selectOptions(
    await dialog.findByLabelText('변경할 상태'),
    'CHECKING',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '확인 시작')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  expect(await dialog.findByRole('alert')).toHaveTextContent(
    '다른 사용자가 먼저 변경했습니다.',
  )
  expect(dialog.getByText('확인중')).toBeInTheDocument()
  expect(dialog.getByLabelText('변경할 상태')).toHaveValue('')
  expect(change).toHaveBeenCalledTimes(1)
})

test('마지막 페이지의 유일한 문제를 상태 변경하면 첫 페이지를 다시 조회한다', async () => {
  let changed = false
  const pages: number[] = []
  server.use(
    http.get('/api/v1/issues', ({ request }) => {
      const params = new URL(request.url).searchParams
      // 상단 전체 건수 조회는 목록의 상태 필터·페이지 이동과 별개다.
      if (!params.has('status')) {
        return HttpResponse.json({
          data: [issueFixture],
          meta: {
            page: 0,
            size: 20,
            totalElements: changed ? 20 : 21,
            hasNext: !changed,
          },
        })
      }
      expect(params.get('status')).toBe('NEW')
      const page = Number(params.get('page'))
      pages.push(page)
      return HttpResponse.json({
        data:
          page === 1
            ? changed
              ? []
              : [{ ...issueFixture, id: 21 }]
            : [{ ...issueFixture, id: 1 }],
        meta: {
          page,
          size: 20,
          totalElements: changed ? 20 : 21,
          hasNext: page === 0 && !changed,
        },
      })
    }),
    http.get('/api/v1/issues/21', () =>
      HttpResponse.json({
        data: {
          ...issueFixture,
          id: 21,
          status: changed ? 'CHECKING' : 'NEW',
          rowVersion: changed ? 1 : 0,
        },
      }),
    ),
    http.patch('/api/v1/issues/21/status', () => {
      changed = true
      return HttpResponse.json({
        data: { ...issueFixture, id: 21, status: 'CHECKING', rowVersion: 1 },
      })
    }),
  )
  const user = userEvent.setup()
  open('?view=issues&issueStatus=NEW&issuePage=1')
  await user.click(await screen.findByRole('button', { name: '#21 · 미연결' }))
  const dialog = within(await screen.findByRole('dialog'))
  await user.selectOptions(
    await dialog.findByLabelText('변경할 상태'),
    'CHECKING',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '확인 시작')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  expect(
    await screen.findByRole('button', { name: '#1 · 미연결' }),
  ).toBeInTheDocument()
  expect(pages).toEqual([1, 1, 0])
  expect(screen.getByText('1 / 1쪽 · 20건')).toBeInTheDocument()
})

test('확인중인 미연결 문제에서 058을 호출하고 연결 결과를 표시한다', async () => {
  server.use(
    http.get('/api/v1/issues/1', () =>
      HttpResponse.json({
        data: {
          ...issueFixture,
          issueKind: 'UNMATCHED',
          status: 'CHECKING',
          objectInstanceId: 11,
          linkMappingId: 12,
          rowVersion: 3,
        },
      }),
    ),
    http.post('/api/v1/issues/1/manual-links', async ({ request }) => {
      expect(await request.json()).toEqual({
        targetObjectId: 301,
        reason: '인사 기록 확인',
        rowVersion: 3,
      })
      return HttpResponse.json({
        data: {
          issueId: 1,
          linkId: 812,
          outcome: 'CREATED',
          actionExecutionId: 1001,
        },
      })
    }),
  )

  open('?view=issues&issue=1')
  const user = userEvent.setup()
  const dialog = within(await screen.findByRole('dialog'))
  await dialog.findByRole('option', { name: '설비' })
  await user.selectOptions(dialog.getByLabelText('도착 객체 유형'), '3')
  await user.type(dialog.getByLabelText('도착 객체 검색어'), '프레스 3호기')
  await user.click(dialog.getByRole('button', { name: '도착 객체 검색' }))
  await user.click(await dialog.findByRole('radio', { name: /프레스 3호기/ }))
  await user.type(dialog.getByLabelText('연결 사유'), '인사 기록 확인')
  await user.click(dialog.getByRole('button', { name: '연결 내용 확인' }))
  await user.click(dialog.getByRole('button', { name: '수동 연결 저장' }))
  expect(
    await dialog.findByText(
      '수동 연결을 저장했습니다. 조치완료로 바꾼 뒤 재검증하세요.',
    ),
  ).toBeInTheDocument()
  expect(dialog.getByText('확인중')).toBeInTheDocument()
})

test('상세 재조회가 권한 오류이면 이전 원본 값과 수동 연결을 숨긴다', async () => {
  let denied = false
  server.use(
    http.get('/api/v1/issues/1', () =>
      denied
        ? HttpResponse.json(
            { error: { code: 'FORBIDDEN', message: '권한이 없습니다.' } },
            { status: 403 },
          )
        : HttpResponse.json({
            data: {
              ...issueFixture,
              status: 'CHECKING',
              rawValue: 'PRIVATE_TEST_VALUE',
            },
          }),
    ),
  )
  open('?view=issues&issue=1')
  const user = userEvent.setup()
  const dialog = within(await screen.findByRole('dialog'))
  await dialog.findByText('PRIVATE_TEST_VALUE')
  denied = true
  await user.click(
    dialog.getByRole('button', { name: '최신 기록 다시 불러오기' }),
  )
  await dialog.findByText('권한이 없습니다.')
  expect(dialog.queryByText('PRIVATE_TEST_VALUE')).not.toBeInTheDocument()
  expect(
    dialog.queryByRole('region', { name: '수동 연결' }),
  ).not.toBeInTheDocument()
})

test('수동 연결 저장 중 상세 닫기와 다른 상태 변경을 막는다', async () => {
  let finish!: () => void
  const waiting = new Promise<void>((resolve) => {
    finish = resolve
  })
  const post = vi.fn(async () => {
    await waiting
    return HttpResponse.json({
      data: {
        issueId: 1,
        linkId: 812,
        outcome: 'CREATED',
        actionExecutionId: 1001,
      },
    })
  })
  server.use(
    http.get('/api/v1/issues/1', () =>
      HttpResponse.json({ data: { ...issueFixture, status: 'CHECKING' } }),
    ),
    http.post('/api/v1/issues/1/manual-links', post),
  )
  const user = userEvent.setup()
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  await dialog.findByRole('option', { name: '설비' })
  await user.selectOptions(dialog.getByLabelText('도착 객체 유형'), '3')
  await user.click(dialog.getByRole('button', { name: '도착 객체 검색' }))
  await user.click(await dialog.findByRole('radio', { name: /프레스 3호기/ }))
  await user.type(dialog.getByLabelText('연결 사유'), '시험 연결')
  await user.click(dialog.getByRole('button', { name: '연결 내용 확인' }))
  await user.click(dialog.getByRole('button', { name: '수동 연결 저장' }))
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
  expect(dialog.getByLabelText('변경할 상태')).toBeDisabled()
  await user.click(dialog.getByRole('button', { name: '닫기' }))
  await user.keyboard('{Escape}')
  expect(screen.getByRole('dialog')).toBeInTheDocument()
  await act(async () => {
    finish()
    await waiting
  })
  await dialog.findByText(
    '수동 연결을 저장했습니다. 조치완료로 바꾼 뒤 재검증하세요.',
  )
  await user.click(dialog.getByRole('button', { name: '닫기' }))
  await waitFor(() =>
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(),
  )
})

test('수동 연결 후 조치완료·재검증 통과·해결을 순서대로 진행한다', async () => {
  let current = { ...issueFixture, status: 'CHECKING' }
  let linked = false
  let passed = false
  const operations: string[] = []
  const run = {
    id: 42,
    triggerKind: 'REVERIFY',
    status: 'SUCCEEDED',
    scope: { kind: 'ISSUES', issueIds: [1], linkMappingIds: [12] },
    comparedToRunId: issueFixture.detectedCheckRunId,
    requestedAt: '2026-11-13T16:00:00Z',
    startedAt: '2026-11-13T16:00:01Z',
    finishedAt: '2026-11-13T16:00:02Z',
    errorMessage: null,
    dataBasis: { resources: [] },
  }
  server.use(
    http.get('/api/v1/issues/1', () => HttpResponse.json({ data: current })),
    http.post('/api/v1/issues/1/manual-links', async ({ request }) => {
      expect(await request.json()).toEqual({
        targetObjectId: 301,
        reason: '시험 연결',
        rowVersion: 0,
      })
      linked = true
      operations.push('MANUAL_LINK')
      return HttpResponse.json({
        data: {
          issueId: 1,
          linkId: 812,
          outcome: 'CREATED',
          actionExecutionId: 1001,
        },
      })
    }),
    http.patch('/api/v1/issues/1/status', async ({ request }) => {
      const body = (await request.json()) as {
        status: string
        rowVersion: number
        resolvedCheckRunId?: number
      }
      expect(body.rowVersion).toBe(current.rowVersion)
      if (body.status === 'FIXED_PENDING_REVERIFY') {
        expect(linked).toBe(true)
        expect(current.status).toBe('CHECKING')
      } else {
        expect(passed).toBe(true)
        expect(body.status).toBe('RESOLVED')
        expect(body.resolvedCheckRunId).toBe(42)
      }
      operations.push(body.status)
      current = {
        ...current,
        status: body.status,
        rowVersion: current.rowVersion + 1,
        resolvedCheckRunId: body.resolvedCheckRunId ?? null,
      }
      return HttpResponse.json({ data: current })
    }),
    http.post('/api/v1/checks/runs', async ({ request }) => {
      expect(current.status).toBe('FIXED_PENDING_REVERIFY')
      expect(await request.json()).toEqual({
        triggerKind: 'REVERIFY',
        scope: { kind: 'ISSUES', issueIds: [1] },
        comparedToRunId: issueFixture.detectedCheckRunId,
      })
      passed = true
      operations.push('REVERIFY_PASSED')
      return HttpResponse.json({
        data: { ...run, joined: false, reused: false },
      })
    }),
    http.get('/api/v1/checks/runs/42', () => HttpResponse.json({ data: run })),
    http.get('/api/v1/issues/1/verifications', () =>
      HttpResponse.json({
        data: passed
          ? [
              {
                issueId: 1,
                checkRunId: 42,
                triggerKind: 'REVERIFY',
                status: 'SUCCEEDED',
                outcome: 'PASSED',
                detail: { linkMappingId: 12 },
                checkedAt: run.finishedAt,
              },
            ]
          : [],
        meta: {
          page: 0,
          size: 20,
          totalElements: passed ? 1 : 0,
          hasNext: false,
        },
      }),
    ),
  )
  const user = userEvent.setup()
  open('?view=issues&issue=1')
  const dialog = within(await screen.findByRole('dialog'))
  await dialog.findByRole('option', { name: '설비' })
  await user.selectOptions(dialog.getByLabelText('도착 객체 유형'), '3')
  await user.type(dialog.getByLabelText('도착 객체 검색어'), '프레스 3호기')
  await user.click(dialog.getByRole('button', { name: '도착 객체 검색' }))
  await user.click(await dialog.findByRole('radio', { name: /프레스 3호기/ }))
  await user.type(dialog.getByLabelText('연결 사유'), '시험 연결')
  await user.click(dialog.getByRole('button', { name: '연결 내용 확인' }))
  await user.click(dialog.getByRole('button', { name: '수동 연결 저장' }))
  await dialog.findByText(
    '수동 연결을 저장했습니다. 조치완료로 바꾼 뒤 재검증하세요.',
  )
  expect(current.status).toBe('CHECKING')
  await user.selectOptions(
    dialog.getByLabelText('변경할 상태'),
    'FIXED_PENDING_REVERIFY',
  )
  await user.type(dialog.getByLabelText('변경 사유'), '연결 조치완료')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  await user.click(await dialog.findByRole('button', { name: '재검증 실행' }))
  await dialog.findByText('재검증 실행 #42 · 완료')
  expect(current.status).toBe('FIXED_PENDING_REVERIFY')
  await waitFor(() =>
    expect(dialog.getByLabelText('변경할 상태')).toBeEnabled(),
  )
  await user.selectOptions(dialog.getByLabelText('변경할 상태'), 'RESOLVED')
  await user.type(dialog.getByLabelText('변경 사유'), '재검증 통과 확인')
  await user.type(dialog.getByLabelText('통과한 재검증 실행 ID'), '42')
  await user.click(dialog.getByRole('button', { name: '상태 변경' }))
  await waitFor(() => expect(current.status).toBe('RESOLVED'))
  expect(operations).toEqual([
    'MANUAL_LINK',
    'FIXED_PENDING_REVERIFY',
    'REVERIFY_PASSED',
    'RESOLVED',
  ])
})
