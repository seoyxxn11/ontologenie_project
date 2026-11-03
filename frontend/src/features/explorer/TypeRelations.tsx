import { cardinalityLabels } from './labels'
import type { ModelEdge, ModelGraph } from './types'

type RelationListProps = {
  title: string
  edges: ModelEdge[]
  typeName: (id: number) => string
}

function RelationList({ title, edges, typeName }: RelationListProps) {
  return (
    <section className="mt-4">
      <h3 className="text-sm font-semibold text-slate-700">
        {`${title} ${edges.length}건`}
      </h3>
      {edges.length === 0 ? (
        <p className="mt-1 text-xs text-slate-500">없음</p>
      ) : (
        <ul className="mt-2 divide-y divide-slate-100 text-sm">
          {edges.map((edge) => (
            <li key={edge.id} className="py-2">
              <p className="font-semibold text-slate-900">{edge.name}</p>
              <p className="text-slate-700">
                {`${typeName(edge.sourceTypeId)} → ${typeName(edge.targetTypeId)} · ${cardinalityLabels[edge.cardinality]}`}
              </p>
              <p className="text-xs text-slate-500">
                {`활성 연결 ${edge.activeLinkCount}건${edge.relationCount == null ? '' : ` · 전체 ${edge.relationCount}건`}`}
              </p>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

type TypeRelationsProps = {
  graph: ModelGraph
  typeId: number
}

// 유형 관계도에서 고른 객체 유형의 나가는 관계와 들어오는 관계
function TypeRelations({ graph, typeId }: TypeRelationsProps) {
  const type = graph.nodes.find((node) => node.id === typeId)
  if (!type) {
    return null
  }

  function typeName(id: number) {
    return graph.nodes.find((node) => node.id === id)?.name ?? `유형 #${id}`
  }

  const outgoing = graph.edges.filter((edge) => edge.sourceTypeId === type.id)
  const incoming = graph.edges.filter(
    (edge) => edge.targetTypeId === type.id && edge.sourceTypeId !== type.id,
  )

  return (
    <div>
      <p className="text-xs text-slate-500">
        {type.objectCount === undefined
          ? '객체 유형'
          : `객체 유형 · 객체 ${type.objectCount}개`}
      </p>
      <h2 className="mt-1 text-lg font-semibold text-slate-900">{type.name}</h2>
      {outgoing.length + incoming.length === 0 ? (
        <p className="mt-4 text-sm text-slate-500">
          이 유형에 정의된 관계가 없습니다.
        </p>
      ) : (
        <>
          <RelationList
            title="나가는 관계"
            edges={outgoing}
            typeName={typeName}
          />
          <RelationList
            title="들어오는 관계"
            edges={incoming}
            typeName={typeName}
          />
        </>
      )}
    </div>
  )
}

export default TypeRelations
