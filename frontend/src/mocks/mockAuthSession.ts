import type { Account } from '../features/auth/types'

const STORAGE_KEY = 'ontologenie.dev.mock-account'
type SessionStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>

function validAccount(value: unknown): value is Account {
  if (!value || typeof value !== 'object') return false
  const account = value as Record<string, unknown>
  return (
    Number.isInteger(account.id) &&
    Number(account.id) > 0 &&
    typeof account.loginId === 'string' &&
    account.loginId.length > 0 &&
    typeof account.displayName === 'string' &&
    ['ADMIN', 'PLANNER', 'MAINTENANCE', 'VIEWER'].includes(
      String(account.roleCode),
    ) &&
    account.status === 'ACTIVE' &&
    typeof account.mustChangePassword === 'boolean' &&
    Number.isInteger(account.rowVersion) &&
    Number(account.rowVersion) >= 0
  )
}

// Account 필드만 저장한다. 로그인 요청의 비밀번호나 추가 필드는 저장하지 않는다.
function accountFields(account: Account): Account {
  const {
    id,
    loginId,
    displayName,
    roleCode,
    status,
    mustChangePassword,
    rowVersion,
  } = account
  return {
    id,
    loginId,
    displayName,
    roleCode,
    status,
    mustChangePassword,
    rowVersion,
  }
}

export function createMockAuthSession(storage?: SessionStorage) {
  let account: Account | null = null
  try {
    const saved = storage?.getItem(STORAGE_KEY)
    if (saved) {
      const value: unknown = JSON.parse(saved)
      if (validAccount(value)) account = accountFields(value)
      else storage?.removeItem(STORAGE_KEY)
    }
  } catch {
    // 저장소가 제한되거나 값이 손상되어도 개발용 로그인 화면은 계속 사용할 수 있다.
  }

  return {
    get: () => account,
    set(next: Account | null) {
      account = next
      try {
        if (next)
          storage?.setItem(STORAGE_KEY, JSON.stringify(accountFields(next)))
        else storage?.removeItem(STORAGE_KEY)
      } catch {
        // 저장할 수 없는 브라우저에서는 기존처럼 메모리에서만 로그인 상태를 유지한다.
      }
    },
  }
}
