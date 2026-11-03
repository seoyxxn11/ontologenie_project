import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { fetchObject } from './api'
import EvidenceDialog from './EvidenceDialog'
import { formatObjectCode, formatPropertyValue } from './format'
import { statusLabels } from './labels'

function formatDateTime(value: string | null | undefined) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('ko-KR')
}

type ObjectDetailProps = {
  objectId: number | null
}

function ObjectDetail({ objectId }: ObjectDetailProps) {
  if (objectId === null) {
    return <p className="text-slate-500">왼쪽 목록에서 객체를 선택해 주세요.</p>
  }

  return <ObjectDetailContent objectId={objectId} />
}

type ObjectDetailContentProps = {
  objectId: number
}

function ObjectDetailContent({ objectId }: ObjectDetailContentProps) {
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const {
    data: object,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })

  if (isPending) {
    return <p className="text-slate-500">상세 정보를 불러오는 중…</p>
  }

  if (isError) {
    return <p className="text-red-600">{error.message}</p>
  }

  const source = object.sourceSystemName
    ? [object.sourceSystemName, object.sourceResourceKey, '원천 레코드']
        .filter(Boolean)
        .join(' · ')
    : '플랫폼 객체'

  return (
    <div>
      <p className="text-sm text-slate-500">
        {object.objectTypeName} / {formatObjectCode(object.id)}
      </p>
      <h2 className="mt-1 text-lg font-semibold text-slate-900">
        {object.displayName}
      </h2>
      <p className="mt-1 text-sm text-slate-500">{source}</p>
      <span className="mt-2 inline-block rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-800">
        {statusLabels[object.status]}
      </span>

      <dl className="mt-4 grid grid-cols-[7rem_1fr] gap-y-1 text-sm">
        <dt className="text-slate-500">기준 시점</dt>
        <dd className="text-slate-900">{formatDateTime(object.dataAsOf)}</dd>
        <dt className="text-slate-500">최근 동기화</dt>
        <dd className="text-slate-900">
          {formatDateTime(object.lastSyncedAt)}
        </dd>
      </dl>

      <h3 className="mt-6 text-sm font-semibold text-slate-900">속성</h3>
      <dl className="mt-2 divide-y divide-slate-100 text-sm">
        {Object.entries(object.properties).map(([code, value]) => (
          <div key={code} className="flex gap-4 py-2">
            <dt className="w-40 shrink-0 text-slate-500">
              {object.propertyNames[code] ?? code}
            </dt>
            {object.restrictedPropertyCodes.includes(code) ? (
              <dd className="text-slate-400">권한 없음</dd>
            ) : (
              <dd className="text-slate-900">{formatPropertyValue(value)}</dd>
            )}
          </div>
        ))}
      </dl>

      <section className="mt-6">
        <h3 className="text-sm font-semibold text-slate-900">원천 근거</h3>
        <button
          type="button"
          onClick={() => setEvidenceOpen(true)}
          className="mt-2 text-sm text-blue-700 hover:underline"
        >
          원본·연결 근거 보기 ↗
        </button>
      </section>

      <div className="mt-6">
        <Link
          to={`/audit?objectId=${object.id}`}
          className="inline-block rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:border-slate-900"
        >
          이 객체 실행 이력
        </Link>
      </div>

      {evidenceOpen && (
        <EvidenceDialog
          object={object}
          onClose={() => setEvidenceOpen(false)}
        />
      )}
    </div>
  )
}

export default ObjectDetail
