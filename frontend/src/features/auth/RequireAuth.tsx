import { Navigate, Outlet, useLocation } from 'react-router'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { useAuth } from './auth-context'

function RequireAuth() {
  const { account, pending, error, refresh } = useAuth()
  const location = useLocation()
  if (pending)
    return (
      <main className="p-6">
        <LoadingState message="인증 상태를 확인하는 중…" />
      </main>
    )
  if (error)
    return (
      <main className="p-6">
        <ErrorState
          error={error}
          onRetry={() => {
            void refresh()
          }}
        />
      </main>
    )
  if (!account)
    return (
      <Navigate
        to="/login"
        state={{ from: location.pathname + location.search }}
        replace
      />
    )
  if (account.mustChangePassword && location.pathname !== '/change-password')
    return <Navigate to="/change-password" replace />
  return <Outlet />
}

export default RequireAuth
