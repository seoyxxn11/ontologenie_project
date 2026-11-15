import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import ProposalDetail from './ProposalDetail'
import ProposalDecision from './ProposalDecision'
import ProposalHistoryPage from './ProposalHistoryPage'

const proposal = proposalFixtures[0]

function renderDecision(item = proposal, unavailable = false) {
  return render(
    <QueryClientProvider client={new QueryClient()}>
      <MemoryRouter>
        <ProposalDecision
          proposal={item}
          unavailable={unavailable}
          onClearSelection={vi.fn()}
          onRefresh={async () => true}
          historyUrl="/history?view=activity"
        />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

function openDetail() {
  const client = new QueryClient()
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/rules/303']}>
        <Routes>
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
          <Route
            path="/admin/rules/:id/history"
            element={<ProposalHistoryPage />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return client
}

test('사유를 입력하기 전에는 결정 요청을 보내지 않는다', async () => {
  const write = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.post('/api/v1/*', write), http.patch('/api/v1/*', write))
  const user = userEvent.setup()
  renderDecision()
  for (const name of ['승인', '이 후보 보류', '거절']) {
    const button = screen.getByRole('button', { name })
    expect(button).toBeDisabled()
    await user.click(button)
  }
  expect(write).not.toHaveBeenCalled()
})

test('공백 사유를 안내하고 필수 입력·1000자 상한을 적용한다', async () => {
  const user = userEvent.setup()
  renderDecision()
  const reason = screen.getByRole('textbox')
  expect(reason).toBeRequired()
  expect(reason).toHaveAttribute('maxlength', '1000')
  await user.type(reason, '   ')
  await user.tab()
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '검토 사유를 입력해 주세요.',
  )
  await user.clear(reason)
  await user.type(reason, '검토 후 보류')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test.each(['APPROVED', 'REJECTED', 'UNKNOWN'])(
  '이미 결정됐거나 미등록 상태 %s에서는 입력과 재결정을 제공하지 않는다',
  (reviewStatus) => {
    renderDecision({ ...proposal, reviewStatus })
    expect(
      screen.getByText(/이미 결정됐거나 현재 결정할 수 없는/),
    ).toBeInTheDocument()
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: '승인' }),
    ).not.toBeInTheDocument()
  },
)

test('보류 상태는 사유 입력을 허용하고 개별 문제 보류와 구분한다', () => {
  renderDecision({ ...proposal, reviewStatus: 'HELD' })
  expect(screen.getByRole('textbox')).toBeEnabled()
  expect(
    screen.getByText(/검사에서 발견한 개별 문제 건의 보류와 구분/),
  ).toBeInTheDocument()
})

test('최신 조회 중·실패 시에는 입력을 차단한다', () => {
  renderDecision(proposal, true)
  expect(screen.getByRole('textbox')).toBeDisabled()
  expect(screen.getByText(/최신 제안 상태를 확인 중이거나/)).toBeInTheDocument()
})

test.each(['RUNNING', 'FAILED'])(
  '최근 검사 %s만으로 과거 성공 검사 여부를 단정하지 않는다',
  (status) => {
    renderDecision({
      ...proposal,
      latestCheck: { ...proposal.latestCheck!, status },
    })
    expect(screen.getByText(/서버에서 최종 확인/)).toBeInTheDocument()
    expect(
      screen.queryByText(/검사를 통과하지 않아 승인할 수 없습니다/),
    ).not.toBeInTheDocument()
  },
)

test('실제 상세 화면에서 제안 개정이 바뀌면 이전 사유를 남기지 않는다', async () => {
  const user = userEvent.setup()
  const client = openDetail()
  const panel = await screen.findByRole('region', { name: '제안 검토 결정' })
  await user.type(within(panel).getByRole('textbox'), '이전 개정에 대한 검토')
  act(() => {
    client.setQueryData(['proposals', 'detail', 303], {
      ...proposal,
      revision: 3,
    })
  })
  await waitFor(() =>
    expect(
      within(screen.getByRole('region', { name: '제안 검토 결정' })).getByRole(
        'textbox',
      ),
    ).toHaveValue(''),
  )
  expect(
    within(screen.getByRole('region', { name: '제안 검토 결정' })).getByText(
      /개정 3에 대한 결정/,
    ),
  ).toBeInTheDocument()
})

test('선택한 대안은 승인할 때 저장되고 반영 결과·결정 이력으로 이어진다', async () => {
  openDetail()
  const user = userEvent.setup()
  await user.click(
    await screen.findByRole('button', { name: '대안 후보 비교' }),
  )
  await screen.findByText('설비 코드 공백 제거 후 연결')
  await user.click(screen.getByRole('button', { name: '다음 후보 →' }))
  await user.click(screen.getByRole('button', { name: '이 후보 선택' }))
  expect(screen.getByText('승인할 대안 후보 2번 (ID #502)')).toBeInTheDocument()
  await user.type(
    screen.getByRole('textbox', { name: '검토 사유 (필수)' }),
    '코드 대조 완료',
  )
  await user.click(screen.getByRole('button', { name: '승인' }))
  expect(await screen.findByText('반영 대상 1건')).toBeInTheDocument()
  expect(screen.queryByText(/개별 보류/)).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '승인' })).not.toBeInTheDocument()
  await user.click(screen.getByRole('link', { name: '이력' }))
  await user.click(await screen.findByRole('button', { name: '활동 기록' }))
  const historyOption = await screen.findByRole('button', { name: '대안 2' })
  expect(historyOption).toBeInTheDocument()
  expect(screen.getByText('코드 대조 완료')).toBeInTheDocument()
  await user.click(historyOption)
  expect(
    await within(screen.getByRole('dialog')).findByText(
      '설비 코드 대문자 변환 후 연결',
    ),
  ).toBeVisible()
})

test.each([
  ['이 후보 보류', 'HOLD'],
  ['거절', 'REJECT'],
] as const)(
  '%s는 대안을 지정하지 않고 현재 개정으로 한 번만 저장한다',
  async (label, decision) => {
    const write = vi.fn(async ({ request }: { request: Request }) => {
      expect(await request.json()).toEqual({
        revision: 2,
        decision,
        reason: '검토 결과',
      })
      return HttpResponse.json({
        data: {
          decisionId: 703,
          decision,
          reviewStatus: decision === 'HOLD' ? 'HELD' : 'REJECTED',
          applyStatus: 'NOT_APPLIED',
          appliedEntityIds: [],
          actionExecutionId: 1703,
        },
      })
    })
    server.use(http.post('/api/v1/proposals/303/decisions', write))
    openDetail()
    const user = userEvent.setup()
    await user.click(
      await screen.findByRole('button', { name: '대안 후보 비교' }),
    )
    await screen.findByText('설비 코드 공백 제거 후 연결')
    await user.click(screen.getByRole('button', { name: '이 후보 선택' }))
    await user.type(screen.getByRole('textbox'), '  검토 결과  ')
    await user.dblClick(screen.getByRole('button', { name: label }))
    expect(
      await screen.findByText(/검토 결정을 저장했습니다/),
    ).toBeInTheDocument()
    expect(write).toHaveBeenCalledOnce()
    expect(screen.getByRole('textbox')).toHaveValue('')
  },
)

test('개정 충돌은 다시 조회하기 전까지 저장을 막고 이전 입력과 후보를 폐기한다', async () => {
  let revision = 2
  const write = vi.fn(() => {
    revision = 3
    return HttpResponse.json(
      {
        error: {
          code: 'REVISION_CONFLICT',
          message: '개정이 변경됐습니다.',
          details: {},
        },
      },
      { status: 409 },
    )
  })
  server.use(
    http.get('/api/v1/proposals/303', () =>
      HttpResponse.json({ data: { ...proposal, revision } }),
    ),
    http.post('/api/v1/proposals/303/decisions', write),
  )
  openDetail()
  const user = userEvent.setup()
  await user.click(
    await screen.findByRole('button', { name: '대안 후보 비교' }),
  )
  await screen.findByText('설비 코드 공백 제거 후 연결')
  await user.click(screen.getByRole('button', { name: '이 후보 선택' }))
  await user.type(screen.getByRole('textbox'), '개정 2 사유')
  await user.click(screen.getByRole('button', { name: '승인' }))
  expect(await screen.findByText('개정이 변경됐습니다.')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '승인' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '최신 제안 다시 확인' }))
  await screen.findByText(/개정 3에 대한 결정/)
  expect(screen.getByRole('textbox')).toHaveValue('')
  expect(screen.queryByText(/승인할 대안 후보/)).not.toBeInTheDocument()
  expect(write).toHaveBeenCalledOnce()
})

test.each([
  [403, 'FORBIDDEN', '검토 결정 권한이 없습니다.', true],
  [
    422,
    'REVIEW_VALIDATION_FAILED',
    '같은 개정의 성공한 검사가 필요합니다.',
    false,
  ],
  [422, 'APPLY_FAILED', '반영 변경을 취소했습니다.', false],
] as const)(
  '결정 실패 %s %s 응답을 표시한다',
  async (status, code, message, blocked) => {
    const write = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code,
            message,
            details:
              code === 'APPLY_FAILED'
                ? { reason: '반영 대상이 변경됐습니다.' }
                : {},
          },
        },
        { status },
      ),
    )
    server.use(http.post('/api/v1/proposals/303/decisions', write))
    renderDecision()
    const user = userEvent.setup()
    await user.type(screen.getByRole('textbox'), '검토 결과')
    await user.click(screen.getByRole('button', { name: '승인' }))
    expect(await screen.findByText(message)).toBeInTheDocument()
    if (code === 'APPLY_FAILED')
      expect(screen.getByText('반영 대상이 변경됐습니다.')).toBeInTheDocument()
    if (blocked) expect(screen.getByRole('textbox')).toBeDisabled()
    else expect(screen.getByRole('textbox')).toBeEnabled()
    expect(
      screen.queryByText(/검토 결정을 저장했습니다/),
    ).not.toBeInTheDocument()
    expect(write).toHaveBeenCalledOnce()
  },
)

test('응답을 받지 못하면 자동 재시도하지 않고 활동 기록 확인을 안내한다', async () => {
  const write = vi.fn(() => HttpResponse.error())
  server.use(http.post('/api/v1/proposals/303/decisions', write))
  renderDecision()
  const user = userEvent.setup()
  await user.type(screen.getByRole('textbox'), '검토 결과')
  await user.click(screen.getByRole('button', { name: '이 후보 보류' }))
  expect(
    await screen.findByRole('link', { name: '활동 기록' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '이 후보 보류' })).toBeDisabled()
  expect(write).toHaveBeenCalledOnce()
})
