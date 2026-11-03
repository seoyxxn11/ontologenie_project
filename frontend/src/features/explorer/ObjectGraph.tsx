import { formatObjectCode, withAndParticle } from './format'
import { useQuery } from '@tanstack/react-query'
import { fetchObject, fetchObjectLinks } from './api'
import { arrowLine, labelStyle, placeRelatedNodes } from './graphLayout'

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

  return <ObjectGraphContent objectId={objectId} onSelect={onSelect} />
}

type ObjectGraphContentProps = {
  objectId: number
  onSelect: (id: number) => void
}

function ObjectGraphContent({ objectId, onSelect }: ObjectGraphContentProps) {
  const objectQuery = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })
  const linksQuery = useQuery({
    queryKey: ['objects', objectId, 'links'],
    queryFn: () => fetchObjectLinks(objectId),
  })

  if (objectQuery.isPending || linksQuery.isPending) {
    return <p className="text-slate-500">관계도를 불러오는 중…</p>
  }

  if (objectQuery.isError) {
    return <p className="text-red-600">{objectQuery.error.message}</p>
  }

  if (linksQuery.isError) {
    return <p className="text-red-600">{linksQuery.error.message}</p>
  }

  const object = objectQuery.data
  const nodes = placeRelatedNodes(linksQuery.data.data)

  return (
    <div>
      <h2 className="mb-3 text-lg font-semibold text-slate-900">
        {withAndParticle(object.displayName)} 연결된 정보
      </h2>

      <div className="relative h-[440px] overflow-hidden bg-[radial-gradient(#d8dce1_1px,transparent_1px)] bg-[length:16px_16px]">
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
            const arrow = arrowLine(node, objectId)
            return (
              <g key={node.link.id} stroke="#94a3b8" strokeWidth={1.5}>
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
            key={`label-${node.link.id}`}
            className="absolute whitespace-nowrap rounded bg-white/80 px-1 text-xs text-slate-600"
            style={labelStyle(node)}
          >
            {node.link.linkTypeName}
          </span>
        ))}

        <div className="absolute left-1/2 top-1/2 w-24 -translate-x-1/2 -translate-y-1/2 rounded-lg border border-slate-900 bg-slate-900 px-2 py-2 text-center text-xs ring-4 ring-slate-200">
          <span className="block text-slate-300">
            {formatObjectCode(object.id)}
          </span>
          <strong className="block text-white">{object.displayName}</strong>
          <span className="text-slate-300">{object.objectTypeName}</span>
        </div>

        {nodes.map((node) => (
          <button
            key={node.link.id}
            type="button"
            onClick={() => onSelect(node.link.relatedObject.id)}
            className="absolute w-24 -translate-x-1/2 -translate-y-1/2 rounded-lg border border-slate-300 bg-white px-2 py-2 text-center text-xs hover:border-slate-900"
            style={{ left: `${node.x}%`, top: `${node.y}%` }}
          >
            <span className="block text-slate-500">
              {formatObjectCode(node.link.relatedObject.id)}
            </span>

            <strong className="block text-slate-900">
              {node.link.relatedObject.displayName}
            </strong>
            <span className="text-slate-500">
              {node.link.relatedObject.objectTypeName}
            </span>
          </button>
        ))}
      </div>

      <p className="mt-2 text-xs text-slate-700">
        {`연결된 정보 · ${nodes.length}건`}
      </p>
      <p className="text-xs text-slate-500">
        {nodes.length > 0
          ? '화살표는 저장된 관계 방향입니다. 카드를 선택하면 그 객체로 이동합니다.'
          : '이 객체에는 연결된 관계가 없습니다.'}
      </p>
    </div>
  )
}

export default ObjectGraph
