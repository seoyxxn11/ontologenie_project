import type { Route } from './edgeRoute'
import { formatCount } from './format'
import {
  selfRelationHeight,
  TYPE_NODE_HEIGHT,
  TYPE_NODE_WIDTH,
  type Point,
  type RelationLine,
} from './modelGraphLayout'
import type { ModelEdge, ModelNode } from './types'

// "· 건수"는 줄이 바뀌어도 앞 단어와 같은 줄에 남도록 줄바꿈 없는 띄어쓰기(NBSP)로 붙인다.
export function relationLabelText(edge: ModelEdge) {
  return `${edge.name}\u00a0·\u00a0${formatCount(edge.activeLinkCount)}건`
}

// 이름표 글자 크기(text-xs, 줄 높이 leading-tight)와 좌우 여백(px-1). 그림 좌표 기준이다.
const LABEL_LINE_HEIGHT = 15
const LABEL_PADDING_X = 8
// 글자 폭은 실제 글꼴(Pretendard 12px)에서 잰 값보다 조금 크게 잡는다. 한글 10.4px·화살표 11px, 숫자 5.3px·띄어쓰기 3px.
const WIDE_CHAR_WIDTH = 11
const NARROW_CHAR_WIDTH = 6
// 이름표 최대 폭. 열 사이 간격(120px)에서 화살표 자리와 양옆 틈을 빼고 남는 폭에 맞춘다.
export const LABEL_MAX_WIDTH = 96
const LABEL_MAX_TEXT_WIDTH = LABEL_MAX_WIDTH - LABEL_PADDING_X

// 한글(자모·완성형)과 화살표는 넓은 글자로 센다.
function isWideChar(char: string) {
  const code = char.codePointAt(0) ?? 0
  return (
    (code >= 0x1100 && code <= 0x11ff) ||
    (code >= 0x3130 && code <= 0x318f) ||
    (code >= 0xac00 && code <= 0xd7a3) ||
    (code >= 0x2190 && code <= 0x21ff)
  )
}

function textWidth(text: string) {
  return [...text].reduce(
    (sum, char) =>
      sum + (isWideChar(char) ? WIDE_CHAR_WIDTH : NARROW_CHAR_WIDTH),
    0,
  )
}

// 한 줄을 띄어쓰기 단위로 최대 폭에 맞춰 나눈다(word-break: keep-all과 같은 방식).
// 한 단어가 최대 폭보다 길면 그 단어만 글자 단위로 나뉜다(overflow-wrap: anywhere).
function wrapLines(text: string) {
  const lines: number[] = []
  let current = 0
  for (const word of text.split(' ')) {
    const width = textWidth(word)
    const withSpace =
      current === 0 ? width : current + NARROW_CHAR_WIDTH + width
    if (withSpace <= LABEL_MAX_TEXT_WIDTH) {
      current = withSpace
      continue
    }
    if (current > 0) lines.push(current)
    let rest = width
    while (rest > LABEL_MAX_TEXT_WIDTH) {
      lines.push(LABEL_MAX_TEXT_WIDTH)
      rest -= LABEL_MAX_TEXT_WIDTH
    }
    current = rest
  }
  lines.push(current)
  return lines
}

// 화면에서 재기 전에 자리를 정해야 하므로 글자 폭으로 크기를 어림한다. 한글·화살표는 넓게, 나머지는 좁게 센다.
export function estimateLabelSize(texts: string[]) {
  const lines = texts.flatMap(wrapLines)
  // 한 줄이라도 바뀌면 브라우저는 상자를 최대 폭까지 채운다.
  const wrapped = lines.length > texts.length
  return {
    width: wrapped ? LABEL_MAX_WIDTH : Math.max(0, ...lines) + LABEL_PADDING_X,
    height: lines.length * LABEL_LINE_HEIGHT,
  }
}

type Box = { left: number; top: number; right: number; bottom: number }
type Candidate = Point & { vertical: boolean; fromMiddle: number }

// 이름표와 카드 사이에 둘 틈
const LABEL_GAP = 4
// 화살표 머리 자리: 선 끝에서 선을 따라 14px, 선 양옆으로 6px
const ARROW_ROOM = 14
const ARROW_HALF_WIDTH = 6
// 선을 따라 이름표 후보 자리를 고르는 간격(px)
const SAMPLE_STEP = 6
// 겹침 면적 1px²마다 더하는 벌점. 세로 구간이나 선 가운데에서 먼 자리보다 훨씬 무겁게 본다.
const OVERLAP_PENALTY = 100
const VERTICAL_PENALTY = 200
const OFFSET_PENALTY = 100
const MIDDLE_PENALTY = 0.05

function overlapArea(a: Box, b: Box) {
  const width = Math.min(a.right, b.right) - Math.max(a.left, b.left)
  const height = Math.min(a.bottom, b.bottom) - Math.max(a.top, b.top)
  return width > 0 && height > 0 ? width * height : 0
}

// 꺾인 선을 따라 일정 간격으로 후보 자리를 만든다.
function polylineCandidates(points: Point[]): Candidate[] {
  const lengths = points
    .slice(1)
    .map((point, i) => Math.hypot(point.x - points[i].x, point.y - points[i].y))
  const total = lengths.reduce((sum, length) => sum + length, 0)
  const candidates: Candidate[] = []
  let walked = 0
  for (let i = 0; i < lengths.length; i++) {
    const a = points[i]
    const b = points[i + 1]
    const steps = Math.max(1, Math.floor(lengths[i] / SAMPLE_STEP))
    for (let step = 1; step < steps; step++) {
      const t = step / steps
      candidates.push({
        x: a.x + (b.x - a.x) * t,
        y: a.y + (b.y - a.y) * t,
        vertical: a.x === b.x,
        fromMiddle: Math.abs(walked + lengths[i] * t - total / 2),
      })
    }
    walked += lengths[i]
  }
  return candidates
}

// 경로를 찾지 못한 선은 React Flow 기본 곡선(getBezierPath)과 같은 곡선 위에서 후보를 만든다.
function bezierCandidates(source: Point, target: Point): Candidate[] {
  const distance = target.x - source.x
  const offset =
    distance >= 0 ? 0.5 * distance : 0.25 * 25 * Math.sqrt(-distance)
  const c1 = { x: source.x + offset, y: source.y }
  const c2 = { x: target.x - offset, y: target.y }
  const candidates: Candidate[] = []
  for (let t = 0.05; t < 0.96; t += 0.025) {
    const u = 1 - t
    candidates.push({
      x:
        u ** 3 * source.x +
        3 * u ** 2 * t * c1.x +
        3 * u * t ** 2 * c2.x +
        t ** 3 * target.x,
      y:
        u ** 3 * source.y +
        3 * u ** 2 * t * c1.y +
        3 * u * t ** 2 * c2.y +
        t ** 3 * target.y,
      vertical: false,
      fromMiddle: Math.abs(t - 0.5) * 100,
    })
  }
  return candidates
}

// 고른 유형에 붙은 선의 이름표 자리(가운데 점)를 정한다.
// 선을 따라 후보를 두고, 카드·화살표·먼저 놓인 이름표와 겹치지 않는 자리 중 가로 구간과 선 가운데에 가까운 곳을 고른다.
// 자기 유형 관계는 카드 위 고리에 따로 적으므로 계산하지 않는다.
export function placeRelationLabels(
  nodes: ModelNode[],
  lines: RelationLine[],
  points: Map<number, Point>,
  routes: Map<string, Route>,
  selectedTypeId: number | null,
) {
  const placed = new Map<string, Point>()
  if (selectedTypeId === null) return placed

  const cardTop = (id: number, point: Point) =>
    point.y + selfRelationHeight(lines, id)
  // 카드는 위의 자기 유형 관계 고리 자리(고리 이름표가 적히는 곳)까지 함께 피한다.
  // 틈(LABEL_GAP)은 이름표 상자 쪽에만 두어 열 사이 좁은 통로에도 이름표가 들어가게 한다.
  const cards: Box[] = nodes.flatMap((node) => {
    const point = points.get(node.id)
    if (!point) return []
    return [
      {
        left: point.x,
        top: point.y,
        right: point.x + TYPE_NODE_WIDTH,
        bottom: cardTop(node.id, point) + TYPE_NODE_HEIGHT,
      },
    ]
  })
  // 화살표 머리는 선이 카드에 닿는 끝에 있다. 선은 카드의 어느 면으로든 들어올 수 있으므로
  // 카드 한쪽이 아니라 선마다 마지막 구간 끝을 비운다. 경로를 못 찾은 선은 카드 왼쪽으로 들어온다.
  const arrows: Box[] = lines.flatMap((line) => {
    if (line.sourceTypeId === line.targetTypeId) return []
    const route = routes.get(line.key)
    let end: Point
    let dx = 1
    let dy = 0
    if (route && !route.fallback && route.points.length >= 2) {
      end = route.points[route.points.length - 1]
      const before = route.points[route.points.length - 2]
      dx = Math.sign(end.x - before.x)
      dy = Math.sign(end.y - before.y)
    } else {
      const target = points.get(line.targetTypeId)
      if (!target) return []
      end = {
        x: target.x,
        y: cardTop(line.targetTypeId, target) + TYPE_NODE_HEIGHT / 2,
      }
    }
    const tail = { x: end.x - dx * ARROW_ROOM, y: end.y - dy * ARROW_ROOM }
    return [
      {
        left: Math.min(end.x, tail.x) - (dx === 0 ? ARROW_HALF_WIDTH : 0),
        top: Math.min(end.y, tail.y) - (dy === 0 ? ARROW_HALF_WIDTH : 0),
        right: Math.max(end.x, tail.x) + (dx === 0 ? ARROW_HALF_WIDTH : 0),
        bottom: Math.max(end.y, tail.y) + (dy === 0 ? ARROW_HALF_WIDTH : 0),
      },
    ]
  })

  const related = lines
    .filter(
      (line) =>
        line.sourceTypeId !== line.targetTypeId &&
        (line.sourceTypeId === selectedTypeId ||
          line.targetTypeId === selectedTypeId),
    )
    .map((line) => ({
      line,
      size: estimateLabelSize(line.edges.map(relationLabelText)),
    }))
    // 큰 이름표부터 자리를 잡아야 남은 틈에 작은 이름표가 들어간다.
    .sort((a, b) => b.size.width * b.size.height - a.size.width * a.size.height)

  const labels: Box[] = []
  for (const { line, size } of related) {
    const source = points.get(line.sourceTypeId)
    const target = points.get(line.targetTypeId)
    if (!source || !target) continue
    const route = routes.get(line.key)
    const candidates =
      route && !route.fallback
        ? polylineCandidates(route.points)
        : bezierCandidates(
            {
              x: source.x + TYPE_NODE_WIDTH,
              y: cardTop(line.sourceTypeId, source) + TYPE_NODE_HEIGHT / 2,
            },
            {
              x: target.x,
              y: cardTop(line.targetTypeId, target) + TYPE_NODE_HEIGHT / 2,
            },
          )

    // 선 위가 막혔을 때를 위해, 선에 바로 붙은 옆자리(가로 구간은 위·아래, 세로 구간은 왼쪽·오른쪽)도 후보로 둔다.
    const besideX = size.width / 2 + LABEL_GAP
    const besideY = size.height / 2 + LABEL_GAP
    const shifted = candidates.flatMap((candidate) => [
      { ...candidate, offset: false },
      ...(candidate.vertical
        ? [
            { ...candidate, x: candidate.x - besideX, offset: true },
            { ...candidate, x: candidate.x + besideX, offset: true },
          ]
        : [
            { ...candidate, y: candidate.y - besideY, offset: true },
            { ...candidate, y: candidate.y + besideY, offset: true },
          ]),
    ])

    let best: { box: Box; center: Point; penalty: number } | null = null
    for (const candidate of shifted) {
      const box = {
        left: candidate.x - size.width / 2 - LABEL_GAP,
        top: candidate.y - size.height / 2 - LABEL_GAP,
        right: candidate.x + size.width / 2 + LABEL_GAP,
        bottom: candidate.y + size.height / 2 + LABEL_GAP,
      }
      let penalty =
        (candidate.vertical ? VERTICAL_PENALTY : 0) +
        (candidate.offset ? OFFSET_PENALTY : 0) +
        candidate.fromMiddle * MIDDLE_PENALTY
      for (const other of [...cards, ...arrows, ...labels]) {
        penalty += overlapArea(box, other) * OVERLAP_PENALTY
      }
      if (!best || penalty < best.penalty) {
        best = { box, center: { x: candidate.x, y: candidate.y }, penalty }
      }
    }
    if (!best) continue
    labels.push(best.box)
    placed.set(line.key, best.center)
  }
  return placed
}
