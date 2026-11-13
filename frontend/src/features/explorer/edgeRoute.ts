/**
 * 카드 사이 관계 선을 격자 위에서 직각으로 꺾어 그리는 경로 계산. React에 의존하지 않는 순수 함수다.
 * 카드를 장애물로 보고 빈 통로로만 선을 보낸다.
 *
 * 계산 순서
 * 1. 격자: 캔버스를 CELL(16px) 단위로 나눈다. 선은 칸의 중심을 지난다.
 * 2. 장애물: 카드를 반 칸(8px)씩 넓혀서 겹치는 칸을 막는다. 선과 카드 사이에 8px 이상 간격이 생긴다.
 *    출발 카드와 도착 카드도 장애물이다. 선은 카드 면의 출구(포트) 바로 바깥 칸에서 시작하고 끝난다.
 * 3. 경로 탐색(A*): 상태는 (칸, 진행 방향). 비용은 한 칸 이동 1, 방향 전환 +3, 다른 선이 지나간 칸 +2.
 *    방향 전환 비용 때문에 덜 꺾이는 경로를 고르고, 사용한 칸 비용 때문에 같은 통로의 선들이 서로 다른 칸으로 나뉜다.
 * 4. 차선: 그래도 같은 행이나 열에서 겹치는 구간은 6px씩 옮긴다. 포트에 닿는 첫 구간과 끝 구간은 옮기지 않는다.
 * 5. 꺾이는 지점은 반경 6px로 둥글린다. 경로가 없으면 직선으로 그리고 fallback을 true로 반환한다.
 *
 * 좌표는 0 이상이어야 한다. boxes에 선이 닿지 않는 상자를 넣으면 장애물로만 쓰인다.
 */

export type Box = { x: number; y: number; w: number; h: number }
export type RouteEdge = { key: string; from: string; to: string }
export type Point = { x: number; y: number }
export type Route = {
  key: string
  /** SVG path. 출발 포트에서 시작해 도착 포트에서 끝난다. 화살표는 끝점에 붙는다 */
  d: string
  /** 둥글리기 전의 꺾이는 점. 첫 점은 출발 포트, 끝 점은 도착 포트 */
  points: Point[]
  /** 라벨 위치. labelOf 참고 */
  labelAt: Point
  /** 경로를 찾지 못해 직선으로 그린 경우 true */
  fallback: boolean
}

export const CELL = 16
/** 카드를 이만큼 넓혀서 막는다. 선과 카드 사이의 최소 간격 */
const MARGIN = CELL / 2
const TURN_COST = 3
const USED_COST = 2
const LANE_GAP = 6
const CORNER_R = 6

/** 방향 번호: 0 오른쪽, 1 아래, 2 왼쪽, 3 위. 카드의 면도 같은 번호를 쓴다 */
const DX = [1, 0, -1, 0]
const DY = [0, 1, 0, -1]
const opposite = (d: number) => (d + 2) % 4

/** 카드 면의 출구. 면 위의 점(px)과 그 바로 바깥 칸 */
type Port = { x: number; y: number; cx: number; cy: number; side: number }

/** 최소 힙. 상태 번호와 값을 별도 배열에 담아 객체 생성을 피한다 */
class Heap {
  private items = new Int32Array(1024)
  private keys = new Float64Array(1024)
  size = 0

  push(item: number, key: number) {
    if (this.size === this.items.length) {
      const items = new Int32Array(this.size * 2)
      const keys = new Float64Array(this.size * 2)
      items.set(this.items)
      keys.set(this.keys)
      this.items = items
      this.keys = keys
    }
    let i = this.size++
    while (i > 0) {
      const p = (i - 1) >> 1
      if (this.keys[p] <= key) break
      this.items[i] = this.items[p]
      this.keys[i] = this.keys[p]
      i = p
    }
    this.items[i] = item
    this.keys[i] = key
  }

  /** 최솟값을 꺼낸다. 비어 있으면 -1 */
  pop(): number {
    if (this.size === 0) return -1
    const top = this.items[0]
    const last = --this.size
    if (last > 0) {
      const item = this.items[last]
      const key = this.keys[last]
      let i = 0
      for (;;) {
        let c = i * 2 + 1
        if (c >= last) break
        if (c + 1 < last && this.keys[c + 1] < this.keys[c]) c++
        if (this.keys[c] >= key) break
        this.items[i] = this.items[c]
        this.keys[i] = this.keys[c]
        i = c
      }
      this.items[i] = item
      this.keys[i] = key
    }
    return top
  }

  clear() {
    this.size = 0
  }
}

export function routeEdges(
  edges: RouteEdge[],
  boxes: Map<string, Box>,
  canvas: { width: number; height: number },
): Map<string, Route> {
  const out = new Map<string, Route>()
  const cols = Math.ceil(canvas.width / CELL) + 2
  const rows = Math.ceil(canvas.height / CELL) + 2
  const cells = cols * rows
  const clampX = (c: number) => Math.min(cols - 1, Math.max(0, c))
  const clampY = (c: number) => Math.min(rows - 1, Math.max(0, c))

  // 장애물: 넓힌 카드와 겹치는 칸을 모두 막는다
  const blocked = new Uint8Array(cells)
  for (const b of boxes.values()) {
    const x0 = clampX(Math.floor((b.x - MARGIN) / CELL))
    const x1 = clampX(Math.floor((b.x + b.w + MARGIN - 1e-6) / CELL))
    const y0 = clampY(Math.floor((b.y - MARGIN) / CELL))
    const y1 = clampY(Math.floor((b.y + b.h + MARGIN - 1e-6) / CELL))
    for (let y = y0; y <= y1; y++)
      for (let x = x0; x <= x1; x++) blocked[y * cols + x] = 1
  }

  // 포트: 상대 카드를 향한 면에 두고, 같은 면을 쓰는 선끼리 위치를 나눈다
  const live = edges.filter((e) => boxes.has(e.from) && boxes.has(e.to))
  const centre = (b: Box) => ({ x: b.x + b.w / 2, y: b.y + b.h / 2 })
  const sideOf = (a: Box, b: Box): number => {
    const ca = centre(a)
    const cb = centre(b)
    const dx = cb.x - ca.x
    const dy = cb.y - ca.y
    return Math.abs(dx) >= Math.abs(dy) ? (dx >= 0 ? 0 : 2) : dy >= 0 ? 1 : 3
  }
  /** 면별로 그 면을 쓰는 선 목록. 키는 `카드id:면` */
  const bySide = new Map<string, { key: string; other: Box }[]>()
  const sides = new Map<string, { s: number; t: number }>()
  for (const e of live) {
    const a = boxes.get(e.from)!
    const b = boxes.get(e.to)!
    const s = sideOf(a, b)
    const t = opposite(s)
    sides.set(e.key, { s, t })
    const push = (id: string, side: number, other: Box) => {
      const k = `${id}:${side}`
      const list = bySide.get(k) ?? []
      list.push({ key: e.key, other })
      bySide.set(k, list)
    }
    push(e.from, s, b)
    push(e.to, t, a)
  }
  /** 한 면의 포트 위치를 정한다. 칸 중심이 면 양 끝에서 4px 안쪽에 들어오는 칸만 사용 */
  const portsOf = (id: string, side: number): Map<string, Port> => {
    const b = boxes.get(id)!
    const list = bySide.get(`${id}:${side}`)!
    const vertical = side === 0 || side === 2
    // 면을 따라 놓을 수 있는 칸 범위
    const lo = vertical ? b.y : b.x
    const len = vertical ? b.h : b.w
    let c0 = Math.ceil((lo + 4 - CELL / 2) / CELL)
    let c1 = Math.floor((lo + len - 4 - CELL / 2) / CELL)
    if (c1 < c0) c0 = c1 = Math.floor((lo + len / 2) / CELL)
    const m = c1 - c0 + 1
    // 면 바로 바깥 칸
    const outer =
      side === 0
        ? Math.ceil((b.x + b.w + MARGIN) / CELL)
        : side === 2
          ? Math.floor((b.x - MARGIN) / CELL) - 1
          : side === 1
            ? Math.ceil((b.y + b.h + MARGIN) / CELL)
            : Math.floor((b.y - MARGIN) / CELL) - 1
    // 상대 카드의 위치 순으로 정렬. 포트에서 선이 서로 엇갈리지 않게 한다
    const sorted = [...list].sort(
      (p, q) =>
        (vertical
          ? centre(p.other).y - centre(q.other).y
          : centre(p.other).x - centre(q.other).x) || (p.key < q.key ? -1 : 1),
    )
    const n = sorted.length
    const result = new Map<string, Port>()
    sorted.forEach((p, i) => {
      const idx = Math.min(
        m - 1,
        Math.max(0, Math.round(((i + 1) / (n + 1)) * (m + 1) - 1)),
      )
      const along = c0 + idx
      const alongPx = along * CELL + CELL / 2
      const port: Port = vertical
        ? {
            x: side === 0 ? b.x + b.w : b.x,
            y: alongPx,
            cx: clampX(outer),
            cy: clampY(along),
            side,
          }
        : {
            x: alongPx,
            y: side === 1 ? b.y + b.h : b.y,
            cx: clampX(along),
            cy: clampY(outer),
            side,
          }
      result.set(p.key, port)
    })
    return result
  }
  const portCache = new Map<string, Map<string, Port>>()
  const portFor = (id: string, side: number, key: string): Port => {
    const k = `${id}:${side}`
    let m = portCache.get(k)
    if (!m) {
      m = portsOf(id, side)
      portCache.set(k, m)
    }
    return m.get(key)!
  }

  // 경로 탐색: 긴 선부터 처리한다. 가까운 카드를 잇는 짧은 선이 직선으로 남는다
  const dist = (e: RouteEdge) => {
    const a = centre(boxes.get(e.from)!)
    const b = centre(boxes.get(e.to)!)
    return Math.abs(a.x - b.x) + Math.abs(a.y - b.y)
  }
  const order = [...live].sort(
    (p, q) => dist(q) - dist(p) || (p.key < q.key ? -1 : 1),
  )
  const used = new Uint8Array(cells)
  const states = cells * 4
  const g = new Float64Array(states)
  const parent = new Int32Array(states)
  const closed = new Uint8Array(states)
  const heap = new Heap()

  const search = (
    sc: number,
    sdir: number,
    gc: number,
    gdir: number,
  ): number[] | null => {
    g.fill(Infinity)
    parent.fill(-1)
    closed.fill(0)
    heap.clear()
    const gx = gc % cols
    const gy = (gc - gx) / cols
    const h = (c: number) => {
      const x = c % cols
      return Math.abs(x - gx) + Math.abs((c - x) / cols - gy)
    }
    const start = sc * 4 + sdir
    g[start] = 0
    heap.push(start, h(sc))
    let found = -1
    while (heap.size > 0) {
      const st = heap.pop()
      if (closed[st]) continue
      closed[st] = 1
      const cell = (st - (st % 4)) / 4
      const dir = st % 4
      if (cell === gc) {
        found = st
        break
      }
      const x = cell % cols
      const y = (cell - x) / cols
      const gs = g[st]
      for (let d = 0; d < 4; d++) {
        if (d === opposite(dir)) continue
        const nx = x + DX[d]
        const ny = y + DY[d]
        if (nx < 0 || ny < 0 || nx >= cols || ny >= rows) continue
        const nc = ny * cols + nx
        if (blocked[nc] && nc !== gc) continue
        let cost = gs + 1
        if (d !== dir) cost += TURN_COST
        if (used[nc]) cost += USED_COST
        if (nc === gc && d !== gdir) cost += TURN_COST
        const ns = nc * 4 + d
        if (cost < g[ns]) {
          g[ns] = cost
          parent[ns] = st
          heap.push(ns, cost + h(nc))
        }
      }
    }
    if (found < 0) return null
    const path: number[] = []
    for (let st = found; st >= 0; st = parent[st])
      path.push((st - (st % 4)) / 4)
    path.reverse()
    return path
  }

  type Built = { key: string; points: Point[]; fallback: boolean }
  const built: Built[] = []
  for (const e of order) {
    const { s, t } = sides.get(e.key)!
    const ps = portFor(e.from, s, e.key)
    const pt = portFor(e.to, t, e.key)
    const sc = ps.cy * cols + ps.cx
    const gc = pt.cy * cols + pt.cx
    const path = search(sc, s, gc, opposite(t))
    if (!path) {
      built.push({
        key: e.key,
        points: [
          { x: ps.x, y: ps.y },
          { x: pt.x, y: pt.y },
        ],
        fallback: true,
      })
      continue
    }
    for (const c of path) used[c] = 1
    const pts: Point[] = [{ x: ps.x, y: ps.y }]
    for (const c of path) {
      const x = c % cols
      pts.push({
        x: x * CELL + CELL / 2,
        y: ((c - x) / cols) * CELL + CELL / 2,
      })
    }
    pts.push({ x: pt.x, y: pt.y })
    built.push({ key: e.key, points: simplify(pts), fallback: false })
  }

  assignLanes(built)

  for (const b of built) {
    out.set(b.key, {
      key: b.key,
      d: b.fallback ? straight(b.points) : rounded(b.points),
      points: b.points,
      labelAt: labelOf(b.points),
      fallback: b.fallback,
    })
  }
  return out
}

/** 일직선 위의 중간 점을 제거하고 꺾이는 점만 남긴다 */
function simplify(pts: Point[]): Point[] {
  const out: Point[] = []
  for (const p of pts) {
    const n = out.length
    if (n >= 1 && out[n - 1].x === p.x && out[n - 1].y === p.y) continue
    if (n >= 2) {
      const a = out[n - 2]
      const b = out[n - 1]
      if ((a.x === b.x && b.x === p.x) || (a.y === b.y && b.y === p.y)) {
        out[n - 1] = p
        continue
      }
    }
    out.push(p)
  }
  return out
}

/**
 * 차선: 같은 행(y)이나 열(x)에서 겹치는 중간 구간을 6px씩 옮긴다.
 * 겹치는 구간마다 차선 번호를 다르게 주고, 번호 1, 2, 3을 +6, -6, +12 순으로 옮긴다. 포트에 닿는 첫 구간과 끝 구간은 옮기지 않는다.
 */
function assignLanes(routes: { points: Point[]; fallback: boolean }[]) {
  type Seg = { r: number; i: number; a: number; b: number }
  const rowsH = new Map<number, Seg[]>()
  const colsV = new Map<number, Seg[]>()
  routes.forEach((r, ri) => {
    if (r.fallback) return
    const p = r.points
    for (let i = 1; i < p.length - 2; i++) {
      const s = p[i]
      const e = p[i + 1]
      if (s.y === e.y) {
        const list = rowsH.get(s.y) ?? []
        list.push({ r: ri, i, a: Math.min(s.x, e.x), b: Math.max(s.x, e.x) })
        rowsH.set(s.y, list)
      } else if (s.x === e.x) {
        const list = colsV.get(s.x) ?? []
        list.push({ r: ri, i, a: Math.min(s.y, e.y), b: Math.max(s.y, e.y) })
        colsV.set(s.x, list)
      }
    }
  })
  const shift = (groups: Map<number, Seg[]>, horizontal: boolean) => {
    for (const segs of groups.values()) {
      if (segs.length < 2) continue
      segs.sort((p, q) => p.a - q.a || p.b - q.b)
      const active: { end: number; lane: number }[] = []
      for (const s of segs) {
        for (let k = active.length - 1; k >= 0; k--)
          if (active[k].end <= s.a) active.splice(k, 1)
        let lane = 0
        while (active.some((a) => a.lane === lane)) lane++
        active.push({ end: s.b, lane })
        if (lane === 0) continue
        const off = Math.ceil(lane / 2) * LANE_GAP * (lane % 2 === 1 ? 1 : -1)
        const pts = routes[s.r].points
        if (horizontal) {
          pts[s.i] = { x: pts[s.i].x, y: pts[s.i].y + off }
          pts[s.i + 1] = { x: pts[s.i + 1].x, y: pts[s.i + 1].y + off }
        } else {
          pts[s.i] = { x: pts[s.i].x + off, y: pts[s.i].y }
          pts[s.i + 1] = { x: pts[s.i + 1].x + off, y: pts[s.i + 1].y }
        }
      }
    }
  }
  shift(rowsH, true)
  shift(colsV, false)
}

function straight(p: Point[]): string {
  return `M${p[0].x},${p[0].y} L${p[p.length - 1].x},${p[p.length - 1].y}`
}

/** 꺾이는 점을 둥글린 path. 반경은 6px과 인접 구간 길이의 절반 중 작은 값 */
function rounded(p: Point[]): string {
  let d = `M${p[0].x},${p[0].y}`
  for (let i = 1; i < p.length - 1; i++) {
    const a = p[i - 1]
    const c = p[i]
    const b = p[i + 1]
    const inLen = Math.hypot(c.x - a.x, c.y - a.y)
    const outLen = Math.hypot(b.x - c.x, b.y - c.y)
    const r = Math.min(CORNER_R, inLen / 2, outLen / 2)
    if (r <= 0 || inLen === 0 || outLen === 0) {
      d += ` L${c.x},${c.y}`
      continue
    }
    const ix = (c.x - a.x) / inLen
    const iy = (c.y - a.y) / inLen
    const ox = (b.x - c.x) / outLen
    const oy = (b.y - c.y) / outLen
    d += ` L${c.x - ix * r},${c.y - iy * r} Q${c.x},${c.y} ${c.x + ox * r},${c.y + oy * r}`
  }
  const last = p[p.length - 1]
  return `${d} L${last.x},${last.y}`
}

/** 라벨을 올릴 수 있는 가로 구간의 최소 길이 */
const LABEL_MIN = 56

/** 라벨 위치. LABEL_MIN 이상인 가로 구간 중 가장 긴 구간의 중간. 없으면 전체에서 가장 긴 구간의 중간 */
function labelOf(p: Point[]): Point {
  let best: Point | null = null
  let bestLen = -1
  let bestAny: Point = {
    x: (p[0].x + p[p.length - 1].x) / 2,
    y: (p[0].y + p[p.length - 1].y) / 2,
  }
  let bestAnyLen = -1
  for (let i = 0; i < p.length - 1; i++) {
    const a = p[i]
    const b = p[i + 1]
    const len = Math.hypot(b.x - a.x, b.y - a.y)
    const mid = { x: (a.x + b.x) / 2, y: (a.y + b.y) / 2 }
    if (len > bestAnyLen) {
      bestAnyLen = len
      bestAny = mid
    }
    if (a.y === b.y && len >= LABEL_MIN && len > bestLen) {
      bestLen = len
      best = mid
    }
  }
  return best ?? bestAny
}
