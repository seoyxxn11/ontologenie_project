import { expect, test } from 'vitest'
import { routeEdges, type Box } from './edgeRoute'

// 가, 나, 다 카드가 한 줄에 나란히 있다.
const boxes = new Map<string, Box>([
  ['가', { x: 32, y: 96, w: 120, h: 64 }],
  ['나', { x: 232, y: 96, w: 120, h: 64 }],
  ['다', { x: 432, y: 96, w: 120, h: 64 }],
])
const canvas = { width: 616, height: 256 }

test('가에서 다로 가는 선은 사이에 있는 나 카드를 피해서 지나간다', () => {
  const route = routeEdges(
    [{ key: '가-다', from: '가', to: '다' }],
    boxes,
    canvas,
  ).get('가-다')!
  const middle = boxes.get('나')!

  expect(route.fallback).toBe(false)
  for (let i = 0; i < route.points.length - 1; i++) {
    const a = route.points[i]
    const b = route.points[i + 1]
    const outsideX =
      Math.max(a.x, b.x) <= middle.x ||
      Math.min(a.x, b.x) >= middle.x + middle.w
    const outsideY =
      Math.max(a.y, b.y) <= middle.y ||
      Math.min(a.y, b.y) >= middle.y + middle.h
    expect(outsideX || outsideY).toBe(true)
  }
})

test('이웃한 카드는 꺾지 않고 곧게 잇는다', () => {
  const route = routeEdges(
    [{ key: '가-나', from: '가', to: '나' }],
    boxes,
    canvas,
  ).get('가-나')!

  expect(route.fallback).toBe(false)
  expect(route.points).toHaveLength(2)
  expect(route.points[0].y).toBe(route.points[1].y)
})

test('자리를 모르는 카드로 가는 선은 결과에서 빠진다', () => {
  const routes = routeEdges(
    [{ key: '가-라', from: '가', to: '라' }],
    boxes,
    canvas,
  )

  expect(routes.size).toBe(0)
})
