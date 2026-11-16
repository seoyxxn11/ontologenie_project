import { useLayoutEffect, useState } from 'react'
import { OBJECT_PAGE_SIZE } from './api'

export function explorerPageSize(
  height: number,
  chrome: number,
  rowHeight: number,
) {
  return Math.max(1, Math.min(100, Math.floor((height - chrome) / rowHeight)))
}

export default function useExplorerLayout() {
  const [graphPanel, setGraphPanel] = useState<HTMLElement | null>(null)
  const [listPanel, setListPanel] = useState<HTMLElement | null>(null)
  const [layout, setLayout] = useState<{ height?: number; pageSize: number }>({
    pageSize: OBJECT_PAGE_SIZE,
  })
  useLayoutEffect(() => {
    if (!graphPanel || !listPanel || typeof ResizeObserver === 'undefined')
      return
    let frame = 0
    function measure() {
      if (!graphPanel || !listPanel) return
      const graph = graphPanel.getBoundingClientRect()
      const list = listPanel.getBoundingClientRect()
      if (
        !graphPanel.querySelector('.explorer-graph-canvas') ||
        graph.height <= 0
      )
        return
      if (graph.left < list.right - 1) {
        setLayout((current) =>
          current.height === undefined && current.pageSize === OBJECT_PAGE_SIZE
            ? current
            : { pageSize: OBJECT_PAGE_SIZE },
        )
        return
      }
      const chrome = Array.from(
        listPanel.querySelectorAll<HTMLElement>(
          '.explorer-panel-heading, .explorer-related-context, .ui-pagination',
        ),
      ).reduce(
        (total, element) => total + element.getBoundingClientRect().height,
        2,
      )
      const rowHeight =
        Number.parseFloat(
          getComputedStyle(listPanel).getPropertyValue(
            '--explorer-row-min-height',
          ),
        ) || 80
      const next = {
        height: graph.height,
        pageSize: explorerPageSize(graph.height, chrome, rowHeight),
      }
      setLayout((current) =>
        current.height === next.height && current.pageSize === next.pageSize
          ? current
          : next,
      )
    }
    function schedule() {
      cancelAnimationFrame(frame)
      frame = requestAnimationFrame(measure)
    }
    const resize = new ResizeObserver(schedule)
    resize.observe(graphPanel)
    resize.observe(listPanel)
    const mutation = new MutationObserver(schedule)
    mutation.observe(graphPanel, { childList: true, subtree: true })
    mutation.observe(listPanel, { childList: true, subtree: true })
    window.addEventListener('resize', schedule)
    schedule()
    return () => {
      cancelAnimationFrame(frame)
      resize.disconnect()
      mutation.disconnect()
      window.removeEventListener('resize', schedule)
    }
  }, [graphPanel, listPanel])
  return { ...layout, setGraphPanel, setListPanel }
}
