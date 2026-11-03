import { http, HttpResponse } from 'msw'
import type { Account } from '../features/auth/types'

export const demoAccount: Account = {
  id: 7,
  loginId: 'demo-user',
  displayName: '체험 사용자',
  roleCode: 'PLANNER',
  status: 'ACTIVE',
  mustChangePassword: false,
  rowVersion: 0,
}
let currentAccount: Account | null = null

// 개발·자동 테스트 전용이다. 실제 세션·쿠키 검증에는 서버를 사용한다.
export function setMockAccount(account: Account | null) {
  currentAccount = account
}

function denied(message = '로그인이 필요합니다.') {
  return HttpResponse.json(
    {
      error: {
        code: 'UNAUTHENTICATED',
        message,
        details: {},
        requestId: 'mock-auth',
      },
    },
    { status: 401 },
  )
}

export const authHandlers = [
  http.get('/api/v1/auth/me', () =>
    currentAccount ? HttpResponse.json({ data: currentAccount }) : denied(),
  ),
  http.post('/api/v1/auth/login', async ({ request }) => {
    const body = (await request.json()) as {
      loginId?: string
      password?: string
    }
    const demos: Record<string, Account['roleCode']> = {
      'demo-user': 'PLANNER',
      'demo-admin': 'ADMIN',
      'demo-maintenance': 'MAINTENANCE',
      'demo-viewer': 'VIEWER',
    }
    const roleCode =
      typeof body.loginId === 'string' && Object.hasOwn(demos, body.loginId)
        ? demos[body.loginId]
        : undefined
    if (!roleCode || body.password !== 'demo-only')
      return denied('로그인 ID 또는 비밀번호가 올바르지 않습니다.')
    currentAccount = { ...demoAccount, loginId: body.loginId!, roleCode }
    return HttpResponse.json({ data: currentAccount })
  }),
  http.post('/api/v1/auth/logout', () => {
    currentAccount = null
    return new HttpResponse(null, { status: 204 })
  }),
  http.patch('/api/v1/auth/password', () => {
    if (!currentAccount) return denied()
    currentAccount = { ...currentAccount, mustChangePassword: false }
    return new HttpResponse(null, { status: 204 })
  }),
]
