import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import HistoryPage from './HistoryPage'

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
  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()
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
