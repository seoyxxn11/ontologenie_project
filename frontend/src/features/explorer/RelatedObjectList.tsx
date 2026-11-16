import {
  keepPreviousData,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router'
import { positiveId } from './searchState'
import Button from '../../components/Button'
import Pagination from '../../components/Pagination'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchObjectLinks, OBJECT_PAGE_SIZE } from './api'
import { formatCount, formatObjectCode } from './format'
import { linkContext } from './detailPresentation'
import type { LinkGroup } from './graphLayout'
import ObjectCard from './ObjectCard'

type Props = {
  objectId: number
  anchorName: string
  group: LinkGroup
  selectedId: number | null
  pageSize?: number
  vanished: boolean
  onSelect: (id: number) => void
  onReturn: () => void
}

export default function RelatedObjectList({ vanished, ...props }: Props) {
  if (vanished) {
    return (
      <section aria-label={`${props.group.relatedTypeName} 연결 목록`}>
        <ListHeading {...props} total={0} />
        <EmptyState message="지금은 이 묶음에 연결이 없습니다." />
      </section>
    )
  }
  return <RelatedObjectPage {...props} />
}

function ListHeading({
  anchorName,
  group,
  onReturn,
  total,
}: Omit<Props, 'vanished'> & { total: number }) {
  return (
    <>
      <div className="explorer-panel-heading">
        <h2>{group.relatedTypeName}</h2>
        <span className="explorer-count">{formatCount(total)}건</span>
      </div>
      <div className="explorer-related-context">
        <Button variant="text" onClick={onReturn}>
          ← 검색 결과
        </Button>
        <p>
          {anchorName} · {group.linkTypeName}
        </p>
      </div>
    </>
  )
}

function RelatedObjectPage(props: Omit<Props, 'vanished'>) {
  const {
    objectId,
    group,
    selectedId,
    onSelect,
    pageSize = OBJECT_PAGE_SIZE,
  } = props
  const [params, setParams] = useSearchParams()
  const page = Math.min(positiveId(params.get('relatedPage')) ?? 0, 2147483647)
  function setPage(nextPage: number, replace = false) {
    const next = new URLSearchParams(params)
    if (nextPage > 0) next.set('relatedPage', String(nextPage))
    else next.delete('relatedPage')
    setParams(next, { replace })
  }
  const client = useQueryClient()
  const query = useQuery({
    queryKey: ['objects', objectId, 'related', group.key, page, pageSize],
    queryFn: () =>
      fetchObjectLinks(
        objectId,
        page,
        { linkTypeId: group.linkTypeId, direction: group.direction },
        pageSize,
      ),
    placeholderData: keepPreviousData,
  })
  const [lastSuccessful, setLastSuccessful] = useState<typeof query.data>()
  if (
    query.isSuccess &&
    !query.isPlaceholderData &&
    query.data !== lastSuccessful
  ) {
    setLastSuccessful(query.data)
  }
  const data = query.data ?? lastSuccessful
  const total =
    data?.meta?.totalElements ?? data?.data.length ?? group.linkCount
  const latestTotal = query.data?.meta?.totalElements
  useEffect(() => {
    if (
      !query.isPlaceholderData &&
      latestTotal !== undefined &&
      latestTotal !== group.linkCount
    ) {
      void client.invalidateQueries({
        queryKey: ['objects', objectId, 'link-groups'],
      })
    }
  }, [client, objectId, group.linkCount, latestTotal, query.isPlaceholderData])

  useEffect(() => {
    if (query.isFetching || query.isPlaceholderData || query.isError) return
    const last = Math.max(0, Math.ceil(total / pageSize) - 1)
    if (page > last) {
      const next = new URLSearchParams(params)
      if (last > 0) next.set('relatedPage', String(last))
      else next.delete('relatedPage')
      setParams(next, { replace: true })
    }
  }, [
    page,
    total,
    pageSize,
    query.isFetching,
    query.isPlaceholderData,
    query.isError,
    params,
    setParams,
  ])

  return (
    <section aria-label={`${group.relatedTypeName} 연결 목록`}>
      <ListHeading {...props} total={total} />
      <div className="explorer-object-list" aria-busy={query.isFetching}>
        {query.isError && (
          <ErrorState
            error={query.error}
            onRetry={() => void query.refetch()}
          />
        )}
        {query.isPending && !data ? (
          <LoadingState message="연결을 불러오는 중…" />
        ) : data?.data.length === 0 ? (
          <EmptyState message="지금은 이 묶음에 연결이 없습니다." />
        ) : (
          data?.data.map((link) => (
            <ObjectCard
              key={link.id}
              code={formatObjectCode(link.relatedObject.id)}
              name={link.relatedObject.displayName}
              typeLabel={link.relatedObject.objectTypeName}
              context={linkContext(link)}
              selected={link.relatedObject.id === selectedId}
              onSelect={() => onSelect(link.relatedObject.id)}
            />
          ))
        )}
      </div>
      {data && total > 0 && (
        <Pagination
          page={Math.min(
            query.isPlaceholderData || query.isError
              ? (data.meta?.page ?? page)
              : page,
            Math.max(0, Math.ceil(total / pageSize) - 1),
          )}
          size={pageSize}
          totalElements={total}
          onChange={(next) => setPage(next)}
          pending={query.isFetching}
          compact
          showSummary={false}
        />
      )}
    </section>
  )
}
