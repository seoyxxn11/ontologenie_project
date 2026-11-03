import { Graph, layout } from '@dagrejs/dagre'
import type { ModelEdge, ModelNode } from './types'

export const TYPE_NODE_WIDTH = 120
export const TYPE_NODE_HEIGHT = 64

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

// 관계 유형을 출발·도착 유형별로 묶는다. 자기 자신으로 이어지는 관계는 선으로 그리지 않는다.
export function groupRelationLines(edges: ModelEdge[]): RelationLine[] {
  const lineByKey = new Map<string, RelationLine>()
  for (const edge of edges) {
    if (edge.sourceTypeId === edge.targetTypeId) {
      continue
    }
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

// 관계 이름 칸의 대략적인 크기. 자동 배치가 이 자리를 비워 두어 이름이 카드와 겹치지 않게 한다.
function labelSize(line: RelationLine) {
  const longest = Math.max(
    ...line.edges.map(
      (edge) => `${edge.name} · ${edge.activeLinkCount}건`.length,
    ),
  )
  return { width: longest * 12 + 8, height: line.edges.length * 16 + 4 }
}

// 유형 카드의 왼쪽 위 자리(px)를 자동 배치(dagre)로 정한다. 관계 방향대로 왼쪽에서 오른쪽으로 놓고,
// 서로 연결되지 않은 묶음이나 관계가 없는 유형도 겹치지 않게 따로 자리를 잡는다.
export function layoutTypeGraph(
  nodes: ModelNode[],
  lines: RelationLine[],
): Map<number, Point> {
  const graph = new Graph()
  graph.setGraph({ rankdir: 'LR', nodesep: 40, ranksep: 80 })
  graph.setDefaultEdgeLabel(() => ({}))

  for (const node of nodes) {
    graph.setNode(String(node.id), {
      width: TYPE_NODE_WIDTH,
      height: TYPE_NODE_HEIGHT,
    })
  }
  for (const line of lines) {
    graph.setEdge(String(line.sourceTypeId), String(line.targetTypeId), {
      ...labelSize(line),
      labelpos: 'c',
    })
  }

  layout(graph)

  const points = new Map<number, Point>()
  for (const node of nodes) {
    const { x, y } = graph.node(String(node.id))
    points.set(node.id, {
      x: x - TYPE_NODE_WIDTH / 2,
      y: y - TYPE_NODE_HEIGHT / 2,
    })
  }
  return points
}
