import { http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { sourceSampleApi } from './sampleApi'

const times = () => ({
  requestedAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 3600000).toISOString(),
})
const preview = () => ({
  id: 41,
  previewKind: 'SAMPLE',
  sourceResourceId: 10,
  status: 'SUCCEEDED',
  ...times(),
  result: {
    columns: ['code', 'masked_by_source', 'null', 'empty', 'zero', 'flag'],
    rows: [['TEST-1', '123-**', null, '', 0, false]],
  },
  errorMessage: null,
})

test('016은 지정한 테이블·행 수를 전송하고 접수·합류·만료 정보를 읽는다', async () => {
  const time = times()
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({ rowLimit: 5 })
    return HttpResponse.json(
      { data: { previewId: 41, status: 'RUNNING', joined: true, ...time } },
      { status: 202 },
    )
  })
  server.use(http.post('/api/v1/sources/1/resources/10/samples', post))
  expect(await sourceSampleApi.request(1, 10, 5)).toEqual({
    previewId: 41,
    joined: true,
    expiresAt: time.expiresAt,
  })
  expect(post).toHaveBeenCalledTimes(1)
})

test('102가 준 컬럼·값 순서와 가림 값을 유지하며 제외 컬럼을 복원하지 않는다', async () => {
  server.use(
    http.get('/api/v1/ontology/source-previews/41', () =>
      HttpResponse.json({ data: preview() }),
    ),
  )
  const result = await sourceSampleApi.read(41)
  expect(result.columns).toEqual(preview().result.columns)
  expect(result.rows[0]).toEqual(
    preview().result.rows[0].map((value) => ({ kind: 'visible', value })),
  )
  expect(result.columns).not.toContain('employee_name')
})

test.each(['QUEUED', 'RUNNING', 'FAILED'])(
  '%s에서는 결과나 원천 오류 원문을 화면 모델에 전달하지 않는다',
  async (status) => {
    server.use(
      http.get('/api/v1/ontology/source-previews/41', () =>
        HttpResponse.json({
          data: { ...preview(), status, errorMessage: 'SECRET_SQL_VALUE' },
        }),
      ),
    )
    expect(await sourceSampleApi.read(41)).toEqual({
      status,
      columns: [],
      rows: [],
    })
  },
)

test.each([
  { id: 42 },
  { previewKind: 'MAPPING_PREVIEW' },
  { status: 'UNKNOWN' },
  { result: { columns: ['a'], rows: [['a', 'extra']] } },
  { result: { columns: [123], rows: [] } },
  { result: { columns: [], rows: Array.from({ length: 21 }, () => []) } },
])('형식이나 대상이 맞지 않는 결과는 표시하지 않는다 (%j)', async (change) => {
  server.use(
    http.get('/api/v1/ontology/source-previews/41', () =>
      HttpResponse.json({ data: { ...preview(), ...change } }),
    ),
  )
  await expect(sourceSampleApi.read(41)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})

test('만료된 결과는 404와 동일하게 처리한다', async () => {
  server.use(
    http.get('/api/v1/ontology/source-previews/41', () =>
      HttpResponse.json({
        data: {
          ...preview(),
          expiresAt: new Date(Date.now() - 1).toISOString(),
        },
      }),
    ),
  )
  await expect(sourceSampleApi.read(41)).rejects.toMatchObject({
    kind: 'not-found',
    status: 404,
  })
})

test('다른 원천 테이블의 응답은 선택한 표본으로 표시하지 않는다', async () => {
  server.use(
    http.get('/api/v1/ontology/source-previews/41', () =>
      HttpResponse.json({ data: preview() }),
    ),
  )
  await expect(sourceSampleApi.read(41, 11)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})

test.each([401, 403, 404])(
  '서버의 %s 응답을 우회하지 않는다',
  async (status) => {
    server.use(
      http.get('/api/v1/ontology/source-previews/41', () =>
        HttpResponse.json(
          { error: { code: 'DENIED', message: '조회 실패' } },
          { status },
        ),
      ),
    )
    await expect(sourceSampleApi.read(41)).rejects.toMatchObject({ status })
  },
)

test('접수 응답이 유실되면 성공으로 표시하지 않는다', async () => {
  server.use(
    http.post(
      '/api/v1/sources/1/resources/10/samples',
      () => new HttpResponse(null, { status: 204 }),
    ),
  )
  await expect(sourceSampleApi.request(1, 10, 20)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})
