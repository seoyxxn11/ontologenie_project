export type PageMeta = {
  page: number
  size: number
  totalElements: number
  hasNext: boolean
}

export type ApiResponse<T, M = PageMeta> = {
  data: T
  meta?: M
}

export type ApiErrorBody = {
  error: {
    code: string
    message: string
    details: Record<string, unknown>
    requestId: string
  }
}
