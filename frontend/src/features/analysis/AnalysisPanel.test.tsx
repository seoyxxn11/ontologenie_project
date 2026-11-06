import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { resetAnalysisMocks } from '../../mocks/analysisHandlers'
import { server } from '../../mocks/node'
import { resetProposalMocks } from '../../mocks/proposalHandlers'
import { resetSourceMocks } from '../../mocks/sourceHandlers'
import ProposalsPage from '../review/ProposalsPage'

afterEach(() => {
  resetSourceMocks()
  resetAnalysisMocks()
  resetProposalMocks()
})

function renderRules(path = '/admin/rules') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/admin/rules" element={<ProposalsPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function startFromRules(user: ReturnType<typeof userEvent.setup>) {
  await user.click(screen.getByRole('button', { name: 'AI 관계 제안 받기' }))
  const dialog = screen.getByRole('dialog', { name: '분석 대상 선택' })
  await user.click(
    await within(dialog).findByRole('checkbox', { name: 'item_code 선택' }),
  )
  await user.click(
    within(dialog).getByRole('button', { name: '분석하고 후보 받기' }),
  )
}

test('분석을 맡기면 진행 상태를 다시 조회하고 끝나면 이번 분석 후보만 보여준다', async () => {
  const user = userEvent.setup()
  renderRules()
  await startFromRules(user)

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(
    await within(current).findByText('분석 대상: 자료 1개'),
  ).toBeInTheDocument()
  expect(current).toHaveTextContent('ANALYSIS-052')
  // 끝나기 전에는 같은 요청을 다시 보내지 않는다
  expect(
    screen.getByRole('button', { name: 'AI 관계 제안 받기' }),
  ).toBeDisabled()

  expect(
    await within(current).findByText(
      '이번 분석 후보 1건',
      {},
      { timeout: 8000 },
    ),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: 'AI 관계 제안 받기' }),
  ).toBeEnabled()
  const table = await screen.findByRole('table', { name: '검토 제안 목록' })
  expect(within(table).getAllByRole('row')).toHaveLength(2)
}, 15000)

test('전체 후보 보기를 누르면 분석 조건 없이 모든 제안을 보여준다', async () => {
  const user = userEvent.setup()
  renderRules('/admin/rules?analysis=50')

  const table = await screen.findByRole('table', { name: '검토 제안 목록' })
  expect(within(table).getAllByRole('row')).toHaveLength(2)

  await user.click(screen.getByRole('button', { name: '전체 후보 보기' }))

  expect(
    screen.queryByRole('region', { name: '이번 분석' }),
  ).not.toBeInTheDocument()
  await expect
    .poll(
      () =>
        within(
          screen.getByRole('table', { name: '검토 제안 목록' }),
        ).getAllByRole('row').length,
    )
    .toBe(4)
})

test('실패한 분석은 실패 사유를 보여준다', async () => {
  renderRules('/admin/rules?analysis=49')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(await within(current).findByRole('alert')).toHaveTextContent(
    'AI 계정이 이 테이블을 읽을 수 없습니다: erp.employee',
  )
  expect(current).toHaveTextContent('분석에 실패했습니다')
  expect(
    await screen.findByText('조건에 맞는 제안이 없습니다.'),
  ).toBeInTheDocument()
})

test('같은 대상의 분석에 합류했으면 알려준다', async () => {
  renderRules('/admin/rules?analysis=51&joined=1')

  expect(
    await screen.findByText(
      '같은 대상의 분석이 이미 진행 중이라 그 분석에 합류했습니다.',
    ),
  ).toBeInTheDocument()
  await screen.findByRole('table', { name: '검토 제안 목록' })
})

test('최근 분석 목록에서 과거 실행을 골라 그 후보를 본다', async () => {
  const user = userEvent.setup()
  renderRules()
  await screen.findByRole('table', { name: '검토 제안 목록' })

  await user.click(screen.getByRole('button', { name: '최근 분석 보기' }))
  const recent = await screen.findByRole(
    'table',
    { name: '최근 분석' },
    { timeout: 3000 },
  )
  const rows = within(recent).getAllByRole('row')
  expect(rows[1]).toHaveTextContent('ANALYSIS-051')
  expect(rows[1]).toHaveTextContent('완료')
  expect(rows[1]).toHaveTextContent('후보 1건')
  expect(rows[3]).toHaveTextContent('실패')

  await user.click(
    within(recent).getByRole('button', { name: 'ANALYSIS-050 후보 보기' }),
  )

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(
    await within(current).findByText(/ANALYSIS-050/, {}, { timeout: 3000 }),
  ).toBeInTheDocument()
})

test('없는 분석을 열면 서버 안내를 보여준다', async () => {
  renderRules('/admin/rules?analysis=999')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  expect(await within(current).findByRole('alert')).toHaveTextContent(
    '요청한 항목을 찾을 수 없습니다.',
  )
})

test('분석 요청 권한이 없으면 창 안에서 서버 안내를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.post('/api/v1/analyses', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '이 작업을 할 권한이 없습니다.',
            details: {},
            requestId: 'mock-analysis',
          },
        },
        { status: 403 },
      ),
    ),
  )
  renderRules()
  await startFromRules(user)

  const dialog = screen.getByRole('dialog', { name: '분석 대상 선택' })
  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    '이 작업을 할 권한이 없습니다.',
  )
})

test('고른 범위와 다르게 분석한 부분은 안내로 보여준다', async () => {
  renderRules('/admin/rules?analysis=48')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  const notices = await within(current).findByRole('list', {
    name: '분석 범위 안내',
  })
  expect(notices).toHaveTextContent(
    'mes.work_order: 고른 컬럼 old_note이(가) 원천에서 사라져 빼고 분석했습니다.',
  )
  expect(notices).toHaveTextContent(
    'mes.sensor_reading: 행이 많아 앞에서 100,000행만 분석했습니다.',
  )
  expect(current).toHaveTextContent('문서 연결 2건')
  await screen.findByText('조건에 맞는 제안이 없습니다.')
})

test('안내할 것이 없으면 안내 목록을 그리지 않는다', async () => {
  renderRules('/admin/rules?analysis=50')

  const current = await screen.findByRole('region', { name: '이번 분석' })
  await within(current).findByText(/이번 분석 후보/)
  expect(
    within(current).queryByRole('list', { name: '분석 범위 안내' }),
  ).not.toBeInTheDocument()
  await screen.findByRole('table', { name: '검토 제안 목록' })
})
