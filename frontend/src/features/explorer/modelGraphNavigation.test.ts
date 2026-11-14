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

test('기본 선택과 재선택은 사용자가 정한 위치·배율을 유지한다', () => {
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
  expect(state.selectedTypeId).toBe(4)
  expect(state.viewport).toEqual(focused)
  expect(state.camera.kind).toBe('keep')
  expect(graphNavigationReducer(state, { type: 'select', id: 4 })).toBe(state)
  expect(
    graphNavigationReducer(state, { type: 'viewport', viewport: focused }),
  ).toBe(state)
})

test('집중 보기에서 다른 유형을 선택해도 집중 상태를 유지하고 나갈 때 전체 배율을 복원한다', () => {
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
  state = graphNavigationReducer(state, { type: 'focus', viewport: focused })
  expect(state.focused).toBe(false)
  expect(state.selectedTypeId).toBe(4)
  expect(state.camera).toEqual({ kind: 'restore', viewport: overview })
})

test('유형 선택을 해제하면 전체 배치와 진입 전 카메라로 돌아가고 선택 없이는 집중하지 않는다', () => {
  let state = createGraphNavigation(4)
  expect(graphNavigationReducer(state, { type: 'select', id: 4 })).toBe(state)
  state = graphNavigationReducer(state, { type: 'focus', viewport: overview })
  state = graphNavigationReducer(state, {
    type: 'select',
    id: null,
    viewport: focused,
  })
  expect(state.focused).toBe(false)
  expect(state.selectedTypeId).toBeNull()
  expect(state.camera).toEqual({ kind: 'restore', viewport: overview })
  expect(
    graphNavigationReducer(createGraphNavigation(null), { type: 'focus' })
      .focused,
  ).toBe(false)
})
