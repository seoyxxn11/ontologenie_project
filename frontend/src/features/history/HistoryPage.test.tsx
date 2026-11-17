import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { mockUser } from '../../mocks/handlers'
import { server } from '../../mocks/node'
import HistoryPage from './HistoryPage'

afterEach(() => {
  mockUser.roleCode = 'PLANNER'
})

function renderHistoryPage(path = '/admin/history') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/history" element={<HistoryPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('처음에는 정의 변경 탭에서 정의를 바꾼 기록을 보여준다', async () => {
  renderHistoryPage()

  expect(
    screen.getByRole('heading', { name: '정의·규칙 이력' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '정의 변경' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(await screen.findByText('3건')).toBeInTheDocument()
  expect(screen.getByText('UPDATE_OBJECT_TYPE')).toBeInTheDocument()
  expect(
    screen.queryByText('object_type · id: 3 · 수정'),
  ).not.toBeInTheDocument()
})

test('내용 보기를 누르면 그 기록의 변경 전후를 펼치고 다시 누르면 접는다', async () => {
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('3건')

  const [firstButton] = screen.getAllByRole('button', { name: '내용 보기' })
  expect(firstButton).toHaveAttribute('aria-expanded', 'false')
  await user.click(firstButton)

  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()
  const comparison = screen.getByRole('table', { name: '변경 전후 비교' })
  expect(within(comparison).getByText('프레스 설비')).toBeInTheDocument()
  expect(screen.getAllByRole('button', { name: '내용 보기' })).toHaveLength(2)

  const closeButton = screen.getByRole('button', { name: '내용 접기' })
  expect(closeButton).toHaveAttribute('aria-expanded', 'true')
  expect(closeButton).toHaveAttribute('aria-controls', 'definition-change-703')
  expect(document.getElementById('definition-change-703')).toContainElement(
    comparison,
  )
  await user.click(closeButton)

  expect(
    screen.queryByText('object_type · id: 3 · 수정'),
  ).not.toBeInTheDocument()
  expect(screen.getAllByRole('button', { name: '내용 보기' })).toHaveLength(3)
})

test('여러 기록을 함께 펼칠 수 있고 초기화하면 모두 접는다', async () => {
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('3건')

  await user.click(screen.getAllByRole('button', { name: '내용 보기' })[0])
  await user.click(screen.getAllByRole('button', { name: '내용 보기' })[0])

  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()
  expect(
    screen.getByText('property_definition · id: 31 · 생성'),
  ).toBeInTheDocument()
  expect(screen.getAllByRole('button', { name: '내용 접기' })).toHaveLength(2)

  await user.click(screen.getByRole('button', { name: '초기화' }))

  expect(
    screen.queryByRole('button', { name: '내용 접기' }),
  ).not.toBeInTheDocument()
  expect(screen.getAllByRole('button', { name: '내용 보기' })).toHaveLength(3)
})

test('같은 조건으로 다시 검색하면 펼쳐 둔 기록을 그대로 둔다', async () => {
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('3건')

  await user.click(screen.getAllByRole('button', { name: '내용 보기' })[0])
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '내용 접기' })).toBeInTheDocument()
})

test('검색하면 펼쳐 둔 기록을 접는다', async () => {
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('3건')

  await user.click(screen.getAllByRole('button', { name: '내용 보기' })[1])
  expect(
    screen.getByText('property_definition · id: 31 · 생성'),
  ).toBeInTheDocument()

  await user.selectOptions(screen.getByLabelText('대상'), 'property_definition')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('1건')).toBeInTheDocument()
  expect(screen.getByText('CREATE_PROPERTY')).toBeInTheDocument()
  expect(
    screen.queryByText('property_definition · id: 31 · 생성'),
  ).not.toBeInTheDocument()
})

// 두 페이지 모두 같은 기록을 돌려주고, 어느 페이지인지는 actionCode로 구분한다.
function useTwoPageChanges() {
  server.use(
    http.get('/api/v1/ontology/changes', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      return HttpResponse.json({
        data: [
          {
            id: 703,
            actionExecutionId: 9004,
            tableName: 'object_type',
            rowKey: { id: 3 },
            changeKind: 'UPDATE',
            beforeData: { name: '설비' },
            afterData: { name: '프레스 설비' },
            actorId: 1,
            actorType: 'USER',
            requestedAt: '2026-10-28T10:00:00+09:00',
            actionCode: `PAGE_${page + 1}_ACTION`,
          },
        ],
        meta: { page, size: 20, totalElements: 40, hasNext: page === 0 },
      })
    }),
  )
}

test('다른 페이지로 옮기면 펼쳐 둔 기록을 접는다', async () => {
  useTwoPageChanges()
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('40건')

  await user.click(screen.getByRole('button', { name: '내용 보기' }))
  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '다음 페이지' }))

  expect(await screen.findByText('PAGE_2_ACTION')).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '2페이지로 이동', current: 'page' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('object_type · id: 3 · 수정'),
  ).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '내용 보기' })).toBeInTheDocument()
})

test('다른 페이지에서 같은 조건으로 검색하면 첫 페이지로 돌아가며 접는다', async () => {
  useTwoPageChanges()
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('40건')
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('PAGE_2_ACTION')

  await user.click(screen.getByRole('button', { name: '내용 보기' }))
  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('PAGE_1_ACTION')).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '1페이지로 이동', current: 'page' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('object_type · id: 3 · 수정'),
  ).not.toBeInTheDocument()
})

test('대상으로 검색하면 그 정의의 변경만 보여준다', async () => {
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('3건')

  await user.selectOptions(screen.getByLabelText('대상'), 'property_definition')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('1건')).toBeInTheDocument()
  expect(screen.getByText('CREATE_PROPERTY')).toBeInTheDocument()
})

test('시작일이 종료일보다 늦으면 검색하지 않고 안내한다', async () => {
  const user = userEvent.setup()
  renderHistoryPage()
  await screen.findByText('3건')

  await user.type(screen.getByLabelText('시작일'), '2026-10-28')
  await user.type(screen.getByLabelText('종료일'), '2026-10-27')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(screen.getByRole('alert')).toHaveTextContent(
    '종료일은 시작일 이후로 선택해 주세요.',
  )
})

test('정의 변경 이력을 볼 권한이 없으면 서버가 준 안내를 보여준다', async () => {
  server.use(
    http.get('/api/v1/ontology/changes', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '요청을 실행할 권한이 없습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 403 },
      ),
    ),
  )
  renderHistoryPage()

  expect(
    await screen.findByText('요청을 실행할 권한이 없습니다.'),
  ).toBeInTheDocument()
})

test('규칙을 고르면 적용 중인 개정본을 이전 개정본과 비교해 보여준다', async () => {
  const user = userEvent.setup()
  renderHistoryPage()

  await user.click(screen.getByRole('button', { name: '규칙 개정' }))
  const picker = await screen.findByLabelText('규칙 선택')
  expect(
    within(picker).getByRole('option', {
      name: '작업지시의 설비 코드로 설비 연결 · 현재 배정 설비 · 개정 2 적용 중',
    }),
  ).toBeInTheDocument()
  await user.selectOptions(picker, 'WO_EQUIPMENT_CODE')

  const revisions = within(await screen.findByRole('group', { name: '개정본' }))
  expect(
    revisions.getByRole('button', { name: '개정 2 · 적용 중' }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(
    revisions.getByRole('button', { name: '개정 1 · 퇴역' }),
  ).toBeInTheDocument()
  expect(screen.getByText('이전 · 개정 1')).toBeInTheDocument()
  expect(screen.getByText('선택 · 개정 2')).toBeInTheDocument()
  expect(
    screen.getByText('작업지시의 설비 코드로 설비 연결(초판)'),
  ).toBeInTheDocument()
  expect(screen.getAllByText('바뀜').length).toBeGreaterThan(0)
  expect(
    screen.getByText('WO_EQUIPMENT_CODE · 현재 배정 설비'),
  ).toBeInTheDocument()
  expect(
    screen.getByText(
      /1\. 출발 배정 설비 코드\(#31\) \(변환 1단계: .*\) = 도착 설비 코드\(#21\)/,
    ),
  ).toBeInTheDocument()
})

test('규칙 목록을 받지 못하면 규칙 코드를 직접 입력해서 조회한다', async () => {
  server.use(
    http.get('/api/v1/ontology/rules', () =>
      HttpResponse.json(
        {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: '요청한 항목을 찾을 수 없습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 404 },
      ),
    ),
  )
  const user = userEvent.setup()
  renderHistoryPage('/admin/history?tab=rules')

  await user.type(
    await screen.findByLabelText('규칙 코드'),
    'NOTE_EQUIPMENT_MENTION',
  )
  await user.click(screen.getByRole('button', { name: '조회' }))

  expect(
    await screen.findByRole('button', { name: '개정 1 · 적용 중' }),
  ).toBeInTheDocument()
  expect(
    screen.getByText('이 개정본에는 비교할 이전 개정본이 없습니다.'),
  ).toBeInTheDocument()
})

test('첫 개정본을 고르면 비교할 이전 개정본이 없다고 안내한다', async () => {
  const user = userEvent.setup()
  renderHistoryPage('/admin/history?tab=rules&code=WO_EQUIPMENT_CODE')

  await user.click(await screen.findByRole('button', { name: '개정 1 · 퇴역' }))

  expect(screen.getByText('선택 · 개정 1')).toBeInTheDocument()
  expect(screen.queryByText(/^이전 · 개정/)).not.toBeInTheDocument()
  expect(
    screen.getByText('이 개정본에는 비교할 이전 개정본이 없습니다.'),
  ).toBeInTheDocument()
})

test('주소에 탭과 규칙 코드가 있으면 그 규칙의 개정 이력을 바로 연다', async () => {
  renderHistoryPage('/admin/history?tab=rules&code=WO_EQUIPMENT_CODE')

  expect(screen.getByRole('button', { name: '규칙 개정' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(await screen.findByLabelText('규칙 선택')).toHaveValue(
    'WO_EQUIPMENT_CODE',
  )
  expect(
    await screen.findByRole('button', { name: '개정 2 · 적용 중' }),
  ).toBeInTheDocument()
})

test('개정 이력이 없는 규칙 코드면 안내를 보여준다', async () => {
  renderHistoryPage('/admin/history?tab=rules&code=NO_SUCH_RULE')

  expect(
    await screen.findByText('이 규칙 코드의 개정 이력이 없습니다.'),
  ).toBeInTheDocument()
})

test('활동 기록 탭은 고른 개정본의 원본 제안에 대한 실행 기록을 보여준다', async () => {
  mockUser.roleCode = 'ADMIN'
  const user = userEvent.setup()
  renderHistoryPage('/admin/history?tab=rules&code=WO_EQUIPMENT_CODE')

  await user.click(await screen.findByRole('button', { name: '활동 기록' }))

  expect(await screen.findByDisplayValue('제안 #300')).toBeInTheDocument()
  expect(await screen.findByText('1건')).toBeInTheDocument()
  expect(screen.getByText('검토·변경 · 연결 규칙 승인')).toBeInTheDocument()
  // 대상을 고른 활동 기록에는 종류 탭이 없다.
  expect(
    screen.queryByRole('group', { name: '실행 이력 종류' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText(
      '조회 범위: 선택한 대상에 대한 실행 기록 · Agent 조회 기록은 제외됩니다.',
    ),
  ).toBeInTheDocument()
})

test('원본 제안이 없는 개정본은 활동 기록이 없다고 안내한다', async () => {
  const user = userEvent.setup()
  renderHistoryPage('/admin/history?tab=rules&code=NOTE_EQUIPMENT_MENTION')

  await user.click(await screen.findByRole('button', { name: '활동 기록' }))

  expect(
    screen.getByText(/원본 제안이 없어\(직접 등록·초기 데이터\)/),
  ).toBeInTheDocument()
})
