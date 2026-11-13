import {
  BaseEdge,
  EdgeLabelRenderer,
  getBezierPath,
  Handle,
  MarkerType,
  Panel,
  Position,
  ReactFlow,
  type Edge,
  type EdgeProps,
  type Node,
  type NodeProps,
  type ReactFlowInstance,
} from '@xyflow/react'
import { useCallback, useEffect, useMemo, useRef } from 'react'
import Button from '../../components/Button'
import type { Route } from './edgeRoute'
import '@xyflow/react/dist/style.css'
import {
  groupRelationLines,
  layoutTypeGraph,
  routeRelationLines,
  selfRelationHeight,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
} from './modelGraphLayout'
import {
  LABEL_MAX_WIDTH,
  placeRelationLabels,
  relationLabelText,
} from './relationLabels'
import { createRefitCheck } from './resizeRefit'
import type {
  ModelEdge,
  ModelGraph as ModelGraphData,
  ModelNode,
} from './types'

type TypeNodeData = {
  type: ModelNode
  selected: boolean
  onSelect: (id: number) => void
  loopHeight: number
}

type TypeFlowNode = Node<TypeNodeData, 'type'>

// 유형 카드. 선이 붙을 자리(Handle)는 보이지 않게 두고, 카드 자체는 누를 수 있는 버튼이다.
function TypeNode({ data }: NodeProps<TypeFlowNode>) {
  const { type, selected, onSelect, loopHeight } = data

  return (
    <div style={{ paddingTop: loopHeight }}>
      <Handle
        type="target"
        position={Position.Left}
        isConnectable={false}
        style={{ opacity: 0, top: loopHeight + TYPE_NODE_HEIGHT / 2 }}
      />
      <button
        type="button"
        aria-pressed={selected}
        // 화면에서는 유형 이름만 보이지만, 화면 낭독기에는 무엇의 이름인지 함께 알린다.
        aria-label={`객체 유형 ${type.name}`}
        onClick={() => onSelect(type.id)}
        // nopan·nodrag: 카드를 누를 때 관계도 끌어서 이동이 같이 시작되지 않게 한다.
        className={`model-graph-node nopan nodrag border px-2 py-2 text-center ${
          selected
            ? 'border-slate-900 bg-white text-slate-900'
            : 'border-slate-300 bg-white text-slate-900 hover:border-slate-900 disabled:border-dashed disabled:bg-slate-50 disabled:text-slate-400 disabled:hover:border-slate-300'
        }`}
        // 누를 수 없는 노드는 React Flow가 pointer-events를 끄기 때문에, 카드 버튼만 다시 켠다.
        style={{
          width: TYPE_NODE_WIDTH,
          height: TYPE_NODE_HEIGHT,
          pointerEvents: 'all',
        }}
      >
        <strong className="block">{type.name}</strong>
      </button>
      <Handle
        type="source"
        position={Position.Right}
        isConnectable={false}
        style={{ opacity: 0, top: loopHeight + TYPE_NODE_HEIGHT / 2 }}
      />
    </div>
  )
}

type RelationEdgeData = {
  edges: ModelEdge[]
  loopHeight: number
  // 카드를 피해 가도록 계산한 경로. 자기 유형 관계에는 없다.
  route?: Route
  // 이름표는 고른 유형에 붙은 선에만 보인다.
  showLabel: boolean
  // 카드와 다른 이름표를 피해 정한 이름표 자리. 자기 유형 관계는 카드 위 고리에 적으므로 없다.
  labelAt?: { x: number; y: number }
}

type RelationFlowEdge = Edge<RelationEdgeData, 'relation'>

// 관계 선. 계산한 경로로 카드를 피해 그리고, 고른 유형의 선이면 같은 출발·도착 유형 사이의 관계 이름을 줄을 나눠 적는다.
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
  let [path, labelX, labelY] = getBezierPath({
    sourceX,
    sourceY,
    targetX,
    targetY,
    sourcePosition,
    targetPosition,
  })
  if (data?.loopHeight) {
    labelX = (sourceX + targetX) / 2
    labelY = sourceY - TYPE_NODE_HEIGHT / 2 - data.loopHeight / 2
    path = `M ${sourceX} ${sourceY} C ${sourceX + 24} ${sourceY}, ${sourceX + 24} ${labelY}, ${labelX} ${labelY} C ${targetX - 24} ${labelY}, ${targetX - 24} ${targetY}, ${targetX} ${targetY}`
  } else {
    // 계산한 경로가 있으면 그 경로로 그린다. 길을 찾지 못한 선은 위의 곡선을 그대로 쓴다.
    if (data?.route && !data.route.fallback) path = data.route.d
    if (data?.labelAt) {
      labelX = data.labelAt.x
      labelY = data.labelAt.y
    }
  }

  return (
    <>
      <BaseEdge id={id} path={path} markerEnd={markerEnd} style={style} />
      {data?.showLabel && (
        <EdgeLabelRenderer>
          {/* 열 사이 간격에 들어가도록 폭을 제한하고 띄어쓰기 단위로 줄을 바꾼다(relationLabels의 크기 어림과 같은 규칙). */}
          <div
            className="model-graph-edge-label absolute rounded bg-white/90 px-1 text-center text-xs leading-tight text-slate-600"
            style={{
              maxWidth: LABEL_MAX_WIDTH,
              transform: `translate(-50%, -50%) translate(${labelX}px, ${labelY}px)`,
            }}
          >
            {data.edges.map((edge) => (
              <span key={edge.id} className="block">
                {relationLabelText(edge)}
              </span>
            ))}
          </div>
        </EdgeLabelRenderer>
      )}
    </>
  )
}

// 선 색. 고른 유형에 붙은 선은 키컬러로 강조한다.
const LINE_COLOR = '#94a3b8'
const SELECTED_LINE_COLOR = '#6f5adf'
// 그림 칸 크기가 이만큼 이상 바뀌어야 고른 유형에 다시 맞춘다.
const RESIZE_REFIT_PX = 40

const nodeTypes = { type: TypeNode }
const edgeTypes = { relation: RelationEdge }

// 개요(관계도 그림) 또는 유형 목록. 고르는 버튼은 관계도 칸 제목 줄에 있다.
export type ModelGraphView = 'graph' | 'list'

type ModelGraphProps = {
  view: ModelGraphView
  graph: ModelGraphData
  selectedTypeId: number | null
  onSelect: (id: number) => void
}

function ModelGraph({
  view,
  graph,
  selectedTypeId,
  onSelect,
}: ModelGraphProps) {
  const flow = useRef<ReactFlowInstance<TypeFlowNode, RelationFlowEdge> | null>(
    null,
  )
  const canvas = useRef<HTMLDivElement | null>(null)
  // 배치와 선 경로는 관계도 데이터가 바뀔 때만 다시 계산한다. 유형을 고를 때는 다시 계산하지 않는다.
  const { lines, points, routes } = useMemo(() => {
    const lines = groupRelationLines(graph.edges)
    const points = layoutTypeGraph(graph.nodes, lines)
    const routes = routeRelationLines(graph.nodes, lines, points)
    return { lines, points, routes }
  }, [graph])
  // 이름표 자리는 고른 유형이 바뀔 때만 다시 정한다.
  const labelPositions = useMemo(
    () =>
      placeRelationLabels(graph.nodes, lines, points, routes, selectedTypeId),
    [graph.nodes, lines, points, routes, selectedTypeId],
  )

  // 고른 유형과 그 유형에 연결된 유형이 모두 보이게 맞춘다. 고른 유형이 없으면 전체를 맞춘다.
  const fitSelection = useCallback(() => {
    const instance = flow.current
    if (!instance) return
    if (selectedTypeId === null) {
      void instance.fitView({ padding: 0.15 })
      return
    }
    const ids = new Set([selectedTypeId])
    for (const edge of graph.edges) {
      if (edge.sourceTypeId === selectedTypeId) ids.add(edge.targetTypeId)
      if (edge.targetTypeId === selectedTypeId) ids.add(edge.sourceTypeId)
    }
    const boxes = [...ids].flatMap((id) => {
      const point = points.get(id)
      if (!point) return []
      return [
        {
          left: point.x,
          top: point.y,
          right: point.x + TYPE_NODE_WIDTH,
          bottom: point.y + TYPE_NODE_HEIGHT + selfRelationHeight(lines, id),
        },
      ]
    })
    if (boxes.length === 0) return
    if (boxes.length === 1) {
      // 연결 없는 유형은 카드 하나만 크게 확대하지 않고 읽기 크기(1배)로 가운데에 둔다.
      const [box] = boxes
      void instance.setCenter(
        (box.left + box.right) / 2,
        (box.top + box.bottom) / 2,
        { zoom: 1 },
      )
      return
    }
    const left = Math.min(...boxes.map((box) => box.left))
    const top = Math.min(...boxes.map((box) => box.top))
    void instance.fitBounds(
      {
        x: left,
        y: top,
        width: Math.max(...boxes.map((box) => box.right)) - left,
        height: Math.max(...boxes.map((box) => box.bottom)) - top,
      },
      { padding: 0.15 },
    )
  }, [selectedTypeId, graph.edges, points, lines])

  // 유형을 고르면 맞추고, 관계도 확장·창 크기 변경으로 그림 칸 크기가 바뀌면 바뀐 크기에 다시 맞춘다.
  useEffect(() => {
    const element = canvas.current
    if (view !== 'graph' || !element) {
      flow.current = null
      return
    }
    fitSelection()
    // 스크롤바가 생기고 없어지는 정도(약 15px)의 작은 변화로는 다시 맞추지 않는다.
    // 그래야 사용자가 직접 확대·이동한 화면이 유지된다. 확장·창 크기 변경처럼 크게 바뀔 때만 맞춘다.
    const shouldRefit = createRefitCheck(
      { width: element.clientWidth, height: element.clientHeight },
      RESIZE_REFIT_PX,
    )
    const observer = new ResizeObserver(() => {
      if (
        shouldRefit({
          width: element.clientWidth,
          height: element.clientHeight,
        })
      ) {
        fitSelection()
      }
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [fitSelection, view])

  // 카드 크기와 선이 붙을 자리를 미리 알려 주면, 화면에서 크기를 재기 전에도 선을 그릴 수 있다.
  const nodes: TypeFlowNode[] = graph.nodes.map((type) => ({
    id: String(type.id),
    type: 'type',
    position: points.get(type.id) ?? { x: 0, y: 0 },
    width: TYPE_NODE_WIDTH,
    height: TYPE_NODE_HEIGHT + selfRelationHeight(lines, type.id),
    handles: [
      {
        type: 'target',
        position: Position.Left,
        x: 0,
        y: selfRelationHeight(lines, type.id) + TYPE_NODE_HEIGHT / 2,
        width: 1,
        height: 1,
      },
      {
        type: 'source',
        position: Position.Right,
        x: TYPE_NODE_WIDTH,
        y: selfRelationHeight(lines, type.id) + TYPE_NODE_HEIGHT / 2,
        width: 1,
        height: 1,
      },
    ],
    data: {
      type,
      selected: type.id === selectedTypeId,
      onSelect,
      loopHeight: selfRelationHeight(lines, type.id),
    },
  }))

  // 유형을 고르면 그 유형에 붙은 선만 강조하고 이름표를 보여 준다. 나머지 선은 흐리게 둔다.
  // 강조한 선은 목록 끝에 두어 다른 선보다 위에 그린다. zIndex를 올리면 카드보다 위로 올라가므로 쓰지 않는다.
  const isRelated = (line: (typeof lines)[number]) =>
    selectedTypeId !== null &&
    (line.sourceTypeId === selectedTypeId ||
      line.targetTypeId === selectedTypeId)
  const orderedLines = [
    ...lines.filter((line) => !isRelated(line)),
    ...lines.filter(isRelated),
  ]
  const edges: RelationFlowEdge[] = orderedLines.map((line) => {
    const related = isRelated(line)
    const color = related ? SELECTED_LINE_COLOR : LINE_COLOR
    return {
      id: line.key,
      type: 'relation',
      source: String(line.sourceTypeId),
      target: String(line.targetTypeId),
      markerEnd: { type: MarkerType.ArrowClosed, color },
      style: {
        stroke: color,
        strokeWidth: related ? 2 : 1.5,
        opacity: selectedTypeId !== null && !related ? 0.3 : 1,
        strokeDasharray: line.edges.every((edge) => edge.activeLinkCount === 0)
          ? '4 4'
          : undefined,
      },
      data: {
        edges: line.edges,
        route: routes.get(line.key),
        showLabel: related,
        labelAt: labelPositions.get(line.key),
        loopHeight:
          line.sourceTypeId === line.targetTypeId
            ? selfRelationHeight(lines, line.sourceTypeId)
            : 0,
      },
    }
  })

  return (
    <div>
      {view === 'list' ? (
        <div className="model-graph-directory" aria-label="객체 유형 목록">
          {graph.nodes.map((type) => (
            <Button
              key={type.id}
              variant="secondary"
              aria-pressed={type.id === selectedTypeId}
              onClick={() => onSelect(type.id)}
            >
              {type.name}
            </Button>
          ))}
        </div>
      ) : (
        <>
          <div ref={canvas} className="explorer-graph-canvas">
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              onInit={(instance) => {
                flow.current = instance
                fitSelection()
              }}
              // 유형을 고른 채 다시 그릴 때는 React Flow 자체 전체 맞춤을 끈다.
              // 켜 두면 onInit의 고른 유형 맞춤과 실행 순서가 정해져 있지 않아 전체 맞춤으로 덮일 수 있다.
              fitView={selectedTypeId === null}
              fitViewOptions={{ padding: 0.15 }}
              minZoom={0.3}
              maxZoom={1.5}
              nodesDraggable={false}
              nodesConnectable={false}
              elementsSelectable={false}
              // Tab은 카드 버튼에만 멈춘다. 카드를 감싼 칸과 선은 이름 없이 초점만 받아 길을 잃게 한다.
              nodesFocusable={false}
              edgesFocusable={false}
              proOptions={{ hideAttribution: true }}
            >
              {/* 배율 버튼은 그림 위 오른쪽에 겹쳐 두어 그림 위에 버튼 줄이 생기지 않게 한다. */}
              <Panel position="top-right">
                <div
                  role="group"
                  aria-label="관계도 배율 조절"
                  className="model-graph-zoom"
                >
                  <button
                    type="button"
                    onClick={() => void flow.current?.zoomTo(1)}
                  >
                    읽기 크기
                  </button>
                  <button
                    type="button"
                    onClick={() =>
                      void flow.current?.fitView({ padding: 0.15 })
                    }
                  >
                    전체 맞춤
                  </button>
                  <button
                    type="button"
                    onClick={() => void flow.current?.zoomIn()}
                    aria-label="관계도 확대"
                  >
                    ＋
                  </button>
                  <button
                    type="button"
                    onClick={() => void flow.current?.zoomOut()}
                    aria-label="관계도 축소"
                  >
                    −
                  </button>
                </div>
              </Panel>
            </ReactFlow>
          </div>
        </>
      )}

      {/* 건수와 범례를 한 줄에 두어 1440×900 첫 화면 안에 들어오게 한다. */}
      <p className="model-graph-caption">
        <span>
          {`객체 유형 ${graph.nodes.length}개 · 관계 유형 ${graph.edges.length}개`}
        </span>
        {view === 'graph' && (
          <span>점선: 현재 연결 0건 · 화살표: 관계 방향</span>
        )}
      </p>
    </div>
  )
}

export default ModelGraph
