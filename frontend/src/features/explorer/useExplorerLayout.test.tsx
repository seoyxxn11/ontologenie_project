import { act, renderHook, waitFor } from '@testing-library/react'
import { expect, test, vi } from 'vitest'
import useExplorerLayout from './useExplorerLayout'

test('관계도 높이에서 제목과 페이지 이동 높이를 빼 행 수를 계산하고 세로 배치에서는 해제한다', async () => {
  let notify = () => {}
  vi.stubGlobal(
    'ResizeObserver',
    class {
      constructor(callback: () => void) {
        notify = callback
      }
      observe() {}
      disconnect() {}
    },
  )
  const graph = document.createElement('section')
  graph.innerHTML = '<div class="explorer-graph-canvas"></div>'
  const list = document.createElement('section')
  list.innerHTML =
    '<div class="explorer-panel-heading"></div><nav class="ui-pagination"></nav>'
  list.style.setProperty('--explorer-row-min-height', '80px')
  let height = 600
  let left = 316
  vi.spyOn(graph, 'getBoundingClientRect').mockImplementation(() =>
    DOMRect.fromRect({ x: left, y: 0, width: 700, height }),
  )
  vi.spyOn(list, 'getBoundingClientRect').mockImplementation(() =>
    DOMRect.fromRect({ x: 0, y: 0, width: 300, height }),
  )
  vi.spyOn(list.children[0], 'getBoundingClientRect').mockReturnValue(
    DOMRect.fromRect({ height: 56 }),
  )
  vi.spyOn(list.children[1], 'getBoundingClientRect').mockReturnValue(
    DOMRect.fromRect({ height: 57 }),
  )
  document.body.append(graph, list)
  const view = renderHook(useExplorerLayout)
  try {
    act(() => {
      view.result.current.setGraphPanel(graph)
      view.result.current.setListPanel(list)
    })
    await waitFor(() => expect(view.result.current.height).toBe(600))
    expect(view.result.current.pageSize).toBe(6)
    height = 720
    act(() => notify())
    await waitFor(() => expect(view.result.current.pageSize).toBe(7))
    expect(view.result.current.height).toBe(720)
    left = 0
    act(() => notify())
    await waitFor(() => expect(view.result.current.height).toBeUndefined())
    expect(view.result.current.pageSize).toBe(8)
  } finally {
    view.unmount()
    graph.remove()
    list.remove()
    vi.unstubAllGlobals()
  }
})
