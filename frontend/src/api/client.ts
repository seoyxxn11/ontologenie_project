import type { ApiErrorBody, ApiResponse, PageMeta } from './types'

const API_BASE = '/api/v1'

async function readErrorMessage(response: Response) {
  try {
    const body: ApiErrorBody = await response.json()
    return body.error.message
  } catch {
    return `요청에 실패했어요. (HTTP ${response.status})`
  }
}

export async function apiGet<T, M = PageMeta>(
  path: string,
): Promise<ApiResponse<T, M>> {
  const response = await fetch(`${API_BASE}${path}`)
  if (!response.ok) {
    throw new Error(await readErrorMessage(response))
  }
  return response.json()
}
