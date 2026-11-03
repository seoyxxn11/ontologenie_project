import {
  BaseEdge,
  Controls,
  EdgeLabelRenderer,
  getBezierPath,
  Handle,
  MarkerType,
  Position,
  ReactFlow,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import {
  groupRelationLines,
  layoutTypeGraph,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
} from './modelGraphLayout'
import type {
  ModelEdge,
  ModelGraph as ModelGraphData,
  ModelNode,
} from './types'

type TypeNodeData = {
  type: ModelNode
  selected: boolean
  onSelect: (id: number) => void
}

type TypeFlowNode = Node<TypeNodeData, 'type'>

// 유형 카드. 선이 붙을 자리(Handle)는 보이지 않게 두고, 카드 자체는 누를 수 있는 버튼이다.
function TypeNode({ data }: NodeProps<TypeFlowNode>) {
  const { type, selected, onSelect } = data
  // 객체가 하나도 없는 유형은 자리와 건수만 보여 주고 누를 수 없게 한다.
  const empty = type.objectCount === 0

  return (
    <>
      <Handle
        type="target"
        position={Position.Left}
        isConnectable={false}
        style={{ opacity: 0 }}
      />
      <button
        type="button"
        aria-pressed={selected}
        disabled={empty}
        onClick={() => onSelect(type.id)}
        // nopan·nodrag: 카드를 누를 때 관계도 끌어서 이동이 같이 시작되지 않게 한다.
        className={`nopan nodrag rounded-lg border px-2 py-2 text-center text-xs ${
          selected
            ? 'border-slate-900 bg-slate-900 text-white ring-4 ring-slate-200'
            : 'border-slate-300 bg-white text-slate-900 hover:border-slate-900 disabled:border-dashed disabled:bg-slate-50 disabled:text-slate-400 disabled:hover:border-slate-300'
        }`}
        // 누를 수 없는 노드는 React Flow가 pointer-events를 끄기 때문에, 카드 버튼만 다시 켠다.
        style={{
          width: TYPE_NODE_WIDTH,
          height: TYPE_NODE_HEIGHT,
          pointerEvents: 'all',
        }}
      >
        <span
          className={`block ${selected ? 'text-slate-300' : 'text-slate-500'}`}
        >
          객체 유형
        </span>
        <strong className="block">{type.name}</strong>
        {type.objectCount !== undefined && (
          <span className={selected ? 'text-slate-300' : ''}>
            {`객체 ${type.objectCount}개`}
          </span>
        )}
      </button>
      <Handle
        type="source"
        position={Position.Right}
        isConnectable={false}
        style={{ opacity: 0 }}
      />
    </>
  )
}

type RelationEdgeData = {
  edges: ModelEdge[]
}

type RelationFlowEdge = Edge<RelationEdgeData, 'relation'>

// 관계 선. 같은 출발·도착 유형 사이의 관계 이름을 선 가운데에 줄을 나눠 적는다.
function RelationEdge({
  id,
  sourceX,
  sourceY,
  targetX,
  targetY,
  sourcePosition,
  targetPosition,
  markerEnd,
  style,
  data,
}: EdgeProps<RelationFlowEdge>) {
  const [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  })

  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} style={style} />
      <EdgeLabelRenderer>
        <div
          className="absolute rounded bg-white/90 px-1 text-center text-xs leading-tight whitespace-nowrap text-slate-600"
          style={{
            transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
          }}
        >
          {data?.edges.map((edge) => (
            <span key={edge.id} className="block">
              {`${edge.name} · ${edge.activeLinkCount}건`}
            </span>
          ))}
        </div>
      </EdgeLabelRenderer>
    </>
  )
}

const nodeTypes = { type: TypeNode }
const edgeTypes = { relation: RelationEdge }

type ModelGraphProps = {
  graph: ModelGraphData
  selectedTypeId: number | null
  onSelect: (id: number) => void
}

function ModelGraph({ graph, selectedTypeId, onSelect }: ModelGraphProps) {
  const lines = groupRelationLines(graph.edges)
  const points = layoutTypeGraph(graph.nodes, lines)

  // 카드 크기와 선이 붙을 자리를 미리 알려 주면, 화면에서 크기를 재기 전에도 선을 그릴 수 있다.
  const nodes: TypeFlowNode[] = graph.nodes.map((type) => ({
    id: String(type.id),
    type: 'type',
    position: points.get(type.id) ?? { x: 0, y: 0 },
    width: TYPE_NODE_WIDTH,
    height: TYPE_NODE_HEIGHT,
    handles: [
      {
        type: 'target',
        position: Position.Left,
        x: 0,
        y: TYPE_NODE_HEIGHT / 2,
        width: 1,
        height: 1,
      },
      {
        type: 'source',
        position: Position.Right,
        x: TYPE_NODE_WIDTH,
        y: TYPE_NODE_HEIGHT / 2,
        width: 1,
        height: 1,
      },
    ],
    data: { type, selected: type.id === selectedTypeId, onSelect },
  }))

  const edges: RelationFlowEdge[] = lines.map((line) => ({
    id: line.key,
    type: 'relation',
    source: String(line.sourceTypeId),
    target: String(line.targetTypeId),
    markerEnd: { type: MarkerType.ArrowClosed, color: '#94a3b8' },
    style: {
      stroke: '#94a3b8',
      strokeWidth: 1.5,
      strokeDasharray: line.edges.every((edge) => edge.activeLinkCount === 0)
        ? '4 4'
        : undefined,
    },
    data: { edges: line.edges },
  }))

  return (
    <div>
      <h2 className="mb-3 text-lg font-semibold text-slate-900">
        객체 유형 간 관계
      </h2>

      <div className="h-[460px] bg-white">
        <ReactFlow
          nodes={nodes}
          edges={edges}
          nodeTypes={nodeTypes}
          edgeTypes={edgeTypes}
          fitView
          fitViewOptions={{ padding: 0.15 }}
          minZoom={0.3}
          maxZoom={1.5}
          nodesDraggable={false}
          nodesConnectable={false}
          elementsSelectable={false}
          proOptions={{ hideAttribution: true }}
        >
          <Controls showInteractive={false} />
        </ReactFlow>
      </div>

      <p className="mt-2 text-xs text-slate-700">
        {`객체 유형 ${graph.nodes.length}개 · 관계 유형 ${graph.edges.length}개`}
      </p>
      <p className="text-xs text-slate-500">
        각 카드는 개별 객체가 아닌 객체 유형입니다. 유형을 선택하면 왼쪽에 그
        유형의 객체 목록이 열리고, 객체를 선택하면 그 객체의 관계도로 바뀝니다.
        빈 곳을 끌어서 옮기고, 마우스 휠이나 왼쪽 아래 버튼으로 확대·축소할 수
        있습니다.
      </p>
    </div>
  )
}

export default ModelGraph
