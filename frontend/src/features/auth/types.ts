export type Account = {
  id: number
  loginId: string
  displayName: string
  roleCode: 'ADMIN' | 'PLANNER' | 'MAINTENANCE' | 'VIEWER'
  status: 'ACTIVE' | 'LOCKED' | 'DISABLED'
  mustChangePassword: boolean
  rowVersion: number
}

export type LoginInput = { loginId: string; password: string }
