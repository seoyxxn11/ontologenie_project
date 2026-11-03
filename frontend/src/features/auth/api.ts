import { apiGet, apiPatch, apiPost, ApiError } from '../../api/client'
import type { Account, LoginInput } from './types'

export async function fetchMyAccount(signal?: AbortSignal) {
  try {
    return (await apiGet<Account>('/auth/me', { signal })).data
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) return null
    throw error
  }
}

export async function login(input: LoginInput) {
  const response = await apiPost<Account>('/auth/login', input)
  if (!response)
    throw new ApiError('로그인 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  return response.data
}

export function logout() {
  return apiPost('/auth/logout')
}

export function changePassword(currentPassword: string, newPassword: string) {
  return apiPatch('/auth/password', { currentPassword, newPassword })
}
