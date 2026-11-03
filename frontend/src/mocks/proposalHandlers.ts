import { delay, http, HttpResponse } from 'msw'
import type { Proposal } from '../features/review/types'

export const proposalFixtures: Proposal[] = [
  {
    id: 303,
    analysisRunId: 51,
    origin: 'AI',
    itemKind: 'LINK_RULE',
    revision: 2,
    payload: {
      name: '작업지시 설비 코드 연결',
      code: 'WO_EQUIPMENT_CODE',
      linkTypeId: 2,
      conditions: [
        { sourcePropertyId: 31, targetPropertyId: 21, operator: 'EQ' },
      ],
    },
    evidence: {
      reason: '작업지시의 설비 코드와 설비 원장의 코드가 대응합니다.',
      originalText: '작업지시 WO-101의 배정 설비는 PR-03입니다.',
    },
    aiConfidence: 0.86,
    reviewStatus: 'PENDING',
    applyStatus: 'NOT_APPLIED',
    applyError: null,
    appliedAt: null,
    latestCheck: {
      checkRunId: 402,
      status: 'SUCCEEDED',
      matchedCount: 2,
      totalCount: 3,
      matchRate: 0.6667,
    },
  },
  {
    id: 302,
    analysisRunId: null,
    origin: 'MANUAL',
    itemKind: 'LINK_RULE',
    revision: 1,
    payload: {
      name: '직접 추가한 대체 설비 연결',
      code: 'ALTERNATIVE_EQUIPMENT',
    },
    evidence: {},
    aiConfidence: null,
    reviewStatus: 'HELD',
    applyStatus: 'NOT_APPLIED',
    applyError: null,
    appliedAt: null,
    latestCheck: null,
  },
  {
    id: 301,
    analysisRunId: 50,
    origin: 'AI',
    itemKind: 'DOCUMENT_LINK',
    revision: 1,
    payload: { name: '인수인계 메모와 설비 연결' },
    evidence: {
      reason: '설비 이름이 문서에 언급됐습니다.',
      originalText: '야간에 3호기 진동이 커진 것 같다.',
    },
    aiConfidence: 0.72,
    reviewStatus: 'APPROVED',
    applyStatus: 'FAILED',
    applyError: '대상 설비가 변경되어 반영하지 못했습니다.',
    appliedAt: null,
    latestCheck: null,
  },
]

export const proposalHandlers = [
  http.get('/api/v1/proposals', async ({ request }) => {
    await delay()
    const params = new URL(request.url).searchParams
    const page = Number(params.get('page') ?? 0)
    const size = Number(params.get('size') ?? 20)
    const matches = proposalFixtures.filter((item) =>
      ['origin', 'itemKind', 'reviewStatus', 'applyStatus'].every(
        (key) =>
          !params.get(key) || item[key as keyof Proposal] === params.get(key),
      ),
    )
    return HttpResponse.json({
      data: matches.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: matches.length,
        hasNext: (page + 1) * size < matches.length,
      },
    })
  }),
  http.get('/api/v1/proposals/:id', async ({ params }) => {
    await delay()
    const item = proposalFixtures.find((item) => item.id === Number(params.id))
    return item
      ? HttpResponse.json({ data: item })
      : HttpResponse.json(
          {
            error: {
              code: 'RESOURCE_NOT_FOUND',
              message: '제안을 찾을 수 없습니다.',
              details: {},
              requestId: 'mock-proposal',
            },
          },
          { status: 404 },
        )
  }),
]
