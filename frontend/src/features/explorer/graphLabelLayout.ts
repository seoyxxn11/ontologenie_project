export type LabelBox = { x: number; y: number; width: number; height: number }
type RelationLabel = {
  key: string
  width: number
  height: number
  node: LabelBox
}

function intersects(a: LabelBox, b: LabelBox, gap: number) {
  return (
    a.x < b.x + b.width + gap &&
    a.x + a.width + gap > b.x &&
    a.y < b.y + b.height + gap &&
    a.y + a.height + gap > b.y
  )
}

// 실측한 글자 크기로 노드·화살촉을 피하고, 이름은 자신의 관계선 가까이에 둔다.
export function placeRelationLabels(
  width: number,
  height: number,
  anchor: LabelBox,
  labels: RelationLabel[],
) {
  const blockers = [
    anchor,
    ...labels.map((label) => label.node),
    ...labels.map((label) => ({
      x:
        (label.node.x + label.node.width / 2 + anchor.x + anchor.width / 2) /
          2 -
        6,
      y:
        (label.node.y + label.node.height / 2 + anchor.y + anchor.height / 2) /
          2 -
        6,
      width: 12,
      height: 12,
    })),
  ]
  const choices = labels.map((label) => {
    const start = {
      x: label.node.x + label.node.width / 2,
      y: label.node.y + label.node.height / 2,
    }
    const end = {
      x: anchor.x + anchor.width / 2,
      y: anchor.y + anchor.height / 2,
    }
    const dx = end.x - start.x,
      dy = end.y - start.y,
      length = Math.hypot(dx, dy) || 1
    // 관계가 하나인 낮은 캔버스에서는 두 카드 옆에 이름을 놓을 공간도 허용한다.
    const maxDistance =
      labels.length === 1
        ? Math.max(anchor.width, label.node.width) / 2 + label.width / 2 + 4
        : 60
    const candidates: (LabelBox & { cost: number })[] = []
    const seen = new Set<string>()
    const add = (cx: number, cy: number) => {
      const x = Math.max(
        8,
        Math.min(width - label.width - 8, cx - label.width / 2),
      )
      const y = Math.max(
        8,
        Math.min(height - label.height - 8, cy - label.height / 2),
      )
      const box = { x, y, width: label.width, height: label.height }
      const key = x.toFixed(1) + ':' + y.toFixed(1)
      if (
        seen.has(key) ||
        blockers.some((blocker) => intersects(box, blocker, 1))
      )
        return
      const mx = x + label.width / 2 - start.x,
        my = y + label.height / 2 - start.y
      const along = (mx * dx + my * dy) / (length * length)
      const distance = Math.abs(mx * dy - my * dx) / length
      if (distance > maxDistance || along < 0.15 || along > 0.85) return
      seen.add(key)
      candidates.push({
        ...box,
        cost: distance + Math.abs(along - 0.5) * length * 0.7,
      })
    }
    for (const fraction of [
      0.5, 0.45, 0.55, 0.4, 0.6, 0.35, 0.65, 0.3, 0.7, 0.25, 0.75, 0.2, 0.8,
    ]) {
      for (const offset of [
        0,
        12,
        -12,
        18,
        -18,
        24,
        -24,
        30,
        -30,
        36,
        -36,
        44,
        -44,
        60,
        -60,
        maxDistance,
        -maxDistance,
      ]) {
        add(
          start.x + dx * fraction - (dy / length) * offset,
          start.y + dy * fraction + (dx / length) * offset,
        )
      }
    }
    const supplement = () => {
      const left = Math.max(
        8,
        Math.min(start.x, end.x) - label.width - maxDistance,
      )
      const top = Math.max(
        8,
        Math.min(start.y, end.y) - label.height - maxDistance,
      )
      const right = Math.min(
        width - 8,
        Math.max(start.x, end.x) + label.width + maxDistance,
      )
      const bottom = Math.min(
        height - 8,
        Math.max(start.y, end.y) + label.height + maxDistance,
      )
      for (let y = top; y + label.height <= bottom; y += 4) {
        for (let x = left; x + label.width <= right; x += 4)
          add(x + label.width / 2, y + label.height / 2)
      }
    }
    return { key: label.key, candidates, supplement }
  })
  const placed = new Map<string, LabelBox>()
  const arrange = () => {
    placed.clear()
    const ordered = [...choices].sort(
      (a, b) => a.candidates.length - b.candidates.length,
    )
    for (const choice of ordered)
      choice.candidates.sort((a, b) => a.cost - b.cost)
    let attempts = 0
    const visit = (index: number): boolean => {
      if (index === ordered.length) return true
      const choice = ordered[index]
      for (const candidate of choice.candidates) {
        if (++attempts > 20000) return false
        if (
          [...placed.values()].some((other) => intersects(candidate, other, 4))
        )
          continue
        placed.set(choice.key, candidate)
        if (visit(index + 1)) return true
        placed.delete(choice.key)
      }
      return false
    }
    return visit(0)
  }
  if (!arrange()) {
    for (const choice of choices) choice.supplement()
    arrange()
  }
  return placed
}
