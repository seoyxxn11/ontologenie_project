import { expect, test } from 'vitest'
import { placeRelationLabels, type LabelBox } from './graphLabelLayout'

const overlap = (a: LabelBox, b: LabelBox) =>
  a.x < b.x + b.width &&
  a.x + a.width > b.x &&
  a.y < b.y + b.height &&
  a.y + a.height > b.y

test.each([
  [1210, 142, 176],
  [730, 142, 176],
  [570, 142, 176],
  [560, 120, 148],
])(
  '%ipx 캔버스에서 관계명이 노드·서로·화살촉을 가리지 않고 해당 선 가까이 놓인다',
  (width, nodeWidth, anchorWidth) => {
    const height = 358
    const anchor = {
      x: width / 2 - anchorWidth / 2,
      y: height / 2 - 44,
      width: anchorWidth,
      height: 88,
    }
    const positions = [
      [50, 13],
      [81, 24],
      [81, 77],
      [50, 85],
      [19, 77],
      [19, 24],
    ]
    const widths = [53, 100, 97, 77, 87, 77]
    const labels = positions.map(([x, y], index) => {
      const nodeHeight = nodeWidth === 120 && index === 2 ? 82 : 64
      return {
        key: String(index),
        width: widths[index],
        height: 18,
        node: {
          x: (x / 100) * width - nodeWidth / 2,
          y: (y / 100) * height - nodeHeight / 2,
          width: nodeWidth,
          height: nodeHeight,
        },
      }
    })
    const result = placeRelationLabels(width, height, anchor, labels)
    expect(result.size).toBe(labels.length)
    for (const label of labels) {
      const box = result.get(label.key)!
      expect(box.x).toBeGreaterThanOrEqual(8)
      expect(box.y).toBeGreaterThanOrEqual(8)
      expect(box.x + box.width).toBeLessThanOrEqual(width - 8)
      expect(box.y + box.height).toBeLessThanOrEqual(height - 8)
      for (const blocker of [anchor, ...labels.map((item) => item.node)])
        expect(overlap(box, blocker)).toBe(false)
      for (const [key, other] of result)
        if (key !== label.key) expect(overlap(box, other)).toBe(false)
      const sx = label.node.x + label.node.width / 2,
        sy = label.node.y + label.node.height / 2
      const dx = width / 2 - sx,
        dy = height / 2 - sy
      const distance =
        Math.abs(
          (box.x + box.width / 2 - sx) * dy -
            (box.y + box.height / 2 - sy) * dx,
        ) / Math.hypot(dx, dy)
      expect(distance).toBeLessThanOrEqual(60)
      for (const item of labels) {
        const arrow = {
          x: (item.node.x + item.node.width / 2 + width / 2) / 2 - 6,
          y: (item.node.y + item.node.height / 2 + height / 2) / 2 - 6,
          width: 12,
          height: 12,
        }
        expect(overlap(box, arrow)).toBe(false)
      }
    }
  },
)

test('연결이 없으면 관계명도 배치하지 않는다', () => {
  expect(
    placeRelationLabels(
      560,
      358,
      { x: 200, y: 140, width: 148, height: 88 },
      [],
    ).size,
  ).toBe(0)
})

test('관계가 하나인 낮은 캔버스에서도 관계명이 두 카드를 가리지 않는다', () => {
  const anchor = { x: 192, y: 76, width: 176, height: 88 }
  const node = { x: 209, y: 1.6, width: 142, height: 64 }
  const result = placeRelationLabels(560, 240, anchor, [
    { key: 'single', width: 100, height: 18, node },
  ])
  expect(result.size).toBe(1)
  const label = result.get('single')!
  expect(overlap(label, anchor)).toBe(false)
  expect(overlap(label, node)).toBe(false)
  expect(label.x).toBeGreaterThanOrEqual(8)
  expect(label.x + label.width).toBeLessThanOrEqual(552)
})
