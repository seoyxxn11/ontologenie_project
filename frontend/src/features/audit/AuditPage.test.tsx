import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { mockUser } from '../../mocks/handlers'
import { server } from '../../mocks/node'
import AuditPage from './AuditPage'

async function openDetails(text: string) {
  const user = userEvent.setup()
  const dialog = screen.queryByRole('dialog')
  if (dialog)
    await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  const row = within(screen.getByRole('table', { name: '실행 이력' }))
    .getAllByRole('row')
    .find((row) => row.textContent?.includes(text))
  if (!row) throw Error('실행 행 없음: ' + text)
  await user.click(within(row).getByText('내용 보기'))
  await screen.findByRole('dialog')
}

async function openChanges() {
  const user = userEvent.setup()
  for (const summary of screen.getAllByText(/^실행 전후 비교/)) {
    await user.click(summary)
  }
}

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
  expect(await screen.findByText('총 3건')).toBeInTheDocument()
  expect(await screen.findByDisplayValue('프레스 3호기')).toBeInTheDocument()
  await openDetails('차단')
  expect(
    await screen.findByText(
      '대체 설비 PR-02는 프레스 공정을 처리할 수 없습니다.',
    ),
  ).toBeInTheDocument()
  await openDetails('성공')
  await openChanges()
  expect(
    await screen.findByText('reassignment_plan · id: 501 · 생성'),
  ).toBeInTheDocument()
  // 객체 실행 이력(080)에는 종류 탭이 없다.
  expect(
    screen.queryByRole('group', { name: '실행 이력 종류' }),
  ).not.toBeInTheDocument()
})

test('객체 번호가 없으면 내 실행 이력을 보여준다', async () => {
  renderAuditPage('/audit')

  expect(await screen.findByText('총 4건')).toBeInTheDocument()
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

  expect(await screen.findByText('총 6건')).toBeInTheDocument()
  expect(
    screen.getByText(
      '조회 범위: 관리자에게 허용된 전체 실행 이력 · Agent 조회 기록은 제외됩니다.',
    ),
  ).toBeInTheDocument()
  expect(screen.getAllByText('관리자')).toHaveLength(2)
  expect(screen.getByText('검토·변경 · 객체 유형 수정')).toBeInTheDocument()
  await openDetails('객체 유형 수정')
  await openChanges()
  await screen.findByText('object_type · id: 3 · 수정')
  // 대상 이름(굵게)과 변경 후 값에 같이 나온다.
  expect(screen.getAllByText('프레스 설비').length).toBeGreaterThan(1)
  expect(screen.getByText('object_type #3')).toBeInTheDocument()
  expect(screen.getByText('object_type · id: 3 · 수정')).toBeInTheDocument()
})

test('동작·실행자·대상을 이름으로 보여주고, 이름이 없는 대상은 번호로 보여준다', async () => {
  renderAuditPage('/audit')

  expect(await screen.findByText('총 4건')).toBeInTheDocument()
  expect(
    screen.getAllByText('업무 실행 · 재배정 계획 생성').length,
  ).toBeGreaterThan(0)
  expect(screen.getAllByText('김계획').length).toBeGreaterThan(0)
  expect(screen.getAllByText('프레스 3호기').length).toBeGreaterThan(0)
  expect(screen.getAllByText('객체 #301').length).toBeGreaterThan(0)
  // 수집처럼 이름이 없는 대상은 번호를 굵게 보여 준다.
  expect(screen.getByText('데이터 수집')).toBeInTheDocument()
  expect(screen.getByText('sync_run #77')).toBeInTheDocument()
})

test('종류 탭을 누르면 그 종류의 실행만 보여준다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit')
  await screen.findByText('총 4건')

  await user.click(screen.getByRole('button', { name: '업무 실행' }))

  expect(await screen.findByText('총 3건')).toBeInTheDocument()
  expect(screen.queryByText('sync_run #77')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '업무 실행' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )

  await user.click(screen.getByRole('button', { name: '전체' }))
  expect(await screen.findByText('총 4건')).toBeInTheDocument()
})

test('실패한 실행은 실패 이유를, 권한으로 가린 값은 가림 표시를 보여준다', async () => {
  renderAuditPage('/audit')
  await screen.findByText('총 4건')

  await openDetails('실패')
  expect(await screen.findByText('실패 이유')).toBeInTheDocument()
  expect(
    screen.getByText('정비 시스템 응답 시간이 초과되었습니다.'),
  ).toBeInTheDocument()
  await openDetails('차단')
  await userEvent.setup().click(await screen.findByText('기술 정보 · JSON'))
  expect(
    screen.getByText(/"unitCost": "\(권한으로 가림\)"/),
  ).toBeInTheDocument()
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
  await screen.findByText('총 3건')

  await user.selectOptions(screen.getByLabelText('결과'), 'BLOCKED')
  await user.click(screen.getByRole('button', { name: '검색' }))
  expect(await screen.findByText('총 1건')).toBeInTheDocument()

  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(await screen.findByText('총 3건')).toBeInTheDocument()
})

test('기간으로 검색하면 그 기간의 이력만 보여준다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('총 3건')

  await user.type(screen.getByLabelText('시작일'), '2026-10-26')
  await user.type(screen.getByLabelText('종료일'), '2026-10-26')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(await screen.findByText('총 1건')).toBeInTheDocument()
})

test('시작일이 종료일보다 늦으면 검색하지 않고 안내한다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit?objectId=301')
  await screen.findByText('총 3건')

  await user.type(screen.getByLabelText('시작일'), '2026-10-27')
  await user.type(screen.getByLabelText('종료일'), '2026-10-26')
  await user.click(screen.getByRole('button', { name: '검색' }))

  expect(screen.getByRole('alert')).toHaveTextContent(
    '종료일은 시작일 이후로 선택해 주세요.',
  )
  expect(screen.getByText('총 3건')).toBeInTheDocument()
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

test('직접 접속하거나 종류를 바꿔도 복귀 화살표는 표시하지 않는다', async () => {
  renderAuditPage('/audit')
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
  await userEvent.click(screen.getByRole('button', { name: '업무 실행' }))
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
})

test('데이터 작업을 따로 조회하고 초기화해도 선택 종류를 유지한다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit')
  await screen.findByText('총 4건')
  await user.click(screen.getByRole('button', { name: '데이터 작업' }))
  expect(await screen.findByText('총 1건')).toBeInTheDocument()
  expect(screen.getByText('sync_run #77')).toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '초기화' }))
  expect(screen.getByRole('button', { name: '데이터 작업' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})

test('상세를 닫으면 검색 입력과 목록을 유지하고 호출 버튼에 포커스를 돌린다', async () => {
  const user = userEvent.setup()
  renderAuditPage('/audit')
  await screen.findByText('총 4건')
  await user.type(screen.getByLabelText('시작일'), '2026-10-01')
  const trigger = screen.getAllByRole('button', { name: '내용 보기' })[0]
  await user.click(trigger)
  const dialog = await screen.findByRole('dialog')
  expect(await within(dialog).findByText('데이터 수집 · 성공')).toBeVisible()
  const inputToggle = within(dialog).getByText('입력값', {
    selector: 'summary',
  })
  expect(inputToggle.closest('details')).not.toHaveAttribute('open')
  expect(within(dialog).getByText('처리 결과')).toBeVisible()
  await user.click(inputToggle)
  expect(within(dialog).getByText('MES')).toBeVisible()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(trigger).toHaveFocus()
  expect(screen.getByLabelText('시작일')).toHaveValue('2026-10-01')
  expect(
    within(screen.getByRole('table', { name: '실행 이력' })).getAllByRole(
      'row',
    ),
  ).toHaveLength(5)
})
