import { createContext, useContext } from 'react'
import type { Account, LoginInput } from './types'

type AuthContextValue = {
  account: Account | null | undefined
  pending: boolean
  error: Error | null
  refresh: () => Promise<{ error: Error | null }>
  signIn: (input: LoginInput) => Promise<void>
  signOut: () => Promise<void>
}

export const AuthContext = createContext<AuthContextValue | null>(null)
export function useAuth() {
  const context = useContext(AuthContext)
  if (!context)
    throw new Error('AuthProvider 안에서 인증 상태를 사용해야 합니다.')
  return context
}
