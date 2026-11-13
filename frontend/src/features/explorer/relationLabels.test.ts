import { expect, test } from 'vitest'
import {
  groupRelationLines,
  layoutTypeGraph,
  routeRelationLines,
  selfRelationHeight,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
} from './modelGraphLayout'
import {
  estimateLabelSize,
  LABEL_MAX_WIDTH,
  placeRelationLabels,
  relationLabelText,
} from './relationLabels'
import type { ModelEdge, ModelNode } from './types'

const names = [
  '설비',
  '설비 센서 측정',
  '작업지시 공정 배정',
  '불량 기록',
  '설비 비가동 기록',
  '정비 지시',
  '공정 실적',
  '창고별 품목 재고',
  '구매 발주 상세',
  '입고 실적',
]
const nodes: ModelNode[] = names.map((name, index) => ({
  id: index + 1,
  code: `T${index + 1}`,
  name,
}))

function edge(id: number, sourceTypeId: number, targetTypeId: number) {
  const value: ModelEdge = {
    id,
    code: `E${id}`,
    name: `${names[sourceTypeId - 1]} → ${names[targetTypeId - 1]}`,
    sourceTypeId,
    targetTypeId,
    cardinality: 'MANY_TO_ONE',
    relationCount: 1,
    activeLinkCount: 615,
  }
  return value
}

type Box = { left: number; top: number; right: number; bottom: number }

function overlaps(a: Box, b: Box) {
  return (
    a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom
  )
}

test('긴 이름표는 열 사이 간격에 들어가도록 줄을 바꿔 크기를 잰다', () => {
  const short = estimateLabelSize(['부서 · 1건'])
  const long = estimateLabelSize(['설비 센서 측정 → 설비 · 615건'])
  const two = estimateLabelSize(['부서 · 1건', '부서 · 1건'])

  expect(long.width).toBeGreaterThan(short.width)
  expect(long.width).toBeLessThanOrEqual(LABEL_MAX_WIDTH)
  expect(long.height).toBeGreaterThan(short.height)
  expect(two.height).toBe(short.height * 2)
  // 줄이 바뀐 이름표는 브라우저가 상자를 최대 폭까지 채우므로 최대 폭으로 잰다.
  expect(long.width).toBe(LABEL_MAX_WIDTH)
  // 열 사이 간격(120px)에서 화살표 자리를 빼도 들어가야 한다.
  expect(LABEL_MAX_WIDTH).toBeLessThan(120 - 14)
})

test('고른 유형의 이름표는 카드와 다른 이름표를 가리지 않는 자리에 둔다', () => {
  // 한 유형(설비)으로 여러 유형이 들어오고, 사이에 다른 유형이 끼어 있는 구조.
  const lines = groupRelationLines([
    edge(1, 2, 1),
    edge(2, 3, 1),
    edge(3, 4, 1),
    edge(4, 5, 1),
    edge(5, 6, 1),
    edge(6, 7, 1),
    edge(7, 8, 9),
    edge(8, 10, 8),
    edge(9, 4, 10),
  ])
  const points = layoutTypeGraph(nodes, lines)
  const routes = routeRelationLines(nodes, lines, points)

  const placed = placeRelationLabels(nodes, lines, points, routes, 1)

  const related = lines.filter(
    (line) => line.sourceTypeId === 1 || line.targetTypeId === 1,
  )
  expect([...placed.keys()].sort()).toEqual(
    related.map((line) => line.key).sort(),
  )

  const cards = nodes.map((node) => {
    const point = points.get(node.id)!
    const top = point.y + selfRelationHeight(lines, node.id)
    return {
      left: point.x,
      top,
      right: point.x + TYPE_NODE_WIDTH,
      bottom: top + TYPE_NODE_HEIGHT,
    }
  })
  const labels = related.map((line) => {
    const center = placed.get(line.key)!
    const size = estimateLabelSize(line.edges.map(relationLabelText))
    return {
      left: center.x - size.width / 2,
      top: center.y - size.height / 2,
      right: center.x + size.width / 2,
      bottom: center.y + size.height / 2,
    }
  })

  for (const label of labels) {
    for (const card of cards) expect(overlaps(label, card)).toBe(false)
  }
  for (let i = 0; i < labels.length; i++) {
    for (let j = i + 1; j < labels.length; j++) {
      expect(overlaps(labels[i], labels[j])).toBe(false)
    }
  }
})

test('이름표는 자기 유형 관계 고리 자리와 위·아래로 들어오는 화살표를 가리지 않는다', () => {
  // 설비(1)는 자기 유형 관계가 있어 카드 위에 고리 자리가 있고,
  // 바로 아래 유형(3)으로 가는 선은 카드 위쪽 면으로 들어간다.
  const three = nodes.slice(0, 3)
  const lines = groupRelationLines([
    edge(1, 1, 1),
    edge(2, 2, 1),
    edge(3, 1, 3),
  ])
  const points = new Map([
    [1, { x: 300, y: 100 }],
    [2, { x: 40, y: 120 }],
    [3, { x: 300, y: 300 }],
  ])
  const routes = routeRelationLines(three, lines, points)

  const placed = placeRelationLabels(three, lines, points, routes, 1)

  const loopTop = points.get(1)!.y
  const loop = {
    left: points.get(1)!.x,
    top: loopTop,
    right: points.get(1)!.x + TYPE_NODE_WIDTH,
    bottom: loopTop + selfRelationHeight(lines, 1),
  }
  // 화살표 머리: 선 끝에서 마지막 구간을 따라 12px, 선 양옆 5px
  const arrows = [...routes.values()]
    .filter((route) => !route.fallback)
    .map((route) => {
      const end = route.points.at(-1)!
      const before = route.points.at(-2)!
      const dx = Math.sign(end.x - before.x)
      const dy = Math.sign(end.y - before.y)
      return {
        left: Math.min(end.x, end.x - dx * 12) - (dx === 0 ? 5 : 0),
        right: Math.max(end.x, end.x - dx * 12) + (dx === 0 ? 5 : 0),
        top: Math.min(end.y, end.y - dy * 12) - (dy === 0 ? 5 : 0),
        bottom: Math.max(end.y, end.y - dy * 12) + (dy === 0 ? 5 : 0),
      }
    })
  expect(
    [...routes.values()].some(
      (route) => !route.fallback && route.points.at(-1)!.y === 300,
    ),
  ).toBe(true)

  for (const line of lines) {
    const center = placed.get(line.key)
    if (!center) continue
    const size = estimateLabelSize(line.edges.map(relationLabelText))
    const label = {
      left: center.x - size.width / 2,
      top: center.y - size.height / 2,
      right: center.x + size.width / 2,
      bottom: center.y + size.height / 2,
    }
    expect(overlaps(label, loop)).toBe(false)
    for (const arrow of arrows) expect(overlaps(label, arrow)).toBe(false)
  }
})

test('유형을 고르지 않으면 이름표 자리를 계산하지 않는다', () => {
  const lines = groupRelationLines([edge(1, 2, 1)])
  const points = layoutTypeGraph(nodes, lines)
  const routes = routeRelationLines(nodes, lines, points)

  expect(placeRelationLabels(nodes, lines, points, routes, null).size).toBe(0)
})
