import { expect, test } from 'vitest'
import { focusTypeGraph, layoutFocusedTypeGraph } from './modelGraphFocus'
import {
  groupRelationLines,
  selfRelationHeight,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
} from './modelGraphLayout'
import type { ModelEdge, ModelGraph } from './types'

function edge(
  id: number,
  sourceTypeId: number,
  targetTypeId: number,
): ModelEdge {
  return {
    id,
    sourceTypeId,
    targetTypeId,
    code: `R${id}`,
    name: `관계 ${id}`,
    cardinality: 'MANY_TO_ONE',
    relationCount: 0,
    activeLinkCount: 0,
  }
}

const graph: ModelGraph = {
  nodes: [1, 2, 3, 4, 5, 6].map((id) => ({
    id,
    code: `T${id}`,
    name: `${id}`,
  })),
  edges: [
    edge(1, 2, 1),
    edge(2, 1, 3),
    edge(3, 1, 2),
    edge(4, 1, 1),
    edge(5, 2, 3),
    edge(6, 3, 4),
    edge(7, 1, 3),
  ],
}

test('직접 연결된 관계만 남기되 양방향·자기 관계·중복 관계와 연결 0건도 보존한다', () => {
  const focused = focusTypeGraph(graph, 1)
  expect(focused.nodes.map((node) => node.id)).toEqual([1, 2, 3])
  expect(focused.edges.map((relation) => relation.id)).toEqual([1, 2, 3, 4, 7])
  expect(graph.nodes).toHaveLength(6)
  expect(graph.edges).toHaveLength(7)
})

test('연결 없는 유형은 카드 하나를 남기고 없는 유형을 선택하면 전체 구조를 유지한다', () => {
  expect(focusTypeGraph(graph, 6)).toEqual({
    nodes: [graph.nodes[5]],
    edges: [],
  })
  expect(focusTypeGraph(graph, 999)).toBe(graph)
})

test('들어오는 유형은 왼쪽, 나가는 유형은 오른쪽에 놓고 양방향 유형은 중복하지 않는다', () => {
  const focused = focusTypeGraph(graph, 1)
  const lines = groupRelationLines(focused.edges)
  const points = layoutFocusedTypeGraph(focused.nodes, lines, 1)
  expect(points.size).toBe(3)
  expect(points.get(2)!.x + TYPE_NODE_WIDTH).toBeLessThan(points.get(1)!.x)
  expect(points.get(3)!.x).toBeGreaterThan(points.get(1)!.x + TYPE_NODE_WIDTH)
  const cardCenter = (id: number) =>
    points.get(id)!.y + selfRelationHeight(lines, id) + TYPE_NODE_HEIGHT / 2
  expect(cardCenter(2)).toBe(cardCenter(1))
  expect(cardCenter(3)).toBe(cardCenter(1))
  expect(points.get(1)!.y).toBeGreaterThanOrEqual(0)
})

test('한쪽에 유형이 몰려도 카드가 겹치지 않고 선택 카드가 열의 세로 중심에 온다', () => {
  const focused = focusTypeGraph(
    { ...graph, edges: [edge(1, 2, 1), edge(2, 3, 1), edge(3, 4, 1)] },
    1,
  )
  const points = layoutFocusedTypeGraph(
    focused.nodes,
    groupRelationLines(focused.edges),
    1,
  )
  expect(points.get(2)!.y + TYPE_NODE_HEIGHT).toBeLessThan(points.get(3)!.y)
  expect(points.get(3)!.y + TYPE_NODE_HEIGHT).toBeLessThan(points.get(4)!.y)
  expect(points.get(1)!.y).toBe(points.get(3)!.y)
})
