import { formatKstDateTime } from '../../utils/dateTime'
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import DataTable from '../../components/DataTable'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { formatObjectCode } from '../explorer/format'
import { fetchReassignmentPlan } from './api'
import type { ReassignmentPlan, ReassignmentPlanItem } from './types'

const planStatusLabels: Record<string, string> = {
  CREATED: '생성됨',
}

// 공정 코드가 없으면 그 작업지시의 남은 공정 전체를 옮긴다.
const ALL_OPERATIONS = '남은 공정 전체'

function formatDateTime(value: string) {
  return formatKstDateTime(value)
}

// 선정 근거는 정해진 모양이 없어, reason이 있으면 그 문장을, 없으면 값을 그대로 보여 준다.
function selectionBasisText(basis: Record<string, unknown>) {
  if (typeof basis.reason === 'string') {
    return basis.reason
  }
  return Object.keys(basis).length > 0 ? JSON.stringify(basis) : '—'
}

function workOrderLabel(item: ReassignmentPlanItem) {
  const number = item.sourceSnapshot.workOrderNo
  return typeof number === 'string'
    ? number
    : formatObjectCode(item.workOrderId)
}

function ObjectLink({ objectId }: { objectId: number }) {
  return (
    <Link
      to={`/explorer?object=${objectId}`}
      className="text-blue-700 hover:underline"
    >
      {formatObjectCode(objectId)}
    </Link>
  )
}

function PlanContent({ plan }: { plan: ReassignmentPlan }) {
  return (
    <>
      <dl className="mt-4 grid grid-cols-[7rem_1fr] gap-y-1 text-sm">
        <dt className="text-slate-500">상태</dt>
        <dd className="text-slate-900">
          {planStatusLabels[plan.status] ?? plan.status} · 실제 MES 배정은
          바뀌지 않음
        </dd>
        <dt className="text-slate-500">정지 설비</dt>
        <dd>
          <ObjectLink objectId={plan.fromEquipmentId} />
        </dd>
        <dt className="text-slate-500">기준 시점</dt>
        <dd className="text-slate-900">{formatDateTime(plan.dataAsOf)}</dd>
        <dt className="text-slate-500">만든 시각</dt>
        <dd className="text-slate-900">{formatDateTime(plan.createdAt)}</dd>
        <dt className="text-slate-500">선정 근거</dt>
        <dd className="text-slate-900">
          {selectionBasisText(plan.selectionBasis)}
        </dd>
      </dl>

      <h3 className="mt-6 text-sm font-semibold text-slate-900">
        옮길 작업 {plan.items.length}건
      </h3>
      <div className="mt-2">
        <DataTable label="옮길 작업">
          <thead className="bg-slate-50 text-slate-600">
            <tr>
              <th className="px-3 py-2">작업지시</th>
              <th className="px-3 py-2">공정</th>
              <th className="px-3 py-2">기존 설비</th>
              <th className="px-3 py-2">대체 설비</th>
            </tr>
          </thead>
          <tbody>
            {plan.items.map((item) => (
              <tr key={item.id} className="border-t border-slate-100">
                <td className="px-3 py-2">{workOrderLabel(item)}</td>
                <td className="px-3 py-2">
                  {item.operationCode ?? ALL_OPERATIONS}
                </td>
                <td className="px-3 py-2">
                  <ObjectLink objectId={item.fromEquipmentId} />
                </td>
                <td className="px-3 py-2">
                  <ObjectLink objectId={item.toEquipmentId} />
                </td>
              </tr>
            ))}
          </tbody>
        </DataTable>
      </div>
    </>
  )
}

// Agent가 만든 재배정 계획을 답변 안에 펼쳐 보여 준다(160, 094). 펼칠 때만 계획을 불러온다.
function ReassignmentPlanDetails({ planId }: { planId: number }) {
  const query = useQuery({
    queryKey: ['reassignment-plans', planId],
    queryFn: ({ signal }) => fetchReassignmentPlan(planId, signal),
    retry: false,
  })

  return (
    <section
      aria-label={`재배정 계획 #${planId}`}
      className="mt-2 rounded-lg bg-slate-50 p-3"
    >
      {query.isPending && <LoadingState message="계획을 불러오는 중…" />}
      {query.isError && (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      )}
      {query.isSuccess && <PlanContent plan={query.data} />}
    </section>
  )
}

export default ReassignmentPlanDetails
