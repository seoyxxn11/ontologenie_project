import { HttpResponse, type HttpResponseResolver } from 'msw'
import { getMockAccount } from './auth'

export function mockFailure(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-contract' } },
    { status },
  )
}

export function requireMockLogin() {
  const account = getMockAccount()
  if (!account)
    return mockFailure(401, 'UNAUTHENTICATED', '로그인이 필요합니다.')
  if (account.status !== 'ACTIVE' || account.mustChangePassword)
    return mockFailure(403, 'FORBIDDEN', '현재 계정으로 요청할 수 없습니다.')
}

export function requireMockAdmin() {
  return (
    requireMockLogin() ??
    (getMockAccount()?.roleCode !== 'ADMIN'
      ? mockFailure(403, 'FORBIDDEN', '관리자만 요청할 수 있습니다.')
      : undefined)
  )
}

export function adminOnly(resolve: HttpResponseResolver): HttpResponseResolver {
  return (args) => requireMockAdmin() ?? resolve(args)
}
