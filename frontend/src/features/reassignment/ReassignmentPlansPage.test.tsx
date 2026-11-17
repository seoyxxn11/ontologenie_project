import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import { demoAccount } from '../../mocks/auth'
import { samplePlan } from '../../mocks/reassignmentHandlers'
import { AuthContext } from '../auth/auth-context'
import AgentPage from '../agent/AgentPage'
import WorkDataLayout from '../explorer/WorkDataLayout'
import ReassignmentPlansPage from './ReassignmentPlansPage'

function Location() {
  const location = useLocation()
  return (
    <output aria-label="현재 주소">
      {location.pathname}
      {location.search}
    </output>
  )
}

function renderPlans(path = '/explorer/plans') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider
        value={{
          account: demoAccount,
          pending: false,
          error: null,
          refresh: async () => ({ error: null }),
          signIn: async () => {},
          signOut: async () => {},
        }}
      >
        <MemoryRouter initialEntries={[path]}>
          <Location />
          <Routes>
            <Route path="/explorer" element={<WorkDataLayout />}>
              <Route index element={<p>객체 탐색 화면</p>} />
              <Route path="plans" element={<ReassignmentPlansPage />} />
            </Route>
            <Route path="/agent" element={<AgentPage />} />
          </Routes>
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  )
  return client
}

function list() {
  return within(screen.getByRole('region', { name: /재배정 계획 \d+건/ }))
}
function detail() {
  return within(screen.getByRole('region', { name: '계획 상세' }))
}

test('업무 데이터 하위 탭에서 API 목록을 열고 선택한 계획의 상세·대화로 이동한다', async () => {
  const user = userEvent.setup()
  renderPlans('/explorer')
  await user.click(screen.getByRole('link', { name: '재배정 계획' }))
  expect(screen.getByRole('link', { name: '재배정 계획' })).toHaveAttribute(
    'aria-current',
    'page',
  )
  const select = await screen.findByRole('button', { name: '계획 3' })
  expect(select).toHaveTextContent(/^3$/)
  expect(list().getByText('WO-101')).toBeVisible()
  expect(
    list().getByRole('button', { name: '1페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
  expect(list().getByRole('columnheader', { name: '생성자' })).toBeVisible()
  expect(
    list().getByRole('cell', { name: demoAccount.displayName }),
  ).toBeVisible()
  await user.click(select)
  expect(
    await detail().findByText('같은 프레스 공정이고 지금 가동 중인 설비'),
  ).toBeVisible()
  expect(detail().getByText('남은 공정 전체')).toBeVisible()
  expect(detail().getByText('상태').nextElementSibling).toHaveTextContent(
    /^생성됨$/,
  )
  expect(detail().getByText('2026-10-27 09:00:00')).toBeVisible()
  expect(detail().getByText('2026-10-27 09:10:02')).toBeVisible()
  expect(list().getByText('2026-10-27 09:10:02')).toBeVisible()
  expect(screen.queryByText(/KST/)).toBeNull()
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
    '/explorer/plans?plan=3',
  )
  const link = detail().getByRole('link', { name: 'Agent 대화로 이동 ↗' })
  expect(link).toHaveAttribute('href', '/agent?conversation=1')
  await user.click(link)
  expect(
    await screen.findByText(/PR-03의 이번 달 비가동은 2건입니다/),
  ).toBeVisible()
})

test('계획 직접 URL은 목록에 없는 계획도 상세 API로 조회하고 연결 대화가 없으면 안내한다', async () => {
  server.use(
    http.get('/api/v1/reassignment-plans/6', () =>
      HttpResponse.json({
        data: {
          ...samplePlan,
          id: 6,
          agentConversationId: null,
          items: [
            { ...samplePlan.items[0], id: 10, operationCode: '20' },
            { ...samplePlan.items[0], id: 11, operationCode: '30' },
          ],
        },
      }),
    ),
  )
  renderPlans('/explorer/plans?plan=6')
  expect(await detail().findByRole('heading', { name: '계획 6' })).toBeVisible()
  expect(detail().getByText('20')).toBeVisible()
  expect(detail().getByText('30')).toBeVisible()
  expect(
    detail().getByText('이 계획에 연결된 Agent 대화가 없습니다.'),
  ).toBeVisible()
  expect(detail().queryByRole('link', { name: /Agent 대화/ })).toBeNull()
})

test('입력 중 상세를 유지하고 다른 필터 적용 시 서버 조건·첫 페이지·선택 해제를 반영한다', async () => {
  const user = userEvent.setup()
  const requests: string[] = []
  server.use(
    http.get('/api/v1/reassignment-plans', ({ request }) => {
      requests.push(request.url)
      return HttpResponse.json({
        data: [samplePlan],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      })
    }),
  )
  renderPlans('/explorer/plans?plan=3')
  await detail().findByRole('heading', { name: '계획 3' })
  await user.type(screen.getByRole('searchbox', { name: '생성자' }), '체험')
  expect(detail().getByRole('heading', { name: '계획 3' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '조회' }))
  await waitFor(() =>
    expect(
      new URLSearchParams(
        screen.getByLabelText('현재 주소').textContent!.split('?')[1],
      ).get('createdByName'),
    ).toBe('체험'),
  )
  expect(detail().queryByRole('heading', { name: '계획 3' })).toBeNull()
  await screen.findByRole('button', { name: '계획 3' })
  expect(new URL(requests.at(-1)!).searchParams.get('createdByName')).toBe(
    '체험',
  )
  expect(new URL(requests.at(-1)!).searchParams.get('page')).toBe('0')
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByLabelText('생성자')).toHaveValue('')
  await waitFor(() =>
    expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
      /^\/explorer\/plans$/,
    ),
  )
})

test('생성자 이름은 부분 검색하고 일치하지 않는 이름은 빈 목록을 표시한다', async () => {
  const user = userEvent.setup()
  renderPlans()
  await screen.findByRole('button', { name: '계획 3' })
  const input = screen.getByRole('searchbox', { name: '생성자' })
  await user.type(input, '  체험  ')
  await user.click(screen.getByRole('button', { name: '조회' }))
  expect(await screen.findByRole('button', { name: '계획 3' })).toBeVisible()
  await user.clear(screen.getByRole('searchbox', { name: '생성자' }))
  await user.type(
    screen.getByRole('searchbox', { name: '생성자' }),
    '없는 생성자',
  )
  await user.click(screen.getByRole('button', { name: '조회' }))
  expect(
    await screen.findByText(/조회할 수 있는 재배정 계획이 없습니다/),
  ).toBeVisible()
})

test('저장된 모든 페이지에서 기존 설비만 중복 없이 선택하고 필터 적용 후에도 선택지를 유지한다', async () => {
  const user = userEvent.setup()
  server.use(
    http.get('/api/v1/reassignment-plans', ({ request }) => {
      const params = new URL(request.url).searchParams
      const page = Number(params.get('page') ?? 0)
      if (params.has('fromEquipmentId')) {
        return HttpResponse.json({
          data: [],
          meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
        })
      }
      return HttpResponse.json({
        data:
          page === 0
            ? [samplePlan, { ...samplePlan, id: 4 }]
            : [{ ...samplePlan, id: 5, fromEquipmentId: 302 }],
        meta: { page, size: 20, totalElements: 21, hasNext: page === 0 },
      })
    }),
  )
  renderPlans('/explorer/plans?plan=3')
  await detail().findByRole('heading', { name: '계획 3' })
  const equipment = screen.getByRole('combobox', { name: '기존 설비' })
  await within(equipment).findByRole('option', { name: 'PR-01 · 프레스 1호기' })
  expect(within(equipment).getAllByRole('option')).toHaveLength(3)
  expect(
    within(equipment).getByRole('option', { name: 'PR-03 · 프레스 3호기' }),
  ).toBeVisible()
  expect(screen.queryByRole('button', { name: '기존 설비 선택' })).toBeNull()
  await user.selectOptions(equipment, '302')
  expect(screen.getByLabelText('현재 주소')).not.toHaveTextContent(
    'fromEquipmentId',
  )
  expect(detail().getByRole('heading', { name: '계획 3' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '조회' }))
  expect(
    await screen.findByText(/조회할 수 있는 재배정 계획이 없습니다/),
  ).toBeVisible()
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent(
    'fromEquipmentId=302',
  )
  expect(screen.getByRole('combobox', { name: '기존 설비' })).toHaveValue('302')
  expect(
    within(screen.getByRole('combobox', { name: '기존 설비' })).getAllByRole(
      'option',
    ),
  ).toHaveLength(3)
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByRole('combobox', { name: '기존 설비' })).toHaveValue('')
})

test('설비 선택지의 다음 페이지 조회가 실패하면 부분 목록을 제시하지 않고 재시도한다', async () => {
  let failSecondPage = true
  server.use(
    http.get('/api/v1/reassignment-plans', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
      if (page === 1 && failSecondPage)
        return new HttpResponse(null, { status: 503 })
      return HttpResponse.json({
        data: [
          {
            ...samplePlan,
            id: page + 3,
            fromEquipmentId: page === 0 ? 301 : 302,
          },
        ],
        meta: { page, size: 20, totalElements: 21, hasNext: page === 0 },
      })
    }),
  )
  const user = userEvent.setup()
  renderPlans()
  expect(
    await screen.findByText('설비 목록을 불러오지 못했습니다.'),
  ).toBeVisible()
  const equipment = screen.getByRole('combobox', { name: '기존 설비' })
  expect(equipment).toBeDisabled()
  expect(within(equipment).getAllByRole('option')).toHaveLength(1)
  expect(screen.getByRole('button', { name: '계획 3' })).toBeVisible()
  failSecondPage = false
  await user.click(
    screen.getByRole('button', { name: '설비 목록 다시 불러오기' }),
  )
  await within(equipment).findByRole('option', { name: 'PR-01 · 프레스 1호기' })
  expect(equipment).toBeEnabled()
})

test('5개 번호·처음·이전·다음·마지막을 서버 페이지로 연결하고 상세 선택을 유지한다', async () => {
  const user = userEvent.setup()
  server.use(
    http.get('/api/v1/reassignment-plans', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page') ?? 0)
      return HttpResponse.json({
        data: [{ ...samplePlan, id: page + 3 }],
        meta: { page, size: 20, totalElements: 101, hasNext: page < 5 },
      })
    }),
  )
  renderPlans('/explorer/plans?plan=3')
  await detail().findByRole('heading', { name: '계획 3' })
  const pager = within(list().getByRole('navigation', { name: '페이지 이동' }))
  expect(
    pager.getAllByRole('button').map((button) => button.textContent),
  ).toEqual(['«', '‹', '1', '2', '3', '4', '5', '›', '»'])
  await user.click(await screen.findByRole('button', { name: '다음 페이지' }))
  expect(await screen.findByRole('button', { name: '계획 4' })).toBeVisible()
  expect(detail().getByRole('heading', { name: '계획 3' })).toBeVisible()
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('page=1')
  await user.click(screen.getByRole('button', { name: '3페이지로 이동' }))
  expect(await screen.findByRole('button', { name: '계획 5' })).toBeVisible()
  expect(screen.getByLabelText('현재 주소')).toHaveTextContent('page=2')
  await user.click(screen.getByRole('button', { name: '마지막 페이지' }))
  expect(await screen.findByRole('button', { name: '계획 8' })).toBeVisible()
  expect(screen.getByRole('button', { name: '마지막 페이지' })).toBeDisabled()
  expect(
    screen.getByRole('button', { name: '6페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
  await user.click(screen.getByRole('button', { name: '이전 페이지' }))
  expect(await screen.findByRole('button', { name: '계획 7' })).toBeVisible()
  await user.click(screen.getByRole('button', { name: '첫 페이지' }))
  expect(await screen.findByRole('button', { name: '계획 3' })).toBeVisible()
  expect(screen.getByRole('button', { name: '첫 페이지' })).toBeDisabled()
  expect(screen.getByLabelText('현재 주소')).not.toHaveTextContent('page=')
  expect(detail().getByRole('heading', { name: '계획 3' })).toBeVisible()
})

test('같은 조건 재조회는 상세 선택을 유지하고 닫기는 목록 버튼으로 포커스를 돌린다', async () => {
  const user = userEvent.setup()
  renderPlans()
  const select = await screen.findByRole('button', { name: '계획 3' })
  await user.click(select)
  await detail().findByRole('heading', { name: '계획 3' })
  await user.click(screen.getByRole('button', { name: '조회' }))
  expect(detail().getByRole('heading', { name: '계획 3' })).toBeVisible()
  await user.click(detail().getByRole('button', { name: '닫기' }))
  expect(
    detail().getByText('왼쪽 목록에서 계획을 선택해 주세요.'),
  ).toBeVisible()
  expect(select).toHaveFocus()
})

test('목록 실패 시 오류·재시도를 제공하고 빈 결과를 삭제나 성공으로 단정하지 않는다', async () => {
  server.use(
    http.get(
      '/api/v1/reassignment-plans',
      () => new HttpResponse(null, { status: 503 }),
    ),
  )
  const user = userEvent.setup()
  renderPlans()
  await screen.findByRole('alert')
  server.use(
    http.get('/api/v1/reassignment-plans', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(
    await screen.findByText(/조회할 수 있는 재배정 계획이 없습니다/),
  ).toBeVisible()
  expect(screen.queryByRole('button', { name: /계획 / })).toBeNull()
})

test('없는 계획 또는 권한 제한 상세는 오류를 보여 주고 다른 계획을 대신 표시하지 않는다', async () => {
  renderPlans('/explorer/plans?plan=99')
  expect(
    await detail().findByText('요청한 항목을 찾을 수 없습니다.'),
  ).toBeVisible()
  expect(detail().queryByRole('link', { name: /Agent 대화/ })).toBeNull()
  expect(await screen.findByRole('button', { name: '계획 3' })).toBeVisible()
})

test('가려진 스냅샷 값을 현재 작업지시 조회로 복원하거나 전체 공정으로 단정하지 않는다', async () => {
  server.use(
    http.get('/api/v1/reassignment-plans/3', () =>
      HttpResponse.json({
        data: {
          ...samplePlan,
          items: [
            {
              ...samplePlan.items[0],
              sourceSnapshot: { workOrderNo: null },
              operationCode: null,
              restrictedPropertyCodes: ['sequence', 'wo_no'],
            },
          ],
        },
      }),
    ),
  )
  renderPlans('/explorer/plans?plan=3')
  expect(await detail().findByText('공정 정보 조회 제한')).toBeVisible()
  expect(detail().queryByText('남은 공정 전체')).toBeNull()
  expect(detail().queryByText('WO-101')).toBeNull()
})
