import { delay, http, HttpResponse } from 'msw'
import type { Proposal, ProposalOption } from '../features/review/types'
import type {
  DecisionInput,
  ProposalDecisionRecord,
} from '../features/review/decisionApi'

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
const comparisonFixtures: ProposalOption[] = [
  {
    id: 501,
    optionNo: 1,
    revision: 2,
    payload: {
      ...proposalFixtures[0].payload,
      name: '설비 코드 공백 제거 후 연결',
      conditions: [
        {
          sourceProperty: 'WORK_ORDER.equipment_code',
          targetProperty: 'EQUIPMENT.code',
          operator: 'EQ',
          sourceTransforms: [{ op: 'TRIM', arg: null }],
          targetTransforms: [],
        },
      ],
    },
    evidence: {
      reason: '원천 설비 코드의 앞뒤 공백을 제거해 비교합니다.',
      originalText: ' PR-03 ',
    },
    aiConfidence: 0.86,
  },
  {
    id: 502,
    optionNo: 2,
    revision: 2,
    payload: {
      ...proposalFixtures[0].payload,
      name: '설비 코드 대문자 변환 후 연결',
    },
    evidence: {
      reason: '원천 설비 코드를 대문자로 통일해 비교합니다.',
      originalText: 'pr-03',
    },
    aiConfidence: 0.72,
  },
]
let savedOptions: Record<string, ProposalOption[]> = {
  '303:2': structuredClone(comparisonFixtures),
}
let savedDecisions: ProposalDecisionRecord[] = []

export function resetProposalMocks() {
  proposals = structuredClone(proposalFixtures)
  savedOptions = { '303:2': structuredClone(comparisonFixtures) }
  savedDecisions = []
}
function failed(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-proposal' } },
    { status },
  )
}

export const proposalHandlers = [
  http.post('/api/v1/proposals/:id/decisions', async ({ params, request }) => {
    await delay()
    const item = proposals.find((proposal) => proposal.id === Number(params.id))
    if (!item)
      return failed(404, 'RESOURCE_NOT_FOUND', '제안을 찾을 수 없습니다.')
    if (!['PENDING', 'HELD'].includes(item.reviewStatus))
      return failed(409, 'PROPOSAL_ALREADY_DECIDED', '이미 결정된 제안입니다.')
    const input = (await request.json()) as DecisionInput
    if (input.revision !== item.revision)
      return failed(409, 'REVISION_CONFLICT', '최신 제안을 확인해 주세요.')
    const { decision, selectedOptionId, reason } = input
    if (
      !['APPROVE', 'HOLD', 'REJECT'].includes(decision) ||
      !reason?.trim() ||
      reason.length > 1000
    )
      return failed(
        400,
        'VALIDATION_FAILED',
        '검토 결정과 사유를 확인해 주세요.',
      )
    if (
      selectedOptionId != null &&
      !savedOptions[`${item.id}:${item.revision}`]?.some(
        (option) => option.id === selectedOptionId,
      )
    )
      return failed(
        400,
        'VALIDATION_FAILED',
        '현재 개정의 대안을 선택해 주세요.',
      )
    if (
      decision === 'APPROVE' &&
      item.itemKind === 'LINK_RULE' &&
      item.latestCheck?.status !== 'SUCCEEDED'
    )
      return failed(
        422,
        'REVIEW_VALIDATION_FAILED',
        '같은 개정의 성공한 검사가 필요합니다.',
      )

    const now = new Date().toISOString()
    const id = 703 + savedDecisions.length
    item.reviewStatus = {
      APPROVE: 'APPROVED',
      HOLD: 'HELD',
      REJECT: 'REJECTED',
    }[decision]
    item.applyStatus = decision === 'APPROVE' ? 'APPLIED' : 'NOT_APPLIED'
    item.appliedAt = decision === 'APPROVE' ? now : null
    savedDecisions.unshift({
      id,
      proposalItemId: item.id,
      itemRevision: item.revision,
      decision,
      selectedOptionId: selectedOptionId ?? null,
      reason,
      decidedBy: 1,
      decidedAt: now,
    })
    return HttpResponse.json({
      data: {
        decisionId: id,
        decision,
        reviewStatus: item.reviewStatus,
        applyStatus: item.applyStatus,
        appliedEntityIds: decision === 'APPROVE' ? [2] : [],
        actionExecutionId: id + 1000,
      },
    })
  }),
  http.get('/api/v1/proposals/:id/decisions', async ({ params, request }) => {
    await delay()
    const item = proposals.find((proposal) => proposal.id === Number(params.id))
    if (!item)
      return failed(404, 'RESOURCE_NOT_FOUND', '제안을 찾을 수 없습니다.')
    const search = new URL(request.url).searchParams
    const page = Number(search.get('page') ?? 0)
    const size = Number(search.get('size') ?? 20)
    const records: ProposalDecisionRecord[] = [
      ...savedDecisions.filter((record) => record.proposalItemId === item.id),
      {
        id: 702,
        proposalItemId: item.id,
        itemRevision: 1,
        decision: item.id === 301 ? 'APPROVE' : 'HOLD',
        selectedOptionId: null,
        reason: '대응 설비와 규칙 조건을 확인했습니다.',
        decidedBy: 1,
        decidedAt: '2026-10-28T03:00:00Z',
      },
      ...(item.id === 303
        ? [
            {
              id: 701,
              proposalItemId: item.id,
              itemRevision: 1,
              decision: 'HOLD',
              selectedOptionId: null,
              reason: '원천 설비 코드를 다시 확인합니다.',
              decidedBy: 1,
              decidedAt: '2026-10-27T03:00:00Z',
            },
          ]
        : []),
    ]
    return HttpResponse.json({
      data: records.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: records.length,
        hasNext: (page + 1) * size < records.length,
      },
    })
  }),
  http.get('/api/v1/checks/runs/:id/results', async ({ params, request }) => {
    await delay()
    const search = new URL(request.url).searchParams
    const page = Number(search.get('page') ?? 0)
    const size = Number(search.get('size') ?? 20)
    const item = proposals.find(
      (proposal) =>
        proposal.id === Number(search.get('proposalItemId')) &&
        proposal.latestCheck?.checkRunId === Number(params.id),
    )
    if (!item)
      return failed(404, 'RESOURCE_NOT_FOUND', '검사 실행을 찾을 수 없습니다.')
    const results =
      item.latestCheck?.status === 'SUCCEEDED'
        ? [
            {
              id: 601,
              checkRunId: Number(params.id),
              proposalItemId: item.id,
              proposalItemRevision: item.revision,
              linkMappingId: null,
              previousResultId: null,
              totalCount: 3,
              matchedCount: 2,
              unmatchedCount: 1,
              duplicateCount: 0,
              violationCount: 0,
              matchRate: 0.6667,
              sampleRows: [
                {
                  sourceKey: { id: 'WO-101' },
                  rawValue: ' pr-03 ',
                  transformedValue: 'PR-03',
                  targetValue: 'PR-03',
                  classification: 'MATCHED',
                  reason: null,
                },
                {
                  sourceKey: { id: 'WO-102' },
                  rawValue: 'PR-99',
                  transformedValue: 'PR-99',
                  targetValue: null,
                  classification: 'UNMATCHED',
                  reason: '대상 설비가 없습니다.',
                },
              ],
            },
          ]
        : []
    return HttpResponse.json({
      data: results.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: results.length,
        hasNext: (page + 1) * size < results.length,
      },
    })
  }),
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
