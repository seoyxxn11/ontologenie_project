import { expect, test, vi } from 'vitest'
import { demoAccount } from './auth'
import { createMockAuthSession } from './mockAuthSession'

function storage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value)
    },
    removeItem: (key: string) => {
      values.delete(key)
    },
    values,
  }
}

test('새 세션 인스턴스를 만들어도 개발용 계정·권한·비밀번호 변경 필요 상태를 복원한다', () => {
  const saved = storage()
  const account = {
    ...demoAccount,
    loginId: 'demo-admin',
    roleCode: 'ADMIN' as const,
    mustChangePassword: true,
  }
  createMockAuthSession(saved).set(account)
  expect(createMockAuthSession(saved).get()).toEqual(account)
})

test('로그아웃한 뒤 새로고침해도 계정을 복원하지 않는다', () => {
  const saved = storage()
  const session = createMockAuthSession(saved)
  session.set(demoAccount)
  session.set(null)
  expect(saved.values.size).toBe(0)
  expect(createMockAuthSession(saved).get()).toBeNull()
})

test('비밀번호 등 Account 외의 값은 저장하지 않는다', () => {
  const saved = storage()
  const accountWithExtraField = {
    ...demoAccount,
    password: 'never-store-this',
  }
  createMockAuthSession(saved).set(accountWithExtraField)
  expect([...saved.values.values()].join('')).not.toContain('password')
  expect([...saved.values.values()].join('')).not.toContain('never-store-this')
})

test('손상된 값·잘못된 계정·비활성 계정은 로그인 상태로 복원하지 않는다', () => {
  for (const raw of [
    '{bad json',
    '{"id":7}',
    JSON.stringify({ ...demoAccount, roleCode: 'UNKNOWN' }),
    JSON.stringify({ ...demoAccount, status: 'DISABLED' }),
  ]) {
    const saved = storage()
    saved.values.set('ontologenie.dev.mock-account', raw)
    expect(createMockAuthSession(saved).get()).toBeNull()
  }
})

test('브라우저 저장소를 사용할 수 없어도 로그인·로그아웃은 메모리에서 동작한다', () => {
  const denied = vi.fn(() => {
    throw new Error('Storage unavailable')
  })
  const session = createMockAuthSession({
    getItem: denied,
    setItem: denied,
    removeItem: denied,
  })
  session.set(demoAccount)
  expect(session.get()).toEqual(demoAccount)
  session.set(null)
  expect(session.get()).toBeNull()
})

test('저장소 없는 테스트 세션 사이에는 계정이 공유되지 않는다', () => {
  createMockAuthSession().set(demoAccount)
  expect(createMockAuthSession().get()).toBeNull()
})
