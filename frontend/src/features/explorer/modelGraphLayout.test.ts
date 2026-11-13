import { expect, test } from 'vitest'
import {
  groupRelationLines,
  layoutTypeGraph,
  routeRelationLines,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
  selfRelationHeight,
} from './modelGraphLayout'
import type { ModelEdge, ModelNode } from './types'

const nodes: ModelNode[] = [
  { id: 1, code: 'A', name: '가' },
  { id: 2, code: 'B', name: '나' },
  { id: 3, code: 'C', name: '다' },
  { id: 4, code: 'D', name: '라' },
  { id: 5, code: 'E', name: '마' },
]

function edge(id: number, sourceTypeId: number, targetTypeId: number) {
  const value: ModelEdge = {
    id,
    code: `E${id}`,
    name: `관계 ${id}`,
    sourceTypeId,
    targetTypeId,
    cardinality: 'MANY_TO_ONE',
    relationCount: 1,
    activeLinkCount: 1,
  }
  return value
}

test('같은 출발·도착 유형 사이의 관계를 묶고 자기 유형 관계도 보존한다', () => {
  const lines = groupRelationLines([
    edge(1, 1, 2),
    edge(2, 1, 2),
    edge(3, 2, 1),
    edge(4, 3, 3),
  ])

  expect(lines.map((line) => line.key)).toEqual(['1-2', '2-1', '3-3'])
  expect(lines[0].edges.map((item) => item.id)).toEqual([1, 2])
  expect(lines[2].edges.map((item) => item.id)).toEqual([4])
})

test('자기 유형 관계 이름이 많아도 카드 위 공간을 확보하고 연결 없는 유형과 겹치지 않는다', () => {
  const lines = groupRelationLines(
    Array.from({ length: 8 }, (_, id) => edge(id, 1, 1)),
  )
  const points = layoutTypeGraph(nodes, lines)
  const height = TYPE_NODE_HEIGHT + selfRelationHeight(lines, 1)
  expect(selfRelationHeight(lines, 1)).toBeGreaterThan(8 * 16)
  expect(selfRelationHeight(lines, 2)).toBe(0)
  for (const node of nodes.slice(1)) {
    const first = points.get(1)!
    const other = points.get(node.id)!
    const overlapX = Math.abs(first.x - other.x) < TYPE_NODE_WIDTH
    const overlapY =
      first.y < other.y + TYPE_NODE_HEIGHT && other.y < first.y + height
    expect(overlapX && overlapY).toBe(false)
  }
})

test('따로 떨어진 묶음과 관계 없는 유형도 카드끼리 겹치지 않게 놓는다', () => {
  // 1→2, 3→4 두 묶음과 관계가 없는 5
  const lines = groupRelationLines([edge(1, 1, 2), edge(2, 3, 4)])
  const points = layoutTypeGraph(nodes, lines)

  const boxes = nodes.map((node) => points.get(node.id)!)
  for (let i = 0; i < boxes.length; i++) {
    for (let j = i + 1; j < boxes.length; j++) {
      const overlapX = Math.abs(boxes[i].x - boxes[j].x) < TYPE_NODE_WIDTH
      const overlapY = Math.abs(boxes[i].y - boxes[j].y) < TYPE_NODE_HEIGHT
      expect(overlapX && overlapY).toBe(false)
    }
  }
  // 관계 방향대로 출발 유형이 도착 유형보다 왼쪽에 온다.
  expect(points.get(1)!.x).toBeLessThan(points.get(2)!.x)
  expect(points.get(3)!.x).toBeLessThan(points.get(4)!.x)
})

test('관계 선 경로는 다른 유형 카드를 지나가지 않고 자기 유형 관계는 계산에서 뺀다', () => {
  const lines = groupRelationLines([
    edge(1, 1, 2),
    edge(2, 2, 3),
    edge(3, 1, 3),
    edge(4, 3, 4),
    edge(5, 1, 4),
    edge(6, 5, 3),
    edge(7, 3, 3),
  ])
  const points = layoutTypeGraph(nodes, lines)
  const routes = routeRelationLines(nodes, lines, points)

  expect(routes.has('3-3')).toBe(false)
  expect(routes.size).toBe(lines.length - 1)

  for (const line of lines) {
    const route = routes.get(line.key)
    if (!route) continue
    expect(route.fallback).toBe(false)
    for (const node of nodes) {
      if (node.id === line.sourceTypeId || node.id === line.targetTypeId) {
        continue
      }
      const point = points.get(node.id)!
      const top = point.y + selfRelationHeight(lines, node.id)
      for (let i = 0; i < route.points.length - 1; i++) {
        const a = route.points[i]
        const b = route.points[i + 1]
        const outsideX =
          Math.max(a.x, b.x) <= point.x ||
          Math.min(a.x, b.x) >= point.x + TYPE_NODE_WIDTH
        const outsideY =
          Math.max(a.y, b.y) <= top ||
          Math.min(a.y, b.y) >= top + TYPE_NODE_HEIGHT
        expect(outsideX || outsideY).toBe(true)
      }
    }
  }
})
