import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'
import App from '../../App'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import { server } from '../../mocks/node'
import RuleEditorPage from './RuleEditorPage'

beforeEach(() => setMockAccount({ ...demoAccount, roleCode: 'ADMIN' }))

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
  await user.selectOptions(await screen.findByLabelText('관계 유형'), '2')
  await waitFor(() => expect(screen.getByLabelText('출발 속성')).toBeEnabled())
  await user.selectOptions(
    screen.getByLabelText('출발 속성'),
    'WORK_ORDER.equipment_code',
  )
  await user.selectOptions(screen.getByLabelText('도착 속성'), 'EQUIPMENT.code')
  await user.click(screen.getByRole('button', { name: '연결 조건' }))
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
  await waitFor(() => expect(save).toBeEnabled())
  fireEvent.submit(save.closest('form')!)
  fireEvent.submit(save.closest('form')!)
  await screen.findByRole('heading', { name: '규칙 후보를 저장했습니다.' })
  expect(request).toHaveBeenCalledTimes(1)
  expect(screen.getByText('제안 #303 · 개정 3')).toBeInTheDocument()
})

test.each([400, 403, 409, 422, 500])(
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
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '후보 저장' })).toBeEnabled(),
    )
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

test('검색으로 관계·속성을 선택하고 관계 변경 후 부적합한 속성을 다시 선택한다', async () => {
  const user = userEvent.setup()
  const request = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toMatchObject({
      payload: {
        linkTypeId: 3,
        conditions: [
          {
            sourceProperty: 'EQUIPMENT.code',
            targetProperty: 'WORK_ORDER.equipment_code',
            sourceTransforms: [{ op: 'UPPER', arg: null }],
          },
        ],
      },
    })
    return HttpResponse.json({
      data: { id: 303, revision: 3, checkRunId: 1200 },
    })
  })
  server.use(http.post('/api/v1/proposals/:id/revisions', request))
  open('/admin/rules/303/edit')
  await screen.findByLabelText('규칙명')
  await waitFor(() => expect(screen.getByLabelText('출발 속성')).toBeEnabled())
  await user.type(screen.getByLabelText('관계 유형 검색'), 'EQUIPMENT_ORDER')
  await user.selectOptions(screen.getByLabelText('관계 유형'), '3')
  await waitFor(() => expect(screen.getByLabelText('출발 속성')).toBeEnabled())
  expect(screen.getByLabelText('출발 속성')).toHaveValue('')
  expect(screen.getByLabelText('도착 속성')).toHaveValue('')
  await user.click(screen.getByRole('button', { name: '후보 저장' }))
  expect(request).not.toHaveBeenCalled()
  await user.type(screen.getByLabelText('출발 속성 검색'), '설비 코드')
  await user.selectOptions(screen.getByLabelText('출발 속성'), 'EQUIPMENT.code')
  await user.selectOptions(
    screen.getByLabelText('도착 속성'),
    'WORK_ORDER.equipment_code',
  )
  await user.click(screen.getByRole('button', { name: '후보 저장' }))
  await screen.findByRole('heading', { name: '규칙 후보를 저장했습니다.' })
  expect(request).toHaveBeenCalledTimes(1)
})

test('편집 URL의 목록 조건을 복귀 링크와 저장한 상세에 유지한다', async () => {
  const search =
    '?q=설비&group=AI&origin=AI&page=2&candidate=303&analysis=51&itemKind=LINK_RULE&reviewStatus=PENDING'
  open(`/admin/rules/303/edit${search}`)
  expect(
    screen.getByRole('link', { name: '연결 규칙 목록으로 돌아가기' }),
  ).toHaveAttribute('href', `/admin/rules${search}`)
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '후보 저장' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: '후보 저장' }))
  expect(
    await screen.findByRole('link', { name: '저장한 제안 보기' }),
  ).toHaveAttribute('href', `/admin/rules/303${search}`)
})

test('동일 분석의 미확정 관계·속성 코드와 묶음 정의·복수 후보·변환을 보존한다', async () => {
  const payload = {
    ...proposalFixtures[0].payload,
    linkTypeId: undefined,
    linkTypeCode: 'PENDING_RELATION',
    linkType: {
      code: 'PENDING_RELATION',
      sourceTypeCode: 'PENDING_SOURCE',
      targetTypeCode: 'PENDING_TARGET',
    },
    conditions: [
      {
        sourceProperty: 'PENDING_SOURCE.name',
        targetProperty: 'PENDING_TARGET.name',
        operator: 'EQ',
        sourceTransforms: [
          { op: 'TRIM', arg: null },
          { op: 'UPPER', arg: null },
        ],
        targetTransforms: [],
      },
    ],
  }
  const request = vi.fn(async ({ request }: { request: Request }) => {
    const body = await request.json()
    expect(body).toMatchObject({
      baseRevision: 2,
      payload: JSON.parse(JSON.stringify(payload)),
      options: expect.arrayContaining([
        expect.objectContaining({ payload: expect.any(Object) }),
      ]),
    })
    expect(body.payload).not.toHaveProperty('linkTypeId')
    return HttpResponse.json({
      data: { id: 303, revision: 3, checkRunId: 1201 },
    })
  })
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({ data: { ...proposalFixtures[0], payload } }),
    ),
    http.post('/api/v1/proposals/:id/revisions', request),
    http.get('/api/v1/ontology/link-types', () => HttpResponse.error()),
  )
  open('/admin/rules/303/edit')
  expect(await screen.findByLabelText('관계 유형 코드')).toHaveValue(
    'PENDING_RELATION',
  )
  expect(screen.getByLabelText('출발 속성 코드')).toHaveValue(
    'PENDING_SOURCE.name',
  )
  await userEvent.click(screen.getByRole('button', { name: '후보 저장' }))
  await screen.findByRole('heading', { name: '규칙 후보를 저장했습니다.' })
  expect(request).toHaveBeenCalledTimes(1)
})

test.each(['network', 'forbidden', 'empty'] as const)(
  '관계 목록 %s 상태에서 안내하고 저장을 막는다',
  async (kind) => {
    server.use(
      http.get('/api/v1/ontology/link-types', () =>
        kind === 'network'
          ? HttpResponse.error()
          : kind === 'empty'
            ? HttpResponse.json({ data: [] })
            : HttpResponse.json(
                {
                  error: {
                    code: 'FORBIDDEN',
                    message: '관계 조회 권한이 없습니다.',
                    details: {},
                    requestId: 'test',
                  },
                },
                { status: 403 },
              ),
      ),
    )
    open('/admin/rules/303/edit')
    await screen.findByLabelText('규칙명')
    await screen.findByRole('alert')
    expect(screen.getByRole('button', { name: '후보 저장' })).toBeDisabled()
    expect(screen.getByLabelText('규칙명')).toHaveValue(
      '작업지시 설비 코드 연결',
    )
  },
)

test('속성 목록 조회 실패 시 저장을 막고 재조회 뒤 입력을 유지한다', async () => {
  server.use(
    http.get('/api/v1/ontology/object-types/904/properties', () =>
      HttpResponse.error(),
    ),
  )
  open('/admin/rules/303/edit')
  await screen.findByRole('alert')
  expect(screen.getByRole('button', { name: '후보 저장' })).toBeDisabled()
  server.use(
    http.get('/api/v1/ontology/object-types/904/properties', () =>
      HttpResponse.json({
        data: [
          {
            id: 9040,
            code: 'equipment_code',
            name: '설비 코드',
            sensitivity: 'NORMAL',
          },
        ],
      }),
    ),
  )
  await userEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '후보 저장' })).toBeEnabled(),
  )
  expect(screen.getByLabelText('출발 속성')).toHaveValue(
    'WORK_ORDER.equipment_code',
  )
})

test.each(['network', 'invalid'] as const)(
  '저장 응답 %s 유실 시 자동·중복 재전송을 차단한다',
  async (kind) => {
    const request = vi.fn(() =>
      kind === 'network'
        ? HttpResponse.error()
        : HttpResponse.json({ data: { id: 303 } }),
    )
    server.use(http.post('/api/v1/proposals/:id/revisions', request))
    open('/admin/rules/303/edit')
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '후보 저장' })).toBeEnabled(),
    )
    const save = screen.getByRole('button', { name: '후보 저장' })
    await userEvent.click(save)
    await screen.findByRole('alert')
    expect(save).toBeDisabled()
    fireEvent.submit(save.closest('form')!)
    expect(request).toHaveBeenCalledTimes(1)
    expect(screen.getByLabelText('규칙명')).toHaveValue(
      '작업지시 설비 코드 연결',
    )
  },
)

test('다른 개정의 복수 후보는 편집에 사용하지 않는다', async () => {
  server.use(
    http.get('/api/v1/proposals/:id/options', () =>
      HttpResponse.json({
        data: [{ id: 1, revision: 1, payload: {}, evidence: {} }],
      }),
    ),
  )
  open('/admin/rules/303/edit')
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '후보 개정이 일치하지 않습니다',
  )
  expect(screen.queryByLabelText('규칙명')).not.toBeInTheDocument()
})

test('앞 조건을 삭제해도 뒤 조건의 동일 분석 미확정 속성을 보존한다', async () => {
  const pendingCondition = {
    sourceProperty: 'WORK_ORDER.pending_code',
    targetProperty: 'EQUIPMENT.pending_code',
    operator: 'EQ',
    sourceTransforms: [{ op: 'UPPER', arg: null }],
    targetTransforms: [],
  }
  const request = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toMatchObject({
      payload: { conditions: [pendingCondition] },
    })
    return HttpResponse.json({
      data: { id: 303, revision: 3, checkRunId: 1202 },
    })
  })
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({
        data: {
          ...proposalFixtures[0],
          payload: {
            ...proposalFixtures[0].payload,
            conditions: [
              ...(proposalFixtures[0].payload.conditions as object[]),
              pendingCondition,
            ],
          },
        },
      }),
    ),
    http.post('/api/v1/proposals/:id/revisions', request),
  )
  open('/admin/rules/303/edit')
  await waitFor(() =>
    expect(screen.getByRole('button', { name: '후보 저장' })).toBeEnabled(),
  )
  await userEvent.click(screen.getByRole('button', { name: '연결 조건' }))
  await userEvent.click(screen.getByRole('button', { name: '조건 삭제' }))
  await userEvent.click(screen.getByRole('button', { name: '후보 저장' }))
  await screen.findByRole('heading', { name: '규칙 후보를 저장했습니다.' })
  expect(request).toHaveBeenCalledTimes(1)
})

test('탭과 조건을 전환해도 규칙명·검색·조건별 속성과 변환 입력을 유지한다', async () => {
  const user = userEvent.setup()
  open('/admin/rules/303/edit')
  const name = await screen.findByLabelText('규칙명')
  await user.clear(name)
  await user.type(name, '탭 입력 유지')
  await user.click(screen.getByRole('button', { name: '연결 조건' }))
  expect(
    screen.queryByRole('button', { name: '조건 1' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '출발 값 변환 추가' }))
  const functionSelect = screen.getAllByLabelText(/출발 값 변환.*함수/).at(-1)!
  await user.selectOptions(functionSelect, 'STRIP_PREFIX')
  await user.type(screen.getByLabelText('접두어'), 'WO-')
  await user.type(screen.getByLabelText('출발 속성 검색'), '설비')
  await user.click(screen.getByRole('button', { name: '조건 추가' }))
  const second = screen.getByRole('group', { name: '조건 2' })
  await user.selectOptions(
    within(second).getByLabelText('출발 속성'),
    'WORK_ORDER.equipment_code',
  )
  await user.selectOptions(
    within(second).getByLabelText('도착 속성'),
    'EQUIPMENT.code',
  )
  await user.click(screen.getByRole('button', { name: '조건 1' }))
  expect(screen.getByLabelText('접두어')).toHaveValue('WO-')
  expect(screen.getByRole('combobox', { name: '출발 속성 검색' })).toHaveValue(
    '설비',
  )
  await user.click(screen.getByRole('button', { name: '기본 정보' }))
  expect(screen.getByLabelText('규칙명')).toHaveValue('탭 입력 유지')
  await user.click(screen.getByRole('button', { name: '연결 조건' }))
  await user.click(screen.getByRole('button', { name: '조건 2' }))
  expect(within(second).getByLabelText('출발 속성')).toHaveValue(
    'WORK_ORDER.equipment_code',
  )
  expect(within(second).getByLabelText('도착 속성')).toHaveValue(
    'EQUIPMENT.code',
  )
})

test('입력이 빠진 다른 조건이 있으면 해당 조건을 열고 저장을 막는다', async () => {
  const user = userEvent.setup()
  const request = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.post('/api/v1/proposals/:id/revisions', request))
  open('/admin/rules/303/edit')
  await screen.findByLabelText('규칙명')
  await user.click(screen.getByRole('button', { name: '연결 조건' }))
  await user.click(screen.getByRole('button', { name: '조건 추가' }))
  await user.click(screen.getByRole('button', { name: '조건 1' }))
  await user.click(screen.getByRole('button', { name: '후보 저장' }))
  await screen.findByRole('alert')
  expect(screen.getByRole('button', { name: '조건 2' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(screen.getByRole('group', { name: '조건 2' })).toBeVisible()
  expect(request).not.toHaveBeenCalled()
})

test('관계 유형 미선택 안내에서 기본 정보로 돌아가도 입력을 유지한다', async () => {
  const user = userEvent.setup()
  open()
  await user.type(
    await screen.findByLabelText('규칙명', { exact: true }),
    '입력 유지 확인',
  )
  await user.click(screen.getByRole('button', { name: '연결 조건' }))
  expect(screen.getByRole('status')).toHaveTextContent('관계 유형을 먼저 선택')
  await user.click(screen.getByRole('button', { name: '관계 유형 선택' }))
  expect(screen.getByLabelText('규칙명', { exact: true })).toHaveValue(
    '입력 유지 확인',
  )
})

test('연결 조건의 양쪽 값 변환 함수를 후보 목록에서 선택한다', async () => {
  const user = userEvent.setup()
  open()
  await user.click(await screen.findByRole('button', { name: '연결 조건' }))
  for (const side of ['출발', '도착']) {
    await user.click(
      screen.getByRole('button', { name: `${side} 값 변환 추가` }),
    )
    const input = screen.getByRole('combobox', {
      name: `${side} 값 변환 1 함수 검색`,
    })
    await user.click(input)
    expect(
      within(screen.getByRole('listbox')).getAllByRole('option'),
    ).toHaveLength(10)
    await user.type(input, '접두어 추가')
    await user.click(
      within(screen.getByRole('listbox')).getByRole('option', {
        name: /접두어 추가/,
      }),
    )
    expect(
      screen.getByRole('combobox', {
        name: `${side} 값 변환 1 함수`,
      }),
    ).toHaveValue('ADD_PREFIX')
  }
  await user.click(screen.getByRole('button', { name: '기본 정보' }))
  await user.click(screen.getByRole('button', { name: '연결 조건' }))
  expect(
    screen.getByRole('combobox', { name: '출발 값 변환 1 함수' }),
  ).toHaveValue('ADD_PREFIX')
  expect(
    screen.getByRole('combobox', { name: '도착 값 변환 1 함수' }),
  ).toHaveValue('ADD_PREFIX')
})

test('같은 변환 함수를 다시 선택해도 접두어와 대응표를 유지한다', async () => {
  const user = userEvent.setup()
  open()
  await user.click(await screen.findByRole('button', { name: '연결 조건' }))
  await user.click(screen.getByRole('button', { name: '출발 값 변환 추가' }))
  const search = screen.getByRole('combobox', {
    name: '출발 값 변환 1 함수 검색',
  })
  for (const [op, name, label, value] of [
    ['ADD_PREFIX', '접두어 추가', '접두어', 'WO-'],
    ['MAP_VALUE', '값 대응표', '값 대응표', '{"A":"B"}'],
  ]) {
    await user.click(search)
    await user.click(
      within(screen.getByRole('listbox')).getByRole('option', {
        name: new RegExp(name),
      }),
    )
    const argument = screen.getByLabelText(label, { exact: true })
    await user.click(argument)
    await user.paste(value)
    await user.click(search)
    await user.click(
      within(screen.getByRole('listbox')).getByRole('option', {
        name: new RegExp(name),
      }),
    )
    expect(argument).toHaveValue(value)
    expect(
      screen.getByRole('combobox', { name: '출발 값 변환 1 함수' }),
    ).toHaveValue(op)
    await user.click(search)
    await user.type(search, name)
    await user.keyboard('{ArrowDown}{Enter}')
    expect(argument).toHaveValue(value)
  }
})
