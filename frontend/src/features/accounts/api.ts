import { apiGet, apiPatch, apiPost, ApiError } from '../../api/client'
import type { Account } from '../auth/types'

export type Role = {
  code: Account['roleCode']
  name: string
  permissions: string[]
}
export type AccountFilters = { q?: string; status?: string; roleCode?: string }
export type CreateAccountInput = {
  loginId: string
  displayName: string
  roleCode: Account['roleCode']
  temporaryPassword: string
}
export type AccountChanges = {
  displayName?: string
  roleCode?: Account['roleCode']
  status?: Account['status']
  rowVersion: number
}
export const ACCOUNT_PAGE_SIZE = 20

export function fetchAccounts(
  filters: AccountFilters,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(ACCOUNT_PAGE_SIZE),
  })
  for (const [key, value] of Object.entries(filters))
    if (value) params.set(key, value)
  return apiGet<Account[]>(`/admin/accounts?${params}`, { signal })
}
export async function fetchRoles(signal?: AbortSignal) {
  return (await apiGet<Role[]>('/admin/roles', { signal })).data
}
export async function fetchAccountEditor(id: number, signal?: AbortSignal) {
  const [response, roles] = await Promise.all([
    apiGet<Account>(`/admin/accounts/${id}`, { signal }),
    fetchRoles(signal),
  ])
  return { account: response.data, roles }
}
export async function createAccount(input: CreateAccountInput) {
  const response = await apiPost<Account>('/admin/accounts', input)
  if (!response?.data)
    throw new ApiError(
      '생성 응답을 확인할 수 없습니다. 목록에서 생성 여부를 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}
export async function updateAccount(id: number, input: AccountChanges) {
  const response = await apiPatch<Account>(`/admin/accounts/${id}`, input)
  if (!response?.data)
    throw new ApiError(
      '수정 응답을 확인할 수 없습니다. 최신 계정 정보를 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}

export const statusLabels: Record<Account['status'], string> = {
  ACTIVE: '활성',
  LOCKED: '잠김',
  DISABLED: '비활성',
}

export function temporaryPasswordError(loginId: string, password: string) {
  if (Array.from(password).length < 8)
    return '임시 비밀번호는 8자 이상이어야 합니다.'
  if (new TextEncoder().encode(password).length > 72)
    return '임시 비밀번호는 72바이트를 넘을 수 없습니다.'
  const punctuation = Array.from(password).some((char) => {
    const code = char.charCodeAt(0)
    return (
      (code >= 33 && code <= 47) ||
      (code >= 58 && code <= 64) ||
      (code >= 91 && code <= 96) ||
      (code >= 123 && code <= 126)
    )
  })
  if (!/[A-Za-z]/.test(password) || !/[0-9]/.test(password) || !punctuation)
    return '임시 비밀번호에 영문·숫자·특수문자를 모두 포함해 주세요.'
  if (password === loginId) return '임시 비밀번호는 로그인 ID와 달라야 합니다.'
  return ''
}
