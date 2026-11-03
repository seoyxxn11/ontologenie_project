import { useQuery } from '@tanstack/react-query'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import { fetchObject } from '../explorer/api'
import ExecutionHistory from './ExecutionHistory'

type ObjectHistoryProps = {
  objectId: number
}

function ObjectHistory({ objectId }: ObjectHistoryProps) {
  const { data: object } = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })

  return (
    <ExecutionHistory
      objectId={objectId}
      targetLabel={object?.displayName ?? `객체 #${objectId}`}
    />
  )
}

function AuditPage() {
  const navigate = useNavigate()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const objectId = Number(searchParams.get('objectId'))
  const hasObject = Number.isInteger(objectId) && objectId > 0

  function goBack() {
    if (location.key !== 'default') {
      navigate(-1)
    } else {
      navigate('/explorer')
    }
  }

  return (
    <div>
      <header className="mb-6">
        <p className="text-xs font-semibold tracking-widest text-slate-500">
          AUDIT
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">실행 이력</h1>
        <p className="mt-1 text-sm text-slate-500">
          실행 기록과 변경 내용을 조건별로 확인하세요.
        </p>
      </header>

      <section className="rounded-xl border border-slate-200 bg-white p-6">
        <button
          type="button"
          onClick={goBack}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
        >
          이전 화면으로
        </button>

        <div className="mt-5">
          {hasObject ? (
            <ObjectHistory objectId={objectId} />
          ) : (
            <ExecutionHistory />
          )}
        </div>
      </section>
    </div>
  )
}

export default AuditPage
