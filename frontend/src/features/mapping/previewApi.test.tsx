import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'
import { resetMappingMocks } from '../../mocks/mappingHandlers'
import { server } from '../../mocks/node'
import SourcesPage from '../sources/SourcesPage'
import { mappingPreviewApi } from './previewApi'
import type { MappingPreviewDraft } from './previewModel'

const postPath = '/api/v1/ontology/source-resources/11/mapping-previews'
const getPath = '/api/v1/ontology/source-previews/91'
const draft: MappingPreviewDraft = {
  sourceResourceId: 11,
  objectTypeId: 501,
  sourceMappingId: 701,
  rowVersion: 2,
  keyColumns: ['item_code'],
  rowFilter: {},
  isActive: true,
  properties: [
    {
      propertyDefinitionId: 601,
      sourceColumnId: 1002,
      sourcePath: null,
      transformSteps: [{ op: 'TRIM', arg: null }],
    },
  ],
}
const receipt = () => ({
  previewId: 91,
  status: 'QUEUED',
  joined: false,
  requestedAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 3600000).toISOString(),
})
const result = () => ({
  id: 91,
  previewKind: 'MAPPING_PREVIEW',
  sourceResourceId: 11,
  status: 'SUCCEEDED',
  requestedAt: new Date().toISOString(),
  startedAt: new Date().toISOString(),
  finishedAt: new Date().toISOString(),
  expiresAt: new Date(Date.now() + 3600000).toISOString(),
  result: {
    objects: [
      {
        sourceKey: { item_code: 'TEST-002' },
        displayName: '시험 품목',
        properties: {
          ITEM_NAME: '품목',
          EMPTY: '',
          NIL: null,
          FLAG: false,
          COUNT: 0,
          JSON: { tags: ['a'] },
        },
      },
    ],
    rowErrors: [
      {
        rowIndex: 0,
        column: 'item_code',
        transform: null,
        message: '키 값이 비어 있어 객체를 만들 수 없습니다.',
      },
      {
        rowIndex: 1,
        column: 'amount',
        transform: 'TO_INTEGER',
        message: 'PRIVATE-ROW-SECRET 변환 실패',
      },
    ],
  },
  errorMessage: null,
})

beforeEach(resetMappingMocks)

test('040은 편집 초안을 계약 필드로 보내고 CSRF 헤더를 포함한다', async () => {
  let body: unknown
  let csrf: string | null = null
  document.cookie = 'XSRF-TOKEN=preview-test; path=/'
  server.use(
    http.post(postPath, async ({ request }) => {
      body = await request.json()
      csrf = request.headers.get('X-XSRF-TOKEN')
      return HttpResponse.json(
        { data: { ...receipt(), joined: true } },
        { status: 202 },
      )
    }),
  )
  try {
    expect(await mappingPreviewApi.request(draft, 10)).toMatchObject({
      previewId: 91,
      joined: true,
    })
    expect(body).toEqual({
      mapping: {
        objectTypeId: 501,
        keyColumns: ['item_code'],
        rowFilter: {},
        propertyMappings: draft.properties,
      },
      rowLimit: 10,
    })
    expect(csrf).toBe('preview-test')
  } finally {
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; path=/'
  }
})

test.each([0, 21, 1.5, NaN])(
  '040은 잘못된 행 수 %s를 접수하지 않는다',
  async (limit) => {
    const request = vi.fn(() =>
      HttpResponse.json({ data: receipt() }, { status: 202 }),
    )
    server.use(http.post(postPath, request))
    await expect(mappingPreviewApi.request(draft, limit)).rejects.toThrow(
      '1~20',
    )
    expect(request).not.toHaveBeenCalled()
  },
)

test('102는 없는 전후 값·행 대응을 추정하지 않고 JSON·빈 값과 오류를 구분한다', async () => {
  server.use(http.get(getPath, () => HttpResponse.json({ data: result() })))
  const data = await mappingPreviewApi.read(91)
  expect(data.rows[0].rowNumber).toBeNull()
  expect(data.rows[0].objectKey).toEqual({
    kind: 'visible',
    value: '{"item_code":"TEST-002"}',
  })
  expect(data.rows[0].values.map((item) => item.after)).toEqual([
    { kind: 'visible', value: '품목' },
    { kind: 'visible', value: '' },
    { kind: 'visible', value: null },
    { kind: 'visible', value: false },
    { kind: 'visible', value: 0 },
    { kind: 'visible', value: '{"tags":["a"]}' },
  ])
  expect(
    data.rows[0].values.every((item) => item.before.kind === 'unavailable'),
  ).toBe(true)
  expect(data.rowErrors[0]).toMatchObject({
    rowNumber: 1,
    source: 'item_code',
    property: null,
    code: null,
  })
  expect(data.rowErrors[1]).toMatchObject({
    rowNumber: 2,
    transform: 'TO_INTEGER',
    step: null,
  })
  expect(JSON.stringify(data)).not.toContain('PRIVATE-ROW-SECRET')
})

test.each(['QUEUED', 'RUNNING', 'FAILED'])(
  '102 상태 %s에서는 행 데이터와 서버 오류 원문을 전달하지 않는다',
  async (status) => {
    server.use(
      http.get(getPath, () =>
        HttpResponse.json({
          data: { ...result(), status, errorMessage: 'PRIVATE-ROW-SECRET' },
        }),
      ),
    )
    expect(await mappingPreviewApi.read(91)).toEqual({
      status,
      rows: [],
      rowErrors: [],
    })
  },
)

test.each([
  { id: 92 },
  { previewKind: 'SAMPLE' },
  { status: 'UNKNOWN' },
  { result: { objects: [], rowErrors: [{ rowIndex: -1 }] } },
  { result: { objects: [{}], rowErrors: [] } },
  { expiresAt: 'invalid' },
])('102의 잘못된 응답을 결과로 표시하지 않는다 (%j)', async (change) => {
  server.use(
    http.get(getPath, () =>
      HttpResponse.json({ data: { ...result(), ...change } }),
    ),
  )
  await expect(mappingPreviewApi.read(91)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})

test.each([
  { previewId: 0 },
  { joined: 'true' },
  { status: 'SUCCEEDED' },
  { expiresAt: 'invalid' },
])('040의 잘못된 접수 결과를 조회하지 않는다 (%j)', async (change) => {
  server.use(
    http.post(postPath, () =>
      HttpResponse.json({ data: { ...receipt(), ...change } }, { status: 202 }),
    ),
  )
  await expect(mappingPreviewApi.request(draft, 20)).rejects.toMatchObject({
    kind: 'invalid-response',
  })
})

test.each([401, 403, 404, 409, 422, 500])(
  '040 HTTP %s 실패를 성공 접수로 바꾸지 않는다',
  async (status) => {
    server.use(
      http.post(postPath, () =>
        HttpResponse.json({ error: { message: 'server error' } }, { status }),
      ),
    )
    await expect(mappingPreviewApi.request(draft, 20)).rejects.toMatchObject({
      status,
    })
  },
)

test('102의 만료된 응답과 404는 만료 안내로 처리한다', async () => {
  server.use(
    http.get(getPath, () =>
      HttpResponse.json({
        data: {
          ...result(),
          expiresAt: new Date(Date.now() - 1).toISOString(),
        },
      }),
    ),
  )
  await expect(mappingPreviewApi.read(91)).rejects.toMatchObject({
    status: 404,
    kind: 'not-found',
  })
  server.use(http.get(getPath, () => new HttpResponse(null, { status: 404 })))
  await expect(mappingPreviewApi.read(91)).rejects.toMatchObject({
    status: 404,
    kind: 'not-found',
  })
})

test('개발용 고정 표본도 1행 요청에서는 2행 오류를 만들지 않는다', async () => {
  const accepted = await mappingPreviewApi.request(draft, 1)
  expect((await mappingPreviewApi.read(accepted.previewId)).status).toBe(
    'RUNNING',
  )
  const completed = await mappingPreviewApi.read(accepted.previewId)
  expect(completed.rows).toHaveLength(1)
  expect(completed.rowErrors).toHaveLength(0)
})

function openSources() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <RouterProvider
        router={createMemoryRouter(
          [{ path: '/admin/sources', element: <SourcesPage /> }],
          { initialEntries: ['/admin/sources?source=1&tab=mapping'] },
        )}
      />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}

test('접수 전 404는 미리보기 만료가 아닌 대상 없음으로 안내한다', async () => {
  server.use(http.post(postPath, () => new HttpResponse(null, { status: 404 })))
  const user = openSources()
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '변환 미리보기 요청' }),
    ).toBeEnabled(),
  )
  await user.click(screen.getByRole('button', { name: '변환 미리보기 요청' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '요청한 항목을 찾을 수 없어요.',
  )
  expect(screen.queryByText(/미리보기가 만료되었거나/)).not.toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '변환 미리보기 요청' }),
  ).toBeEnabled()
})

test('실제 화면의 객체·속성 편집에서 API를 요청하며 저장 API는 호출하지 않는다', async () => {
  const post = vi.fn(async ({ request }: { request: Request }) => {
    const body = (await request.json()) as {
      mapping: { propertyMappings: unknown[] }
    }
    expect(body.mapping.propertyMappings).toHaveLength(1)
    return HttpResponse.json({ data: receipt() }, { status: 202 })
  })
  const save = vi.fn(() => HttpResponse.json({ data: {} }))
  let reads = 0
  server.use(
    http.post(postPath, post),
    http.get(getPath, () =>
      HttpResponse.json({
        data:
          ++reads === 1
            ? { ...result(), status: 'RUNNING', result: null }
            : result(),
      }),
    ),
    http.patch('/api/v1/ontology/object-mappings/:id', save),
    http.patch('/api/v1/ontology/property-mappings/:id', save),
    http.post('/api/v1/ontology/object-mappings', save),
    http.post('/api/v1/ontology/property-mappings', save),
  )
  const user = openSources()
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  const object = within(screen.getByRole('dialog', { name: '객체 매핑 편집' }))
  await waitFor(() =>
    expect(
      object.getByRole('button', { name: '변환 미리보기 요청' }),
    ).toBeEnabled(),
  )
  await user.dblClick(
    object.getByRole('button', { name: '변환 미리보기 요청' }),
  )
  await object.findByText('변환 미리보기 실행 중…')
  await object.findByRole('table', { name: '변환 객체 1 속성 결과' })
  expect(object.getByText(/원천 행 번호 미제공/)).toBeInTheDocument()
  expect(object.getByText(/원천 컬럼: amount/)).toBeInTheDocument()
  expect(object.getByRole('cell', { name: 'false' })).toBeInTheDocument()
  expect(screen.queryByText(/PRIVATE-ROW-SECRET/)).not.toBeInTheDocument()
  expect(post).toHaveBeenCalledTimes(1)
  await user.click(object.getByRole('button', { name: '닫기' }))
  await user.click(screen.getByRole('button', { name: '품목 #701' }))
  const edit = await screen.findByRole('button', {
    name: '품목명 속성 매핑 편집',
  })
  await waitFor(() => expect(edit).toBeEnabled())
  await user.click(edit)
  const property = within(
    screen.getByRole('dialog', { name: '속성 매핑 편집' }),
  )
  await waitFor(() =>
    expect(
      property.getByRole('button', { name: '변환 미리보기 요청' }),
    ).toBeEnabled(),
  )
  await user.click(property.getByRole('button', { name: '변환 미리보기 요청' }))
  await property.findByRole('table', { name: '변환 객체 1 속성 결과' })
  expect(post).toHaveBeenCalledTimes(2)
  expect(save).not.toHaveBeenCalled()
})

test('실제 결과 조회 오류는 원문 없이 안내하고 POST 없이 GET만 재시도한다', async () => {
  const post = vi.fn(() =>
    HttpResponse.json({ data: receipt() }, { status: 202 }),
  )
  let reads = 0
  server.use(
    http.post(postPath, post),
    http.get(getPath, () =>
      ++reads === 1
        ? HttpResponse.json(
            { error: { message: 'PRIVATE-ROW-SECRET' } },
            { status: 503 },
          )
        : HttpResponse.json({ data: result() }),
    ),
  )
  const user = openSources()
  await user.click(
    await screen.findByRole('button', { name: '품목 #701 객체 매핑 편집' }),
  )
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '변환 미리보기 요청' }),
    ).toBeEnabled(),
  )
  await user.click(screen.getByRole('button', { name: '변환 미리보기 요청' }))
  expect(await screen.findByRole('alert')).not.toHaveTextContent(
    'PRIVATE-ROW-SECRET',
  )
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  await screen.findByRole('table', { name: '변환 객체 1 속성 결과' })
  expect(post).toHaveBeenCalledTimes(1)
  expect(reads).toBe(2)
})
