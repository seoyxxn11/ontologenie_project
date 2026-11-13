import { expect, test } from 'vitest'
import {
  createGraphNavigation,
  fullscreenGraphViewport,
  graphNavigationReducer,
} from './modelGraphNavigation'

test('전체 화면은 작은 배율을 100%로 키우고 선택 유형을 가운데에 놓는다', () => {
  expect(
    fullscreenGraphViewport(
      { x: 10, y: 20, zoom: 0.4 },
      { width: 500, height: 480 },
      { width: 1440, height: 900 },
      { x: 800, y: 600 },
    ),
  ).toEqual({ x: -80, y: -150, zoom: 1 })
})

test('선택 없는 전체 화면은 보던 중심을 유지하고 이미 확대한 배율은 줄이지 않는다', () => {
  const viewport = fullscreenGraphViewport(
    { x: 100, y: 40, zoom: 1.2 },
    { width: 500, height: 480 },
    { width: 1440, height: 900 },
  )
  expect(viewport.x).toBeCloseTo(570, 10)
  expect(viewport.y).toBeCloseTo(250, 10)
  expect(viewport.zoom).toBe(1.2)
})

const overview = { x: 125, y: -80, zoom: 0.75 }
const focused = { x: -220, y: 95, zoom: 1.2 }

test('기본 선택은 카메라를 그대로 두고 이전 보기에서 최초 선택과 위치·배율을 복원한다', () => {
  let state = createGraphNavigation(null)
  state = graphNavigationReducer(state, {
    type: 'viewport',
    viewport: overview,
  })
  state = graphNavigationReducer(state, { type: 'select', id: 14 })
  expect(state.focused).toBe(false)
  expect(state.camera.kind).toBe('keep')
  state = graphNavigationReducer(state, { type: 'viewport', viewport: focused })
  state = graphNavigationReducer(state, { type: 'select', id: 4 })
  state = graphNavigationReducer(state, { type: 'back' })
  expect(state.selectedTypeId).toBe(14)
  expect(state.camera).toEqual({ kind: 'restore', viewport: focused })
  state = graphNavigationReducer(state, { type: 'back' })
  expect(state.selectedTypeId).toBeNull()
  expect(state.viewport).toEqual(overview)
  expect(state.camera).toEqual({ kind: 'restore', viewport: overview })
})

test('집중 보기와 유형 이동 이력을 뒤로 가면 각각의 보기 방식과 카메라를 복원한다', () => {
  let state = createGraphNavigation(14)
  state = graphNavigationReducer(state, { type: 'focus', viewport: overview })
  expect(state.focused).toBe(true)
  expect(state.camera.kind).toBe('fit')
  state = graphNavigationReducer(state, {
    type: 'select',
    id: 4,
    viewport: focused,
  })
  expect(state.focused).toBe(true)
  expect(state.camera.kind).toBe('fit')
  state = graphNavigationReducer(state, { type: 'back' })
  expect(state.selectedTypeId).toBe(14)
  expect(state.focused).toBe(true)
  expect(state.viewport).toEqual(focused)
  state = graphNavigationReducer(state, { type: 'back' })
  expect(state.focused).toBe(false)
  expect(state.camera).toEqual({ kind: 'restore', viewport: overview })
})

test('전체 배치로 나갈 때는 집중 보기 진입 전 화면으로 돌아가며 빈 이력과 재선택은 무시한다', () => {
  let state = createGraphNavigation(4)
  expect(graphNavigationReducer(state, { type: 'back' })).toBe(state)
  expect(graphNavigationReducer(state, { type: 'select', id: 4 })).toBe(state)
  state = graphNavigationReducer(state, { type: 'focus', viewport: overview })
  state = graphNavigationReducer(state, { type: 'focus', viewport: focused })
  expect(state.focused).toBe(false)
  expect(state.camera).toEqual({ kind: 'restore', viewport: overview })
  state = graphNavigationReducer(state, { type: 'back' })
  expect(state.focused).toBe(true)
  expect(state.viewport).toEqual(focused)
  expect(
    graphNavigationReducer(createGraphNavigation(null), { type: 'focus' })
      .focused,
  ).toBe(false)
})
