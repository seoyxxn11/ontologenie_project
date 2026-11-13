import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import {
  ApiError,
  apiDelete,
  apiGet,
  apiPatch,
  apiPost,
  apiPut,
} from './client'

const fetchMock = vi.fn<typeof fetch>()

function clearCsrfCookie() {
  document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  document.cookie = 'OTHER-XSRF-TOKEN=; Max-Age=0; Path=/'
}

beforeEach(() => {
  fetchMock.mockReset()
  vi.stubGlobal('fetch', fetchMock)
  clearCsrfCookie()
})

afterEach(() => {
  clearCsrfCookie()
  vi.unstubAllGlobals()
})

test('기존 GET 호출은 data·페이지 정보를 유지하고 세션 쿠키를 사용한다', async () => {
  const body = {
    data: [{ id: 1 }],
    meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
  }
  document.cookie = 'XSRF-TOKEN=test-csrf; Path=/'
  fetchMock.mockResolvedValue(Response.json(body))

  expect(await apiGet('/objects?page=0')).toEqual(body)
  const [url, options] = fetchMock.mock.calls[0]
  expect(url).toBe('/api/v1/objects?page=0')
  expect(options?.credentials).toBe('same-origin')
  expect(options?.method).toBe('GET')
  expect(options?.body).toBeUndefined()
  const headers = new Headers(options?.headers)
  expect(headers.get('Accept')).toBe('application/json')
  expect(headers.has('Content-Type')).toBe(false)
  expect(headers.has('X-XSRF-TOKEN')).toBe(false)
  expect(headers.has('Authorization')).toBe(false)
})

test.each(['POST', 'PUT', 'PATCH', 'DELETE'] as const)(
  '%s는 현재 CSRF 쿠키를 디코딩해 헤더에 보낸다',
  async (method) => {
    document.cookie = 'OTHER-XSRF-TOKEN=wrong; Path=/'
    document.cookie = 'XSRF-TOKEN=test%2Bcsrf%3D; Path=/'
    fetchMock.mockResolvedValue(Response.json({ data: { id: 1 } }))

    if (method === 'POST') await apiPost('/objects', { name: '설비' })
    if (method === 'PUT')
      await apiPut('/sources/1/sync-schedule', { name: '설비' })
    if (method === 'PATCH') await apiPatch('/objects/1', { name: '설비' })
    if (method === 'DELETE') await apiDelete('/objects/1')

    const options = fetchMock.mock.calls[0][1]
    expect(options?.method).toBe(method)
    expect(options?.credentials).toBe('same-origin')
    const headers = new Headers(options?.headers)
    expect(headers.get('X-XSRF-TOKEN')).toBe('test+csrf=')
    expect(headers.has('Authorization')).toBe(false)
    if (method === 'DELETE') {
      expect(options?.body).toBeUndefined()
      expect(headers.has('Content-Type')).toBe(false)
    } else {
      expect(options?.body).toBe(JSON.stringify({ name: '설비' }))
      expect(headers.get('Content-Type')).toBe('application/json')
    }
  },
)

test('CSRF 토큰이 바뀌면 다음 요청에서 새 쿠키를 읽는다', async () => {
  fetchMock.mockImplementation(async () => Response.json({ data: null }))
  document.cookie = 'XSRF-TOKEN=first-test-token; Path=/'
  await apiPatch('/objects/1', {})
  document.cookie = 'XSRF-TOKEN=second-test-token; Path=/'
  await apiPatch('/objects/1', {})

  expect(
    new Headers(fetchMock.mock.calls[0][1]?.headers).get('X-XSRF-TOKEN'),
  ).toBe('first-test-token')
  expect(
    new Headers(fetchMock.mock.calls[1][1]?.headers).get('X-XSRF-TOKEN'),
  ).toBe('second-test-token')
})

test('CSRF 쿠키 없는 로그인도 서버에 요청하고 쿠키를 임의 생성하지 않는다', async () => {
  fetchMock.mockResolvedValue(Response.json({ data: { id: 1 } }))
  await apiPost('/auth/login', { loginId: 'test-user', password: 'test-only' })

  expect(
    new Headers(fetchMock.mock.calls[0][1]?.headers).has('X-XSRF-TOKEN'),
  ).toBe(false)
  expect(document.cookie).not.toContain('XSRF-TOKEN=')
})

test('잘못 인코딩된 CSRF 쿠키는 그대로 전달하지 않는다', async () => {
  document.cookie = 'XSRF-TOKEN=%E0%A4%A; Path=/'
  fetchMock.mockResolvedValue(Response.json({ data: null }))
  await apiPatch('/objects/1', {})
  expect(
    new Headers(fetchMock.mock.calls[0][1]?.headers).has('X-XSRF-TOKEN'),
  ).toBe(false)
})

test('로그아웃 등 204 응답을 JSON으로 읽지 않는다', async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
  expect(await apiPost('/auth/logout')).toBeUndefined()
})

test('400 입력 오류의 전체 필드·오류 코드·추적 ID를 보존한다', async () => {
  const details = {
    field: 'name',
    reason: '공백일 수 없습니다',
    errors: [
      { field: 'name', reason: '공백일 수 없습니다' },
      { field: 'size', reason: '널이어서는 안됩니다' },
      { field: 'invalid' },
    ],
  }
  fetchMock.mockResolvedValue(
    Response.json(
      {
        error: {
          code: 'VALIDATION_FAILED',
          message: '요청 필드를 확인해 주세요.',
          details,
          requestId: 'test-request-id',
        },
      },
      { status: 400 },
    ),
  )

  await expect(apiPost('/objects', {})).rejects.toMatchObject({
    name: 'ApiError',
    kind: 'validation',
    status: 400,
    code: 'VALIDATION_FAILED',
    message: '요청 필드를 확인해 주세요.',
    details,
    requestId: 'test-request-id',
    fieldErrors: details.errors.slice(0, 2),
  })
})

test('단일 field·reason 형식의 업무 오류도 폼에서 사용할 수 있다', async () => {
  fetchMock.mockResolvedValue(
    Response.json(
      {
        error: {
          code: 'BUSINESS_RULE_VIOLATION',
          message: '업무 규칙에 맞지 않습니다.',
          details: {
            field: 'status',
            reason: '현재 상태에서는 변경할 수 없습니다',
          },
          requestId: 'test-id',
        },
      },
      { status: 422 },
    ),
  )
  await expect(apiPatch('/objects/1', {})).rejects.toMatchObject({
    fieldErrors: [
      { field: 'status', reason: '현재 상태에서는 변경할 수 없습니다' },
    ],
  })
})

test.each([
  [401, 'UNAUTHENTICATED', 'unauthenticated'],
  [403, 'FORBIDDEN', 'forbidden'],
  [404, 'RESOURCE_NOT_FOUND', 'not-found'],
  [409, 'CONFLICT', 'conflict'],
  [409, 'VERSION_CONFLICT', 'conflict'],
  [409, 'IN_USE', 'conflict'],
  [500, 'INTERNAL_ERROR', 'server'],
] as const)('%i %s를 공통 오류로 구분한다', async (status, code, kind) => {
  fetchMock.mockResolvedValue(
    Response.json(
      {
        error: {
          code,
          message: '서버가 제공한 안내',
          details: { expected: 1, actual: 2 },
          requestId: 'trace-id',
        },
      },
      { status },
    ),
  )

  await expect(apiGet('/objects')).rejects.toMatchObject({
    kind,
    status,
    code,
    message: '서버가 제공한 안내',
    details: { expected: 1, actual: 2 },
    requestId: 'trace-id',
  })
})

test('CSRF 오류와 임시 비밀번호 403의 서버 안내 문구를 유지한다', async () => {
  for (const message of [
    'CSRF 토큰이 없거나 올바르지 않습니다.',
    '비밀번호를 변경한 뒤 이용할 수 있습니다.',
  ]) {
    fetchMock.mockResolvedValue(
      Response.json(
        {
          error: {
            code: 'FORBIDDEN',
            message,
            details: {},
            requestId: 'test-id',
          },
        },
        { status: 403 },
      ),
    )
    await expect(apiPatch('/objects/1', {})).rejects.toMatchObject({
      kind: 'forbidden',
      message,
    })
  }
})

test.each([400, 401, 403, 404, 405, 409, 422, 502])(
  '오류 본문이 JSON이 아니어도 HTTP %i와 추적 ID를 유지한다',
  async (status) => {
    fetchMock.mockResolvedValue(
      new Response('<html>proxy error</html>', {
        status,
        headers: { 'X-Request-Id': 'header-trace' },
      }),
    )
    await expect(apiGet('/objects')).rejects.toMatchObject({
      status,
      code: null,
      requestId: 'header-trace',
      details: {},
      fieldErrors: [],
    })
  },
)

test.each([null, { error: null }, { error: { message: 1, details: [] } }])(
  '잘못된 오류 구조도 안전한 공통 안내로 바꾼다: %j',
  async (body) => {
    fetchMock.mockResolvedValue(Response.json(body, { status: 500 }))
    await expect(apiGet('/objects')).rejects.toMatchObject({
      kind: 'server',
      message: '서버 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.',
    })
  },
)

test('연결 실패는 서버 오류와 구분하고 자동으로 다시 보내지 않는다', async () => {
  fetchMock.mockRejectedValue(new TypeError('Failed to fetch'))
  await expect(apiPost('/actions', {})).rejects.toMatchObject({
    kind: 'network',
    status: null,
    code: null,
    requestId: null,
  })
  expect(fetchMock).toHaveBeenCalledTimes(1)
})

test('요청 취소는 연결 실패와 구분하고 signal을 전달한다', async () => {
  const controller = new AbortController()
  controller.abort()
  fetchMock.mockRejectedValue(new DOMException('Aborted', 'AbortError'))

  await expect(
    apiGet('/objects', { signal: controller.signal }),
  ).rejects.toMatchObject({
    kind: 'aborted',
  })
  expect(fetchMock.mock.calls[0][1]?.signal).toBe(controller.signal)
})

test.each([200, 400])(
  'HTTP %i 본문을 읽는 중의 취소도 구분한다',
  async (status) => {
    const response = Response.json({ data: null }, { status })
    vi.spyOn(response, 'json').mockRejectedValue(
      new DOMException('Aborted', 'AbortError'),
    )
    fetchMock.mockResolvedValue(response)
    await expect(apiGet('/objects')).rejects.toMatchObject({ kind: 'aborted' })
  },
)

test.each([new Response('not-json'), Response.json({ items: [] })])(
  '성공 상태여도 잘못된 공통 응답은 오류로 처리한다',
  async (response) => {
    fetchMock.mockResolvedValue(response)
    await expect(apiGet('/objects')).rejects.toMatchObject({
      kind: 'invalid-response',
      status: 200,
    })
  },
)

test('GET의 빈 204는 정상 조회 데이터로 위장하지 않는다', async () => {
  fetchMock.mockResolvedValue(new Response(null, { status: 204 }))
  await expect(apiGet('/objects')).rejects.toMatchObject({
    kind: 'invalid-response',
    status: 204,
  })
})

test('공통 오류는 기존 화면의 Error.message 사용과 호환된다', () => {
  const error = new ApiError('안내 메시지', {
    status: 401,
    kind: 'unauthenticated',
  })
  expect(error).toBeInstanceOf(Error)
  expect(error.message).toBe('안내 메시지')
})
