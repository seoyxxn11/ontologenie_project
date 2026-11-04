import { delay, http, HttpResponse } from 'msw'
import type {
  Analysis,
  AnalysisRun,
  AnalysisTarget,
} from '../features/analysis/types'
import type { Proposal } from '../features/review/types'
import { proposalFixtures } from './proposalHandlers'

// 가짜 분석은 019를 두 번 부르면 끝나고, 분석 51의 후보를 복사해 새 실행의 후보로 둔다.
const FINISH_AFTER_POLLS = 2

let runs: Analysis[] = []
let polls: Record<number, number> = {}
let created: Proposal[] = []

function run(overrides: Partial<Analysis> & { id: number }): Analysis {
  return {
    runKind: 'INITIAL',
    status: 'SUCCEEDED',
    targets: [{ sourceResourceId: 11 }],
    requestedAt: '2026-11-03T09:10:00+09:00',
    startedAt: '2026-11-03T09:10:01+09:00',
    finishedAt: '2026-11-03T09:10:31+09:00',
    resultSummary: { proposals: 1, holdRecommended: 0 },
    errorMessage: null,
    requestedBy: 1,
    ...overrides,
  }
}

export function resetAnalysisMocks() {
  runs = [
    run({
      id: 51,
      targets: [{ sourceResourceId: 32 }, { sourceResourceId: 29 }],
      resultSummary: { proposals: 1, holdRecommended: 1 },
    }),
    run({
      id: 50,
      requestedAt: '2026-11-02T15:00:00+09:00',
      resultSummary: { proposals: 1, holdRecommended: 0 },
    }),
    run({
      id: 49,
      status: 'FAILED',
      requestedAt: '2026-11-02T14:00:00+09:00',
      resultSummary: null,
      errorMessage: 'AI 계정이 이 테이블을 읽을 수 없습니다: erp.employee',
    }),
  ]
  polls = {}
  created = []
}
resetAnalysisMocks()

function notFound() {
  return HttpResponse.json(
    {
      error: {
        code: 'RESOURCE_NOT_FOUND',
        message: '요청한 항목을 찾을 수 없습니다.',
        details: { resource: 'analysis_run' },
        requestId: 'mock-analysis',
      },
    },
    { status: 404 },
  )
}

function progress(item: Analysis) {
  if (item.status !== 'QUEUED' && item.status !== 'RUNNING') return item
  polls[item.id] = (polls[item.id] ?? 0) + 1
  if (polls[item.id] < FINISH_AFTER_POLLS) {
    return Object.assign(item, {
      status: 'RUNNING',
      startedAt: item.requestedAt,
    })
  }
  const copies = proposalFixtures
    .filter((proposal) => proposal.analysisRunId === 51)
    .map((proposal, index) => ({
      ...structuredClone(proposal),
      id: item.id * 100 + index,
      analysisRunId: item.id,
      reviewStatus: 'PENDING',
    }))
  created.push(...copies)
  return Object.assign(item, {
    status: 'SUCCEEDED',
    finishedAt: new Date().toISOString(),
    resultSummary: { proposals: copies.length, holdRecommended: 0 },
  })
}

export const analysisHandlers = [
  // 018 선택 범위 AI 분석 실행. 같은 대상이 대기·실행 중이면 그 실행에 합류한다.
  http.post('/api/v1/analyses', async ({ request }) => {
    await delay(150)
    const body = (await request.json()) as { targets: AnalysisTarget[] }
    const ids = JSON.stringify(body.targets.map((t) => t.sourceResourceId))
    const running = runs.find(
      (item) =>
        (item.status === 'QUEUED' || item.status === 'RUNNING') &&
        JSON.stringify(item.targets.map((t) => t.sourceResourceId)) === ids,
    )
    const accepted: Analysis =
      running ??
      run({
        id: Math.max(...runs.map((item) => item.id)) + 1,
        status: 'QUEUED',
        targets: body.targets.map(({ sourceResourceId }) => ({
          sourceResourceId,
        })),
        requestedAt: new Date().toISOString(),
        startedAt: null,
        finishedAt: null,
        resultSummary: null,
      })
    if (!running) runs.unshift(accepted)
    const data: AnalysisRun = {
      id: accepted.id,
      runKind: accepted.runKind,
      status: accepted.status,
      targets: body.targets,
      requestedAt: accepted.requestedAt,
      joined: Boolean(running),
    }
    return HttpResponse.json({ data }, { status: 202 })
  }),

  // 019 분석 실행 상태·결과 조회
  http.get('/api/v1/analyses/:id', async ({ params }) => {
    await delay(150)
    const item = runs.find((entry) => entry.id === Number(params.id))
    return item ? HttpResponse.json({ data: progress(item) }) : notFound()
  }),

  // 020 분석에서 생성된 제안
  http.get('/api/v1/analyses/:id/proposals', async ({ params, request }) => {
    await delay(150)
    const id = Number(params.id)
    if (!runs.some((entry) => entry.id === id)) return notFound()
    const search = new URL(request.url).searchParams
    const size = Number(search.get('size') ?? 20)
    const data = [...created, ...proposalFixtures].filter(
      (proposal) =>
        proposal.analysisRunId === id &&
        ['itemKind', 'reviewStatus', 'applyStatus'].every((key) => {
          const value = search.get(key)
          return !value || proposal[key as keyof Proposal] === value
        }),
    )
    return HttpResponse.json({
      data: data.slice(0, size),
      meta: { page: 0, size, totalElements: data.length, hasNext: false },
    })
  }),

  // 017 분석 실행 목록 (최근 것부터)
  http.get('/api/v1/analyses', async ({ request }) => {
    await delay(150)
    const size = Number(new URL(request.url).searchParams.get('size') ?? 20)
    return HttpResponse.json({
      data: runs.slice(0, size),
      meta: {
        page: 0,
        size,
        totalElements: runs.length,
        hasNext: runs.length > size,
      },
    })
  }),
]
