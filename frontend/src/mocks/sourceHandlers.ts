import { delay, http, HttpResponse } from 'msw'
import type {
  ConnectionCheck,
  Source,
  SourceCreateRequest,
  SourceDetail,
  SourceResource,
  SourceUpdateRequest,
} from '../features/sources/types'
import { sourceColumns, sourceResources } from './sourceData'

// 원천 코드는 infra/cdc/README.md, 접속 주소 형식은 API 명세 010 예시를 따른다.
const initialSources: Source[] = [
  {
    id: 1,
    code: 'ERP_MAIN',
    name: 'ERP 운영 DB',
    sourceKind: 'POSTGRES',
    connectionStatus: 'CONNECTED',
    lastCheckedAt: '2026-11-03T09:00:00+09:00',
    syncEnabled: true,
    syncIntervalMinutes: null,
    rowVersion: 1,
    connectionConfig: { host: 'erp-db.internal', port: 5432, database: 'erp' },
  },
  {
    id: 2,
    code: 'MES_MAIN',
    name: 'MES 운영 DB',
    sourceKind: 'POSTGRES',
    connectionStatus: 'UNVERIFIED',
    lastCheckedAt: null,
    syncEnabled: true,
    syncIntervalMinutes: null,
    rowVersion: 1,
    connectionConfig: { host: 'mes-db.internal', port: 5432, database: 'mes' },
  },
]

// 계정 이름은 infra/source-db/sql/setup_roles.sql의 조회 전용 계정과 같다.
const defaultCredentials: SourceDetail['credentials'] = [
  { purpose: 'SYNC', username: 'platform_ro' },
  { purpose: 'AI', username: 'platform_ai' },
]

// 등록·수정으로 바뀌는 가짜 상태. 테스트마다 resetSourceMocks로 되돌린다.
let sources: Source[] = []
let credentials: Record<number, SourceDetail['credentials']> = {}

export function resetSourceMocks() {
  sources = initialSources.map((source) => ({
    ...source,
    connectionConfig: { ...source.connectionConfig },
  }))
  credentials = { 1: defaultCredentials, 2: defaultCredentials }
}
resetSourceMocks()

function errorResponse(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-request-id' } },
    { status },
  )
}

const latestChecks: Record<number, SourceDetail['latestConnectionCheck']> = {
  1: {
    checkId: 100,
    status: 'SUCCEEDED',
    finishedAt: '2026-11-03T09:00:03+09:00',
    errorMessage: null,
  },
  2: null,
}

let nextCheckId = 100

function readableCounts(sourceId: number) {
  const resources = sourceResources.filter(
    (resource) => resource.dataSourceId === sourceId && resource.isReadable,
  )
  const columns = resources.reduce(
    (sum, resource) => sum + (sourceColumns[resource.id]?.length ?? 0),
    0,
  )
  return { tables: resources.length, columns }
}

function notFound(message: string) {
  return HttpResponse.json(
    {
      error: {
        code: 'RESOURCE_NOT_FOUND',
        message,
        details: {},
        requestId: 'mock-request-id',
      },
    },
    { status: 404 },
  )
}

export const sourceHandlers = [
  // 009 원천 목록 조회
  http.get('/api/v1/sources', async () => {
    await delay(150)
    return HttpResponse.json({
      data: sources,
      meta: {
        page: 0,
        size: 100,
        totalElements: sources.length,
        hasNext: false,
      },
    })
  }),

  // 010 원천 등록
  http.post('/api/v1/sources', async ({ request }) => {
    await delay(150)
    const body = (await request.json()) as SourceCreateRequest
    if (sources.some((source) => source.code === body.code)) {
      return errorResponse(409, 'CONFLICT', '이미 등록된 원천 코드입니다.')
    }
    const created: Source = {
      id: Math.max(...sources.map((source) => source.id)) + 1,
      code: body.code,
      name: body.name,
      sourceKind: body.sourceKind,
      connectionStatus: 'UNVERIFIED',
      lastCheckedAt: null,
      syncEnabled: false,
      syncIntervalMinutes: null,
      rowVersion: 0,
      connectionConfig: body.connectionConfig,
    }
    sources.push(created)
    credentials[created.id] = body.credentials.map(({ purpose, username }) => ({
      purpose,
      username,
    }))
    return HttpResponse.json({ data: created }, { status: 201 })
  }),

  // 012 원천 정보 변경
  http.patch('/api/v1/sources/:id', async ({ params, request }) => {
    await delay(150)
    const source = sources.find((item) => item.id === Number(params.id))
    if (!source) {
      return notFound('원천을 찾을 수 없습니다.')
    }
    const body = (await request.json()) as SourceUpdateRequest
    if (body.rowVersion !== source.rowVersion) {
      return errorResponse(
        409,
        'CONFLICT',
        '다른 사용자가 먼저 수정했습니다. 새로 불러온 뒤 다시 저장해 주세요.',
      )
    }
    if (body.name) source.name = body.name
    if (body.connectionConfig) source.connectionConfig = body.connectionConfig
    if (body.credentials) {
      const kept = (credentials[source.id] ?? []).filter(
        (item) =>
          !body.credentials!.some((next) => next.purpose === item.purpose),
      )
      credentials[source.id] = [
        ...kept,
        ...body.credentials.map(({ purpose, username }) => ({
          purpose,
          username,
        })),
      ]
    }
    // 접속 정보가 바뀌면 연결 상태를 처음으로 되돌린다.
    if (body.connectionConfig || body.credentials) {
      source.connectionStatus = 'UNVERIFIED'
    }
    source.rowVersion += 1
    return HttpResponse.json({ data: source })
  }),

  // 011 원천 상세 조회
  http.get('/api/v1/sources/:id', async ({ params }) => {
    await delay(150)
    const source = sources.find((item) => item.id === Number(params.id))
    if (!source) {
      return notFound('원천을 찾을 수 없습니다.')
    }
    const detail: SourceDetail = {
      ...source,
      credentials: credentials[source.id] ?? [],
      latestConnectionCheck: latestChecks[source.id] ?? null,
    }
    return HttpResponse.json({ data: detail })
  }),

  // 013 DB 접속 및 읽기 범위 검사 요청
  http.post('/api/v1/sources/:id/test-connection', async ({ params }) => {
    await delay(150)
    if (!sources.some((source) => source.id === Number(params.id))) {
      return notFound('원천을 찾을 수 없습니다.')
    }
    nextCheckId += 1
    return HttpResponse.json(
      {
        data: {
          checkId: nextCheckId,
          status: 'QUEUED',
          joined: false,
          requestedAt: new Date().toISOString(),
          startedAt: null,
          finishedAt: null,
          errorMessage: null,
        },
      },
      { status: 202 },
    )
  }),

  // 100 원천 연결 검사 결과 조회 (가짜 데이터는 바로 끝난 결과를 준다)
  http.get(
    '/api/v1/sources/:id/connection-checks/:checkId',
    async ({ params }) => {
      await delay(150)
      const sourceId = Number(params.id)
      const counts = readableCounts(sourceId)
      const now = new Date().toISOString()
      const check: ConnectionCheck = {
        checkId: Number(params.checkId),
        dataSourceId: sourceId,
        status: 'SUCCEEDED',
        requestedBy: 1,
        requestedAt: now,
        startedAt: now,
        finishedAt: now,
        errorMessage: null,
        result: {
          accounts: (['SYNC', 'AI'] as const).map((purpose) => ({
            purpose,
            status: 'SUCCEEDED' as const,
            readableTableCount: counts.tables,
            readableColumnCount: counts.columns,
            errorMessage: null,
          })),
        },
      }
      return HttpResponse.json({ data: check })
    },
  ),

  // 014 원천 표·뷰·자료 목록
  http.get('/api/v1/sources/:id/resources', async ({ params, request }) => {
    await delay(150)
    const url = new URL(request.url)
    const q = url.searchParams.get('q')?.toLowerCase()
    const kind = url.searchParams.get('resourceKind')
    const readable = url.searchParams.get('isReadable')
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const matched: SourceResource[] = sourceResources.filter(
      (resource) =>
        resource.dataSourceId === Number(params.id) &&
        (!q || resource.resourceKey.toLowerCase().includes(q)) &&
        (!kind || resource.resourceKind === kind) &&
        (readable === null || String(resource.isReadable) === readable),
    )
    return HttpResponse.json({
      data: matched.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: matched.length,
        hasNext: (page + 1) * size < matched.length,
      },
    })
  }),

  // 015 자원의 컬럼 조회
  http.get(
    '/api/v1/sources/:id/resources/:resourceId/columns',
    async ({ params }) => {
      await delay(150)
      const columns = sourceColumns[Number(params.resourceId)]
      if (!columns) {
        return notFound('원천 자원을 찾을 수 없습니다.')
      }
      return HttpResponse.json({ data: columns })
    },
  ),
]
