import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test, vi } from 'vitest'
import App from '../../App'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import { server } from '../../mocks/node'
import RuleEditorPage from './RuleEditorPage'

function open(
  path = '/admin/rules/new',
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/rules/new" element={<RuleEditorPage />} />
          <Route path="/admin/rules/:id/edit" element={<RuleEditorPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('이전 버전이 캐시에 있어도 최신 제안·복수 후보 조회가 끝난 뒤 편집한다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  client.setQueryData(['proposals', 'editor', 303], {
    proposal: proposalFixtures[0],
    options: [],
  })
  let releaseDetail!: () => void
  let releaseOptions!: () => void
  const detailGate = new Promise<void>((resolve) => {
    releaseDetail = resolve
  })
  const optionsGate = new Promise<void>((resolve) => {
    releaseOptions = resolve
  })
  const latest = {
    ...proposalFixtures[0],
    revision: 3,
    payload: { ...proposalFixtures[0].payload, name: '최신 버전 규칙' },
  }
  const detailRequest = vi.fn(async () => {
    await detailGate
    return HttpResponse.json({ data: latest })
  })
  const optionsRequest = vi.fn(async ({ request }: { request: Request }) => {
    expect(new URL(request.url).searchParams.get('revision')).toBe('3')
    await optionsGate
    return HttpResponse.json({ data: [] })
  })
  server.use(
    http.get('/api/v1/proposals/:id', detailRequest),
    http.get('/api/v1/proposals/:id/options', optionsRequest),
  )
  open('/admin/rules/303/edit', client)
  try {
    await waitFor(() => expect(detailRequest).toHaveBeenCalledTimes(1))
    expect(screen.getByText('규칙 후보를 불러오는 중…')).toBeInTheDocument()
    expect(screen.queryByLabelText('규칙명')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '후보 저장' }),
    ).not.toBeInTheDocument()
    releaseDetail()
    await waitFor(() => expect(optionsRequest).toHaveBeenCalledTimes(1))
    expect(screen.queryByLabelText('규칙명')).not.toBeInTheDocument()
    releaseOptions()
    expect(await screen.findByLabelText('규칙명')).toHaveValue('최신 버전 규칙')
    expect(screen.getByText(/수정 기준 개정 3/)).toBeInTheDocument()
    const user = userEvent.setup()
    await user.clear(screen.getByLabelText('규칙명'))
    await user.type(screen.getByLabelText('규칙명'), '조회 완료 후 입력')
    expect(screen.getByLabelText('규칙명')).toHaveValue('조회 완료 후 입력')
  } finally {
    releaseDetail()
    releaseOptions()
  }
})

test('최신 조회가 실패하면 캐시의 이전 버전으로 편집을 허용하지 않는다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  client.setQueryData(['proposals', 'editor', 303], {
    proposal: proposalFixtures[0],
    options: [],
  })
  server.use(http.get('/api/v1/proposals/:id', () => HttpResponse.error()))
  open('/admin/rules/303/edit', client)
  await screen.findByRole('alert')
  expect(screen.queryByLabelText('규칙명')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '후보 저장' }),
  ).not.toBeInTheDocument()
})

test('필수 조건이 없으면 요청을 보내지 않는다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.post('/api/v1/proposals/manual', request))
  open()
  await userEvent.click(screen.getByRole('button', { name: '후보 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('관계 유형 ID')
  expect(request).not.toHaveBeenCalled()
})

test('후보를 생성하고 새 개정의 재검사 요청을 안내한다', async () => {
  const user = userEvent.setup()
  open()
  await user.type(screen.getByLabelText('규칙명'), '새 연결 규칙')
  await user.type(screen.getByLabelText('관계 유형 ID'), '2')
  await user.type(
    screen.getByLabelText('출발 속성 코드'),
    'WORK_ORDER.equipment_code',
  )
  await user.type(screen.getByLabelText('도착 속성 코드'), 'EQUIPMENT.code')
  await user.click(screen.getByRole('button', { name: '출발 값 변환 추가' }))
  await user.click(screen.getByRole('button', { name: '후보 저장' }))
  expect(
    await screen.findByRole('heading', { name: '규칙 후보를 저장했습니다.' }),
  ).toBeInTheDocument()
  expect(screen.getByText(/재검사가 필요합니다/)).toBeInTheDocument()
  expect(
    screen.getByRole('link', { name: '저장한 제안 보기' }),
  ).toHaveAttribute('href', '/admin/rules/304')
})

test('기준 개정과 복수 후보·기존 근거를 보존해 수정하고 중복 제출을 막는다', async () => {
  const option = {
    id: 51,
    optionNo: 1,
    revision: 2,
    payload: proposalFixtures[0].payload,
    evidence: { reason: '대안 근거' },
    aiConfidence: 0.7,
  }
  const request = vi.fn(async ({ request }: { request: Request }) => {
    const body = await request.json()
    await delay(120)
    expect(body).toMatchObject({
      baseRevision: 2,
      evidence: proposalFixtures[0].evidence,
      options: [{ payload: option.payload, evidence: option.evidence }],
    })
    return HttpResponse.json(
      { data: { ...proposalFixtures[0], revision: 3, checkRunId: 1111 } },
      { status: 201 },
    )
  })
  server.use(
    http.get('/api/v1/proposals/:id/options', ({ request }) => {
      expect(new URL(request.url).searchParams.get('revision')).toBe('2')
      return HttpResponse.json({ data: [option] })
    }),
    http.post('/api/v1/proposals/:id/revisions', request),
  )
  open('/admin/rules/303/edit')
  await screen.findByLabelText('규칙명')
  const save = screen.getByRole('button', { name: '후보 저장' })
  fireEvent.submit(save.closest('form')!)
  fireEvent.submit(save.closest('form')!)
  await screen.findByRole('heading', { name: '규칙 후보를 저장했습니다.' })
  expect(request).toHaveBeenCalledTimes(1)
  expect(screen.getByText('제안 #303 · 개정 3')).toBeInTheDocument()
})

test.each([400, 403, 409, 500])(
  '저장 HTTP %s에서 입력과 오류 안내를 유지한다',
  async (status) => {
    server.use(
      http.post('/api/v1/proposals/:id/revisions', () =>
        HttpResponse.json(
          {
            error: {
              code: status === 409 ? 'REVISION_CONFLICT' : 'TEST_ERROR',
              message: '검사용 저장 실패',
              details: {},
              requestId: 'rule-editor',
            },
          },
          { status },
        ),
      ),
    )
    open('/admin/rules/303/edit')
    const input = await screen.findByLabelText('규칙명')
    await userEvent.click(screen.getByRole('button', { name: '후보 저장' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '검사용 저장 실패',
    )
    expect(input).toHaveValue('작업지시 설비 코드 연결')
    if ([403, 409].includes(status))
      expect(screen.getByRole('button', { name: '후보 저장' })).toBeDisabled()
  },
)

test('복수 후보 조회가 실패하면 빈 후보로 덮어쓰는 편집을 허용하지 않는다', async () => {
  server.use(
    http.get('/api/v1/proposals/:id/options', () => HttpResponse.error()),
  )
  open('/admin/rules/303/edit')
  await screen.findByRole('alert')
  expect(
    screen.queryByRole('button', { name: '후보 저장' }),
  ).not.toBeInTheDocument()
})

test('승인된 연결 규칙은 편집할 수 없다', async () => {
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({
        data: { ...proposalFixtures[0], reviewStatus: 'APPROVED' },
      }),
    ),
  )
  open('/admin/rules/303/edit')
  await screen.findByText(
    '검토 대기·보류 상태의 연결 규칙 후보만 수정할 수 있습니다.',
  )
  expect(
    screen.queryByRole('button', { name: '후보 저장' }),
  ).not.toBeInTheDocument()
})

test('관리자 아닌 사용자는 생성 주소에서도 저장 화면·API를 열지 못한다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: [] }))
  server.use(http.post('/api/v1/proposals/manual', request))
  setMockAccount({ ...demoAccount, roleCode: 'VIEWER' })
  window.history.pushState({}, '', '/admin/rules/new')
  render(
    <QueryClientProvider client={new QueryClient()}>
      <App />
    </QueryClientProvider>,
  )
  await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })
  await waitFor(() => expect(request).not.toHaveBeenCalled())
})
