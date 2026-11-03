import { expect, test } from 'vitest'
import { arrowLine, labelStyle, placeRelatedNodes } from './graphLayout'
import type { ObjectLink } from './types'

function link(id: number, sourceObjectId: number, targetObjectId: number) {
  return { id, sourceObjectId, targetObjectId } as ObjectLink
}

test('연결이 없으면 카드 자리도 없다', () => {
  expect(placeRelatedNodes([])).toEqual([])
})

test('첫 카드는 위쪽 가운데, 나머지는 왼쪽·오른쪽에 번갈아 놓는다', () => {
  const nodes = placeRelatedNodes([link(1, 1, 2), link(2, 1, 3), link(3, 1, 4)])

  expect(nodes.map(({ x, y }) => [x, y])).toEqual([
    [50, 12],
    [14, 50],
    [86, 50],
  ])
})

test('화살촉 선은 출발 쪽에서 선 가운데까지 그린다', () => {
  const outgoing = { link: link(1, 1, 2), x: 88, y: 50 }
  const incoming = { link: link(2, 2, 1), x: 88, y: 50 }

  expect(arrowLine(outgoing, 1)).toEqual({ x1: 50, y1: 50, x2: 69, y2: 50 })
  expect(arrowLine(incoming, 1)).toEqual({ x1: 88, y1: 50, x2: 69, y2: 50 })
})

test('관계 이름은 선 가운데에서 선과 직각 방향으로 비켜 놓는다', () => {
  const right = { link: link(1, 1, 2), x: 88, y: 50 }
  const top = { link: link(2, 1, 3), x: 50, y: 12 }

  expect(labelStyle(right)).toEqual({
    left: '69%',
    top: '50%',
    transform: 'translate(calc(-50% + 0px), calc(-100% + -10px))',
  })
  expect(labelStyle(top)).toEqual({
    left: '50%',
    top: '31%',
    transform: 'translate(calc(0% + 10px), calc(-50% + 0px))',
  })
})
