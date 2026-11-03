import type { ApiFieldError } from './types'

export type ApiErrorKind =
  | 'unauthenticated'
  | 'forbidden'
  | 'validation'
  | 'not-found'
  | 'conflict'
  | 'server'
  | 'http'
  | 'network'
  | 'aborted'
  | 'invalid-response'

export function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function isFieldError(value: unknown): value is ApiFieldError {
  return (
    isRecord(value) &&
    typeof value.field === 'string' &&
    typeof value.reason === 'string'
  )
}

export function getHttpErrorKind(status: number): ApiErrorKind {
  if (status === 401) return 'unauthenticated'
  if (status === 403) return 'forbidden'
  if (status === 400 || status === 422) return 'validation'
  if (status === 404) return 'not-found'
  if (status === 409) return 'conflict'
  if (status >= 500) return 'server'
  return 'http'
}

export function getHttpErrorMessage(status: number): string {
  switch (getHttpErrorKind(status)) {
    case 'unauthenticated':
      return '로그인이 필요하거나 세션이 만료됐어요.'
    case 'forbidden':
      return '요청을 실행할 권한이 없어요.'
    case 'validation':
      return '입력한 내용을 확인해 주세요.'
    case 'not-found':
      return '요청한 항목을 찾을 수 없어요.'
    case 'conflict':
      return '이미 처리됐거나 다른 변경과 충돌했어요. 최신 상태를 확인해 주세요.'
    case 'server':
      return '서버 요청을 처리하지 못했어요. 잠시 후 다시 시도해 주세요.'
    default:
      return `요청에 실패했어요. (HTTP ${status})`
  }
}

export class ApiError extends Error {
  readonly status: number | null
  readonly code: string | null
  readonly details: Record<string, unknown>
  readonly requestId: string | null
  readonly kind: ApiErrorKind

  constructor(
    message: string,
    options: {
      status?: number
      code?: string
      details?: Record<string, unknown>
      requestId?: string
      kind: ApiErrorKind
    },
  ) {
    super(message)
    this.name = 'ApiError'
    this.status = options.status ?? null
    this.code = options.code ?? null
    this.details = options.details ?? {}
    this.requestId = options.requestId ?? null
    this.kind = options.kind
  }

  get fieldErrors(): ApiFieldError[] {
    const errors = this.details.errors
    if (Array.isArray(errors)) {
      const fields = errors.filter(isFieldError)
      if (fields.length > 0) return fields
    }
    if (isFieldError(this.details)) {
      return [{ field: this.details.field, reason: this.details.reason }]
    }
    return []
  }
}
