import { useQuery } from '@tanstack/react-query'
import { fetchObjectLinks } from './api'
import { formatPropertyValue, formatSourceKey } from './format'
import type { ObjectLink } from './types'

type LinkEvidenceProps = {
  link: ObjectLink
}

function LinkSummary({ link }: LinkEvidenceProps) {
  const evidence = link.evidence

  if (link.origin === 'RULE') {
    const rawValue = formatPropertyValue(evidence.rawValue)
    const transformedValue = formatPropertyValue(evidence.transformedValue)
    return <p>{`값 ${rawValue} → ${transformedValue}`}</p>
  }

  if (link.origin === 'DOCUMENT') {
    const expression = link.expressionKind === 'ESTIMATED' ? '추정' : '관찰'
    return (
      <p>
        {`원문: “${evidence.quote ?? '-'}”`}
        <span className="ml-2 rounded-full bg-amber-100 px-2 py-0.5 text-xs text-amber-800">
          {expression}
        </span>
      </p>
    )
  }

  return <p>관리자가 직접 연결</p>
}

function LinkDetail({ link }: LinkEvidenceProps) {
  const evidence = link.evidence

  if (link.origin === 'RULE') {
    return (
      <details className="mt-1 text-xs text-slate-500">
        <summary className="cursor-pointer">상세 근거</summary>
        <dl className="mt-1 grid grid-cols-[5rem_1fr] gap-y-1">
          <dt>원천 행</dt>
          <dd>{formatSourceKey(evidence.sourceKey)}</dd>
          <dt>원래 값</dt>
          <dd>{formatPropertyValue(evidence.rawValue)}</dd>
          <dt>바꾼 값</dt>
          <dd>{formatPropertyValue(evidence.transformedValue)}</dd>
        </dl>
      </details>
    )
  }

  if (link.origin === 'DOCUMENT') {
    return (
      <details className="mt-1 text-xs text-slate-500">
        <summary className="cursor-pointer">상세 근거</summary>
        <dl className="mt-1 grid grid-cols-[5rem_1fr] gap-y-1">
          <dt>사건</dt>
          <dd>{evidence.event ?? '-'}</dd>
          <dt>시간 표현</dt>
          <dd>{evidence.timeExpression ?? '-'}</dd>
        </dl>
      </details>
    )
  }

  return null
}

type ObjectLinksProps = {
  objectId: number
}

function ObjectLinks({ objectId }: ObjectLinksProps) {
  const {
    data: linkPage,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['objects', objectId, 'links'],
    queryFn: () => fetchObjectLinks(objectId),
  })

  if (isPending) {
    return <p className="text-slate-500">연결을 불러오는 중…</p>
  }

  if (isError) {
    return <p className="text-red-600">{error.message}</p>
  }

  if (linkPage.data.length === 0) {
    return <p className="text-slate-500">현재 연결된 정보가 없습니다.</p>
  }

  return (
    <table className="w-full text-left">
      <thead className="text-xs text-slate-500">
        <tr>
          <th className="py-2 pr-3 font-normal">연결된 정보</th>
          <th className="py-2 pr-3 font-normal">관계</th>
          <th className="py-2 font-normal">핵심 근거</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100 align-top">
        {linkPage.data.map((link) => (
          <tr key={link.id}>
            <td className="py-2 pr-3">
              {link.relatedObject.displayName}
              <p className="text-xs text-slate-500">
                {link.relatedObject.objectTypeName}
              </p>
            </td>
            <td className="py-2 pr-3">
              {link.linkTypeName}
              <p className="text-xs text-slate-500">
                {link.sourceObjectId === objectId
                  ? '나가는 연결'
                  : '들어오는 연결'}
              </p>
            </td>
            <td className="py-2">
              <LinkSummary link={link} />
              <LinkDetail link={link} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

export default ObjectLinks
