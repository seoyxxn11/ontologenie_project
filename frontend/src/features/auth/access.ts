import type { Account } from './types'

const workspaceRoles = ['ADMIN', 'PLANNER', 'MAINTENANCE', 'VIEWER'] as const

// 화면 접근 분류이며 서버의 세부 업무 권한 코드를 대신하지 않는다.
export type ScreenAccess = 'workspace' | 'administration'

export function canAccess(
  account: Account | null | undefined,
  access: ScreenAccess,
): boolean {
  if (!account || account.status !== 'ACTIVE' || account.mustChangePassword)
    return false
  if (access === 'administration') return account.roleCode === 'ADMIN'
  return workspaceRoles.some((role) => role === account.roleCode)
}

export const roleLabels: Record<Account['roleCode'], string> = {
  ADMIN: '관리자',
  PLANNER: '생산기획자',
  MAINTENANCE: '정비 담당',
  VIEWER: '조회 사용자',
}
