import { expect, test } from 'vitest'
import {
  groupRelationLines,
  layoutTypeGraph,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
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

test('같은 출발·도착 유형 사이의 관계는 선 하나로 묶고, 자기 자신으로 가는 관계는 뺀다', () => {
  const lines = groupRelationLines([
    edge(1, 1, 2),
    edge(2, 1, 2),
    edge(3, 2, 1),
    edge(4, 3, 3),
  ])

  expect(lines.map((line) => line.key)).toEqual(['1-2', '2-1'])
  expect(lines[0].edges.map((item) => item.id)).toEqual([1, 2])
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
