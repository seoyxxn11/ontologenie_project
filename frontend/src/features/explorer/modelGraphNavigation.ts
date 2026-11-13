import type { Viewport } from '@xyflow/react'

type GraphSnapshot = {
  selectedTypeId: number | null
  focused: boolean
  viewport: Viewport | null
  overviewViewport: Viewport | null
}

export type GraphCameraRequest =
  { kind: 'keep' | 'fit' } | { kind: 'restore'; viewport: Viewport }

export type GraphNavigation = GraphSnapshot & {
  history: GraphSnapshot[]
  camera: GraphCameraRequest
}

type NavigationAction =
  | { type: 'select'; id: number | null; viewport?: Viewport }
  | { type: 'focus'; viewport?: Viewport }
  | { type: 'back' }
  | { type: 'viewport'; viewport: Viewport }

type CanvasSize = { width: number; height: number }

// 전체 화면은 최소 읽기 배율로 연다. 선택 유형이 있으면 그 카드를, 없으면 보던 화면 중심을 유지한다.
export function fullscreenGraphViewport(
  viewport: Viewport,
  previousSize: CanvasSize,
  size: CanvasSize,
  selectedCenter?: { x: number; y: number },
): Viewport {
  const center = selectedCenter ?? {
    x: (previousSize.width / 2 - viewport.x) / viewport.zoom,
    y: (previousSize.height / 2 - viewport.y) / viewport.zoom,
  }
  const zoom = Math.max(1, viewport.zoom)
  return {
    x: size.width / 2 - center.x * zoom,
    y: size.height / 2 - center.y * zoom,
    zoom,
  }
}

export function createGraphNavigation(
  selectedTypeId: number | null,
): GraphNavigation {
  return {
    selectedTypeId,
    focused: false,
    viewport: null,
    overviewViewport: null,
    history: [],
    camera: { kind: 'fit' },
  }
}

function snapshot(
  state: GraphNavigation,
  viewport = state.viewport,
): GraphSnapshot {
  return {
    selectedTypeId: state.selectedTypeId,
    focused: state.focused,
    viewport,
    overviewViewport: state.overviewViewport,
  }
}

export function graphNavigationReducer(
  state: GraphNavigation,
  action: NavigationAction,
): GraphNavigation {
  if (action.type === 'viewport') {
    if (
      state.viewport?.x === action.viewport.x &&
      state.viewport.y === action.viewport.y &&
      state.viewport.zoom === action.viewport.zoom
    )
      return state
    return { ...state, viewport: action.viewport }
  }
  if (action.type === 'back') {
    const previous = state.history.at(-1)
    if (!previous) return state
    return {
      ...previous,
      history: state.history.slice(0, -1),
      camera: previous.viewport
        ? { kind: 'restore', viewport: previous.viewport }
        : { kind: 'fit' },
    }
  }
  const viewport = action.viewport ?? state.viewport
  if (action.type === 'select') {
    if (action.id === state.selectedTypeId) return state
    const focused = state.focused && action.id !== null
    return {
      ...state,
      selectedTypeId: action.id,
      focused,
      viewport,
      history: [...state.history, snapshot(state, viewport)],
      camera: focused
        ? { kind: 'fit' }
        : state.focused && state.overviewViewport
          ? { kind: 'restore', viewport: state.overviewViewport }
          : { kind: 'keep' },
    }
  }
  if (state.selectedTypeId === null) return state
  return {
    ...state,
    focused: !state.focused,
    viewport,
    overviewViewport: state.focused ? state.overviewViewport : viewport,
    history: [...state.history, snapshot(state, viewport)],
    camera: !state.focused
      ? { kind: 'fit' }
      : state.overviewViewport
        ? { kind: 'restore', viewport: state.overviewViewport }
        : { kind: 'fit' },
  }
}
