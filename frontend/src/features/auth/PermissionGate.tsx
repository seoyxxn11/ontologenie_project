import type { ReactNode } from 'react'
import { useAuth } from './auth-context'
import { canAccess, type ScreenAccess } from './access'

function PermissionGate({
  access,
  children,
}: {
  access: ScreenAccess
  children: ReactNode
}) {
  const { account, pending, error } = useAuth()
  return !pending && !error && canAccess(account, access) ? children : null
}

export default PermissionGate
