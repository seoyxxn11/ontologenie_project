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

test('후보 카드 선택 후 규칙·근거·검사·결정을 함께 확인한다', async () => {
  const user = userEvent.setup()
  open()
  expect(
    screen.getByRole('link', { name: '수동 검사·실행 목록' }),
  ).toHaveAttribute('href', '/admin/checks')
  const list = within(
    await screen.findByRole('complementary', { name: '검토 목록' }),
  )
  expect(
    list.getByRole('button', { name: /작업지시 설비 코드 연결/ }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(list.getByText('직접 추가한 대체 설비 연결')).toBeInTheDocument()
  const detail = within(
    await screen.findByRole('region', { name: '후보 검토 상세' }),
  )
  await detail.findByText('신뢰도: 86.0%')
  expect(detail.getByText('검토 대기')).toHaveClass('bg-amber-50')
  expect(detail.getByRole('region', { name: '제안 근거' })).toBeVisible()
  expect(detail.getByRole('region', { name: '정합성 검사' })).toBeVisible()
  expect(
    detail.queryByRole('group', { name: '후보 상세 보기' }),
  ).not.toBeInTheDocument()
  expect(
    detail.getByText('작업지시의 설비 코드와 설비 원장의 코드가 대응합니다.'),
  ).toBeVisible()
  expect(detail.getByRole('button', { name: '승인' })).toBeDisabled()
  await user.click(
    list.getByRole('button', { name: /직접 추가한 대체 설비 연결/ }),
  )
  expect(
    await screen.findByRole('heading', { name: '직접 추가한 대체 설비 연결' }),
  ).toBeVisible()
  expect(screen.getByText('신뢰도: 제공되지 않음')).toBeVisible()
})

test('근거·검사를 확인하고 새로고침해도 작성 중인 검토 사유를 유지한다', async () => {
  const user = userEvent.setup()
  open('/admin/rules?candidate=303')
  const detail = within(
    await screen.findByRole('region', { name: '후보 검토 상세' }),
  )
  const reason = await detail.findByRole('textbox', {
    name: '검토 사유 (필수)',
  })
  await user.type(reason, '원천 근거를 확인했습니다.')
  expect(detail.getByRole('heading', { name: '연결·변환 규칙' })).toBeVisible()
  expect(detail.getByRole('region', { name: '제안 근거' })).toBeVisible()
  const check = detail.getByRole('region', { name: '정합성 검사' })
  expect(check).toBeVisible()
  await user.click(
    within(check).getByRole('button', { name: '검사 상태 새로고침' }),
  )
  expect(
    await detail.findByRole('button', { name: '검사 상태 새로고침' }),
  ).toBeEnabled()
  expect(reason).toHaveValue('원천 근거를 확인했습니다.')
})

test('상세 필터를 접었다 펼쳐도 적용 전 선택을 보존한다', async () => {
  const user = userEvent.setup()
  open()
  await screen.findByRole('complementary', { name: '검토 목록' })
  const summary = screen.getByText('상세 필터', { selector: 'summary' })
  await user.click(summary)
  await user.selectOptions(screen.getByLabelText('제안 출처'), 'MANUAL')
  await user.click(summary)
  await user.click(summary)
  expect(screen.getByLabelText('제안 출처')).toHaveValue('MANUAL')
  expect(screen.getByText('후보 3건')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
})

test('상세 필터와 브라우저 이동으로 조회 조건을 복원한다', async () => {
  const user = userEvent.setup()
  open('/admin/rules', true)
  await screen.findByRole('complementary')
  await user.click(screen.getByText('상세 필터', { selector: 'summary' }))
  await user.selectOptions(screen.getByLabelText('제안 출처'), 'MANUAL')
  await user.selectOptions(screen.getByLabelText('검토 상태'), 'HELD')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
  expect(
    within(screen.getByRole('complementary')).queryByText(
      '작업지시 설비 코드 연결',
    ),
  ).not.toBeInTheDocument()
  expect(screen.getByLabelText('제안 출처')).toHaveValue('MANUAL')
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(await screen.findByText('후보 3건')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '뒤로가기' }))
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
  expect(screen.getByLabelText('검토 상태')).toHaveValue('HELD')
  await user.click(screen.getByRole('button', { name: '앞으로가기' }))
  expect(await screen.findByText('후보 3건')).toBeInTheDocument()
})

test('상태 탭·유형·검색을 결합하고 빈 결과에서도 전체 건수를 유지한다', async () => {
  const object = {
    ...proposalFixtures[0],
    id: 900,
    itemKind: 'OBJECT_TYPE',
    payload: {
      name: '작업지시 객체 정의',
      objectMapping: { resourceKey: 'mes.work_order' },
      properties: [],
    },
  }
  server.use(
    http.get('/api/v1/proposals', () =>
      HttpResponse.json({
        data: [object, ...proposalFixtures],
        meta: { page: 0, size: 20, totalElements: 4, hasNext: false },
      }),
    ),
  )
  const user = userEvent.setup()
  open()
  expect(await screen.findByRole('button', { name: '전체 4' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  const types = within(screen.getByRole('group', { name: '후보 유형 필터' }))
  expect(types.getByRole('button', { name: '객체 속성 1' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await user.click(types.getByRole('button', { name: '매핑 3' }))
  await user.click(screen.getByRole('button', { name: '보류 1' }))
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
  await user.type(
    screen.getByRole('searchbox', { name: '후보 검색' }),
    '없는 후보',
  )
  expect(screen.getByText('후보 1건')).toBeInTheDocument()
  expect(screen.getByLabelText('적용된 후보 검색 조건')).not.toHaveTextContent(
    '없는 후보',
  )
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  expect(
    await screen.findByText(
      '선택한 상태·유형·검색어에 해당하는 후보가 없습니다.',
    ),
  ).toBeVisible()
  expect(screen.getByRole('button', { name: '전체 4' })).toBeInTheDocument()
})

test('카드에 보류 권고·재분석 비교를 보이고 실제 후보 종류로 필터한다', async () => {
  const base = proposalFixtures[0]
  server.use(
    http.get('/api/v1/proposals', () =>
      HttpResponse.json({
        data: [
          {
            ...base,
            id: 901,
            payload: { name: '보류 권고 규칙' },
            evidence: { holdRecommended: true, changeType: 'CHANGED' },
            reviewStatus: 'PENDING',
          },
          {
            ...base,
            id: 902,
            payload: { name: '이미 보류한 규칙' },
            evidence: { holdRecommended: true },
            reviewStatus: 'HELD',
          },
        ],
        meta: { page: 0, size: 20, totalElements: 2, hasNext: false },
      }),
    ),
  )
  open()
  const list = within(await screen.findByRole('complementary'))
  const flagged = within(list.getByRole('button', { name: /보류 권고 규칙/ }))
  expect(flagged.getByText('보류 권고')).toBeInTheDocument()
  expect(flagged.getByText('재분석 변경')).toBeInTheDocument()
  expect(
    within(list.getByRole('button', { name: /이미 보류한 규칙/ })).queryByText(
      '보류 권고',
    ),
  ).not.toBeInTheDocument()
  expect(
    within(screen.getByLabelText('제안 종류'))
      .getAllByRole('option', { hidden: true })
      .map((o) => o.textContent),
  ).toEqual(['전체', '객체 유형', '연결 규칙', '관계 유형', '문서 연결'])
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
    await screen.findByText(
      '선택한 상태·유형·검색어에 해당하는 후보가 없습니다.',
    ),
  ).toBeVisible()
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

test('서버에서는 100건씩 모든 쪽을 받아 집계·검색하고 화면은 20건씩 넘긴다', async () => {
  const items = Array.from({ length: 101 }, (_, index) => ({
    ...proposalFixtures[0],
    id: 1000 + index,
    payload: { name: '후보 ' + index },
  }))
  const requests: string[] = []
  server.use(
    http.get('/api/v1/proposals', ({ request }) => {
      const params = new URL(request.url).searchParams
      const page = Number(params.get('page') ?? 0)
      const size = Number(params.get('size'))
      requests.push(`${page}:${size}`)
      return HttpResponse.json({
        data: items.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalElements: items.length,
          hasNext: (page + 1) * size < items.length,
        },
      })
    }),
  )
  const user = userEvent.setup()
  open()
  await screen.findByText('후보 101건')
  expect(requests).toEqual(['0:100', '1:100'])
  const list = within(screen.getByRole('complementary'))
  expect(list.getByText('후보 0')).toBeInTheDocument()
  expect(list.queryByText('후보 20')).not.toBeInTheDocument()
  await user.click(list.getByRole('button', { name: '다음 페이지' }))
  expect(list.getByText('후보 20')).toBeInTheDocument()
  await user.type(
    screen.getByRole('searchbox', { name: '후보 검색' }),
    '후보 100',
  )
  expect(screen.getByText('후보 101건')).toBeInTheDocument()
  expect(list.getByText('2 / 6쪽 · 101건')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
  expect(list.getByText('1 / 1쪽 · 1건')).toBeInTheDocument()
  expect(screen.getByLabelText('적용된 후보 검색 조건')).toHaveTextContent(
    '검색어: 후보 100',
  )
  expect(list.getByText('후보 100')).toBeInTheDocument()
})

function serveProposals(items: typeof proposalFixtures) {
  server.use(
    http.get('/api/v1/proposals', () =>
      HttpResponse.json({
        data: items,
        meta: {
          page: 0,
          size: 100,
          totalElements: items.length,
          hasNext: false,
        },
      }),
    ),
  )
}

const approvedObject = {
  ...proposalFixtures[0],
  id: 910,
  itemKind: 'OBJECT_TYPE',
  reviewStatus: 'APPROVED',
  payload: {
    name: '작업지시 객체 정의',
    objectMapping: { sourceId: 'MES', resourceKey: 'mes.work_order' },
    properties: [],
  },
  evidence: {
    reason: '작업지시 표를 객체로 둡니다.',
    fingerprint: 'fp-9f3a',
    candidateId: 'cand-77',
  },
}

test('검토 대기에 객체 후보가 없으면 매핑 유형을 먼저 연다', async () => {
  serveProposals([approvedObject, ...proposalFixtures])
  open('/admin/rules?reviewStatus=PENDING')
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
  const types = within(screen.getByRole('group', { name: '후보 유형 필터' }))
  expect(types.getByRole('button', { name: '매핑 1' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(types.getByRole('button', { name: '객체 속성 0' })).toHaveAttribute(
    'aria-pressed',
    'false',
  )
  expect(
    within(screen.getByRole('complementary')).getByText(
      '작업지시 설비 코드 연결',
    ),
  ).toBeInTheDocument()
})

test('유형 탭 개수는 출처·반영 상태·검색을 적용한 목록 개수와 같다', async () => {
  serveProposals([approvedObject, ...proposalFixtures])
  const user = userEvent.setup()
  open('/admin/rules?origin=AI&group=mappings')
  expect(await screen.findByText('후보 2건')).toBeInTheDocument()
  const types = within(screen.getByRole('group', { name: '후보 유형 필터' }))
  // 직접 추가(MANUAL) 후보는 출처 조건에서 빠진다
  expect(types.getByRole('button', { name: '매핑 2' })).toBeInTheDocument()
  expect(types.getByRole('button', { name: '객체 속성 1' })).toBeInTheDocument()
  await user.type(
    screen.getByRole('searchbox', { name: '후보 검색' }),
    '인수인계',
  )
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  expect(
    await types.findByRole('button', { name: '매핑 1' }),
  ).toBeInTheDocument()
  expect(types.getByRole('button', { name: '객체 속성 0' })).toBeInTheDocument()
  expect(screen.getByText('후보 1건')).toBeInTheDocument()
})

test('검색은 보이는 칸만 찾고 내부 대조용 값은 찾지 않는다', async () => {
  serveProposals([approvedObject, ...proposalFixtures])
  const user = userEvent.setup()
  open('/admin/rules?group=objects')
  await screen.findByText('후보 1건')
  const search = () => screen.getByRole('searchbox', { name: '후보 검색' })
  await user.type(search(), 'fp-9f3a')
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  expect(
    await screen.findByText(
      '선택한 상태·유형·검색어에 해당하는 후보가 없습니다.',
    ),
  ).toBeVisible()
  await user.clear(search())
  await user.type(search(), 'cand-77')
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  expect(screen.getByText('후보 0건')).toBeInTheDocument()
  await user.clear(search())
  await user.type(search(), 'mes.work_order')
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
  await user.clear(search())
  await user.type(search(), '객체로 둡니다')
  await user.click(screen.getByRole('button', { name: '검색 적용' }))
  expect(await screen.findByText('후보 1건')).toBeInTheDocument()
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

test('목록 안 수정 링크에 검색·필터·선택 후보·페이지·분석 범위를 보존한다', async () => {
  const search = '?q=설비&origin=AI&candidate=303&page=1&analysis=51'
  open(`/admin/rules${search}`)
  expect(
    await screen.findByRole('link', { name: '규칙 후보 수정' }),
  ).toHaveAttribute(
    'href',
    `/admin/rules/303/edit?${new URLSearchParams(search)}`,
  )
  expect(screen.getByRole('link', { name: '직접 관계 제안' })).toHaveAttribute(
    'href',
    `/admin/rules/new?${new URLSearchParams(search)}`,
  )
})
