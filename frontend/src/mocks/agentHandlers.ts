import { delay, http, HttpResponse } from 'msw'
import type {
  AgentConversation,
  AgentMessage,
  AgentStage,
  AnswerMeta,
  QuestionRequest,
} from '../features/agent/types'
import { samplePlan } from './reassignmentHandlers'

const STAGES: AgentStage[] = [
  'INTERPRETING',
  'SYNCING',
  'QUERYING',
  'ANSWERING',
]

const sampleAnswerMeta: AnswerMeta = {
  syncStatus: 'TIMED_OUT',
  results: [
    {
      label: '비가동 이력',
      toolCode: 'OBJECT_LINKS',
      status: 'SUCCEEDED',
      resultCategory: 'FOUND',
      sources: [{ objectId: 4101, sourceResourceId: 14 }],
      dataAsOf: '2026-10-26T23:40:00Z',
      estimated: false,
      errorMessage: null,
    },
    {
      label: '정지 원인',
      toolCode: 'OBJECT_LINKS',
      status: 'SUCCEEDED',
      resultCategory: 'FOUND',
      sources: [{ linkId: 35, objectId: 2001 }],
      dataAsOf: '2026-10-26T06:00:00Z',
      estimated: true,
      errorMessage: null,
    },
    {
      label: '장부가',
      toolCode: 'OBJECT_DETAIL',
      status: 'SUCCEEDED',
      resultCategory: 'DENIED',
      sources: [],
      dataAsOf: null,
      estimated: false,
      errorMessage: null,
    },
    {
      label: '정비 기록',
      toolCode: 'OBJECT_LINKS',
      status: 'FAILED',
      resultCategory: null,
      sources: [],
      dataAsOf: null,
      estimated: false,
      errorMessage: '조회 시간이 초과됐습니다.',
    },
  ],
}

const SAMPLE_ANSWER =
  '10-27 08:40 기준(동기화 중) PR-03의 이번 달 비가동은 2건입니다. 정지 원인은 베어링 이상으로 추정됩니다. 장부가는 조회 권한이 없고, 정비 기록은 확인하지 못했습니다.'

// 질문에 "재배정"이 있으면 Agent가 재배정 계획을 만든 답변(160).
const PLAN_ANSWER =
  '프레스 3호기의 WO-101을 프레스 1호기로 옮기는 내부 계획을 만들었습니다. 실제 MES 배정은 그대로입니다.'

const planAnswerMeta: AnswerMeta = {
  syncStatus: 'NONE',
  results: [],
  actions: [
    {
      code: 'CREATE_REASSIGNMENT_PLAN',
      name: '재배정 계획 생성',
      status: 'SUCCEEDED',
      executionId: samplePlan.actionExecutionId,
      result: { planId: samplePlan.id, itemCount: samplePlan.items.length },
      blockedReason: null,
    },
  ],
}

// 질문·답변으로 바뀌는 가짜 상태. 테스트마다 resetAgentMocks로 되돌린다.
let conversations: AgentConversation[] = []
let messages: AgentMessage[] = []
// 처리 중인 답변이 084 조회를 받은 횟수. 조회할 때마다 다음 단계로 넘어간다.
let pollCounts = new Map<number, number>()
let nextId = 100

export function resetAgentMocks() {
  conversations = [
    {
      id: 2,
      title: null,
      startedAt: '2026-10-27T01:00:00Z',
      lastMessageAt: '2026-10-27T01:00:00Z',
    },
    {
      id: 1,
      title: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
      startedAt: '2026-10-27T00:00:00Z',
      lastMessageAt: '2026-10-27T00:05:04Z',
    },
  ]
  messages = [
    {
      id: 10,
      conversationId: 1,
      sender: 'USER',
      content: '설비 PR-03의 최근 비가동 이력과 장부가를 알려줘',
      context: { referenceObjectIds: [301] },
      status: null,
      errorMessage: null,
      answerMeta: {},
      createdAt: '2026-10-27T00:05:00Z',
    },
    {
      id: 11,
      conversationId: 1,
      sender: 'AGENT',
      content: SAMPLE_ANSWER,
      context: {},
      status: 'SUCCEEDED',
      errorMessage: null,
      answerMeta: sampleAnswerMeta,
      createdAt: '2026-10-27T00:05:04Z',
    },
  ]
  pollCounts = new Map()
  nextId = 100
}
resetAgentMocks()

function errorResponse(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-request-id' } },
    { status },
  )
}

// 처리 중인 답변을 한 단계 넘긴다. 마지막 단계 다음에는 최종 답변으로 바꾼다.
function advance(message: AgentMessage, question: string) {
  const count = (pollCounts.get(message.id) ?? 0) + 1
  pollCounts.set(message.id, count)
  if (count < STAGES.length) {
    message.answerMeta = { stage: STAGES[count] }
    return
  }
  // 질문에 "실패"가 들어 있으면 답변을 끝내지 못한 경우를 보여 준다.
  if (question.includes('실패')) {
    message.status = 'FAILED'
    message.content = '답변을 끝내지 못했습니다. 잠시 후 다시 질문해 주세요.'
    message.errorMessage = 'LLM 응답 시간 초과'
    message.answerMeta = { syncStatus: 'NONE', results: [] }
    return
  }
  if (question.includes('재배정')) {
    message.status = 'SUCCEEDED'
    message.content = PLAN_ANSWER
    message.answerMeta = planAnswerMeta
    return
  }
  message.status = 'SUCCEEDED'
  message.content = SAMPLE_ANSWER
  message.answerMeta = sampleAnswerMeta
}

export const agentHandlers = [
  // 081 내 대화 목록
  http.get('/api/v1/agent/conversations', async () => {
    await delay(150)
    const sorted = [...conversations].sort(
      (a, b) => b.lastMessageAt.localeCompare(a.lastMessageAt) || b.id - a.id,
    )
    return HttpResponse.json({
      data: sorted,
      meta: { page: 0, size: 50, totalElements: sorted.length, hasNext: false },
    })
  }),

  // 082 대화 생성
  http.post('/api/v1/agent/conversations', async () => {
    await delay(150)
    const at = new Date().toISOString()
    const created: AgentConversation = {
      id: nextId++,
      title: null,
      startedAt: at,
      lastMessageAt: at,
    }
    conversations.push(created)
    return HttpResponse.json({ data: created }, { status: 201 })
  }),

  // 106 대화 삭제
  http.delete('/api/v1/agent/conversations/:id', async ({ params }) => {
    await delay(150)
    const id = Number(params.id)
    conversations = conversations.filter(
      (conversation) => conversation.id !== id,
    )
    messages = messages.filter((message) => message.conversationId !== id)
    return new HttpResponse(null, { status: 204 })
  }),

  // 084 대화 메시지 목록. 처리 중인 답변은 읽을 때마다 한 단계씩 진행한다.
  http.get('/api/v1/agent/conversations/:id/messages', async ({ params }) => {
    await delay(150)
    const id = Number(params.id)
    if (!conversations.some((conversation) => conversation.id === id)) {
      return errorResponse(
        404,
        'RESOURCE_NOT_FOUND',
        '요청한 항목을 찾을 수 없습니다.',
      )
    }
    const own = messages.filter((message) => message.conversationId === id)
    own.forEach((message, index) => {
      if (message.status === 'RUNNING') {
        advance(message, own[index - 1]?.content ?? '')
      }
    })
    return HttpResponse.json({
      data: own,
      meta: { page: 0, size: 100, totalElements: own.length, hasNext: false },
    })
  }),

  // 085 질문하고 답변 받기: 202로 접수하고 처리 중인 답변을 함께 돌려준다.
  http.post(
    '/api/v1/agent/conversations/:id/messages',
    async ({ params, request }) => {
      await delay(150)
      const id = Number(params.id)
      const conversation = conversations.find((item) => item.id === id)
      if (!conversation) {
        return errorResponse(
          404,
          'RESOURCE_NOT_FOUND',
          '요청한 항목을 찾을 수 없습니다.',
        )
      }
      if (
        messages.some(
          (message) =>
            message.conversationId === id && message.status === 'RUNNING',
        )
      ) {
        return errorResponse(
          409,
          'CONFLICT',
          '이 대화에서 처리 중인 답변이 있습니다.',
        )
      }
      const body = (await request.json()) as QuestionRequest
      const at = new Date().toISOString()
      const question: AgentMessage = {
        id: nextId++,
        conversationId: id,
        sender: 'USER',
        content: body.content,
        context: {
          referenceObjectIds: body.referenceObjectIds ?? [],
          ...body.context,
        },
        status: null,
        errorMessage: null,
        answerMeta: {},
        createdAt: at,
      }
      const answer: AgentMessage = {
        id: nextId++,
        conversationId: id,
        sender: 'AGENT',
        content: '',
        context: {},
        status: 'RUNNING',
        errorMessage: null,
        answerMeta: { stage: 'INTERPRETING' },
        createdAt: at,
      }
      messages.push(question, answer)
      conversation.title ??= body.content.slice(0, 200)
      conversation.lastMessageAt = at
      return HttpResponse.json({ data: [question, answer] }, { status: 202 })
    },
  ),
]
