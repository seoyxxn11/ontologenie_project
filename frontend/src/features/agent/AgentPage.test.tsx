import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test } from 'vitest'
import { resetAgentMocks } from '../../mocks/agentHandlers'
import AgentPage from './AgentPage'

afterEach(() => {
  resetAgentMocks()
})

function renderAgentPage(path = '/agent') {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[path]}>
        <Routes>
          <Route path="/agent" element={<AgentPage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

const SAMPLE_ANSWER = /PR-03의 이번 달 비가동은 2건입니다/
const EMPTY_MESSAGE = '업무에 대해 질문해 주세요. 예: PR-03의 상태를 알려줘'

test('대화 목록을 최근 순으로 보여 주고 가장 최근 대화를 연다', async () => {
  renderAgentPage()

  const list = await screen.findByRole('complementary', { name: '대화방 목록' })
  const items = within(list).getAllByRole('listitem')
  expect(items[0]).toHaveTextContent('새 대화')
  expect(items[1]).toHaveTextContent(
    '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
  )
  expect(
    within(list).getByRole('button', { name: '새 대화', pressed: true }),
  ).toBeInTheDocument()
  expect(await screen.findByText(EMPTY_MESSAGE)).toBeInTheDocument()
})

test('답변의 정보별 결과와 기준 시점을 구분해 보여 준다', async () => {
  renderAgentPage('/agent?conversation=1')

  expect(await screen.findByText(SAMPLE_ANSWER)).toBeInTheDocument()
  expect(screen.getByText('확인됨')).toBeInTheDocument()
  expect(screen.getByText('확인됨 · 추정')).toBeInTheDocument()
  expect(screen.getByText('권한 없음')).toBeInTheDocument()
  expect(screen.getByText('확인하지 못함')).toBeInTheDocument()
  expect(screen.getAllByText('기준 시점 확인 불가')).toHaveLength(2)
  expect(screen.getByText('최신 데이터 반영 중')).toBeInTheDocument()
})

test('질문을 보내면 처리 단계를 보여 주다가 답변으로 바꾼다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=2')
  await screen.findByText(EMPTY_MESSAGE)

  const input = screen.getByLabelText('업무 질문')
  await user.type(input, 'PR-03 비가동 알려줘')
  await user.click(screen.getByRole('button', { name: '보내기 ↑' }))

  expect(await screen.findByText(/답변 준비 중/)).toBeInTheDocument()
  expect(input).toBeDisabled()
  expect(
    await screen.findByText(SAMPLE_ANSWER, {}, { timeout: 10_000 }),
  ).toBeInTheDocument()
  expect(input).toBeEnabled()
  const list = screen.getByRole('complementary', { name: '대화방 목록' })
  await waitFor(() =>
    expect(
      within(list).getByRole('button', { name: 'PR-03 비가동 알려줘' }),
    ).toBeInTheDocument(),
  )
}, 15_000)

test('답변을 끝내지 못하면 실패 안내를 보여 준다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=2')
  await screen.findByText(EMPTY_MESSAGE)

  await user.type(screen.getByLabelText('업무 질문'), '실패 확인용 질문')
  await user.click(screen.getByRole('button', { name: '보내기 ↑' }))

  const alert = await screen.findByRole('alert', {}, { timeout: 10_000 })
  expect(alert).toHaveTextContent('답변을 끝내지 못했어요.')
  expect(alert).toHaveTextContent('LLM 응답 시간 초과')
}, 15_000)

test('새 대화를 만들면 목록에 추가하고 그 대화를 연다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')
  const list = await screen.findByRole('complementary', { name: '대화방 목록' })

  await user.click(within(list).getByRole('button', { name: '+ 새 대화' }))

  await waitFor(() =>
    expect(within(list).getAllByRole('listitem')).toHaveLength(3),
  )
  expect(
    within(list).getByRole('button', { name: '새 대화', pressed: true }),
  ).toBeInTheDocument()
})
