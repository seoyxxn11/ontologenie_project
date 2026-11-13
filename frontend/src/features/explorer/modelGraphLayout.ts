import { Graph, layout } from '@dagrejs/dagre'
import { routeEdges, type Box, type Route } from './edgeRoute'
import type { ModelEdge, ModelNode } from './types'

export const TYPE_NODE_WIDTH = 156
export const TYPE_NODE_HEIGHT = 52

// 선 경로 계산이 (0, 0)에서 시작하므로, 가장자리 카드 바깥으로도 선이 지나갈 여백을 둔다.
const GRAPH_MARGIN = 32

export type Point = {
  x: number
  y: number
}

// 같은 출발·도착 유형 사이의 관계 유형들. 화면에는 선 하나로 그리고 이름을 줄을 나눠 적는다.
export type RelationLine = {
  key: string
  sourceTypeId: number
  targetTypeId: number
  edges: ModelEdge[]
}

// 관계 유형을 출발·도착 유형별로 묶는다. 같은 유형으로 돌아오는 관계도 포함한다.
export function groupRelationLines(edges: ModelEdge[]): RelationLine[] {
  const lineByKey = new Map<string, RelationLine>()
  for (const edge of edges) {
    const key = `${edge.sourceTypeId}-${edge.targetTypeId}`
    const line = lineByKey.get(key) ?? {
      key,
      sourceTypeId: edge.sourceTypeId,
      targetTypeId: edge.targetTypeId,
      edges: [],
    }
    line.edges.push(edge)
    lineByKey.set(key, line)
  }
  return [...lineByKey.values()]
}

// 자기 유형 관계의 이름과 곡선이 카드 위에 들어갈 공간을 확보한다.
export function selfRelationHeight(lines: RelationLine[], typeId: number) {
  const line = lines.find(
    (item) => item.sourceTypeId === typeId && item.targetTypeId === typeId,
  )
  return line ? Math.max(80, line.edges.length * 16 + 24) : 0
}

// 유형 카드의 왼쪽 위 자리(px)를 자동 배치(dagre)로 정한다. 관계 방향대로 왼쪽에서 오른쪽으로 놓고,
// 서로 연결되지 않은 묶음이나 관계가 없는 유형도 겹치지 않게 따로 자리를 잡는다.
export function layoutTypeGraph(
  nodes: ModelNode[],
  lines: RelationLine[],
): Map<number, Point> {
  const graph = new Graph()
  // 이름표는 고른 유형의 선에만 보이므로 이름표 자리를 비워 두지 않는다. 기록 유형이 한 열에 많이
  // 몰리는 구조라 카드 세로 간격을 좁히고, 그 대신 열 간격을 넓혀 선이 지나갈 통로를 확보한다.
  graph.setGraph({
    rankdir: 'LR',
    nodesep: 16,
    ranksep: 120,
    marginx: GRAPH_MARGIN,
    marginy: GRAPH_MARGIN,
  })
  graph.setDefaultEdgeLabel(() => ({}))

  for (const node of nodes) {
    graph.setNode(String(node.id), {
      width: TYPE_NODE_WIDTH,
      height: TYPE_NODE_HEIGHT + selfRelationHeight(lines, node.id),
    })
  }
  for (const line of lines) {
    if (line.sourceTypeId === line.targetTypeId) continue
    graph.setEdge(String(line.sourceTypeId), String(line.targetTypeId))
  }

  layout(graph)

  const points = new Map<number, Point>()
  for (const node of nodes) {
    const { x, y } = graph.node(String(node.id))
    points.set(node.id, {
      x: x - TYPE_NODE_WIDTH / 2,
      y: y - (TYPE_NODE_HEIGHT + selfRelationHeight(lines, node.id)) / 2,
    })
  }
  return points
}

// 관계 선이 다른 유형 카드를 지나가지 않게, 카드 사이 빈 길로 꺾이는 경로를 계산한다.
// 자기 유형 관계는 카드 위에 따로 그리므로 계산에서 빼고, 그 자리는 다른 선이 지나가지 못하게 막는다.
// 길을 찾지 못한 선은 route.fallback이 true로 온다. 그 선은 화면에서 원래 곡선으로 그린다.
export function routeRelationLines(
  nodes: ModelNode[],
  lines: RelationLine[],
  points: Map<number, Point>,
): Map<string, Route> {
  const boxes = new Map<string, Box>()
  let width = 0
  let height = 0
  for (const node of nodes) {
    const point = points.get(node.id)
    if (!point) continue
    const loopHeight = selfRelationHeight(lines, node.id)
    boxes.set(String(node.id), {
      x: point.x,
      y: point.y + loopHeight,
      w: TYPE_NODE_WIDTH,
      h: TYPE_NODE_HEIGHT,
    })
    if (loopHeight > 0) {
      boxes.set(`loop-${node.id}`, {
        x: point.x,
        y: point.y,
        w: TYPE_NODE_WIDTH,
        h: loopHeight,
      })
    }
    width = Math.max(width, point.x + TYPE_NODE_WIDTH)
    height = Math.max(height, point.y + loopHeight + TYPE_NODE_HEIGHT)
  }

  return routeEdges(
    lines
      .filter((line) => line.sourceTypeId !== line.targetTypeId)
      .map((line) => ({
        key: line.key,
        from: String(line.sourceTypeId),
        to: String(line.targetTypeId),
      })),
    boxes,
    { width: width + GRAPH_MARGIN * 2, height: height + GRAPH_MARGIN * 2 },
  )
}
