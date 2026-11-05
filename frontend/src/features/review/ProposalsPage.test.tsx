import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router'
import { expect, test, vi } from 'vitest'
import App from '../../App'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import ProposalDetail from './ProposalDetail'
import ProposalsPage from './ProposalsPage'
import { confidence } from './labels'

function HistoryControls() {
  const navigate = useNavigate()
  return (
    <nav aria-label="검사용 브라우저 이동">
      <button type="button" onClick={() => navigate(-1)}>
        뒤로가기
      </button>
      <button type="button" onClick={() => navigate(1)}>
        앞으로가기
      </button>
    </nav>
  )
}

function open(path = '/admin/rules', withHistoryControls = false) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        {withHistoryControls && <HistoryControls />}
        <Routes>
          <Route path="/admin/rules" element={<ProposalsPage />} />
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('AI·직접 추가 제안을 함께 표시하고 상세의 근거·신뢰도를 확인한다', async () => {
  const user = userEvent.setup()
  open()
  expect(
    await screen.findByRole('link', { name: '작업지시 설비 코드 연결' }),
  ).toBeInTheDocument()
  expect(screen.getByText('직접 추가한 대체 설비 연결')).toBeInTheDocument()
  await user.click(
    screen.getByRole('link', { name: '작업지시 설비 코드 연결' }),
  )
  expect(
    await screen.findByRole('heading', { name: '제안 근거' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText('작업지시의 설비 코드와 설비 원장의 코드가 대응합니다.'),
  ).toBeInTheDocument()
  expect(screen.getByText('86.0%')).toBeInTheDocument()
  expect(screen.getByText(/AI가 제안한 신뢰도이며/)).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '승인' }),
  ).toBeDisabled()
})

test('서버 필터를 전달하고 상세에서 돌아와도 선택한 조회 조건을 유지한다', async () => {
  const user = userEvent.setup()
  open()
  await screen.findByRole('table')
  await user.selectOptions(screen.getByLabelText('제안 출처'), 'MANUAL')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('1건')
  expect(screen.queryByText('작업지시 설비 코드 연결')).not.toBeInTheDocument()
  await user.click(
    screen.getByRole('link', { name: '직접 추가한 대체 설비 연결' }),
  )
  await screen.findByRole('heading', { name: '제안 근거' })
  expect(screen.getByText('제공되지 않음')).toBeInTheDocument()
  await user.click(screen.getByRole('link', { name: '제안 목록으로 돌아가기' }))
  expect(screen.getByLabelText('제안 출처')).toHaveValue('MANUAL')
  await user.click(screen.getByRole('button', { name: '초기화' }))
  await screen.findByText('3건')
})

test('뒤로가기·앞으로가기 시 모든 필터와 조회 결과를 해당 주소로 복원한다', async () => {
  const user = userEvent.setup()
  open('/admin/rules', true)
  await screen.findByRole('table')
  await user.selectOptions(screen.getByLabelText('제안 출처'), 'AI')
  await user.selectOptions(screen.getByLabelText('제안 종류'), 'LINK_RULE')
  await user.selectOptions(screen.getByLabelText('검토 상태'), 'PENDING')
  await user.selectOptions(screen.getByLabelText('반영 상태'), 'NOT_APPLIED')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('1건')

  await user.selectOptions(screen.getByLabelText('제안 출처'), 'MANUAL')
  await user.selectOptions(screen.getByLabelText('검토 상태'), 'HELD')
  // 검색 전에는 선택만 바뀌며 기존 조회 결과를 유지한다.
  expect(
    screen.getByRole('link', { name: '작업지시 설비 코드 연결' }),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByRole('link', { name: '직접 추가한 대체 설비 연결' })

  await user.click(screen.getByRole('button', { name: '뒤로가기' }))
  await screen.findByRole('link', { name: '작업지시 설비 코드 연결' })
  expect(screen.getByLabelText('제안 출처')).toHaveValue('AI')
  expect(screen.getByLabelText('제안 종류')).toHaveValue('LINK_RULE')
  expect(screen.getByLabelText('검토 상태')).toHaveValue('PENDING')
  expect(screen.getByLabelText('반영 상태')).toHaveValue('NOT_APPLIED')
  expect(
    screen.queryByRole('link', { name: '직접 추가한 대체 설비 연결' }),
  ).not.toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '앞으로가기' }))
  await screen.findByRole('link', { name: '직접 추가한 대체 설비 연결' })
  expect(screen.getByLabelText('제안 출처')).toHaveValue('MANUAL')
  expect(screen.getByLabelText('제안 종류')).toHaveValue('LINK_RULE')
  expect(screen.getByLabelText('검토 상태')).toHaveValue('HELD')
  expect(screen.getByLabelText('반영 상태')).toHaveValue('NOT_APPLIED')
  expect(
    screen.queryByRole('link', { name: '작업지시 설비 코드 연결' }),
  ).not.toBeInTheDocument()
})

test('빈 결과와 로딩을 구별한다', async () => {
  server.use(
    http.get('/api/v1/proposals', async () => {
      await delay(100)
      return HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      })
    }),
  )
  open()
  expect(screen.getByText('제안 목록을 불러오는 중…')).toBeInTheDocument()
  expect(
    await screen.findByText('조건에 맞는 제안이 없습니다.'),
  ).toBeInTheDocument()
})

test.each([403, 404, 409, 500])(
  '상세 HTTP %s는 오류로 표시하며 이전 제안으로 위장하지 않는다',
  async (status) => {
    server.use(
      http.get('/api/v1/proposals/:id', () =>
        HttpResponse.json(
          {
            error: {
              code: 'TEST_ERROR',
              message: '검사용 조회 거부',
              details: {},
              requestId: 'review-test',
            },
          },
          { status },
        ),
      ),
    )
    open('/admin/rules/303')
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '검사용 조회 거부',
    )
    expect(
      screen.queryByRole('heading', { name: '제안 근거' }),
    ).not.toBeInTheDocument()
  },
)

test('잘못된 상세 ID는 요청하지 않는다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: proposalFixtures[0] }))
  server.use(http.get('/api/v1/proposals/:id', request))
  open('/admin/rules/not-an-id')
  expect(screen.getByText('올바른 제안 주소가 아닙니다.')).toBeInTheDocument()
  expect(request).not.toHaveBeenCalled()
})

test('다음 페이지는 서버 페이지 번호를 사용한다', async () => {
  const pages: string[] = []
  server.use(
    http.get('/api/v1/proposals', ({ request }) => {
      const page = new URL(request.url).searchParams.get('page') ?? '0'
      pages.push(page)
      return HttpResponse.json({
        data: [{ ...proposalFixtures[0], payload: { name: `페이지 ${page}` } }],
        meta: {
          page: Number(page),
          size: 20,
          totalElements: 21,
          hasNext: page === '0',
        },
      })
    }),
  )
  const user = userEvent.setup()
  open()
  await screen.findByText('페이지 0')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('페이지 1')
  expect(pages).toEqual(['0', '1'])
  expect(
    within(screen.getByRole('navigation', { name: '페이지 이동' })).getByRole(
      'button',
      { name: '다음 페이지' },
    ),
  ).toBeDisabled()
})

test('조회 사용자가 상세 주소로 진입해도 제안 API를 호출하지 않는다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: proposalFixtures[0] }))
  server.use(http.get('/api/v1/proposals/:id', request))
  setMockAccount({ ...demoAccount, roleCode: 'VIEWER' })
  window.history.pushState({}, '', '/admin/rules/303')
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <App />
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })
  expect(request).not.toHaveBeenCalled()
})

test('미제공·범위 밖 신뢰도를 0%로 표시하지 않는다', () => {
  for (const value of [null, -1, 2, NaN])
    expect(confidence(value)).toBe('제공되지 않음')
  expect(confidence(0)).toBe('0.0%')
})
