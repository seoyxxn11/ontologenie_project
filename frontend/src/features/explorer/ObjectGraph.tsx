import { useEffect, useState } from 'react'
import { formatCount, formatObjectCode } from './format'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import Button from '../../components/Button'
import { fetchObject, fetchObjectLinkGroups } from './api'
import {
  arrowLine,
  labelStyle,
  placeGroups,
  toLinkGroups,
  type LinkGroup,
} from './graphLayout'
import { useObjectLinks } from './useObjectLinks'

type ObjectGraphProps = {
  objectId: number | null
  onSelect: (id: number) => void
}

function ObjectGraph({ objectId, onSelect }: ObjectGraphProps) {
  if (objectId === null) {
    return (
      <p className="text-slate-500">객체를 선택하면 연결 관계를 표시합니다.</p>
    )
  }

  return (
    <ObjectGraphContent
      key={objectId}
      objectId={objectId}
      onSelect={onSelect}
    />
  )
}

type ObjectGraphContentProps = {
  objectId: number
  onSelect: (id: number) => void
}

function ObjectGraphContent({ objectId, onSelect }: ObjectGraphContentProps) {
  const [pinnedGroup, setPinnedGroup] = useState<LinkGroup | null>(null)
  const objectQuery = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })
  const groupsQuery = useQuery({
    queryKey: ['objects', objectId, 'link-groups'],
    queryFn: () => fetchObjectLinkGroups(objectId),
  })

  if (objectQuery.isPending || groupsQuery.isPending) {
    return <p className="text-slate-500">관계도를 불러오는 중…</p>
  }

  if (objectQuery.isError) {
    return <p className="text-red-600">{objectQuery.error.message}</p>
  }

  if (groupsQuery.isError && !groupsQuery.data) {
    return <p className="text-red-600">{groupsQuery.error.message}</p>
  }

  const object = objectQuery.data
  const groups = toLinkGroups(groupsQuery.data)
  const totalCount = groups.reduce((sum, group) => sum + group.linkCount, 0)
  const nodes = placeGroups(groups)
  // 처음 보인 묶음이나 사용자가 고른 묶음을 고정한다. 묶음 건수를 다시 받아 순서가 바뀌어도
  // 보던 목록이 다른 묶음으로 넘어가지 않게 한다.
  if (pinnedGroup === null && groups.length > 0) {
    setPinnedGroup(groups[0])
  }
  const pinned = pinnedGroup ?? groups[0]
  const selectedGroup = groups.find((group) => group.key === pinned?.key)
  // 고른 묶음의 연결이 그 사이 모두 없어져 묶음 목록에서 빠진 경우
  const vanishedGroup = pinned && !selectedGroup ? pinned : undefined

  return (
    <div>
      <div className="explorer-graph-canvas">
        <div className="object-graph-canvas relative h-full overflow-hidden bg-[radial-gradient(#d8dce1_1px,transparent_1px)] bg-[length:16px_16px]">
          <svg className="absolute inset-0 h-full w-full" aria-hidden="true">
            <defs>
              <marker
                id="graph-arrow"
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto"
              >
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" />
              </marker>
            </defs>
            {nodes.map((node) => {
              const arrow = arrowLine(node)
              return (
                <g key={node.group.key} stroke="#94a3b8" strokeWidth={1.5}>
                  <line x1="50%" y1="50%" x2={`${node.x}%`} y2={`${node.y}%`} />
                  <line
                    x1={`${arrow.x1}%`}
                    y1={`${arrow.y1}%`}
                    x2={`${arrow.x2}%`}
                    y2={`${arrow.y2}%`}
                    markerEnd="url(#graph-arrow)"
                  />
                </g>
              )
            })}
          </svg>

          {nodes.map((node) => (
            <span
              key={`label-${node.group.key}`}
              className="object-graph-label absolute rounded bg-white/80 px-1 text-xs text-slate-600"
              style={labelStyle(node)}
              title={node.group.linkTypeName}
            >
              {node.group.linkTypeName}
            </span>
          ))}

          <div className="object-graph-center absolute left-1/2 top-1/2 w-28 -translate-x-1/2 -translate-y-1/2 rounded border px-2 py-2 text-center text-sm">
            <span className="block text-slate-300">
              {formatObjectCode(object.id)}
            </span>
            {/* 긴 이름은 2줄까지만 보여 준다. 전체 이름은 칸 제목과 상세에 있다. */}
            <strong
              className="object-graph-center-name text-white"
              title={object.displayName}
            >
              {object.displayName}
            </strong>
            <span className="text-slate-300">{object.objectTypeName}</span>
          </div>

          {nodes.map((node) => {
            const selected = node.group.key === selectedGroup?.key
            return (
              <button
                key={node.group.key}
                type="button"
                onClick={() => setPinnedGroup(node.group)}
                aria-pressed={selected}
                className="object-graph-node absolute w-28 -translate-x-1/2 -translate-y-1/2 border px-2 py-2 text-center"
                style={{ left: `${node.x}%`, top: `${node.y}%` }}
              >
                <strong className="block text-slate-900">
                  {node.group.relatedTypeName}
                </strong>
                <span className="text-slate-500">
                  {`${formatCount(node.group.linkCount)}건`}
                </span>
              </button>
            )
          })}
        </div>
      </div>

      <p className="mt-2 text-sm text-slate-700">
        {`연결된 정보 · ${formatCount(totalCount)}건`}
      </p>
      <p className="text-sm text-slate-500">
        {groups.length > 0
          ? '화살표: 관계 방향'
          : '이 객체에는 연결된 관계가 없습니다.'}
      </p>

      {selectedGroup && (
        <GroupObjectList
          key={selectedGroup.key}
          objectId={objectId}
          group={selectedGroup}
          onSelect={onSelect}
        />
      )}
      {vanishedGroup && (
        <section
          aria-label={`${vanishedGroup.relatedTypeName} 연결 목록`}
          className="mt-4"
        >
          <h3 className="text-sm font-semibold text-slate-900">
            {`${vanishedGroup.linkTypeName} · ${vanishedGroup.relatedTypeName} 0건`}
          </h3>
          <p className="mt-2 text-sm text-slate-500">
            지금은 이 묶음에 연결이 없습니다.
          </p>
        </section>
      )}
    </div>
  )
}

type GroupObjectListProps = {
  objectId: number
  group: LinkGroup
  onSelect: (id: number) => void
}

// 고른 묶음의 연결만 078로 쪽 단위로 받아, 묶음 안에서 더 불러온다.
function GroupObjectList({ objectId, group, onSelect }: GroupObjectListProps) {
  const queryClient = useQueryClient()
  const linksQuery = useObjectLinks(objectId, {
    linkTypeId: group.linkTypeId,
    direction: group.direction,
  })
  const { links, latestTotal } = linksQuery

  // 묶음 건수와 목록은 다른 요청이라 그 사이 연결이 바뀌면 건수가 어긋난다.
  // 어긋나면 묶음 건수를 다시 받아 카드와 머리글을 목록에 맞춘다.
  useEffect(() => {
    if (latestTotal !== undefined && latestTotal !== group.linkCount) {
      void queryClient.invalidateQueries({
        queryKey: ['objects', objectId, 'link-groups'],
      })
    }
  }, [latestTotal, group.linkCount, objectId, queryClient])

  return (
    <section aria-label={`${group.relatedTypeName} 연결 목록`} className="mt-4">
      <h3 className="text-sm font-semibold text-slate-900">
        {`${group.linkTypeName} · ${group.relatedTypeName} ${formatCount(group.linkCount)}건`}
      </h3>
      {linksQuery.isPending && (
        <p className="mt-2 text-sm text-slate-500">연결을 불러오는 중…</p>
      )}
      {linksQuery.isError && !linksQuery.data && (
        <p className="mt-2 text-sm text-red-600">{linksQuery.error.message}</p>
      )}
      {linksQuery.isSuccess && links.length === 0 && (
        <p className="mt-2 text-sm text-slate-500">
          지금은 이 묶음에 연결이 없습니다.
        </p>
      )}
      {links.length > 0 && (
        <ul className="mt-2 max-h-64 divide-y divide-slate-100 overflow-y-auto">
          {links.map((link) => (
            <li key={link.id}>
              <button
                type="button"
                onClick={() => onSelect(link.relatedObject.id)}
                className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm hover:bg-slate-50"
              >
                <span className="min-w-0 break-words text-slate-900">
                  {link.relatedObject.displayName}
                </span>
                <span className="shrink-0 text-xs text-slate-500">
                  {formatObjectCode(link.relatedObject.id)}
                </span>
              </button>
            </li>
          ))}
        </ul>
      )}
      {linksQuery.hasNextPage && (
        <div className="mt-1 flex flex-wrap items-center gap-2 text-sm text-slate-500">
          <span>{`전체 ${formatCount(linksQuery.totalCount)}건 중 ${formatCount(links.length)}건`}</span>
          <Button
            variant="secondary"
            className="px-2 py-1 text-xs"
            pending={linksQuery.isFetchingNextPage}
            onClick={() => {
              void linksQuery.fetchNextPage()
            }}
          >
            더 불러오기
          </Button>
        </div>
      )}
      {linksQuery.isFetchNextPageError && (
        <p className="mt-1 text-sm text-red-600">
          {linksQuery.error?.message ?? '연결을 더 불러오지 못했습니다.'}
        </p>
      )}
    </section>
  )
}

export default ObjectGraph
