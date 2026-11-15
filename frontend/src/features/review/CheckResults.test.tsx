import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import ProposalDetail from './ProposalDetail'
import CheckResults from './CheckResults'
import type { CheckResult } from './checkApi'

const proposal = proposalFixtures[0]
const result: CheckResult = {
  id: 601,
  checkRunId: 402,
  proposalItemId: 303,
  proposalItemRevision: 2,
  linkMappingId: 2,
  previousResultId: null,
  totalCount: 4,
  matchedCount: 1,
  unmatchedCount: 1,
  duplicateCount: 1,
  violationCount: 1,
  matchRate: 0.25,
  sampleRows: [
    {
      sourceKey: { id: 'WO-102' },
      rawValue: 'W23',
      transformedValue: 'E023',
      classification: 'UNMATCHED',
      reason: '대상 객체 없음',
    },
  ],
}
function open(id = '303') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[`/admin/rules/${id}`]}>
        <Routes>
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return userEvent.setup()
}
function results(rows = [result], totalElements = rows.length) {
  server.use(
    http.get('/api/v1/checks/runs/:id/results', ({ request, params }) => {
      const search = new URL(request.url).searchParams
      expect(params.id).toBe('402')
      expect(search.get('proposalItemId')).toBe('303')
      expect(search.get('size')).toBe('20')
      return HttpResponse.json({
        data: rows,
        meta: {
          page: Number(search.get('page')),
          size: 20,
          totalElements,
          hasNext: totalElements > 20,
        },
      })
    }),
  )
}

test('같은 실행·제안·개정의 집계와 판정 근거를 AI 신뢰도와 구분한다', async () => {
  results()
  open()
  const panel = await screen.findByRole('region', { name: '정합성 검사' })
  expect(
    await within(panel).findByText('시험 매칭률: 25.0%'),
  ).toBeInTheDocument()
  for (const label of ['전체', '정상', '미연결', '중복', '조건 위반'])
    expect(
      within(panel).getByText(label, { selector: 'dt' }),
    ).toBeInTheDocument()
  const table = within(panel).getByRole('table', {
    name: '검사 결과 601 판정 근거',
  })
  expect(within(table).getByText('W23')).toBeInTheDocument()
  expect(within(table).getByText('E023')).toBeInTheDocument()
  expect(within(table).getByText('대상 객체 없음')).toBeInTheDocument()
  expect(within(panel).getByText(/전체 행 목록이 아닙니다/)).toBeInTheDocument()
  expect(screen.getByText('86.0%')).toBeInTheDocument()
})

test.each(['QUEUED', 'RUNNING', 'FAILED'])(
  '%s 상태는 부분 결과를 조회하지 않는다',
  async (status) => {
    const requested = vi.fn(() => HttpResponse.json({ data: [] }))
    server.use(
      http.get('/api/v1/proposals/:id', () =>
        HttpResponse.json({
          data: {
            ...proposal,
            latestCheck: { ...proposal.latestCheck, status },
          },
        }),
      ),
      http.get('/api/v1/checks/runs/:id/results', requested),
    )
    open()
    const panel = await screen.findByRole('region', { name: '정합성 검사' })
    expect(
      within(panel).getByText(
        status === 'FAILED' ? /검사 실행에 실패/ : /3초마다 상태/,
      ),
    ).toBeInTheDocument()
    expect(requested).not.toHaveBeenCalled()
  },
)

test('미지원 후보는 실행 실패 대신 자동 시험 미지원으로 표시한다', async () => {
  const requested = vi.fn(() => HttpResponse.json({ data: [] }))
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({
        data: {
          ...proposal,
          latestCheck: {
            ...proposal.latestCheck,
            status: 'FAILED',
            unsupported: true,
          },
        },
      }),
    ),
    http.get('/api/v1/checks/runs/:id/results', requested),
  )
  open()
  const panel = await screen.findByRole('region', { name: '정합성 검사' })
  expect(
    within(panel).getByText(/이 후보는 아직 자동 시험을 지원하지 않음/),
  ).toBeInTheDocument()
  expect(within(panel).queryByText(/검사 실행에 실패/)).not.toBeInTheDocument()
  expect(requested).not.toHaveBeenCalled()
})

test('검사 대상이 아닌 종류는 검사 결과 없이 승인할 수 있다고 안내한다', async () => {
  const requested = vi.fn(() => HttpResponse.json({ data: [] }))
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({
        data: {
          ...proposal,
          itemKind: 'OBJECT_TYPE',
          checkRequired: false,
          latestCheck: null,
        },
      }),
    ),
    http.get('/api/v1/checks/runs/:id/results', requested),
  )
  open()
  const panel = await screen.findByRole('region', { name: '정합성 검사' })
  expect(within(panel).getByRole('status')).toHaveTextContent(
    /검사 대상 아님: 이 종류는 원천 행으로 시험하지 않으며/,
  )
  expect(
    within(panel).queryByText('아직 검사 결과가 없습니다.'),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText(/승인에 검사 결과가 필요하지 않습니다\. AI 신뢰도만으로/),
  ).toBeInTheDocument()
  expect(requested).not.toHaveBeenCalled()
})

test('후보 시험에서 반영 규칙 수동 검사로 이동하며 후보를 수동 검사로 보내지 않는다', async () => {
  const write = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.post('/api/v1/*', write))
  open()
  await screen.findByRole('region', { name: '정합성 검사' })
  expect(
    screen.getByRole('link', { name: '수동 검사·실행 목록' }),
  ).toHaveAttribute('href', '/admin/checks')
  expect(
    screen.getByText(/승인 전 후보 시험은 제안을 저장·수정할 때/),
  ).toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
})

test.each([
  { ...result, proposalItemRevision: 1 },
  { ...result, proposalItemId: 302 },
  { ...result, checkRunId: 401 },
])('다른 실행·제안·개정의 결과를 표시하지 않는다: %j', async (row) => {
  results([row])
  open()
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '검사 결과의 제안·개정이 일치하지 않습니다.',
  )
  expect(screen.queryByText('대상 객체 없음')).not.toBeInTheDocument()
})

test('빈 결과와 전체 0건·표본 없음은 성공 건수로 꾸미지 않는다', async () => {
  results([
    {
      ...result,
      totalCount: 0,
      matchedCount: 0,
      unmatchedCount: 0,
      duplicateCount: 0,
      violationCount: 0,
      matchRate: null,
      sampleRows: [],
    },
  ])
  open()
  expect(
    await screen.findByText('시험 매칭률: 계산할 수 없음'),
  ).toBeInTheDocument()
  expect(
    screen.getByText('제공된 판정 근거 표본이 없습니다.'),
  ).toBeInTheDocument()
})

test('결과가 없으면 별도의 빈 결과 안내를 표시한다', async () => {
  results([])
  open()
  expect(
    await screen.findByText('현재 제안 개정에 해당하는 검사 결과가 없습니다.'),
  ).toBeInTheDocument()
})

test('서버 페이지를 이동하며 이전 페이지 결과를 남기지 않는다', async () => {
  const pages: string[] = []
  server.use(
    http.get('/api/v1/checks/runs/:id/results', ({ request }) => {
      const page = new URL(request.url).searchParams.get('page')!
      pages.push(page)
      return HttpResponse.json({
        data: [{ ...result, id: page === '0' ? 601 : 602 }],
        meta: {
          page: Number(page),
          size: 20,
          totalElements: 21,
          hasNext: page === '0',
        },
      })
    }),
  )
  const user = open()
  await screen.findByText('결과 #601 · 연결 규칙 #2')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  expect(
    await screen.findByText('결과 #602 · 연결 규칙 #2'),
  ).toBeInTheDocument()
  expect(screen.queryByText('결과 #601 · 연결 규칙 #2')).not.toBeInTheDocument()
  expect(pages).toEqual(['0', '1'])
})

test('결과 조회 실패는 자동 재시도하지 않고 명시적인 조회만 다시 보낸다', async () => {
  const request = vi.fn(() => HttpResponse.error())
  server.use(http.get('/api/v1/checks/runs/:id/results', request))
  const user = open()
  await screen.findByRole('alert')
  expect(request).toHaveBeenCalledOnce()
  results()
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('시험 매칭률: 25.0%')).toBeInTheDocument()
})

test('진행 상태를 자동 갱신하고 완료된 뒤 결과를 읽는다', async () => {
  let calls = 0
  server.use(
    http.get('/api/v1/proposals/:id', () => {
      calls++
      return HttpResponse.json({
        data: {
          ...proposal,
          latestCheck: {
            ...proposal.latestCheck,
            status: calls === 1 ? 'RUNNING' : 'SUCCEEDED',
          },
        },
      })
    }),
  )
  results()
  open()
  await screen.findByText(/3초마다 상태/)
  await waitFor(
    () => expect(screen.getByText('시험 매칭률: 25.0%')).toBeInTheDocument(),
    { timeout: 5000 },
  )
  expect(calls).toBe(2)
}, 10000)

test('상태 재조회가 실패하면 이전 결과 대신 오류를 표시한다', async () => {
  results()
  const user = open()
  await screen.findByText('시험 매칭률: 25.0%')
  server.use(http.get('/api/v1/proposals/:id', () => HttpResponse.error()))
  await user.click(screen.getByRole('button', { name: '검사 상태 새로고침' }))
  await screen.findByRole('alert')
  expect(screen.queryByText('시험 매칭률: 25.0%')).not.toBeInTheDocument()
})

test('검토 집중형은 검사 집계를 기본 노출하고 표본만 펼친다', async () => {
  results()
  const user = userEvent.setup()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <CheckResults
          proposal={proposal}
          refreshing={false}
          onRefresh={() => {}}
          compact
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(await screen.findByText('시험 매칭률: 25.0%')).toBeVisible()
  expect(screen.getByText('조건 위반', { selector: 'dt' })).toBeVisible()
  expect(
    screen.getByRole('table', { name: '검사 결과 601 판정 근거' }),
  ).not.toBeVisible()
  await user.click(
    screen.getByText('검사 상세·표본 보기', { selector: 'summary' }),
  )
  expect(
    screen.getByRole('table', { name: '검사 결과 601 판정 근거' }),
  ).toBeVisible()
  expect(screen.getByText('대상 객체 없음')).toBeVisible()
})

test('검토 집중형에서 검사 결과 조회 실패는 접힌 표본 밖에 노출한다', async () => {
  server.use(
    http.get('/api/v1/checks/runs/:id/results', () =>
      HttpResponse.json(
        {
          error: {
            code: 'RESULT_ERROR',
            message: '검사 표본 조회 실패',
            details: {},
            requestId: 'compact-check',
          },
        },
        { status: 500 },
      ),
    ),
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter>
        <CheckResults
          proposal={proposal}
          refreshing={false}
          onRefresh={() => {}}
          compact
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '검사 표본 조회 실패',
  )
  expect(screen.getByRole('button', { name: '다시 불러오기' })).toBeVisible()
})
