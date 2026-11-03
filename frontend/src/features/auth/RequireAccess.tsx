import { Outlet } from 'react-router'
import { useAuth } from './auth-context'
import { canAccess, type ScreenAccess } from './access'
import AccessDeniedPage from './AccessDeniedPage'

// RequireAuth 하위에서만 사용한다. 권한 부족을 로그인 만료로 처리하지 않는다.
function RequireAccess({ access }: { access: ScreenAccess }) {
  const { account } = useAuth()
  return canAccess(account, access) ? <Outlet /> : <AccessDeniedPage />
}

export default RequireAccess
