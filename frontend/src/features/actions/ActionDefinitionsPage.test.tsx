import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import App from '../../App'
import { actionFixtures } from '../../mocks/actionHandlers'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import type { ActionDefinition } from './types'

function open(path: string) {
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
  window.history.pushState({}, '', path)
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <App />
    </QueryClientProvider>,
  )
}

async function fillRequired(user: ReturnType<typeof userEvent.setup>) {
  await user.type(await screen.findByLabelText(/^코드/), 'find_alternative')
  await user.type(screen.getByLabelText(/^이름/), '대체 설비 찾기')
  await user.type(
    screen.getByLabelText(/^설명/),
    '같은 공정을 할 수 있는 설비를 찾는다.',
  )
  await user.click(screen.getByText('개발 연결 설정', { exact: true }))
  await user.type(screen.getByLabelText(/^Handler 이름/), 'FIND_ALTERNATIVE')
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.selectOptions(screen.getByLabelText('필요 권한'), 'ACCOUNT_MANAGE')
}

test('목록에서 데이터 변경 여부와 Agent 사용 상태·이유를 보여 주고 조건으로 거른다', async () => {
  const user = userEvent.setup()
  open('/admin/actions')
  const table = await screen.findByRole('table', { name: 'Action 정의 목록' })
  const rows = within(table).getAllByRole('row')
  expect(within(rows[1]).getByText('Agent 사용 가능')).toBeInTheDocument()
  expect(within(rows[1]).getByText('데이터를 바꿈')).toBeInTheDocument()
  expect(within(rows[2]).getByText('구현 대기')).toBeInTheDocument()
  expect(
    within(rows[2]).getByText(/아직 기능을 구현하지 않았습니다/),
  ).toBeInTheDocument()
  expect(within(rows[3]).getByText('관리 작업')).toBeInTheDocument()
  expect(
    within(rows[2]).getByRole('link', { name: '예상 완료 시각 계산' }),
  ).toHaveAttribute('href', '/admin/actions/ESTIMATE_COMPLETION')
  // 화면에는 Action/Tool 구분 용어를 쓰지 않는다(363 화면 원칙).
  expect(document.body.textContent).not.toMatch(/조회 도구|READ|Tool/)

  await user.selectOptions(screen.getByLabelText('데이터 변경'), 'READ')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('1건')
  expect(
    screen.queryByRole('link', { name: '재배정 계획 생성' }),
  ).not.toBeInTheDocument()
})

test('등록은 데이터 변경 질문에 답해야 보내고, 답을 actionKind로 저장한다', async () => {
  let body: Record<string, unknown> | undefined
  server.use(
    http.post('/api/v1/actions', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>
      const created = {
        ...body,
        id: 9,
        handlerReady: false,
        rowVersion: 0,
      } as ActionDefinition
      return HttpResponse.json({ data: created }, { status: 201 })
    }),
  )
  const user = userEvent.setup()
  open('/admin/actions/new')
  await fillRequired(user)
  expect(screen.getByLabelText(/^코드/)).toHaveValue('FIND_ALTERNATIVE')

  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  expect(
    await screen.findByText(
      '이 기능이 데이터를 만들거나 바꾸는지 선택해 주세요.',
    ),
  ).toBeInTheDocument()
  expect(body).toBeUndefined()

  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.selectOptions(screen.getByLabelText('데이터 변경'), 'READ')
  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  await screen.findByRole('heading', { name: 'Action을 등록했습니다.' })
  expect(body).toMatchObject({
    code: 'FIND_ALTERNATIVE',
    actionKind: 'READ',
    actionMode: 'EXECUTE',
    targetKind: 'OBJECT',
    targetObjectTypeId: null,
    requiredPermission: 'ACCOUNT_MANAGE',
    inputSchema: { type: 'object', properties: {} },
    isActive: true,
  })
  expect(screen.getByText(/구현 요청 알림을 보냅니다/)).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '정의 상세 확인' })).toHaveAttribute(
    'href',
    '/admin/actions/FIND_ALTERNATIVE',
  )
})

test('코드와 입력 형식이 규칙에 맞지 않으면 등록 요청을 보내지 않는다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: actionFixtures[0] }))
  server.use(http.post('/api/v1/actions', request))
  const user = userEvent.setup()
  open('/admin/actions/new')
  await user.type(await screen.findByLabelText(/^코드/), '1abc')
  expect(screen.getByText(/영문 대문자로 시작하고/)).toBeInTheDocument()
  await user.clear(screen.getByLabelText(/^코드/))
  await fillRequired(user)
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.selectOptions(screen.getByLabelText('데이터 변경'), 'WRITE')
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: 'JSON으로 편집' }))
  fireEvent.change(screen.getByLabelText(/^JSON Schema/), {
    target: { value: '[1, 2]' },
  })
  expect(screen.getByText('JSON 객체 형식이 아닙니다.')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  expect(
    await screen.findByText('입력 형식은 JSON 객체로 입력해 주세요.'),
  ).toBeInTheDocument()
  expect(request).not.toHaveBeenCalled()
})

test('코드 경로로 상세를 열고 바뀐 칸과 수정 기준 버전만 보낸다', async () => {
  let body: unknown
  server.use(
    http.patch('/api/v1/actions/:code', async ({ request }) => {
      body = await request.json()
      return HttpResponse.json({
        data: { ...actionFixtures[1], name: '완료 시각 계산', rowVersion: 3 },
      })
    }),
  )
  const user = userEvent.setup()
  open('/admin/actions/ESTIMATE_COMPLETION')
  const name = await screen.findByLabelText(/^이름/)
  expect(screen.getByText('구현 대기')).toBeInTheDocument()
  expect(screen.getByLabelText(/^코드/)).toBeDisabled()
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  expect(screen.getByLabelText('데이터 변경')).toHaveValue('READ')
  await user.click(screen.getByRole('tab', { name: '기본 정보' }))
  expect(screen.getByText('입력 항목이 없습니다.')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  expect(
    await screen.findByText('변경할 내용을 입력해 주세요.'),
  ).toBeInTheDocument()
  expect(body).toBeUndefined()

  await user.clear(name)
  await user.type(name, '완료 시각 계산')
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  await screen.findByRole('heading', { name: 'Action 정의를 변경했습니다.' })
  expect(body).toEqual({ rowVersion: 2, name: '완료 시각 계산' })
  // handlerKey를 바꾸지 않았으면 서버가 알림을 다시 보내지 않는다.
  expect(screen.queryByText(/구현 요청 알림/)).not.toBeInTheDocument()
})

test('다른 사람이 먼저 고쳐 버전이 충돌하면 저장을 막고 최신 정의를 다시 불러온다', async () => {
  server.use(
    http.patch('/api/v1/actions/:code', () =>
      HttpResponse.json(
        {
          error: {
            code: 'VERSION_CONFLICT',
            message: '요청한 버전과 현재 버전이 다릅니다.',
            details: { field: 'rowVersion' },
            requestId: 'mock-conflict',
          },
        },
        { status: 409 },
      ),
    ),
  )
  const user = userEvent.setup()
  open('/admin/actions/CREATE_REASSIGNMENT_PLAN')
  const name = await screen.findByLabelText(/^이름/)
  expect(screen.getByLabelText('1번째 항목 이름')).toHaveValue(
    'fromEquipmentId',
  )
  expect(screen.getByLabelText('1번째 항목 자료형')).toHaveValue('integer')
  expect(screen.getByLabelText('1번째 항목 필수')).toBeChecked()
  expect(screen.getByLabelText('1번째 항목 최소 값')).toHaveValue('1')
  await user.type(name, ' v2')
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  expect(
    await screen.findByText('최신 상태를 확인해 주세요.'),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeDisabled()

  await user.click(
    screen.getByRole('button', { name: '최신 정의 다시 조회·입력 초기화' }),
  )
  expect(await screen.findByLabelText(/^이름/)).toHaveValue('재배정 계획 생성')
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeEnabled()
})

test('관리 작업 정의는 읽기 전용으로 보여 주고 저장 버튼을 두지 않는다', async () => {
  open('/admin/actions/UPDATE_ACCOUNT')
  expect(
    await screen.findByText(
      '관리 작업의 정의는 시스템이 관리해 화면에서 바꿀 수 없습니다.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByText('계정 변경')).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '변경 저장' }),
  ).not.toBeInTheDocument()
})

test('없는 코드로 열면 대상을 찾을 수 없다고 알린다', async () => {
  open('/admin/actions/NO_SUCH_ACTION')
  expect(
    await screen.findByText('대상을 찾을 수 없습니다.'),
  ).toBeInTheDocument()
})

test('필요 권한은 역할에서 모아 겹치지 않게 사전 순으로 보여 준다', async () => {
  server.use(
    http.get('/api/v1/admin/roles', () =>
      HttpResponse.json({
        data: [
          {
            code: 'ADMIN',
            name: '관리자',
            permissions: ['PLAN_CREATE', 'ACCOUNT_MANAGE'],
          },
          {
            code: 'PLANNER',
            name: '생산기획자',
            permissions: ['PLANNER_VIEW', 'PLAN_CREATE'],
          },
        ],
      }),
    ),
  )
  open('/admin/actions/new')
  await userEvent.click(await screen.findByRole('tab', { name: '실행 조건' }))
  const select = await screen.findByLabelText('필요 권한')
  await within(select).findByRole('option', { name: 'PLANNER_VIEW' })
  expect(
    within(select)
      .getAllByRole('option')
      .map((option) => option.getAttribute('value'))
      .filter(Boolean),
  ).toEqual(['ACCOUNT_MANAGE', 'PLAN_CREATE', 'PLANNER_VIEW'])
})

test('코드가 NEW인 정의도 등록 폼이 아니라 상세로 연다', async () => {
  server.use(
    http.get('/api/v1/actions/NEW', () =>
      HttpResponse.json({
        data: { ...actionFixtures[1], code: 'NEW', name: '새 기능' },
      }),
    ),
  )
  open('/admin/actions/NEW')
  expect(await screen.findByLabelText(/^이름/)).toHaveValue('새 기능')
  expect(screen.getByLabelText(/^코드/)).toHaveValue('NEW')
  expect(screen.getByLabelText(/^코드/)).toBeDisabled()
})

test('입력 형식을 표로 만들면 JSON Schema로 바꿔 보낸다', async () => {
  let body: Record<string, unknown> | undefined
  server.use(
    http.post('/api/v1/actions', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>
      return HttpResponse.json(
        {
          data: {
            ...body,
            id: 9,
            handlerReady: false,
            rowVersion: 0,
          } as ActionDefinition,
        },
        { status: 201 },
      )
    }),
  )
  const user = userEvent.setup()
  open('/admin/actions/new')
  await fillRequired(user)
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.selectOptions(screen.getByLabelText('데이터 변경'), 'READ')
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: '입력 항목 추가' }))
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: '입력 항목 추가' }))
  await user.type(screen.getByLabelText('1번째 항목 이름'), 'equipmentId')
  await user.selectOptions(
    screen.getByLabelText('1번째 항목 자료형'),
    'integer',
  )
  await user.click(screen.getByLabelText('1번째 항목 필수'))
  await user.type(screen.getByLabelText('1번째 항목 최소 값'), '1')
  await user.type(screen.getByLabelText('1번째 항목 설명'), '대상 설비 ID')
  await user.type(screen.getByLabelText('2번째 항목 이름'), 'processCodes')
  await user.selectOptions(screen.getByLabelText('2번째 항목 자료형'), 'array')
  await user.selectOptions(
    screen.getByLabelText('2번째 항목 목록 안 자료형'),
    'string',
  )
  await user.type(screen.getByLabelText('2번째 항목 최대 개수'), '5')

  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  await screen.findByRole('heading', { name: 'Action을 등록했습니다.' })
  expect(body?.inputSchema).toEqual({
    type: 'object',
    required: ['equipmentId'],
    properties: {
      equipmentId: { type: 'integer', minimum: 1, description: '대상 설비 ID' },
      processCodes: { type: 'array', items: { type: 'string' }, maxItems: 5 },
    },
  })
})

test('표의 항목 이름이 겹치거나 범위가 거꾸로면 등록 요청을 보내지 않는다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: actionFixtures[0] }))
  server.use(http.post('/api/v1/actions', request))
  const user = userEvent.setup()
  open('/admin/actions/new')
  await fillRequired(user)
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.selectOptions(screen.getByLabelText('데이터 변경'), 'WRITE')
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: '입력 항목 추가' }))
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: '입력 항목 추가' }))
  await user.type(screen.getByLabelText('1번째 항목 이름'), 'reason')
  await user.type(screen.getByLabelText('2번째 항목 이름'), 'reason')
  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  expect(
    await screen.findByText("입력 항목 이름 'reason'이 겹칩니다."),
  ).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '2번째 항목 삭제' }))
  await user.type(screen.getByLabelText('1번째 항목 최소 길이'), '10')
  await user.type(screen.getByLabelText('1번째 항목 최대 길이'), '2')
  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  expect(
    await screen.findByText('1번째 입력 항목: 최소가 최대보다 클 수 없습니다.'),
  ).toBeInTheDocument()
  expect(request).not.toHaveBeenCalled()
})

test('표로 나타낼 수 없는 형식은 JSON으로 열고, 바꾸지 않으면 입력 형식을 보내지 않는다', async () => {
  const nested = {
    ...actionFixtures[0],
    inputSchema: {
      type: 'object',
      properties: {
        items: {
          type: 'array',
          items: {
            type: 'object',
            properties: { workOrderId: { type: 'integer' } },
          },
        },
      },
    },
  }
  let body: unknown
  server.use(
    http.get('/api/v1/actions/:code', () =>
      HttpResponse.json({ data: nested }),
    ),
    http.patch('/api/v1/actions/:code', async ({ request }) => {
      body = await request.json()
      return HttpResponse.json({ data: { ...nested, rowVersion: 1 } })
    }),
  )
  const user = userEvent.setup()
  open('/admin/actions/CREATE_REASSIGNMENT_PLAN')
  await user.click(await screen.findByRole('tab', { name: '입력 형식' }))
  const schema = await screen.findByLabelText(/^JSON Schema/)
  expect(schema).toHaveValue(JSON.stringify(nested.inputSchema, null, 2))
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: '표로 편집' }))
  expect(screen.getByText(/표로 나타낼 수 없는 형식입니다/)).toBeInTheDocument()
  expect(screen.getByLabelText(/^JSON Schema/)).toBeInTheDocument()

  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.click(screen.getByLabelText('사용'))
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  await screen.findByRole('heading', { name: 'Action 정의를 변경했습니다.' })
  expect(body).toEqual({ rowVersion: 0, isActive: false })
})

test('표로 연 입력 형식을 바꾸지 않으면 다른 칸만 보낸다', async () => {
  let body: unknown
  server.use(
    http.patch('/api/v1/actions/:code', async ({ request }) => {
      body = await request.json()
      return HttpResponse.json({
        data: { ...actionFixtures[0], isActive: false, rowVersion: 1 },
      })
    }),
  )
  const user = userEvent.setup()
  open('/admin/actions/CREATE_REASSIGNMENT_PLAN')
  expect(await screen.findByLabelText('1번째 항목 이름')).toHaveValue(
    'fromEquipmentId',
  )
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.click(screen.getByLabelText('사용'))
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  await screen.findByRole('heading', { name: 'Action 정의를 변경했습니다.' })
  expect(body).toEqual({ rowVersion: 0, isActive: false })
})

test('서버는 받지만 표 검사에 걸리는 형식은 JSON으로 열어 다른 칸을 저장할 수 있다', async () => {
  const outOfRange = {
    ...actionFixtures[0],
    inputSchema: {
      type: 'object',
      properties: { count: { type: 'integer', minimum: 10, maximum: 1 } },
    },
  }
  let body: unknown
  server.use(
    http.get('/api/v1/actions/:code', () =>
      HttpResponse.json({ data: outOfRange }),
    ),
    http.patch('/api/v1/actions/:code', async ({ request }) => {
      body = await request.json()
      return HttpResponse.json({ data: { ...outOfRange, rowVersion: 1 } })
    }),
  )
  const user = userEvent.setup()
  open('/admin/actions/CREATE_REASSIGNMENT_PLAN')
  await user.click(await screen.findByRole('tab', { name: '입력 형식' }))
  expect(await screen.findByLabelText(/^JSON Schema/)).toHaveValue(
    JSON.stringify(outOfRange.inputSchema, null, 2),
  )
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: '표로 편집' }))
  expect(
    screen.getByText(
      '표로 바꿀 수 없습니다. 1번째 입력 항목: 최소가 최대보다 클 수 없습니다.',
    ),
  ).toBeInTheDocument()
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.click(screen.getByLabelText('사용'))
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  await screen.findByRole('heading', { name: 'Action 정의를 변경했습니다.' })
  expect(body).toEqual({ rowVersion: 0, isActive: false })
})

test('JSON 전환에 실패한 안내는 표를 고치면 사라진다', async () => {
  const user = userEvent.setup()
  open('/admin/actions/new')
  await user.click(await screen.findByRole('tab', { name: '입력 형식' }))
  await user.click(
    await screen.findByRole('button', { name: '입력 항목 추가' }),
  )
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: 'JSON으로 편집' }))
  expect(
    screen.getByText('1번째 입력 항목의 이름을 입력해 주세요.'),
  ).toBeInTheDocument()
  await user.type(screen.getByLabelText('1번째 항목 이름'), 'reason')
  expect(
    screen.queryByText('1번째 입력 항목의 이름을 입력해 주세요.'),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: 'JSON으로 편집' }))
  expect(screen.getByLabelText(/^JSON Schema/)).toHaveValue(
    JSON.stringify(
      { type: 'object', properties: { reason: { type: 'string' } } },
      null,
      2,
    ),
  )
})

test('탭을 왕복해도 기본 정보·조건·미완성 JSON과 편집 모드를 보존한다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: actionFixtures[0] }))
  server.use(http.post('/api/v1/actions', request))
  const user = userEvent.setup()
  open('/admin/actions/new')
  await fillRequired(user)
  await user.selectOptions(screen.getByLabelText('데이터 변경'), 'WRITE')
  await user.selectOptions(screen.getByLabelText('대상'), 'SYSTEM')
  await user.click(screen.getByLabelText('실행 전에 사용자 확인 받기'))
  await user.click(screen.getByLabelText('사용'))
  expect(screen.getByLabelText('대상 객체 유형')).toBeDisabled()
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: 'JSON으로 편집' }))
  const unfinished = '{"type":"object", "properties":'
  fireEvent.change(screen.getByLabelText(/^JSON Schema/), {
    target: { value: unfinished },
  })
  await user.click(screen.getByRole('tab', { name: '기본 정보' }))
  expect(screen.getByLabelText(/^이름/)).toHaveValue('대체 설비 찾기')
  expect(screen.getByLabelText(/^Handler 이름/)).toHaveValue('FIND_ALTERNATIVE')
  expect(screen.getByRole('tabpanel', { name: '기본 정보' })).toBeVisible()
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  expect(screen.getByLabelText('데이터 변경')).toHaveValue('WRITE')
  expect(screen.getByLabelText('대상')).toHaveValue('SYSTEM')
  expect(screen.getByLabelText('필요 권한')).toHaveValue('ACCOUNT_MANAGE')
  expect(screen.getByLabelText('실행 전에 사용자 확인 받기')).toBeChecked()
  expect(screen.getByLabelText('사용')).not.toBeChecked()
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  expect(screen.getByLabelText(/^JSON Schema/)).toHaveValue(unfinished)
  expect(screen.getByRole('button', { name: '표로 편집' })).toBeVisible()
  expect(request).not.toHaveBeenCalled()
})

test('숨은 탭의 오류로 이동하고 접힌 Handler 오류도 열어 포커스한다', async () => {
  const user = userEvent.setup()
  open('/admin/actions/new')
  await user.type(await screen.findByLabelText(/^코드/), 'CHECK_ACTION')
  await user.type(screen.getByLabelText(/^이름/), '조건 확인')
  await user.type(screen.getByLabelText(/^설명/), '조건을 확인한다.')
  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  expect(screen.getByRole('tab', { name: '실행 조건' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(screen.getByLabelText('데이터 변경')).toHaveFocus()
  await user.selectOptions(screen.getByLabelText('데이터 변경'), 'READ')
  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  expect(screen.getByRole('tab', { name: '기본 정보' })).toHaveAttribute(
    'aria-selected',
    'true',
  )
  expect(screen.getByLabelText(/^Handler 이름/)).toBeVisible()
  expect(screen.getByLabelText(/^Handler 이름/)).toHaveFocus()
  expect(screen.getByLabelText(/^Handler 이름/)).toHaveAttribute(
    'aria-invalid',
    'true',
  )
})

test('탭 키보드 탐색과 미저장 이동 취소 뒤에도 작성 값을 유지한다', async () => {
  const user = userEvent.setup()
  open('/admin/actions/new')
  const first = await screen.findByRole('tab', { name: '기본 정보' })
  first.focus()
  await user.keyboard('{ArrowRight}')
  expect(screen.getByRole('tab', { name: '실행 조건' })).toHaveFocus()
  await user.keyboard('{End}')
  expect(screen.getByRole('tab', { name: '입력 형식' })).toHaveFocus()
  await user.keyboard('{Home}')
  expect(first).toHaveFocus()
  await user.type(screen.getByLabelText(/^이름/), '유지할 질문')
  await user.click(
    screen.getByRole('link', { name: 'Action 정의 목록으로 돌아가기' }),
  )
  await user.click(screen.getByRole('button', { name: '편집 계속' }))
  expect(screen.getByLabelText(/^이름/)).toHaveValue('유지할 질문')
  await user.click(
    screen.getByRole('link', { name: 'Action 정의 목록으로 돌아가기' }),
  )
  await user.click(screen.getByRole('button', { name: '입력 폐기하고 이동' }))
  expect(
    await screen.findByRole('table', { name: 'Action 정의 목록' }),
  ).toBeVisible()
})

test('저장 실패 뒤에 모든 탭의 입력을 유지하고 자동 재전송하지 않는다', async () => {
  const request = vi.fn(() => HttpResponse.error())
  server.use(http.patch('/api/v1/actions/:code', request))
  const user = userEvent.setup()
  open('/admin/actions/ESTIMATE_COMPLETION')
  await user.type(await screen.findByLabelText(/^이름/), ' 수정')
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.click(screen.getByLabelText('사용'))
  await user.click(screen.getByRole('tab', { name: '입력 형식' }))
  await user.click(screen.getByRole('button', { name: '입력 항목 추가' }))
  await user.type(screen.getByLabelText('1번째 항목 이름'), 'reason')
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  await screen.findByRole('alert')
  expect(request).toHaveBeenCalledTimes(1)
  expect(screen.getByLabelText('1번째 항목 이름')).toHaveValue('reason')
  await user.click(screen.getByRole('tab', { name: '기본 정보' }))
  expect(screen.getByLabelText(/^이름/)).toHaveValue('예상 완료 시각 계산 수정')
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  expect(screen.getByLabelText('사용')).not.toBeChecked()
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeEnabled()
})

test('필수 입력은 접근성 속성으로 알리고 해당 입력 아래에 오류를 연결한다', async () => {
  const user = userEvent.setup()
  open('/admin/actions/new')
  await screen.findByRole('tab', { name: '기본 정보' })
  for (const label of [
    /^코드/,
    /^이름/,
    /^설명/,
    /^Handler 이름/,
    /^데이터 변경/,
    /^필요 권한/,
  ]) {
    expect(screen.getByLabelText(label)).toHaveAttribute(
      'aria-required',
      'true',
    )
  }
  await user.click(screen.getByRole('button', { name: 'Action 등록' }))
  const code = screen.getByLabelText(/^코드/)
  expect(code).toHaveAttribute(
    'aria-describedby',
    'action-code-help action-field-code-error',
  )
  expect(code.closest('.actions-field')).toHaveTextContent(
    '코드를 입력해 주세요.',
  )
  expect(screen.getByText('코드를 입력해 주세요.')).toHaveAttribute(
    'id',
    'action-field-code-error',
  )
  await user.type(code, 'CHECK_ACTION')
  expect(screen.queryByText('코드를 입력해 주세요.')).not.toBeInTheDocument()
})

test('저장된 Agent 상태를 편집 중 초안의 상태와 구분한다', async () => {
  const user = userEvent.setup()
  open('/admin/actions/CREATE_REASSIGNMENT_PLAN')
  await screen.findByRole('tab', { name: '실행 조건' })
  expect(screen.getByText('저장된 상태')).toBeVisible()
  expect(screen.getByText('Agent 사용 가능')).toBeVisible()
  await user.click(screen.getByRole('tab', { name: '실행 조건' }))
  await user.click(screen.getByLabelText('사용'))
  expect(screen.getByLabelText('사용')).not.toBeChecked()
  expect(screen.getByText('저장된 상태')).toBeVisible()
  expect(screen.getByText(/저장하지 않은 변경 있음/)).toBeVisible()
})
