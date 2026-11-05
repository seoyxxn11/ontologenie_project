import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import ProposalDetail from './ProposalDetail'
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

test('수동 실행 API 연결 전에는 재검사를 비활성화하고 쓰기 요청을 보내지 않는다', async () => {
  const write = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.post('/api/v1/*', write))
  open()
  await screen.findByRole('region', { name: '정합성 검사' })
  expect(screen.getByRole('button', { name: '함께 재검사' })).toBeDisabled()
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
