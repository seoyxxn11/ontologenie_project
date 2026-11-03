import { Link } from 'react-router'
import PermissionGate from './PermissionGate'

function AccessDeniedPage() {
  return (
    <section
      role="alert"
      className="rounded-xl border border-slate-200 bg-slate-50 p-6"
    >
      <h1 className="text-xl font-semibold text-slate-900">
        접근 권한이 없습니다.
      </h1>
      <p className="mt-2 text-sm text-slate-600">
        현재 계정으로 이 화면을 사용할 수 없습니다. 권한이 필요하면 관리자에게
        문의해 주세요.
      </p>
      <PermissionGate access="workspace">
        <Link
          to="/explorer"
          className="mt-4 inline-block rounded-lg border border-slate-300 bg-white px-3 py-2 text-sm text-slate-700"
        >
          업무 데이터로 돌아가기
        </Link>
      </PermissionGate>
    </section>
  )
}

export default AccessDeniedPage
