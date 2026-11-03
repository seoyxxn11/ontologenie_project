import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { mockUser } from '../../mocks/handlers'
import { server } from '../../mocks/node'
import AuditPage from './AuditPage'

function renderAuditPage(path: string) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/audit" element={<AuditPage />} />
          <Route path="/explorer" element={<p>탐색 화면</p>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

afterEach(() => {
  mockUser.roleCode = 'PLANNER'
})

test('객체 번호가 있으면 그 객체의 실행 이력을 보여준다', async () => {
  renderAuditPage('/audit?objectId=301')

  expect(screen.getByRole('heading', { name: '실행 이력' })).toBeInTheDocument()
  expect(await screen.findByText('3건')).toBeInTheDocument()
  expect(await screen.findByDisplayValue('프레스 3호기')).toBeInTheDocument()
  expect(
    screen.getByText('대체 설비 PR-02는 프레스 공정을 처리할 수 없습니다.'),
  ).toBeInTheDocument()
  expect(
    screen.getByText('reassignment_plan · id: 501 · 생성'),
  ).toBeInTheDocument()
})

test('객체 번호가 없으면 내 실행 이력을 보여준다', async () => {
  renderAuditPage('/audit')

  expect(await screen.findByText('4건')).toBeInTheDocument()
  expect(screen.getByText('sync_run #77')).toBeInTheDocument()
  expect(screen.queryByLabelText('대상')).not.toBeInTheDocument()
  expect(
    screen.getByText(
      '조회 범위: 현재 계정의 실행 이력 · Agent 조회 기록은 제외됩니다.',
    ),
  ).toBeInTheDocument()
})

test('관리자는 같은 실행 이력에서 다른 사람의 실행과 변경 전후도 본다', async () => {
  mockUser.roleCode = 'ADMIN'
  renderAuditPage('/audit')

  expect(await screen.findByText('5건')).toBeInTheDocument()
  expect(
    screen.getByText(
      '조회 범위: 관리자에게 허용된 전체 실행 이력 · Agent 조회 기록은 제외됩니다.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByText('사용자 · 사용자 #1')).toBeInTheDocument()
  expect(screen.getByText('object_type #3')).toBeInTheDocument()
  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()
})

test('서버가 조회 범위를 주지 않으면 권한에 따른 범위라고 안내한다', async () => {
  server.use(
    http.get('/api/v1/audit/actions', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  renderAuditPage('/audit')

  expect(await screen.findByText('0건')).toBeInTheDocument()
  expect(
    screen.getByText(
      '조회 범위: 권한에 따라 볼 수 있는 실행 이력 · Agent 조회 기록은 제외됩니다.',
    ),
  ).toBeInTheDocument()
})

test('결과로 검색하고 초기화하면 다시 전체를 보여준다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('3건')

  await user.selectOptions(screen.getByLabelText('결과'), 'BLOCKED')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('1건')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(await screen.findByText('3건')).toBeInTheDocument()
})

test('기간으로 검색하면 그 기간의 이력만 보여준다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('3건')

  await user.type(screen.getByLabelText('시작일'), '2026-10-26')
  await user.type(screen.getByLabelText('종료일'), '2026-10-26')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('1건')).toBeInTheDocument()
})

test('시작일이 종료일보다 늦으면 검색하지 않고 안내한다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('3건')

  await user.type(screen.getByLabelText('시작일'), '2026-10-27')
  await user.type(screen.getByLabelText('종료일'), '2026-10-26')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(screen.getByRole('alert')).toHaveTextContent(
    '종료일은 시작일 이후로 선택해 주세요.',
  )
  expect(screen.getByText('3건')).toBeInTheDocument()
})

test('실행 이력 조회가 실패하면 오류 안내를 보여준다', async () => {
  server.use(
    http.get('/api/v1/audit/actions', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: '서버 내부 오류',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 500 },
      ),
    ),
  )
  renderAuditPage('/audit')

  expect(await screen.findByText('서버 내부 오류')).toBeInTheDocument()
})

test('처음 들어온 화면에서 이전 화면으로를 누르면 탐색 화면으로 간다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit')

  await user.click(screen.getByRole('button', { name: '이전 화면으로' }))

  expect(screen.getByText('탐색 화면')).toBeInTheDocument()
})
