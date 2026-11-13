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
  type Viewport,
} from '@xyflow/react'
import {
  useCallback,
  useEffect,
  useMemo,
  useReducer,
  useRef,
  useState,
} from 'react'
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
import { focusTypeGraph, layoutFocusedTypeGraph } from './modelGraphFocus'
import {
  createGraphNavigation,
  fullscreenGraphViewport,
  graphNavigationReducer,
  type GraphCameraRequest,
} from './modelGraphNavigation'
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

const LINE_COLOR = '#94a3b8'
const INCOMING_LINE_COLOR = '#657319'
const OUTGOING_LINE_COLOR = '#6f5adf'
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
  onSelect: (id: number | null) => void
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
  const [navigation, navigate] = useReducer(
    graphNavigationReducer,
    selectedTypeId,
    createGraphNavigation,
  )
  const [fullscreen, setFullscreen] = useState(false)
  const [fullscreenError, setFullscreenError] = useState<string | null>(null)
  const fullscreenEntry = useRef<{
    viewport: Viewport
    size: { width: number; height: number }
    selectedCenter?: { x: number; y: number }
    entered: boolean
  } | null>(null)
  // 검색 줄에서 유형을 바꾼 경우도 이전 선택과 마지막 화면 위치를 기록한다.
  if (navigation.selectedTypeId !== selectedTypeId) {
    navigate({ type: 'select', id: selectedTypeId })
  }
  const selectedType = graph.nodes.find((node) => node.id === selectedTypeId)
  const focused = Boolean(selectedType) && navigation.focused
  const visibleGraph = useMemo(
    () =>
      focused && selectedTypeId !== null
        ? focusTypeGraph(graph, selectedTypeId)
        : graph,
    [graph, focused, selectedTypeId],
  )
  const { lines, points, routes } = useMemo(() => {
    const lines = groupRelationLines(visibleGraph.edges)
    const points =
      focused && selectedTypeId !== null
        ? layoutFocusedTypeGraph(visibleGraph.nodes, lines, selectedTypeId)
        : layoutTypeGraph(visibleGraph.nodes, lines)
    const routes = routeRelationLines(visibleGraph.nodes, lines, points)
    return { lines, points, routes }
  }, [visibleGraph, focused, selectedTypeId])
  // 이름표 자리는 고른 유형이 바뀔 때만 다시 정한다.
  const labelPositions = useMemo(
    () =>
      placeRelationLabels(
        visibleGraph.nodes,
        lines,
        points,
        routes,
        selectedTypeId,
      ),
    [visibleGraph.nodes, lines, points, routes, selectedTypeId],
  )

  // 고른 유형과 그 유형에 연결된 유형이 모두 보이게 맞춘다. 고른 유형이 없으면 전체를 맞춘다.
  const fitSelection = useCallback(() => {
    const instance = flow.current
    if (!instance) return
    if (!focused || selectedTypeId === null) {
      void instance.fitView({ padding: 0.15 })
      return
    }
    const boxes = visibleGraph.nodes.flatMap(({ id }) => {
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
    if (boxes.length === 1 && selfRelationHeight(lines, selectedTypeId) === 0) {
      // 연결 없는 유형은 카드 하나만 크게 확대하지 않고 읽기 크기(1배)로 가운데에 둔다.
      const [box] = boxes
      void instance.setCenter(
        (box.left + box.right) / 2,
        (box.top + box.bottom) / 2,
        { zoom: 1 },
      )
      return
    }
    const selected = points.get(selectedTypeId)
    if (!selected) return
    const centerX = selected.x + TYPE_NODE_WIDTH / 2
    const centerY =
      selected.y +
      selfRelationHeight(lines, selectedTypeId) +
      TYPE_NODE_HEIGHT / 2
    // 양옆 관계 수가 달라도 선택한 카드가 화면 가운데에 오도록 대칭 영역을 맞춘다.
    const halfWidth = Math.max(
      ...boxes.flatMap((box) => [centerX - box.left, box.right - centerX]),
    )
    const halfHeight = Math.max(
      ...boxes.flatMap((box) => [centerY - box.top, box.bottom - centerY]),
    )
    void instance.fitBounds(
      {
        x: centerX - halfWidth,
        y: centerY - halfHeight,
        width: halfWidth * 2,
        height: halfHeight * 2,
      },
      { padding: 0.15 },
    )
  }, [focused, selectedTypeId, visibleGraph.nodes, points, lines])

  const applyCamera = useCallback(
    (camera: GraphCameraRequest, remembered: Viewport | null = null) => {
      const instance = flow.current
      if (!instance) return
      if (camera.kind === 'restore') {
        void instance.setViewport(camera.viewport)
      } else if (camera.kind === 'keep' && remembered) {
        void instance.setViewport(remembered)
      } else if (camera.kind === 'fit') {
        fitSelection()
      }
    },
    [fitSelection],
  )

  useEffect(() => {
    if (view === 'graph' && navigation.camera.kind !== 'keep') {
      applyCamera(navigation.camera)
    }
  }, [applyCamera, navigation.camera, view])

  const rememberViewport = useCallback((viewport: Viewport) => {
    navigate({ type: 'viewport', viewport })
  }, [])

  function selectType(id: number) {
    navigate({ type: 'select', id, viewport: flow.current?.getViewport() })
    onSelect(id)
  }

  function previousView() {
    const previous = navigation.history.at(-1)
    if (!previous) return
    navigate({ type: 'back' })
    onSelect(previous.selectedTypeId)
  }

  async function toggleFullscreen() {
    setFullscreenError(null)
    try {
      if (document.fullscreenElement === canvas.current) {
        await document.exitFullscreen()
      } else {
        const element = canvas.current
        const instance = flow.current
        if (!element || !instance) return
        const point =
          selectedTypeId === null ? undefined : points.get(selectedTypeId)
        fullscreenEntry.current = {
          viewport: instance.getViewport(),
          size: { width: element.clientWidth, height: element.clientHeight },
          selectedCenter:
            point && selectedTypeId !== null
              ? {
                  x: point.x + TYPE_NODE_WIDTH / 2,
                  y:
                    point.y +
                    selfRelationHeight(lines, selectedTypeId) +
                    TYPE_NODE_HEIGHT / 2,
                }
              : undefined,
          entered: false,
        }
        await element.requestFullscreen()
      }
    } catch {
      setFullscreenError(
        '전체 화면을 열거나 닫지 못했습니다. 다시 시도해 주세요.',
      )
    }
  }

  useEffect(() => {
    const onFullscreenChange = () =>
      setFullscreen(
        Boolean(canvas.current) &&
          document.fullscreenElement === canvas.current,
      )
    document.addEventListener('fullscreenchange', onFullscreenChange)
    return () =>
      document.removeEventListener('fullscreenchange', onFullscreenChange)
  }, [])

  useEffect(() => {
    const entry = fullscreenEntry.current
    const instance = flow.current
    const element = canvas.current
    if (!entry || !instance || !element) return
    if (fullscreen) {
      entry.entered = true
      void instance.setViewport(
        fullscreenGraphViewport(
          entry.viewport,
          entry.size,
          { width: element.clientWidth, height: element.clientHeight },
          entry.selectedCenter,
        ),
      )
    } else if (entry.entered) {
      void instance.setViewport(entry.viewport)
      fullscreenEntry.current = null
    }
  }, [fullscreen])

  // 칸 크기가 바뀌어도 같은 배율과 화면 중심을 유지한다.
  useEffect(() => {
    const element = canvas.current
    if (view !== 'graph' || !element) {
      flow.current = null
      return
    }
    // 스크롤바가 생기고 없어지는 정도(약 15px)의 작은 변화로는 다시 맞추지 않는다.
    // 그래야 사용자가 직접 확대·이동한 화면이 유지된다. 확장·창 크기 변경처럼 크게 바뀔 때만 맞춘다.
    const shouldRefit = createRefitCheck(
      { width: element.clientWidth, height: element.clientHeight },
      RESIZE_REFIT_PX,
    )
    let previousSize = {
      width: element.clientWidth,
      height: element.clientHeight,
    }
    let wasFullscreen = document.fullscreenElement === element
    const observer = new ResizeObserver(() => {
      const size = { width: element.clientWidth, height: element.clientHeight }
      const isFullscreen = document.fullscreenElement === element
      // 진입·종료는 위의 전체 화면 카메라가 처리한다. 일반 창 크기 변경만 중심을 보정한다.
      if (wasFullscreen !== isFullscreen) {
        wasFullscreen = isFullscreen
        previousSize = size
        shouldRefit(size)
        return
      }
      if (shouldRefit(size)) {
        const instance = flow.current
        if (instance) {
          const viewport = instance.getViewport()
          void instance.setViewport({
            ...viewport,
            x: viewport.x + (size.width - previousSize.width) / 2,
            y: viewport.y + (size.height - previousSize.height) / 2,
          })
        }
        previousSize = size
      }
    })
    observer.observe(element)
    return () => observer.disconnect()
  }, [view])

  // 카드 크기와 선이 붙을 자리를 미리 알려 주면, 화면에서 크기를 재기 전에도 선을 그릴 수 있다.
  const nodes: TypeFlowNode[] = visibleGraph.nodes.map((type) => ({
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
      onSelect: selectType,
      loopHeight: selfRelationHeight(lines, type.id),
    },
  }))

  // 선택 유형 기준 들어오는 선은 황록, 나가는 선과 자기 관계는 보라로 구분한다.
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
    const color = !related
      ? LINE_COLOR
      : line.sourceTypeId === selectedTypeId
        ? OUTGOING_LINE_COLOR
        : INCOMING_LINE_COLOR
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

  const navigationButtons = (
    <>
      <Button
        variant="secondary"
        disabled={navigation.history.length === 0}
        onClick={previousView}
      >
        이전 보기
      </Button>
      <Button
        variant="secondary"
        disabled={!selectedType}
        aria-pressed={focused}
        onClick={() =>
          navigate({ type: 'focus', viewport: flow.current?.getViewport() })
        }
      >
        {focused ? '전체 배치 보기' : '집중 보기'}
      </Button>
    </>
  )

  return (
    <div>
      {view === 'list' ? (
        <div className="model-graph-directory" aria-label="객체 유형 목록">
          {graph.nodes.map((type) => (
            <Button
              key={type.id}
              variant="secondary"
              aria-pressed={type.id === selectedTypeId}
              onClick={() => selectType(type.id)}
            >
              {type.name}
            </Button>
          ))}
        </div>
      ) : (
        <>
          <div className="model-graph-focus-bar">
            <p role="status">
              <strong>{selectedType?.name ?? '전체 유형'}</strong>
              {focused
                ? ` 중심 · 관련 없는 유형 ${graph.nodes.length - visibleGraph.nodes.length}개 접힘`
                : ' · 전체 배치 유지'}
            </p>
            <div className="model-graph-navigation-actions">
              {navigationButtons}
            </div>
          </div>
          {fullscreenError && <p role="alert">{fullscreenError}</p>}
          <div ref={canvas} className="explorer-graph-canvas">
            <ReactFlow
              nodes={nodes}
              edges={edges}
              nodeTypes={nodeTypes}
              edgeTypes={edgeTypes}
              onInit={(instance) => {
                flow.current = instance
                applyCamera(navigation.camera, navigation.viewport)
              }}
              // 카메라는 초기 맞춤·명시적 집중 보기·이전 보기에서만 바꾼다.
              fitView={false}
              onViewportChange={rememberViewport}
              zoomOnScroll
              zoomOnPinch
              preventScrolling
              panOnScroll={false}
              panOnDrag
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
              {fullscreen && (
                <Panel position="bottom-left">
                  <div
                    className="model-graph-fullscreen-navigation"
                    role="group"
                    aria-label="전체 화면 탐색"
                  >
                    <strong>{selectedType?.name ?? '전체 유형'}</strong>
                    {navigationButtons}
                    <span>Esc로 전체 화면 닫기</span>
                  </div>
                </Panel>
              )}
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
                  <button type="button" onClick={fitSelection}>
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
                  <button type="button" onClick={() => void toggleFullscreen()}>
                    {fullscreen ? '전체 화면 닫기' : '전체 화면'}
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
          {focused && view === 'graph'
            ? `객체 유형 ${visibleGraph.nodes.length}/${graph.nodes.length}개 · 관계 유형 ${visibleGraph.edges.length}/${graph.edges.length}개`
            : `객체 유형 ${graph.nodes.length}개 · 관계 유형 ${graph.edges.length}개`}
        </span>
        {view === 'graph' && (
          <span>점선: 현재 연결 0건 · 화살표: 관계 방향</span>
        )}
        {view === 'graph' && selectedType && (
          <>
            <span className="model-graph-legend-in">→ 들어오는 관계</span>
            <span className="model-graph-legend-out">
              → 나가는 관계 · 자기 관계
            </span>
          </>
        )}
      </p>
    </div>
  )
}

export default ModelGraph
