import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchAllResources, fetchColumns } from './api'
import SourceSyncSchedule from './SourceSyncSchedule'
import type { Source, SourceResource } from './types'

function ResourceColumns({
  sourceId,
  resource,
}: {
  sourceId: number
  resource: SourceResource
}) {
  const {
    data: columns,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['sources', sourceId, 'resources', resource.id, 'columns'],
    queryFn: () => fetchColumns(sourceId, resource.id),
  })

  if (isPending) {
    return <LoadingState message="컬럼을 불러오는 중…" />
  }
  if (isError) {
    return (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  }
  if (columns.length === 0) {
    return <EmptyState message="읽을 수 있는 컬럼이 없습니다." />
  }
  return (
    <ul aria-label={`${resource.resourceKey} 컬럼`} className="space-y-1">
      {columns.map((column) => (
        <li
          key={column.id}
          className={column.isPresent ? 'text-slate-700' : 'text-slate-400'}
        >
          <span className="font-mono">{column.columnName}</span>
          {' · '}
          <span className="font-mono text-xs">{column.sourceDataType}</span>
          {column.isKeyPart && ' · 기본키'}
          {!column.isPresent && ' · 원천에서 사라짐'}
        </li>
      ))}
    </ul>
  )
}

function ResourceItem({
  sourceId,
  resource,
}: {
  sourceId: number
  resource: SourceResource
}) {
  // 펼친 표의 컬럼만 불러온다.
  const [open, setOpen] = useState(false)
  const description = resource.sourceMetadata.description
  return (
    <details
      onToggle={(event) => setOpen(event.currentTarget.open)}
      className="sources-resource"
    >
      <summary className="cursor-pointer font-semibold text-slate-900">
        {resource.resourceKey}
        {description ? ` · ${description}` : ''}
        {!resource.isReadable && (
          <span className="ml-2 text-xs font-normal text-red-700">
            읽기 권한 없음
          </span>
        )}
      </summary>
      <div className="mt-2 pl-5">
        {open && <ResourceColumns sourceId={sourceId} resource={resource} />}
      </div>
    </details>
  )
}

// 수집 대상 탭: 원천에서 읽을 수 있는 표(014)와 컬럼(015)
function ReadableScope({ source }: { source: Source }) {
  const sourceId = source.id
  const {
    data: resources,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['sources', sourceId, 'resources', 'all'],
    queryFn: () => fetchAllResources(sourceId),
  })

  let content
  if (isPending) {
    content = <LoadingState message="읽을 수 있는 표를 불러오는 중…" />
  } else if (isError) {
    content = (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  } else if (resources.length === 0) {
    content = (
      <EmptyState message="읽을 수 있는 표가 없습니다. 접속 정보에서 연결을 다시 확인해 주세요." />
    )
  } else {
    content = (
      <div aria-label="읽을 수 있는 표" role="group">
        {resources.map((resource) => (
          <ResourceItem
            key={resource.id}
            sourceId={sourceId}
            resource={resource}
          />
        ))}
      </div>
    )
  }

  return (
    <div className="space-y-3 text-sm">
      <p className="text-slate-600">
        수집 계정으로 읽을 수 있는 표와 컬럼입니다. AI 분석 계정이 읽지 못하는
        표는 분석을 실행할 때 알려 줍니다.
      </p>
      {resources && (
        <p className="text-xs text-slate-500">{`표 ${resources.length}개`}</p>
      )}
      {content}
      {source.sourceKind === 'POSTGRES' &&
        !isError &&
        resources?.some(
          (resource) => resource.syncStrategy === 'FULL_COMPARE',
        ) && <SourceSyncSchedule key={source.id} sourceId={source.id} />}
    </div>
  )
}

export default ReadableScope
