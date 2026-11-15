import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import type { ReactNode } from 'react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, onTestFinished, test } from 'vitest'
import { resetAgentMocks } from '../../mocks/agentHandlers'
import { demoAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { AuthContext } from '../auth/auth-context'
import type { Account } from '../auth/types'
import AgentPage from './AgentPage'
import type { AgentAction } from './types'

// 테스트마다 시작 전에 가짜 대화를 되돌린다. 앞 테스트가 끝날 때 아직 처리 중이던 요청은 공통 설정(src/test/setup.ts)이 먼저 기다리므로,
// 끝나서 되돌린 뒤에 늦은 요청이 대화를 다시 바꾸는 일이 없다.
beforeEach(() => {
  resetAgentMocks()
})

// 이 역할로 로그인한 상태를 넣는다. 기본은 생산기획자(데모 계정).
function SignedInAs({
  roleCode,
  children,
}: {
  roleCode: Account['roleCode']
  children: ReactNode
}) {
  return (
    <AuthContext.Provider
      value={{
        account: { ...demoAccount, roleCode },
        pending: false,
        error: null,
        refresh: async () => ({ error: null }),
        signIn: async () => {},
        signOut: async () => {},
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

function renderAgentPage(
  path = '/agent',
  roleCode: Account['roleCode'] = 'PLANNER',
) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SignedInAs roleCode={roleCode}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route path="/agent" element={<AgentPage />} />
          </Routes>
        </MemoryRouter>
      </SignedInAs>
    </QueryClientProvider>,
  )
}

const SAMPLE_ANSWER = /PR-03의 이번 달 비가동은 2건입니다/
const EMPTY_MESSAGE = '어떤 업무를 확인할까요?'

test('대화 목록을 접고 펴도 질문·답변·열린 출처를 유지한다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')
  await screen.findByText(SAMPLE_ANSWER)
  const input = screen.getByLabelText('업무 질문')
  await user.type(input, '정비 기록을 다시 확인해줘')
  await user.click(screen.getByText('출처 및 조회 상태'))
  const evidence = screen.getByText('출처 및 조회 상태').closest('details')!
  await user.click(screen.getByRole('button', { name: '대화 목록 접기' }))
  expect(
    screen.queryByRole('complementary', { name: '대화방 목록' }),
  ).toBeNull()
  expect(screen.getByText(SAMPLE_ANSWER)).toBeVisible()
  expect(input).toHaveValue('정비 기록을 다시 확인해줘')
  expect(evidence).toHaveAttribute('open')
  await user.click(screen.getByRole('button', { name: '대화 목록 펼치기' }))
  expect(
    screen.getByRole('complementary', { name: '대화방 목록' }),
  ).toBeVisible()
  expect(input).toHaveValue('정비 기록을 다시 확인해줘')
  expect(evidence).toHaveAttribute('open')
})

test('아이콘 새 대화를 열고 돌아오면 대화별 초안을 복원하고 같은 새 대화의 질문은 지우지 않는다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')
  await screen.findByText(SAMPLE_ANSWER)
  await user.type(screen.getByLabelText('업무 질문'), '기존 대화 초안')
  const list = screen.getByRole('complementary', { name: '대화방 목록' })
  const create = within(
    within(list).getByRole('heading', { name: '대화' }).parentElement!,
  ).getByRole('button', { name: '새 대화' })
  expect(create.textContent).toBe('')
  expect(create.querySelector('svg')).not.toBeNull()
  await user.click(create)
  await screen.findByText(EMPTY_MESSAGE)
  await waitFor(() => expect(screen.getByLabelText('업무 질문')).toHaveFocus())
  await user.type(screen.getByLabelText('업무 질문'), '새 대화 초안')
  const rows = within(list).getAllByRole('listitem')
  const created = within(rows[0]).getByRole('button', {
    name: '새 대화',
    pressed: true,
  })
  await user.click(create)
  expect(screen.getByLabelText('업무 질문')).toHaveValue('새 대화 초안')
  expect(within(list).getAllByRole('listitem')).toHaveLength(rows.length)
  await user.click(
    within(list).getByRole('button', {
      name: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
    }),
  )
  expect(await screen.findByText(SAMPLE_ANSWER)).toBeVisible()
  expect(screen.getByLabelText('업무 질문')).toHaveValue('기존 대화 초안')
  await user.click(created)
  expect(await screen.findByText(EMPTY_MESSAGE)).toBeVisible()
  expect(screen.getByLabelText('업무 질문')).toHaveValue('새 대화 초안')
  expect(screen.queryByText(/\/ 2,000자/)).toBeNull()
  expect(screen.getByLabelText('업무 질문')).toHaveAttribute(
    'maxlength',
    '2000',
  )
})

test('객체에서 이어온 질문은 다른 대화에 다녀와도 초안과 참고 객체를 유지한다', async () => {
  const user = userEvent.setup()
  const sent = recordQuestionBodies(2)
  renderEnteredFromObject()
  const input = await screen.findByLabelText('업무 질문')
  await user.clear(input)
  await user.type(input, '프레스 3호기의 정비 이력을 알려줘')
  const list = screen.getByRole('complementary', { name: '대화방 목록' })
  await user.click(
    within(list).getByRole('button', {
      name: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
    }),
  )
  await screen.findByText(SAMPLE_ANSWER)
  await user.click(
    within(list).getByRole('button', { name: '새 대화', pressed: false }),
  )
  const restored = await screen.findByLabelText('업무 질문')
  expect(restored).toHaveValue('프레스 3호기의 정비 이력을 알려줘')
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await waitFor(() =>
    expect(sent).toEqual([
      {
        content: '프레스 3호기의 정비 이력을 알려줘',
        referenceObjectIds: [301],
      },
    ]),
  )
})

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

test('전송 응답 전에 다른 대화를 다녀와도 중복 제출을 막고 성공한 초안은 비운다', async () => {
  const user = userEvent.setup()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  const sent = recordQuestionBodies(2)
  server.use(
    http.post('/api/v1/agent/conversations/2/messages', async () => {
      await held
    }),
  )
  onTestFinished(release)
  try {
    renderAgentPage('/agent?conversation=2')
    await screen.findByText(EMPTY_MESSAGE)
    await user.type(screen.getByLabelText('업무 질문'), '전송 중인 질문')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    const list = screen.getByRole('complementary', { name: '대화방 목록' })
    await user.click(
      within(list).getByRole('button', {
        name: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
      }),
    )
    await screen.findByText(SAMPLE_ANSWER)
    await user.click(
      within(list).getByRole('button', { name: '새 대화', pressed: false }),
    )
    const input = await screen.findByLabelText('업무 질문')
    expect(input).toHaveValue('전송 중인 질문')
    expect(input).toBeDisabled()
    expect(screen.getByRole('button', { name: '보내기' })).toBeDisabled()
    release()
    await waitFor(() => expect(input).toHaveValue(''))
    expect(sent).toHaveLength(1)
  } finally {
    release()
  }
})

test('객체 질문을 전송하는 중 대화를 왕복해도 첫 질문의 참고 객체를 다시 쓰지 않는다', async () => {
  const user = userEvent.setup()
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  const sent = recordQuestionBodies(2)
  server.use(
    http.post('/api/v1/agent/conversations/2/messages', async () => {
      await held
    }),
  )
  onTestFinished(release)
  try {
    renderEnteredFromObject()
    await user.click(await screen.findByRole('button', { name: '보내기' }))
    const list = screen.getByRole('complementary', { name: '대화방 목록' })
    await user.click(
      within(list).getByRole('button', {
        name: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
      }),
    )
    await screen.findByText(SAMPLE_ANSWER)
    await user.click(
      within(list).getByRole('button', { name: '새 대화', pressed: false }),
    )
    const input = await screen.findByLabelText('업무 질문')
    expect(input).toBeDisabled()
    release()
    await waitFor(() => expect(input).toHaveValue(''))
    await screen.findByText(SAMPLE_ANSWER)
    await waitFor(() => expect(input).toBeEnabled())
    await user.type(input, '06번 설비의 실행 이력을 알려줘')
    await user.click(screen.getByRole('button', { name: '보내기' }))
    await waitFor(() => expect(sent).toHaveLength(2))
    expect(sent[0]).toEqual({
      content: '프레스 3호기에 연결된 업무 정보를 알려줘',
      referenceObjectIds: [301],
    })
    expect(sent[1]).toEqual({ content: '06번 설비의 실행 이력을 알려줘' })
  } finally {
    release()
  }
}, 15000)

test('답변은 바로 보이고 출처와 조회 상태는 기본으로 접혀 있으며 펼치고 접을 수 있다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')

  const answer = await screen.findByText(SAMPLE_ANSWER)
  expect(answer).toBeVisible()
  const toggle = screen.getByText('출처 및 조회 상태')
  const details = toggle.closest('details')!
  expect(details).not.toHaveAttribute('open')
  expect(within(details).getByText('4건')).toBeVisible()
  expect(
    within(details).getByText('권한 제한 1건 · 미확인 1건 · 추정 1건'),
  ).toBeVisible()
  const snapshotNotice = within(details).getByText(
    '응답 시점의 값 · 이후 수정은 다시 질문해 확인',
  )
  expect(snapshotNotice).not.toBeVisible()
  expect(screen.getByText('최신 데이터 반영 중')).toBeVisible()

  await user.click(toggle)
  expect(details).toHaveAttribute('open')
  // 정보별 결과는 정보 하나가 한 줄인 표다(머리줄 + 4줄).
  expect(
    within(screen.getByRole('table', { name: '정보별 결과' })).getAllByRole(
      'row',
    ),
  ).toHaveLength(5)
  expect(screen.getByText('확인됨')).toBeInTheDocument()
  expect(screen.getByText('확인됨 · 추정')).toBeInTheDocument()
  expect(screen.getByText('권한 없음')).toBeInTheDocument()
  expect(screen.getByText('확인하지 못함')).toBeInTheDocument()
  expect(screen.getAllByText('기준 시점 확인 불가')).toHaveLength(2)
  expect(snapshotNotice).toBeVisible()

  await user.click(toggle)
  expect(details).not.toHaveAttribute('open')
  expect(snapshotNotice).not.toBeVisible()
  expect(answer).toBeVisible()
})

test('질문을 보내면 처리 단계를 보여 주다가 답변으로 바꾼다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=2')
  await screen.findByText(EMPTY_MESSAGE)

  const input = screen.getByLabelText('업무 질문')
  await user.type(input, 'PR-03 비가동 알려줘')
  await user.click(screen.getByRole('button', { name: '보내기' }))

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
  await user.click(screen.getByRole('button', { name: '보내기' }))

  const alert = await screen.findByRole('alert', {}, { timeout: 10_000 })
  expect(alert).toHaveTextContent('답변을 끝내지 못했어요.')
  expect(alert).toHaveTextContent('LLM 응답 시간 초과')
}, 15_000)

test('새 대화를 만들면 목록에 추가하고 그 대화를 연다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')
  const list = await screen.findByRole('complementary', { name: '대화방 목록' })

  await user.click(
    within(
      within(list).getByRole('heading', { name: '대화' }).parentElement!,
    ).getByRole('button', { name: '새 대화' }),
  )

  await waitFor(() =>
    expect(within(list).getAllByRole('listitem')).toHaveLength(3),
  )
  // 목록이 먼저 늘고 선택 표시는 그 뒤에 바뀐다. 바쁠 때는 바로 찾으면 아직 없어 실패한다
  expect(
    await within(list).findByRole('button', { name: '새 대화', pressed: true }),
  ).toBeInTheDocument()
})

// 대화(기본 2)로 보낸 질문 본문을 모은다. 테스트가 끝나면(실패해도) 이 테스트의 리스너만 지운다.
function recordQuestionBodies(conversationId = 2) {
  const bodies: unknown[] = []
  const recordBody = ({ request }: { request: Request }) => {
    if (
      request.method === 'POST' &&
      request.url.endsWith(`/agent/conversations/${conversationId}/messages`)
    ) {
      void request
        .clone()
        .json()
        .then((body) => bodies.push(body))
    }
  }
  server.events.on('request:start', recordBody)
  onTestFinished(() => {
    server.events.removeListener('request:start', recordBody)
  })
  return bodies
}

// Explorer 객체 상세의 [질문하기]로 대화(기본 2)에 들어온 상태로 그린다(152·150).
function renderEnteredFromObject(
  conversationId = 2,
  queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  }),
  roleCode: Account['roleCode'] = 'PLANNER',
) {
  render(
    <QueryClientProvider client={queryClient}>
      <SignedInAs roleCode={roleCode}>
        <MemoryRouter
          initialEntries={[
            {
              pathname: '/agent',
              search: `?conversation=${conversationId}`,
              state: {
                conversationId,
                referenceObjectId: 301,
                referenceObjectName: '프레스 3호기',
                referenceObjectTypeName: '설비',
                draft: '프레스 3호기에 연결된 업무 정보를 알려줘',
              },
            },
          ]}
        >
          <Routes>
            <Route path="/agent" element={<AgentPage />} />
          </Routes>
        </MemoryRouter>
      </SignedInAs>
    </QueryClientProvider>,
  )
}

test('객체에서 들어오면 질문을 미리 채우고 첫 질문에 그 객체를 함께 보낸다', async () => {
  const user = userEvent.setup()
  const sent = recordQuestionBodies()
  renderEnteredFromObject()

  expect(await screen.findByLabelText('업무 질문')).toHaveValue(
    '프레스 3호기에 연결된 업무 정보를 알려줘',
  )
  expect(
    screen.queryByRole('list', { name: '추천 질문' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '보내기' }))

  await waitFor(() =>
    expect(sent).toEqual([
      {
        content: '프레스 3호기에 연결된 업무 정보를 알려줘',
        referenceObjectIds: [301],
      },
    ]),
  )
})

test('참고 객체는 첫 질문에만 보내고, 다음 질문부터는 보내지 않는다', async () => {
  const user = userEvent.setup()
  const sent = recordQuestionBodies()
  renderEnteredFromObject()

  await user.click(await screen.findByRole('button', { name: '보내기' }))
  // 첫 답변이 다 끝날 때까지 기다린다(보낸 직후 입력란이 잠깐 열리는 순간과 구분).
  const input = screen.getByLabelText('업무 질문')
  expect(await screen.findByText(SAMPLE_ANSWER)).toBeInTheDocument()
  await waitFor(() => expect(input).toBeEnabled())
  // 미리 채운 질문을 다시 채우지 않고, 대화 중 추천 질문은 숨긴다.
  expect(input).toHaveValue('')
  expect(
    screen.queryByRole('list', { name: '추천 질문' }),
  ).not.toBeInTheDocument()

  await user.type(input, '06번의 실행 이력은?')
  await user.click(screen.getByRole('button', { name: '보내기' }))

  // 다른 객체를 물어도 처음 객체(301)로 답하지 않게, 다음 질문에는 참고 객체를 붙이지 않는다.
  await waitFor(() => expect(sent).toHaveLength(2))
  expect(sent[1]).toEqual({ content: '06번의 실행 이력은?' })
})

test('추천 질문 없이 직접 입력하고, 답변을 기다리는 동안에는 입력할 수 없다', async () => {
  const user = userEvent.setup()
  renderEnteredFromObject()
  const input = await screen.findByLabelText('업무 질문')
  expect(
    screen.queryByRole('list', { name: '추천 질문' }),
  ).not.toBeInTheDocument()
  await user.clear(input)
  await user.type(input, '프레스 3호기의 최근 실행 이력을 알려줘')
  await user.click(screen.getByRole('button', { name: '보내기' }))
  expect(await screen.findByText(/^답변 준비 중/)).toBeInTheDocument()
  expect(input).toBeDisabled()
})

test('첫 질문을 보내는 사이 다른 대화로 옮기면, 답이 와도 원래 대화로 되돌아가지 않는다', async () => {
  const user = userEvent.setup()
  // 첫 질문 응답을 붙잡아 두었다가 풀어 준다. 아무것도 돌려주지 않으면 원래 가짜 처리기가 이어서 답한다.
  let release = () => {}
  const held = new Promise<void>((resolve) => {
    release = resolve
  })
  server.use(
    http.post('/api/v1/agent/conversations/2/messages', async () => {
      await held
    }),
  )
  // 중간에 실패해도 붙잡은 요청을 이 테스트 안에서 풀어, 공통 정리(afterEach)보다 늦게 끝나지 않게 한다.
  try {
    renderEnteredFromObject()

    await user.click(await screen.findByRole('button', { name: '보내기' }))
    const list = screen.getByRole('complementary', { name: '대화방 목록' })
    await user.click(
      within(list).getByRole('button', {
        name: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
      }),
    )
    expect(await screen.findByText(SAMPLE_ANSWER)).toBeInTheDocument()
    release()
    // 응답을 처리해 목록을 다시 읽으면 대화 2의 제목이 첫 질문으로 바뀐다. 이때는 보낸 뒤 처리가 다 끝나 있다.
    expect(
      await within(list).findByRole('button', {
        name: '프레스 3호기에 연결된 업무 정보를 알려줘',
      }),
    ).toBeInTheDocument()

    // 응답을 받은 뒤에도 옮겨 간 대화가 그대로 열려 있다.
    expect(
      within(list).getByRole('button', {
        name: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
        pressed: true,
      }),
    ).toBeInTheDocument()
    expect(screen.getByText(SAMPLE_ANSWER)).toBeInTheDocument()
  } finally {
    release()
  }
})

test('이미 질문이 있는 대화에 남은 기록으로 들어오면 참고 객체를 쓰지 않는다', async () => {
  const user = userEvent.setup()
  const sent = recordQuestionBodies(1)
  // 첫 질문을 보내는 사이 다른 대화로 옮겼다가 뒤로 가기로 돌아온 경우처럼, 질문이 있는 대화 1에 참고 객체 기록이 남아 있다.
  // 떠나기 전에 읽은 빈 목록도 캐시에 남아 있다. 이 낡은 목록이 아니라 새로 읽은 목록으로 판단해야 한다.
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  queryClient.setQueryData(['agent', 'messages', 1], [])
  renderEnteredFromObject(1, queryClient)

  expect(await screen.findByText(SAMPLE_ANSWER)).toBeInTheDocument()
  const input = await screen.findByLabelText('업무 질문')
  expect(input).toHaveValue('')
  expect(
    screen.queryByRole('list', { name: '추천 질문' }),
  ).not.toBeInTheDocument()

  await user.type(input, '06번의 실행 이력은?')
  await user.click(screen.getByRole('button', { name: '보내기' }))

  await waitFor(() =>
    expect(sent).toEqual([{ content: '06번의 실행 이력은?' }]),
  )
})

test('질문을 보내면 메시지 영역을 맨 아래로 내리고, 위로 올려 읽는 중이면 끌어내리지 않는다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')
  expect(await screen.findByText(SAMPLE_ANSWER)).toBeInTheDocument()

  // 테스트 환경은 높이를 계산하지 않으므로, 내용 높이 1000·보이는 높이 300인 영역으로 둔다.
  const log = screen.getByRole('log', { name: '대화 내용' })
  let top = 0
  const scrolledTo: number[] = []
  Object.defineProperty(log, 'scrollHeight', { get: () => 1000 })
  Object.defineProperty(log, 'clientHeight', { get: () => 300 })
  Object.defineProperty(log, 'scrollTop', {
    get: () => top,
    set: (value: number) => {
      top = value
      scrolledTo.push(value)
    },
  })

  const input = screen.getByLabelText('업무 질문')
  await user.type(input, '06번의 실행 이력은?{Enter}')
  await waitFor(() => expect(scrolledTo).toContain(1000))

  // 답을 기다리는 동안 위로 올려 이전 내용을 읽으면, 답이 와도 맨 아래로 끌어내리지 않는다.
  top = 0
  fireEvent.scroll(log)
  const before = scrolledTo.length
  await waitFor(() => expect(input).toBeEnabled())
  expect(scrolledTo.slice(before)).not.toContain(1000)
})

test('Enter로 질문을 보내고, Shift+Enter와 한글 조합 중 Enter로는 보내지 않는다', async () => {
  const user = userEvent.setup()
  const sent = recordQuestionBodies()
  renderAgentPage('/agent?conversation=2')

  const input = await screen.findByLabelText('업무 질문')
  await user.type(input, 'PR-03 상태{Shift>}{Enter}{/Shift}알려줘')
  expect(input).toHaveValue('PR-03 상태\n알려줘')

  // 한글을 조합하는 중에 누른 Enter는 글자를 끝내는 키라 보내지 않는다.
  // 보내기는 비동기로 일어나므로 잠시 기다린 뒤 요청이 없었는지 본다.
  fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
  await new Promise((resolve) => setTimeout(resolve, 300))
  expect(sent).toEqual([])
  expect(input).toHaveValue('PR-03 상태\n알려줘')

  await user.keyboard('{Enter}')
  await waitFor(() => expect(sent).toEqual([{ content: 'PR-03 상태\n알려줘' }]))
})

test('참고 객체 없이 시작해도 추천 질문 없이 빈 입력란을 보여 준다', async () => {
  renderAgentPage('/agent?conversation=2', 'VIEWER')
  expect(await screen.findByLabelText('업무 질문')).toHaveValue('')
  expect(
    screen.queryByRole('list', { name: '추천 질문' }),
  ).not.toBeInTheDocument()
})

test('이름·유형이 없는 예전 기록으로 들어오면 참고 객체 없이 시작한다', async () => {
  const user = userEvent.setup()
  const sent = recordQuestionBodies()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <SignedInAs roleCode="PLANNER">
        <MemoryRouter
          initialEntries={[
            {
              pathname: '/agent',
              search: '?conversation=2',
              // 150 이전에 만든 기록에는 이름·유형이 없다.
              state: {
                conversationId: 2,
                referenceObjectId: 301,
                draft: '프레스 3호기에 연결된 업무 정보를 알려줘',
              },
            },
          ]}
        >
          <Routes>
            <Route path="/agent" element={<AgentPage />} />
          </Routes>
        </MemoryRouter>
      </SignedInAs>
    </QueryClientProvider>,
  )

  expect(await screen.findByLabelText('업무 질문')).toHaveValue('')
  expect(screen.queryByText(/undefined/)).not.toBeInTheDocument()

  await user.type(screen.getByLabelText('업무 질문'), '설비 상태 알려줘')
  await user.click(screen.getByRole('button', { name: '보내기' }))
  await waitFor(() => expect(sent).toEqual([{ content: '설비 상태 알려줘' }]))
})

test('답변의 정보별 결과에서 출처 객체와 실행 이력으로 이동할 수 있다', async () => {
  const user = userEvent.setup()
  server.use(
    http.get('/api/v1/agent/conversations/:id/messages', () =>
      HttpResponse.json({
        data: [
          {
            id: 900,
            conversationId: 1,
            sender: 'AGENT',
            content: '프레스 3호기의 최근 실행 이력은 1건입니다.',
            context: {},
            status: 'SUCCEEDED',
            errorMessage: null,
            answerMeta: {
              syncStatus: 'NONE',
              results: [
                {
                  label: '실행 이력',
                  toolCode: 'OBJECT_EXECUTIONS',
                  status: 'SUCCEEDED',
                  resultCategory: 'FOUND',
                  sources: [{ objectId: 301 }, { objectId: 301 }],
                  dataAsOf: null,
                  estimated: false,
                  errorMessage: null,
                },
              ],
            },
            createdAt: '2026-11-06T01:00:00Z',
          },
        ],
        meta: { page: 0, size: 100, totalElements: 1, hasNext: false },
      }),
    ),
  )
  renderAgentPage('/agent?conversation=1')

  await user.click(await screen.findByText('출처 및 조회 상태'))
  expect(
    await screen.findByRole('link', { name: '#301 열기 ↗' }),
  ).toHaveAttribute('href', '/explorer?object=301')
  expect(screen.getByRole('link', { name: '실행 이력 ↗' })).toHaveAttribute(
    'href',
    '/audit?objectId=301',
  )
  expect(
    screen.getByText('응답 시점의 값 · 이후 수정은 다시 질문해 확인'),
  ).toBeInTheDocument()
})

test('Agent 답변의 마크다운은 그려 보이고, 사용자 질문은 글자 그대로 보인다', async () => {
  server.use(
    http.get('/api/v1/agent/conversations/:id/messages', () =>
      HttpResponse.json({
        data: [
          {
            id: 899,
            conversationId: 1,
            sender: 'USER',
            content: '**굵게** 써도 되나요?',
            context: {},
            status: null,
            errorMessage: null,
            answerMeta: {},
            createdAt: '2026-11-11T01:00:00Z',
          },
          {
            id: 900,
            conversationId: 1,
            sender: 'AGENT',
            content: [
              '# 이력 요약',
              '프레스 6호기의 **최근 이력**입니다.',
              '- 11-09 실패\n- 11-03 성공',
              '| 날짜 | 결과 |\n|:---|:---:|\n| 11-09 | 실패 |',
              '![추적 이미지](https://example.com/a.png?d=PR-06)',
              '기간 11/03~11/09에 3~5건 실패',
              '[객체 보기](/explorer?object=301) [외부 상세](https://example.com/?d=PR-06)',
              '<b>태그</b>',
              '```text\n긴 식별자와 원문은 코드 영역에서 확인합니다.\n```',
            ].join('\n\n'),
            context: {},
            status: 'SUCCEEDED',
            errorMessage: null,
            answerMeta: { syncStatus: 'NONE', results: [] },
            createdAt: '2026-11-11T01:00:01Z',
          },
        ],
        meta: { page: 0, size: 100, totalElements: 2, hasNext: false },
      }),
    ),
  )
  renderAgentPage('/agent?conversation=1')

  const answer = await screen.findByRole('article', { name: 'Agent 답변' })
  expect(
    within(answer).getByRole('heading', { name: '이력 요약', level: 3 }),
  ).toBeInTheDocument()
  expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
  expect(
    within(answer).getByRole('region', { name: '답변 코드' }),
  ).toHaveAttribute('tabindex', '0')
  expect(
    within(answer).queryByText('출처 및 조회 상태'),
  ).not.toBeInTheDocument()
  // 별표 대신 굵은 글씨로, "- " 줄은 목록으로 그린다.
  expect(within(answer).getByText('최근 이력').tagName).toBe('STRONG')
  expect(answer).not.toHaveTextContent('**')
  expect(
    within(within(answer).getByRole('list')).getAllByRole('listitem'),
  ).toHaveLength(2)
  // 표는 표로 그리고, 열 정렬(:---:)을 지킨다.
  const table = within(answer).getByRole('table')
  expect(within(table).getAllByRole('columnheader')[1]).toHaveStyle({
    textAlign: 'center',
  })
  // 이미지는 그리지 않아 외부 주소로 요청이 나가지 않고, 대체 글자만 보인다.
  expect(answer.querySelector('img')).toBeNull()
  expect(answer).toHaveTextContent('추적 이미지')
  // 물결표 하나로 쓴 범위는 취소선으로 긋지 않는다.
  expect(answer.querySelector('del')).toBeNull()
  expect(answer).toHaveTextContent('기간 11/03~11/09에 3~5건 실패')
  // 앱 안 주소만 링크로 만들고, 외부 주소는 글자만 보인다.
  expect(
    within(answer).getByRole('link', { name: '객체 보기' }),
  ).toHaveAttribute('href', '/explorer?object=301')
  expect(
    within(answer).queryByRole('link', { name: '외부 상세' }),
  ).not.toBeInTheDocument()
  expect(answer).toHaveTextContent('외부 상세')
  // AI가 보낸 HTML 태그는 요소로 만들지 않고 글자로 보인다.
  expect(answer.querySelector('b')).toBeNull()
  expect(answer).toHaveTextContent('<b>태그</b>')
  // 사용자가 쓴 별표는 꾸미지 않고 그대로 보여 준다.
  expect(screen.getByText(/\*\*굵게\*\* 써도 되나요\?/)).toBeInTheDocument()
})

test('실행 이력 도구가 아닌 결과에는 실행 이력 링크를 달지 않는다', async () => {
  const user = userEvent.setup()
  renderAgentPage('/agent?conversation=1')

  await user.click(await screen.findByText('출처 및 조회 상태'))
  expect(
    await screen.findByRole('link', { name: '#4101 열기 ↗' }),
  ).toHaveAttribute('href', '/explorer?object=4101')
  expect(
    screen.queryByRole('link', { name: '실행 이력 ↗' }),
  ).not.toBeInTheDocument()
})

// 대화 1에 Agent 답변 하나만 있고, 그 답변이 실행한 작업이 actions인 상태로 바꾼다(160).
// failed면 Action을 실행한 뒤 답변을 끝내지 못한 경우다.
function mockAnswerWithActions(actions: AgentAction[], failed = false) {
  server.use(
    http.get('/api/v1/agent/conversations/:id/messages', () =>
      HttpResponse.json({
        data: [
          {
            id: 901,
            conversationId: 1,
            sender: 'AGENT',
            content: failed
              ? '답변을 끝내지 못했습니다. 잠시 후 다시 질문해 주세요.'
              : '재배정 계획 요청을 처리했습니다.',
            context: {},
            status: failed ? 'FAILED' : 'SUCCEEDED',
            errorMessage: failed ? 'AI 응답 실패' : null,
            answerMeta: { syncStatus: 'NONE', results: [], actions },
            createdAt: '2026-11-09T01:00:00Z',
          },
        ],
        meta: { page: 0, size: 100, totalElements: 1, hasNext: false },
      }),
    ),
  )
}

test('재배정 계획을 만든 답변 안에서 계획을 펼치고 접는다', async () => {
  const user = userEvent.setup()
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'SUCCEEDED',
      executionId: 512,
      result: { planId: 3, itemCount: 1 },
      blockedReason: null,
    },
  ])
  renderAgentPage('/agent?conversation=1')

  expect(
    await screen.findByText('계획 #3 · 실제 MES 배정은 바뀌지 않아요'),
  ).toBeInTheDocument()
  // 펼치기 전에는 계획을 불러오지 않는다.
  expect(
    screen.queryByRole('region', { name: '재배정 계획 #3' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '계획 펼치기' }))

  const plan = await screen.findByRole('region', { name: '재배정 계획 #3' })
  expect(await within(plan).findByText('WO-101')).toBeInTheDocument()
  expect(within(plan).getByText('남은 공정 전체')).toBeInTheDocument()
  expect(
    within(plan).getByText('같은 프레스 공정이고 지금 가동 중인 설비'),
  ).toBeInTheDocument()
  expect(within(plan).getByRole('link', { name: '#302' })).toHaveAttribute(
    'href',
    '/explorer?object=302',
  )

  await user.click(screen.getByRole('button', { name: '계획 접기' }))
  expect(
    screen.queryByRole('region', { name: '재배정 계획 #3' }),
  ).not.toBeInTheDocument()
})

test('막힌 작업은 이유를 보여 주고 계획 펼치기를 달지 않는다', async () => {
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'BLOCKED',
      executionId: 513,
      result: {},
      blockedReason: 'PLAN_CREATE 권한이 없습니다.',
    },
  ])
  renderAgentPage('/agent?conversation=1')

  expect(
    await screen.findByText('PLAN_CREATE 권한이 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getByText('실행하지 않음')).toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '계획 펼치기' }),
  ).not.toBeInTheDocument()
})

test('답변이 실패해도 그 전에 만든 계획은 보여 준다', async () => {
  mockAnswerWithActions(
    [
      {
        code: 'CREATE_REASSIGNMENT_PLAN',
        name: '재배정 계획 생성',
        status: 'SUCCEEDED',
        executionId: 512,
        result: { planId: 3, itemCount: 1 },
        blockedReason: null,
      },
    ],
    true,
  )
  renderAgentPage('/agent?conversation=1')

  expect(await screen.findByRole('alert')).toHaveTextContent('AI 응답 실패')
  // 계획은 이미 저장됐으므로 다시 요청하지 않도록 계획을 보여 준다.
  expect(
    screen.getByText('계획 #3 · 실제 MES 배정은 바뀌지 않아요'),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '계획 펼치기' }),
  ).toBeInTheDocument()
})

test('실패·실행하지 못함·확인 필요는 성공처럼 보이지 않는다', async () => {
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'FAILED',
      executionId: 514,
      result: { errorMessage: '실행 중 오류가 발생했습니다.' },
      blockedReason: null,
    },
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'NOT_RUN',
      executionId: null,
      result: {},
      blockedReason: null,
    },
    {
      code: 'CLOSE_ORDER',
      name: '작업지시 종료',
      status: 'CONFIRMATION_REQUIRED',
      executionId: null,
      result: {},
      blockedReason: null,
    },
  ])
  renderAgentPage('/agent?conversation=1')

  const list = await screen.findByRole('list', { name: '실행한 작업' })
  expect(within(list).getByText('실패')).toBeInTheDocument()
  expect(
    within(list).getByText('실행 중 오류가 발생했습니다.'),
  ).toBeInTheDocument()
  expect(within(list).getByText('실행하지 못함')).toBeInTheDocument()
  expect(
    within(list).getByText('확인이 필요해 실행하지 않음'),
  ).toBeInTheDocument()
  expect(within(list).queryByText('완료')).not.toBeInTheDocument()
  expect(
    within(list).queryByRole('button', { name: '계획 펼치기' }),
  ).not.toBeInTheDocument()
})

test('계획을 불러오지 못하면 오류를 보여 준다', async () => {
  const user = userEvent.setup()
  mockAnswerWithActions([
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'SUCCEEDED',
      executionId: 515,
      // 목 서버에 없는 계획 번호라 094가 404를 돌려준다.
      result: { planId: 99, itemCount: 1 },
      blockedReason: null,
    },
  ])
  renderAgentPage('/agent?conversation=1')

  await user.click(await screen.findByRole('button', { name: '계획 펼치기' }))

  const plan = screen.getByRole('region', { name: '재배정 계획 #99' })
  expect(
    await within(plan).findByText('요청한 항목을 찾을 수 없습니다.'),
  ).toBeInTheDocument()
})
