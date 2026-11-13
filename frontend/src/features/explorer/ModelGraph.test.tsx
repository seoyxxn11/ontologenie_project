import {
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { expect, test, vi } from 'vitest'
import ModelGraph from './ModelGraph'
import type { ModelGraph as GraphData } from './types'

const graph: GraphData = {
  nodes: [
    { id: 3, code: 'EQUIPMENT', name: '설비' },
    { id: 4, code: 'WORK_ORDER', name: '작업지시' },
    { id: 5, code: 'SALES_ORDER', name: '수주' },
  ],
  edges: [
    [4, 3],
    [4, 5],
    [5, 4],
  ].map(([sourceTypeId, targetTypeId], index) => ({
    id: index + 1,
    code: `R${index + 1}`,
    name: `관계 ${index + 1}`,
    sourceTypeId,
    targetTypeId,
    cardinality: 'MANY_TO_ONE',
    relationCount: 1,
    activeLinkCount: 1,
  })),
}

function TestGraph() {
  const [selected, setSelected] = useState<number | null>(null)
  return (
    <ModelGraph
      graph={graph}
      view="graph"
      selectedTypeId={selected}
      onSelect={setSelected}
    />
  )
}

const viewport = () =>
  document.querySelector('.react-flow__viewport')?.getAttribute('style')

test('관계도 위의 휠을 올리면 확대하고 내리면 축소한다', async () => {
  const user = userEvent.setup()
  render(<TestGraph />)
  await user.click(screen.getByRole('button', { name: '읽기 크기' }))
  const scale = () => Number(viewport()?.match(/scale\(([^)]+)\)/)?.[1])
  await waitFor(() => expect(scale()).toBe(1))
  const pane = document.querySelector('.react-flow__pane')!
  fireEvent.wheel(pane, { deltaY: -120, clientX: 0, clientY: 0 })
  await waitFor(() => expect(scale()).toBeGreaterThan(1))
  const enlarged = scale()
  fireEvent.wheel(pane, { deltaY: 120, clientX: 0, clientY: 0 })
  await waitFor(() => expect(scale()).toBeLessThan(enlarged))
})

test('이전 보기는 선택·집중 상태와 사용자가 확대한 화면 위치·배율을 함께 복원한다', async () => {
  const user = userEvent.setup()
  render(<TestGraph />)
  await user.click(
    await screen.findByRole('button', { name: '객체 유형 작업지시' }),
  )
  await user.click(screen.getByRole('button', { name: '읽기 크기' }))
  const reading = viewport()
  await user.click(screen.getByRole('button', { name: '관계도 확대' }))
  await waitFor(() => expect(viewport()).not.toBe(reading))
  const overview = viewport()

  await user.click(screen.getByRole('button', { name: '객체 유형 수주' }))
  expect(viewport()).toBe(overview)
  await user.click(screen.getByRole('button', { name: '집중 보기' }))
  expect(screen.queryByRole('button', { name: '객체 유형 설비' })).toBeNull()
  await user.click(screen.getByRole('button', { name: '읽기 크기' }))
  const focused = viewport()
  await user.click(screen.getByRole('button', { name: '객체 유형 작업지시' }))

  await user.click(screen.getByRole('button', { name: '이전 보기' }))
  expect(
    screen.getByRole('button', { name: '객체 유형 수주' }),
  ).toHaveAttribute('aria-pressed', 'true')
  expect(screen.getByRole('button', { name: '전체 배치 보기' })).toBeVisible()
  await waitFor(() => expect(viewport()).toBe(focused))

  await user.click(screen.getByRole('button', { name: '이전 보기' }))
  expect(screen.getByRole('button', { name: '집중 보기' })).toBeVisible()
  expect(screen.getByRole('button', { name: '객체 유형 설비' })).toBeVisible()
  await waitFor(() => expect(viewport()).toBe(overview))

  await user.click(screen.getByRole('button', { name: '이전 보기' }))
  expect(
    screen.getByRole('button', { name: '객체 유형 작업지시' }),
  ).toHaveAttribute('aria-pressed', 'true')
  await waitFor(() => expect(viewport()).toBe(overview))
})

test('전체 화면 진입·종료와 브라우저에서 종료된 상태를 반영하며 실패를 안내한다', async () => {
  const user = userEvent.setup()
  render(<TestGraph />)
  const canvas = document.querySelector('.explorer-graph-canvas')!
  const originalElement = Object.getOwnPropertyDescriptor(
    document,
    'fullscreenElement',
  )
  const originalExit = Object.getOwnPropertyDescriptor(
    document,
    'exitFullscreen',
  )
  const changeFullscreen = (element: Element | null) => {
    Object.defineProperty(document, 'fullscreenElement', {
      configurable: true,
      value: element,
    })
    document.dispatchEvent(new Event('fullscreenchange'))
  }
  const request = vi.fn(async () => changeFullscreen(canvas))
  Object.defineProperty(canvas, 'requestFullscreen', {
    configurable: true,
    value: request,
  })
  Object.defineProperty(document, 'exitFullscreen', {
    configurable: true,
    value: vi.fn(async () => changeFullscreen(null)),
  })
  try {
    const beforeFullscreen = viewport()
    await user.click(screen.getByRole('button', { name: '전체 화면' }))
    expect(request).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: '전체 화면 닫기' })).toBeVisible()
    expect(
      within(screen.getByRole('group', { name: '전체 화면 탐색' })).getByRole(
        'button',
        { name: '이전 보기' },
      ),
    ).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '전체 화면 닫기' }))
    await waitFor(() => expect(viewport()).toBe(beforeFullscreen))
    expect(screen.getByRole('button', { name: '전체 화면' })).toBeVisible()
    expect(screen.queryByRole('group', { name: '전체 화면 탐색' })).toBeNull()

    request.mockRejectedValueOnce(new Error('Fullscreen unavailable'))
    await user.click(screen.getByRole('button', { name: '전체 화면' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '전체 화면을 열거나 닫지 못했습니다.',
    )
  } finally {
    if (originalElement)
      Object.defineProperty(document, 'fullscreenElement', originalElement)
    else Reflect.deleteProperty(document, 'fullscreenElement')
    if (originalExit)
      Object.defineProperty(document, 'exitFullscreen', originalExit)
    else Reflect.deleteProperty(document, 'exitFullscreen')
  }
})
