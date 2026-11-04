import { delay, http, HttpResponse } from 'msw'
import type { Account } from '../features/auth/types'
import { temporaryPasswordError, type Role } from '../features/accounts/api'
import { getMockAccount, setMockAccount } from './auth'

const roles: Role[] = [
  { code: 'ADMIN', name: '관리자', permissions: ['ACCOUNT_MANAGE'] },
  { code: 'PLANNER', name: '생산기획자', permissions: [] },
  { code: 'MAINTENANCE', name: '정비 담당', permissions: [] },
  { code: 'VIEWER', name: '조회 사용자', permissions: [] },
]
export const accountFixtures: Account[] = [
  {
    id: 7,
    loginId: 'demo-admin',
    displayName: '관리자',
    roleCode: 'ADMIN',
    status: 'ACTIVE',
    mustChangePassword: false,
    rowVersion: 0,
  },
  {
    id: 8,
    loginId: 'demo-viewer',
    displayName: '조회 담당',
    roleCode: 'VIEWER',
    status: 'ACTIVE',
    mustChangePassword: false,
    rowVersion: 0,
  },
  {
    id: 9,
    loginId: 'demo-locked',
    displayName: '잠긴 계정',
    roleCode: 'PLANNER',
    status: 'LOCKED',
    mustChangePassword: true,
    rowVersion: 1,
  },
]
let accounts = structuredClone(accountFixtures)
export function resetAccountMocks() {
  accounts = structuredClone(accountFixtures)
}
function fail(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-account' } },
    { status },
  )
}
function denied() {
  const current = getMockAccount()
  return !current
    ? fail(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
    : current.roleCode !== 'ADMIN'
      ? fail(403, 'FORBIDDEN', '관리자만 계정을 관리할 수 있습니다.')
      : undefined
}

export const accountHandlers = [
  http.get('/api/v1/admin/roles', async () => {
    await delay()
    return denied() ?? HttpResponse.json({ data: roles })
  }),
  http.get('/api/v1/admin/accounts', async ({ request }) => {
    await delay()
    const error = denied()
    if (error) return error
    const params = new URL(request.url).searchParams
    const q = (params.get('q') ?? '').toLowerCase()
    const page = Number(params.get('page') ?? 0)
    const size = Number(params.get('size') ?? 20)
    const matches = accounts.filter(
      (item) =>
        (item.loginId.toLowerCase().includes(q) ||
          item.displayName.toLowerCase().includes(q)) &&
        (!params.get('status') || params.get('status') === item.status) &&
        (!params.get('roleCode') || params.get('roleCode') === item.roleCode),
    )
    return HttpResponse.json({
      data: matches.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: matches.length,
        hasNext: (page + 1) * size < matches.length,
      },
    })
  }),
  http.get('/api/v1/admin/accounts/:id', async ({ params }) => {
    await delay()
    const error = denied()
    if (error) return error
    const item = accounts.find((item) => item.id === Number(params.id))
    return item
      ? HttpResponse.json({ data: item })
      : fail(404, 'RESOURCE_NOT_FOUND', '계정을 찾을 수 없습니다.')
  }),
  http.post('/api/v1/admin/accounts', async ({ request }) => {
    await delay()
    const error = denied()
    if (error) return error
    const body = (await request.json()) as {
      loginId: string
      displayName: string
      roleCode: Account['roleCode']
      temporaryPassword: string
    }
    if (
      !body.loginId?.trim() ||
      !body.displayName?.trim() ||
      !roles.some((role) => role.code === body.roleCode) ||
      temporaryPasswordError(body.loginId, body.temporaryPassword ?? '')
    )
      return fail(400, 'VALIDATION_FAILED', '계정 입력값을 확인해 주세요.')
    if (accounts.some((item) => item.loginId === body.loginId))
      return fail(409, 'CONFLICT', '이미 등록된 로그인 ID입니다.')
    const item: Account = {
      id: Math.max(...accounts.map((item) => item.id)) + 1,
      loginId: body.loginId,
      displayName: body.displayName,
      roleCode: body.roleCode,
      status: 'ACTIVE',
      mustChangePassword: true,
      rowVersion: 0,
    }
    accounts.push(item)
    return HttpResponse.json({ data: item }, { status: 201 })
  }),
  http.patch('/api/v1/admin/accounts/:id', async ({ params, request }) => {
    await delay()
    const error = denied()
    if (error) return error
    const item = accounts.find((item) => item.id === Number(params.id))
    if (!item)
      return fail(404, 'RESOURCE_NOT_FOUND', '계정을 찾을 수 없습니다.')
    const body = (await request.json()) as {
      displayName?: string
      roleCode?: Account['roleCode']
      status?: Account['status']
      rowVersion: number
    }
    if (body.rowVersion !== item.rowVersion)
      return fail(409, 'VERSION_CONFLICT', '다른 관리자가 먼저 수정했습니다.')
    const roleCode = body.roleCode ?? item.roleCode
    const status = body.status ?? item.status
    if (
      item.roleCode === 'ADMIN' &&
      item.status === 'ACTIVE' &&
      (roleCode !== 'ADMIN' || status !== 'ACTIVE') &&
      !accounts.some(
        (value) =>
          value.id !== item.id &&
          value.roleCode === 'ADMIN' &&
          value.status === 'ACTIVE',
      )
    )
      return fail(
        422,
        'BUSINESS_RULE_VIOLATION',
        '마지막 활성 관리자 계정의 역할이나 상태는 바꿀 수 없습니다.',
      )
    const expires =
      roleCode !== item.roleCode ||
      body.status === 'LOCKED' ||
      body.status === 'DISABLED'
    if (body.displayName !== undefined) item.displayName = body.displayName
    item.roleCode = roleCode
    item.status = status
    item.rowVersion += 1
    if (getMockAccount()?.id === item.id)
      setMockAccount(expires ? null : { ...item })
    return HttpResponse.json({ data: item })
  }),
]
