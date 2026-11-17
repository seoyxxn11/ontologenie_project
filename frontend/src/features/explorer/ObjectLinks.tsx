import StatusBadge from '../../components/StatusBadge'
import DataTable from '../../components/DataTable'
import Button from '../../components/Button'
import { LoadingState, EmptyState, ErrorState } from '../../components/Feedback'
import { linkContext } from './detailPresentation'
import type { ObjectLink } from './types'
import { useObjectLinks } from './useObjectLinks'

type LinkEvidenceProps = {
  link: ObjectLink
}

function LinkSummary({ link }: LinkEvidenceProps) {
  const evidence = link.evidence

  if (evidence.evidenceRestricted)
    return <p>연결 근거는 권한으로 가려져 있습니다.</p>

  if (link.origin === 'RULE') {
    if (!evidence.comparisons?.length) return null
    const matchedValues = evidence.comparisons.map(
      (condition) => condition.matchedValue,
    )
    return <p>{`비교 값 ${matchedValues.join(' / ')} 일치`}</p>
  }

  if (link.origin === 'DOCUMENT') {
    const expression = link.expressionKind === 'ESTIMATED' ? '추정' : '관찰'
    return (
      <p>
        {`원문: “${evidence.quote ?? '-'}”`}
        <StatusBadge tone="warning">{expression}</StatusBadge>
      </p>
    )
  }

  return <p>관리자가 직접 연결</p>
}

function ComparisonValue({
  property,
  value,
  column,
}: {
  property: string
  value: string
  column?: string
}) {
  return (
    <>
      <span>{`${property} = ${value}`}</span>
      {column && column !== property && <p>현재 원천 컬럼 {column}</p>}
    </>
  )
}

function LinkDetail({ link }: LinkEvidenceProps) {
  const evidence = link.evidence
  if (evidence.evidenceRestricted) return null

  if (link.origin === 'RULE') {
    const comparisons = evidence.comparisons ?? []
    if (!comparisons.length) return null
    return (
      <details className="mt-1 text-xs text-slate-500">
        <summary className="cursor-pointer">상세 근거</summary>
        <table
          aria-label="비교 조건"
          className="mt-2 w-full table-fixed border-separate border-spacing-0 rounded border border-slate-200 bg-white text-left"
        >
          <thead>
            <tr>
              <th
                scope="col"
                className="border-b border-slate-200 px-2 py-1 font-medium"
              >
                출발 원본
              </th>
              <th
                scope="col"
                className="border-b border-slate-200 px-2 py-1 font-medium"
              >
                비교 대상
              </th>
            </tr>
          </thead>
          <tbody>
            {comparisons.map((condition) => (
              <tr key={condition.sequenceNo}>
                <td className="break-all whitespace-pre-wrap px-2 py-1 align-top">
                  <ComparisonValue
                    property={condition.sourceProperty}
                    value={condition.sourceValue}
                    column={condition.sourceColumn}
                  />
                </td>
                <td className="break-all whitespace-pre-wrap px-2 py-1 align-top">
                  <ComparisonValue
                    property={condition.targetProperty}
                    value={condition.targetValue}
                    column={condition.targetColumn}
                  />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
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
    links,
    totalCount,
    isPending,
    isError,
    error,
    refetch,
    hasNextPage,
    fetchNextPage,
    isFetchingNextPage,
    isFetchNextPageError,
  } = useObjectLinks(objectId)

  if (isPending) {
    return <LoadingState message="연결을 불러오는 중…" />
  }

  if (isError && links.length === 0) {
    return (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  }

  if (links.length === 0) {
    return <EmptyState message="현재 연결된 정보가 없습니다." />
  }

  return (
    <>
      <DataTable label="연결 근거" className="min-w-[480px] table-fixed">
        <thead className="text-xs text-slate-500">
          <tr>
            <th className="w-[26%] px-3 py-2 font-normal">연결된 정보</th>
            <th className="w-[24%] py-2 pr-3 font-normal">관계</th>
            <th className="py-2 pr-3 font-normal">핵심 근거</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 align-top">
          {links.map((link) => (
            <tr key={link.id}>
              <td className="px-3 py-2 [overflow-wrap:anywhere]">
                {link.relatedObject.displayName}
                <p className="text-xs text-slate-500">
                  {link.relatedObject.objectTypeName} · 객체 #
                  {link.relatedObject.id}
                </p>
                {!link.evidence.evidenceRestricted && linkContext(link) && (
                  <p className="mt-1 text-xs text-slate-500">
                    {linkContext(link)}
                  </p>
                )}
              </td>
              <td className="py-2 pr-3 [overflow-wrap:anywhere]">
                {link.linkTypeName}
                <p className="text-xs text-slate-500">
                  {link.sourceObjectId === objectId
                    ? '나가는 연결'
                    : '들어오는 연결'}
                </p>
              </td>
              <td className="py-2 pr-3 [overflow-wrap:anywhere]">
                <LinkSummary link={link} />
                <LinkDetail link={link} />
              </td>
            </tr>
          ))}
        </tbody>
      </DataTable>
      <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-500">
        <span>{`전체 ${totalCount}건 중 ${links.length}건`}</span>
        {hasNextPage && (
          <Button
            variant="secondary"
            className="px-2 py-1 text-xs"
            pending={isFetchingNextPage}
            onClick={() => {
              void fetchNextPage()
            }}
          >
            더 불러오기
          </Button>
        )}
        {isFetchNextPageError && (
          <span className="text-red-600">{error?.message}</span>
        )}
      </div>
    </>
  )
}

export default ObjectLinks
