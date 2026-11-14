import type { Viewport } from '@xyflow/react'

export type GraphNavigation = {
  selectedTypeId: number | null
  focused: boolean
  viewport: Viewport | null
  overviewViewport: Viewport | null
  camera: GraphCameraRequest
}

export type GraphCameraRequest =
  { kind: 'keep' | 'fit' } | { kind: 'restore'; viewport: Viewport }

type NavigationAction =
  | { type: 'select'; id: number | null; viewport?: Viewport }
  | { type: 'focus'; viewport?: Viewport }
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
    camera: { kind: 'fit' },
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
  const viewport = action.viewport ?? state.viewport
  if (action.type === 'select') {
    if (action.id === state.selectedTypeId) return state
    const focused = state.focused && action.id !== null
    return {
      ...state,
      selectedTypeId: action.id,
      focused,
      viewport,
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
    camera: !state.focused
      ? { kind: 'fit' }
      : state.overviewViewport
        ? { kind: 'restore', viewport: state.overviewViewport }
        : { kind: 'fit' },
  }
}
