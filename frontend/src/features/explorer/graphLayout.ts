import type { ObjectLink } from './types'

export type GraphNode = {
  link: ObjectLink
  x: number
  y: number
}

// 관계도에서 연결 상대 카드의 자리(%)를 정한다. 가운데(50, 50)는 선택한 객체 자리다.
// 첫 번째는 위쪽 가운데, 나머지는 왼쪽·오른쪽에 번갈아 세로로 고르게 놓는다.
export function placeRelatedNodes(links: ObjectLink[]): GraphNode[] {
  const sideTotal = links.length - 1
  const leftCount = Math.ceil(sideTotal / 2)
  const rightCount = Math.floor(sideTotal / 2)

  return links.map((link, index) => {
    if (index === 0) {
      return { link, x: 50, y: 12 }
    }
    const onLeft = index % 2 === 1
    const order = Math.floor((index - 1) / 2)
    const count = onLeft ? leftCount : rightCount
    const y = count === 1 ? 50 : 28 + (order * 60) / (count - 1)
    return { link, x: onLeft ? 14 : 86, y }
  })
}

// 화살촉을 붙일 선. 출발 쪽에서 선의 한가운데까지 그려서, 화살촉이 카드에 가려지지 않고
// 선 가운데에서 관계 방향(출발 → 도착)을 가리키게 한다.
export function arrowLine(node: GraphNode, objectId: number) {
  const outgoing = node.link.sourceObjectId === objectId
  const [fromX, fromY, toX, toY] = outgoing
    ? [50, 50, node.x, node.y]
    : [node.x, node.y, 50, 50]
  return {
    x1: fromX,
    y1: fromY,
    x2: (fromX + toX) / 2,
    y2: (fromY + toY) / 2,
  }
}

// 관계 이름을 놓을 자리. 선의 가운데에 두되, 글자 상자를 선과 직각 방향으로 밀어내
// 선에 걸치지 않게 한다. 가로 선은 위쪽, 세로 선은 오른쪽으로 비킨다.
export function labelStyle(node: GraphNode) {
  const dx = node.x - 50
  const dy = node.y - 50
  const length = Math.hypot(dx, dy)
  const side = dx > 0 ? -1 : 1
  const normalX = (side * -dy) / length
  const normalY = (side * dx) / length
  return {
    left: `${(50 + node.x) / 2}%`,
    top: `${(50 + node.y) / 2}%`,
    transform: `translate(calc(${(normalX - 1) * 50}% + ${normalX * 10}px), calc(${(normalY - 1) * 50}% + ${normalY * 10}px))`,
  }
}
