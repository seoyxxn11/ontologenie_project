import { delay, http, HttpResponse } from 'msw'
import type { Proposal, ProposalOption } from '../features/review/types'

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
        {
          sourceProperty: 'WORK_ORDER.equipment_code',
          targetProperty: 'EQUIPMENT.code',
          operator: 'EQ',
          sourceTransforms: [{ op: 'UPPER', arg: null }],
          targetTransforms: [],
        },
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
      linkTypeId: 2,
      conditions: [
        {
          sourceProperty: 'WORK_ORDER.equipment_code',
          targetProperty: 'EQUIPMENT.code',
          operator: 'EQ',
          sourceTransforms: [],
          targetTransforms: [],
        },
      ],
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

let proposals: Proposal[] = structuredClone(proposalFixtures)
let savedOptions: Record<string, ProposalOption[]> = {}
export function resetProposalMocks() {
  proposals = structuredClone(proposalFixtures)
  savedOptions = {}
}
function failed(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-proposal' } },
    { status },
  )
}

export const proposalHandlers = [
  http.post('/api/v1/proposals/manual', async ({ request }) => {
    await delay()
    const body = (await request.json()) as {
      itemKind: string
      payload: Record<string, unknown>
      evidence: Record<string, unknown>
    }
    if (body.itemKind !== 'LINK_RULE')
      return failed(
        400,
        'VALIDATION_FAILED',
        '연결 규칙만 직접 추가할 수 있습니다.',
      )
    const id = Math.max(...proposals.map((item) => item.id), 300) + 1
    const item: Proposal = {
      ...proposalFixtures[1],
      id,
      origin: 'MANUAL',
      revision: 1,
      payload: body.payload,
      evidence: body.evidence,
      reviewStatus: 'PENDING',
      latestCheck: {
        checkRunId: id + 1000,
        status: 'QUEUED',
        matchedCount: null,
        totalCount: null,
        matchRate: null,
      },
    }
    proposals.unshift(item)
    return HttpResponse.json(
      { data: { ...item, checkRunId: id + 1000 } },
      { status: 201 },
    )
  }),
  http.get('/api/v1/proposals/:id/options', async ({ params, request }) => {
    await delay()
    const id = Number(params.id)
    const revision = Number(new URL(request.url).searchParams.get('revision'))
    if (!proposals.some((item) => item.id === id))
      return failed(404, 'RESOURCE_NOT_FOUND', '제안을 찾을 수 없습니다.')
    return HttpResponse.json({ data: savedOptions[`${id}:${revision}`] ?? [] })
  }),
  http.post('/api/v1/proposals/:id/revisions', async ({ params, request }) => {
    await delay()
    const item = proposals.find((value) => value.id === Number(params.id))
    if (!item)
      return failed(404, 'RESOURCE_NOT_FOUND', '제안을 찾을 수 없습니다.')
    if (!['PENDING', 'HELD'].includes(item.reviewStatus))
      return failed(409, 'PROPOSAL_ALREADY_DECIDED', '이미 결정된 제안입니다.')
    const body = (await request.json()) as {
      baseRevision: number
      payload: Record<string, unknown>
      evidence: Record<string, unknown>
      options: Pick<ProposalOption, 'payload' | 'evidence'>[]
    }
    if (body.baseRevision !== item.revision)
      return failed(409, 'REVISION_CONFLICT', '다른 사람이 먼저 수정했습니다.')
    item.revision += 1
    item.payload = body.payload
    item.evidence = body.evidence
    item.reviewStatus = 'PENDING'
    item.aiConfidence = null
    item.latestCheck = {
      checkRunId: item.id + 1000 + item.revision,
      status: 'QUEUED',
      matchedCount: null,
      totalCount: null,
      matchRate: null,
    }
    savedOptions[`${item.id}:${item.revision}`] = body.options.map(
      (option, index) => ({
        ...option,
        id: index + 1,
        optionNo: index + 1,
        revision: item.revision,
        aiConfidence: null,
      }),
    )
    return HttpResponse.json(
      { data: { ...item, checkRunId: item.latestCheck.checkRunId } },
      { status: 201 },
    )
  }),
  http.get('/api/v1/proposals', async ({ request }) => {
    await delay()
    const params = new URL(request.url).searchParams
    const page = Number(params.get('page') ?? 0)
    const size = Number(params.get('size') ?? 20)
    const matches = proposals.filter((item) =>
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
    const item = proposals.find((item) => item.id === Number(params.id))
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
