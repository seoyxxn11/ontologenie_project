import {
  selfRelationHeight,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
  type Point,
  type RelationLine,
} from './modelGraphLayout'
import type { ModelGraph, ModelNode } from './types'

// 선택 유형에 직접 닿는 관계만 남긴다. 이웃끼리의 관계와 두 단계 밖의 유형은 접는다.
export function focusTypeGraph(graph: ModelGraph, selectedTypeId: number) {
  if (!graph.nodes.some((node) => node.id === selectedTypeId)) return graph
  const edges = graph.edges.filter(
    (edge) =>
      edge.sourceTypeId === selectedTypeId ||
      edge.targetTypeId === selectedTypeId,
  )
  const ids = new Set([selectedTypeId])
  for (const edge of edges) {
    ids.add(edge.sourceTypeId)
    ids.add(edge.targetTypeId)
  }
  return { nodes: graph.nodes.filter((node) => ids.has(node.id)), edges }
}

// 선택 유형을 가운데, 들어오는 유형을 왼쪽, 나가는 유형을 오른쪽에 놓는다.
// 양방향 이웃은 왼쪽에 한 번만 놓고, 양쪽 관계의 방향은 각각의 화살표로 보존한다.
export function layoutFocusedTypeGraph(
  nodes: ModelNode[],
  lines: RelationLine[],
  selectedTypeId: number,
): Map<number, Point> {
  const incoming = new Set(
    lines
      .filter((line) => line.targetTypeId === selectedTypeId)
      .map((line) => line.sourceTypeId),
  )
  const left = nodes.filter(
    (node) => node.id !== selectedTypeId && incoming.has(node.id),
  )
  const right = nodes.filter(
    (node) => node.id !== selectedTypeId && !incoming.has(node.id),
  )
  const margin = 32
  const columnGap = 160
  const rowGap = 48
  const columnHeight = (count: number) =>
    count * TYPE_NODE_HEIGHT + Math.max(0, count - 1) * rowGap
  const loopHeight = selfRelationHeight(lines, selectedTypeId)
  const centerY =
    margin +
    Math.max(
      columnHeight(left.length) / 2,
      columnHeight(right.length) / 2,
      loopHeight + TYPE_NODE_HEIGHT / 2,
    )
  const points = new Map<number, Point>([
    [
      selectedTypeId,
      {
        x: margin + TYPE_NODE_WIDTH + columnGap,
        y: centerY - TYPE_NODE_HEIGHT / 2 - loopHeight,
      },
    ],
  ])
  for (const [column, x] of [
    [left, margin],
    [right, margin + 2 * (TYPE_NODE_WIDTH + columnGap)],
  ] as const) {
    const top = centerY - columnHeight(column.length) / 2
    column.forEach((node, index) => {
      points.set(node.id, { x, y: top + index * (TYPE_NODE_HEIGHT + rowGap) })
    })
  }
  return points
}
