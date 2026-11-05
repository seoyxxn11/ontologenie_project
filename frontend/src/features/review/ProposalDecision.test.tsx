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

const proposal = proposalFixtures[0]

test('API 제공 전 결정 버튼을 비활성화하고 사유 입력으로 쓰기 요청을 보내지 않는다', async () => {
  const write = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.post('/api/v1/*', write), http.patch('/api/v1/*', write))
  const user = userEvent.setup()
  render(<ProposalDecision proposal={proposal} />)
  await user.type(
    screen.getByRole('textbox', { name: '검토 사유 (필수)' }),
    '설비 코드 조건을 확인했습니다.',
  )
  for (const name of ['승인', '이 후보 보류', '거절']) {
    const button = screen.getByRole('button', { name })
    expect(button).toBeDisabled()
    await user.click(button)
  }
  expect(
    screen.getByText(/입력한 사유는 저장되지 않습니다/),
  ).toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
})

test('공백 사유를 안내하고 필수 입력·1000자 상한을 적용한다', async () => {
  const user = userEvent.setup()
  render(<ProposalDecision proposal={proposal} />)
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
    render(<ProposalDecision proposal={{ ...proposal, reviewStatus }} />)
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
  render(<ProposalDecision proposal={{ ...proposal, reviewStatus: 'HELD' }} />)
  expect(screen.getByRole('textbox')).toBeEnabled()
  expect(
    screen.getByText(/검사에서 발견한 개별 문제 건의 보류와 구분/),
  ).toBeInTheDocument()
})

test('최신 조회 중·실패 시에는 입력을 차단한다', () => {
  render(<ProposalDecision proposal={proposal} unavailable />)
  expect(screen.getByRole('textbox')).toBeDisabled()
  expect(screen.getByText(/최신 제안 상태를 확인 중이거나/)).toBeInTheDocument()
})

test.each(['RUNNING', 'FAILED'])(
  '최근 검사 %s만으로 과거 성공 검사 여부를 단정하지 않는다',
  (status) => {
    render(
      <ProposalDecision
        proposal={{
          ...proposal,
          latestCheck: { ...proposal.latestCheck!, status },
        }}
      />,
    )
    expect(screen.getByText(/서버에서 최종 확인/)).toBeInTheDocument()
    expect(
      screen.queryByText(/검사를 통과하지 않아 승인할 수 없습니다/),
    ).not.toBeInTheDocument()
  },
)

test('실제 상세 화면에서 제안 개정이 바뀌면 이전 사유를 남기지 않는다', async () => {
  const user = userEvent.setup()
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/admin/rules/303']}>
        <Routes>
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
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
