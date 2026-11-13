import { useId, useLayoutEffect, useRef } from 'react'
import { formatCount, formatObjectCode, formatObjectKey } from './format'
import { useQuery } from '@tanstack/react-query'
import { fetchObject, fetchObjectLinkGroups } from './api'
import {
  arrowLine,
  labelStyle,
  placeGroups,
  toLinkGroups,
  type GroupNode,
  type LinkGroup,
} from './graphLayout'
import { placeRelationLabels } from './graphLabelLayout'

type ObjectGraphProps = {
  objectId: number | null
  selectedGroupKey: string | null
  onSelectGroup: (group: LinkGroup) => void
  onSelectAnchor: () => void
}

function ObjectGraph({ objectId, ...props }: ObjectGraphProps) {
  if (objectId === null) {
    return (
      <p className="text-slate-500">객체를 선택하면 연결 관계를 표시합니다.</p>
    )
  }

  return <ObjectGraphContent key={objectId} objectId={objectId} {...props} />
}

type ObjectGraphContentProps = Omit<ObjectGraphProps, 'objectId'> & {
  objectId: number
}

function ObjectGraphContent({
  objectId,
  selectedGroupKey,
  onSelectGroup,
  onSelectAnchor,
}: ObjectGraphContentProps) {
  const arrowId = useId()
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
  const compactPositions = [
    [50, 13],
    [81, 24],
    [81, 77],
    [50, 85],
    [19, 77],
    [19, 24],
  ]
  const nodes = placeGroups(groups).map((node, index) =>
    groups.length === 6
      ? {
          ...node,
          x: compactPositions[index][0],
          y: compactPositions[index][1],
        }
      : node,
  )
  const selectedGroup = groups.find((group) => group.key === selectedGroupKey)

  return (
    <div className="explorer-object-graph">
      <div
        className={`explorer-graph-canvas${groups.length <= 1 ? ' is-sparse' : ''}`}
        role="group"
        aria-label="선택 객체의 연결 다이어그램"
      >
        <div
          className="object-graph-canvas relative h-full"
          style={
            groups.length > 6
              ? {
                  minWidth: Math.max(560, groups.length * 70),
                  minHeight: Math.max(360, groups.length * 28),
                }
              : undefined
          }
        >
          <svg className="absolute inset-0 h-full w-full" aria-hidden="true">
            <defs>
              <marker
                id={`${arrowId}-neutral`}
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto"
              >
                <path
                  d="M 0 0 L 10 5 L 0 10 z"
                  className="object-graph-arrow"
                />
              </marker>
              <marker
                id={`${arrowId}-selected`}
                viewBox="0 0 10 10"
                refX="9"
                refY="5"
                markerWidth="6"
                markerHeight="6"
                orient="auto"
              >
                <path
                  d="M 0 0 L 10 5 L 0 10 z"
                  className="object-graph-arrow is-selected"
                />
              </marker>
            </defs>
            {nodes.map((node) => {
              const arrow = arrowLine(node)
              const selected = node.group.key === selectedGroup?.key
              return (
                <g
                  key={node.group.key}
                  className="object-graph-edge"
                  data-group-key={node.group.key}
                  data-selected={selected}
                >
                  <line x1="50%" y1="50%" x2={`${node.x}%`} y2={`${node.y}%`} />
                  <line
                    x1={`${arrow.x1}%`}
                    y1={`${arrow.y1}%`}
                    x2={`${arrow.x2}%`}
                    y2={`${arrow.y2}%`}
                    markerEnd={`url(#${arrowId}-${selected ? 'selected' : 'neutral'})`}
                  />
                </g>
              )
            })}
          </svg>

          <RelationLabels
            nodes={nodes}
            selectedKey={selectedGroup?.key}
            onSelect={onSelectGroup}
          />

          <button
            type="button"
            onClick={onSelectAnchor}
            aria-label={`${object.displayName} 상세 보기`}
            className="object-graph-center absolute left-1/2 top-1/2 w-28 -translate-x-1/2 -translate-y-1/2 rounded border px-2 py-2 text-center text-sm"
          >
            <span className="block text-slate-300">
              {object.objectTypeName} ·{' '}
              {formatObjectKey(object) ?? formatObjectCode(object.id)}
            </span>
            <strong
              className="object-graph-center-name text-white"
              title={object.displayName}
            >
              {object.displayName}
            </strong>
          </button>

          {nodes.map((node) => {
            const selected = node.group.key === selectedGroup?.key
            return (
              <button
                key={node.group.key}
                data-group-key={node.group.key}
                type="button"
                onClick={() => onSelectGroup(node.group)}
                aria-pressed={selected}
                aria-label={`${node.group.relatedTypeName} ${formatCount(node.group.linkCount)}건 · ${node.group.linkTypeName} · ${node.group.outgoing ? '나가는 연결' : '들어오는 연결'}`}
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

      <div className="explorer-graph-summary">
        <p>{`연결된 정보 · ${formatCount(totalCount)}건`}</p>
        <p>
          {groups.length > 0
            ? '화살표: 관계 방향'
            : '이 객체에는 연결된 관계가 없습니다.'}
        </p>
      </div>
    </div>
  )
}

function RelationLabels({
  nodes,
  selectedKey,
  onSelect,
}: {
  nodes: GroupNode[]
  selectedKey?: string
  onSelect: (group: LinkGroup) => void
}) {
  const ref = useRef<HTMLDivElement>(null)
  useLayoutEffect(() => {
    const layer = ref.current,
      canvas = layer?.parentElement
    if (!layer || !canvas) return
    let active = true
    const position = () => {
      if (!active) return
      const bounds = canvas.getBoundingClientRect()
      const anchor = canvas.querySelector<HTMLElement>('.object-graph-center')
      if (!anchor || !bounds.width) return
      const box = (element: Element) => {
        const r = element.getBoundingClientRect()
        return {
          x: r.left - bounds.left,
          y: r.top - bounds.top,
          width: r.width,
          height: r.height,
        }
      }
      const labels = [
        ...layer.querySelectorAll<HTMLElement>('.object-graph-label'),
      ]
      const geometry = labels.flatMap((element) => {
        const node = [
          ...canvas.querySelectorAll<HTMLElement>('.object-graph-node'),
        ].find((item) => item.dataset.groupKey === element.dataset.groupKey)
        const r = element.getBoundingClientRect()
        return node
          ? [
              {
                key: element.dataset.groupKey!,
                width: r.width,
                height: r.height,
                node: box(node),
              },
            ]
          : []
      })
      const placements = placeRelationLabels(
        bounds.width,
        bounds.height,
        box(anchor),
        geometry,
      )
      for (const element of labels) {
        const placement = placements.get(element.dataset.groupKey!)
        if (!placement) continue
        element.style.left = placement.x + 'px'
        element.style.top = placement.y + 'px'
        element.style.transform = 'none'
      }
    }
    position()
    const observer = new ResizeObserver(position)
    observer.observe(canvas)
    canvas
      .querySelectorAll('.object-graph-node,.object-graph-center')
      .forEach((element) => observer.observe(element))
    void document.fonts?.ready.then(position)
    return () => {
      active = false
      observer.disconnect()
    }
  }, [nodes, selectedKey])
  return (
    <div ref={ref} className="object-graph-labels">
      {nodes.map((node) => (
        <button
          type="button"
          tabIndex={-1}
          onClick={() => onSelect(node.group)}
          aria-label={`${node.group.linkTypeName} 연결 객체 보기`}
          key={node.group.key}
          className="object-graph-label"
          data-group-key={node.group.key}
          data-selected={node.group.key === selectedKey}
          style={labelStyle(node)}
        >
          {node.group.linkTypeName}
        </button>
      ))}
    </div>
  )
}

export default ObjectGraph
