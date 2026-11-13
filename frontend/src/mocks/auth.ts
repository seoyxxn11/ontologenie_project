import { http, HttpResponse } from 'msw'
import type { Account } from '../features/auth/types'
import { createMockAuthSession } from './mockAuthSession'

export const demoAccount: Account = {
  id: 7,
  loginId: 'demo-user',
  displayName: '체험 사용자',
  roleCode: 'PLANNER',
  status: 'ACTIVE',
  mustChangePassword: false,
  rowVersion: 0,
}
let session = createMockAuthSession()

// browser.ts에서만 켠다. 자동 테스트와 운영의 서버 인증에는 브라우저 저장소를 사용하지 않는다.
export function restoreBrowserMockSession() {
  try {
    session = createMockAuthSession(window.sessionStorage)
  } catch {
    // 저장소 접근이 제한된 경우 메모리 세션을 유지한다.
  }
}

// 개발·자동 테스트 전용이다. 실제 세션·쿠키 검증에는 서버를 사용한다.
export function setMockAccount(account: Account | null) {
  session.set(account)
}

export function getMockAccount() {
  return session.get()
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
  http.get('/api/v1/auth/me', () => {
    const account = getMockAccount()
    return account ? HttpResponse.json({ data: account }) : denied()
  }),
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
    const account = { ...demoAccount, loginId: body.loginId!, roleCode }
    setMockAccount(account)
    return HttpResponse.json({ data: account })
  }),
  http.post('/api/v1/auth/logout', () => {
    setMockAccount(null)
    return new HttpResponse(null, { status: 204 })
  }),
  http.patch('/api/v1/auth/password', () => {
    const account = getMockAccount()
    if (!account) return denied()
    setMockAccount({ ...account, mustChangePassword: false })
    return new HttpResponse(null, { status: 204 })
  }),
]
