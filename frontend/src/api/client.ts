import {
  ApiError,
  getHttpErrorKind,
  getHttpErrorMessage,
  isRecord,
} from './errors'
import type { ApiResponse, PageMeta } from './types'

export { ApiError } from './errors'

const API_BASE = '/api/v1'
type ApiMethod = 'GET' | 'POST' | 'PATCH' | 'DELETE'

export type ApiRequestOptions = {
  signal?: AbortSignal
}

function readCsrfToken(): string | undefined {
  const prefix = 'XSRF-TOKEN='
  const cookie = document.cookie
    .split(';')
    .map((entry) => entry.trim())
    .find((entry) => entry.startsWith(prefix))
  if (!cookie) return undefined
  try {
    return decodeURIComponent(cookie.slice(prefix.length)) || undefined
  } catch {
    // 잘못 인코딩된 쿠키는 전달하지 않고 서버의 CSRF 검사에 맡긴다.
    return undefined
  }
}

function throwIfAborted(error: unknown, signal?: AbortSignal): void {
  if (signal?.aborted || (isRecord(error) && error.name === 'AbortError')) {
    throw new ApiError('요청이 취소됐어요.', { kind: 'aborted' })
  }
}

async function readHttpError(
  response: Response,
  signal?: AbortSignal,
): Promise<ApiError> {
  let body: unknown
  try {
    body = await response.json()
  } catch (error) {
    throwIfAborted(error, signal)
    // nginx의 HTML이나 빈 오류 응답도 원래 HTTP 상태를 유지한다.
  }
  const error: Record<string, unknown> =
    isRecord(body) && isRecord(body.error) ? body.error : {}
  return new ApiError(
    typeof error.message === 'string' && error.message.trim()
      ? error.message
      : getHttpErrorMessage(response.status),
    {
      status: response.status,
      kind: getHttpErrorKind(response.status),
      code: typeof error.code === 'string' ? error.code : undefined,
      details: isRecord(error.details) ? error.details : undefined,
      requestId:
        typeof error.requestId === 'string' && error.requestId
          ? error.requestId
          : (response.headers.get('X-Request-Id') ?? undefined),
    },
  )
}

async function request<T, M>(
  method: ApiMethod,
  path: string,
  body: unknown,
  options: ApiRequestOptions,
): Promise<ApiResponse<T, M> | undefined> {
  const headers = new Headers({ Accept: 'application/json' })
  const serializedBody = body === undefined ? undefined : JSON.stringify(body)
  if (serializedBody !== undefined) {
    headers.set('Content-Type', 'application/json')
  }
  if (method !== 'GET') {
    const token = readCsrfToken()
    if (token) headers.set('X-XSRF-TOKEN', token)
  }

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      method,
      headers,
      credentials: 'same-origin',
      body: serializedBody,
      signal: options.signal,
    })
  } catch (error) {
    throwIfAborted(error, options.signal)
    throw new ApiError(
      '서버에 연결할 수 없어요. 네트워크 상태를 확인해 주세요.',
      {
        kind: 'network',
      },
    )
  }

  if (!response.ok) throw await readHttpError(response, options.signal)
  if (response.status === 204) return undefined

  let result: unknown
  try {
    result = await response.json()
  } catch (error) {
    throwIfAborted(error, options.signal)
    throw invalidResponse(response)
  }
  if (!isRecord(result) || !Object.hasOwn(result, 'data')) {
    throw invalidResponse(response)
  }
  return result as ApiResponse<T, M>
}

function invalidResponse(response: Response): ApiError {
  return new ApiError(
    '서버 응답을 확인할 수 없어요. 잠시 후 다시 시도해 주세요.',
    {
      status: response.status,
      kind: 'invalid-response',
      requestId: response.headers.get('X-Request-Id') ?? undefined,
    },
  )
}

export async function apiGet<T, M = PageMeta>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<ApiResponse<T, M>> {
  const result = await request<T, M>('GET', path, undefined, options)
  if (result === undefined) {
    throw new ApiError('조회 응답에 필요한 데이터가 없어요.', {
      status: 204,
      kind: 'invalid-response',
    })
  }
  return result
}

export function apiPost<T = unknown, M = PageMeta>(
  path: string,
  body?: unknown,
  options: ApiRequestOptions = {},
): Promise<ApiResponse<T, M> | undefined> {
  return request<T, M>('POST', path, body, options)
}

export function apiPatch<T = unknown, M = PageMeta>(
  path: string,
  body?: unknown,
  options: ApiRequestOptions = {},
): Promise<ApiResponse<T, M> | undefined> {
  return request<T, M>('PATCH', path, body, options)
}

export function apiDelete<T = unknown, M = PageMeta>(
  path: string,
  options: ApiRequestOptions = {},
): Promise<ApiResponse<T, M> | undefined> {
  return request<T, M>('DELETE', path, undefined, options)
}
