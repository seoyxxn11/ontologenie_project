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

test('집중 보기는 그림 안의 공통 도구에만 있고 해제하면 전체 구조와 이전 배율을 복원한다', async () => {
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
  expect(screen.queryByRole('button', { name: '이전 보기' })).toBeNull()
  expect(document.querySelector('.model-graph-focus-bar')).toBeNull()
  const tools = within(screen.getByRole('group', { name: '관계도 도구' }))
  expect(screen.getByRole('button', { name: '전체 배치 보기' })).toBeVisible()
  expect(tools.getByRole('button', { name: '전체 배치 보기' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  for (const name of ['읽기 크기', '전체 맞춤'])
    expect(tools.getByRole('button', { name })).toBeVisible()
  expect(tools.queryByRole('button', { name: '관계도 확대' })).toBeNull()
  expect(tools.queryByRole('button', { name: '전체 화면' })).toBeNull()
  const zoom = screen.getByRole('group', { name: '관계도 배율 조절' })
  expect(zoom.closest('.react-flow__panel')).toHaveClass('bottom', 'left')
  expect(
    within(zoom)
      .getAllByRole('button')
      .map((button) => button.getAttribute('aria-label')),
  ).toEqual(['관계도 확대', '관계도 축소', '전체 화면'])
  const fullscreenButton = within(zoom).getByRole('button', {
    name: '전체 화면',
  })
  expect(fullscreenButton).toHaveAttribute('title', '전체 화면')
  expect(fullscreenButton).toHaveTextContent('')
  expect(fullscreenButton.querySelector('svg')).toBeInTheDocument()
  await user.click(within(zoom).getByRole('button', { name: '관계도 확대' }))
  await waitFor(() => expect(viewport()).not.toBe(focused))
  const enlarged = viewport()
  await user.click(within(zoom).getByRole('button', { name: '관계도 축소' }))
  await waitFor(() => expect(viewport()).not.toBe(enlarged))
  expect(focused).toBeTruthy()
  await user.click(tools.getByRole('button', { name: '전체 배치 보기' }))
  expect(screen.getByRole('button', { name: '집중 보기' })).toBeVisible()
  expect(screen.getByRole('button', { name: '객체 유형 설비' })).toBeVisible()
  await waitFor(() => expect(viewport()).toBe(overview))

  expect(
    screen.getByRole('button', { name: '객체 유형 작업지시' }),
  ).toHaveAttribute('aria-pressed', 'true')
})

test('범례는 선택 유형의 실제 들어오는 선과 나가는 선에만 표시하고 같은 색으로 구분한다', async () => {
  const user = userEvent.setup()
  render(<TestGraph />)
  const incoming = () => screen.queryByText('→ 들어오는 관계')
  const outgoing = () => screen.queryByText('→ 나가는 관계')
  const edge = (id: string) =>
    document.querySelector(`[data-id="${id}"] .react-flow__edge-path`)
  expect(
    screen.queryByRole('group', { name: '선택 유형 기준 관계 방향' }),
  ).toBeNull()
  await user.click(screen.getByRole('button', { name: '객체 유형 설비' }))
  expect(incoming()).toBeVisible()
  expect(outgoing()).toBeNull()
  expect(edge('4-3')).toHaveStyle({ stroke: '#657319' })
  await user.click(screen.getByRole('button', { name: '객체 유형 작업지시' }))
  expect(incoming()).toBeVisible()
  expect(outgoing()).toBeVisible()
  expect(edge('5-4')).toHaveStyle({ stroke: '#657319' })
  expect(edge('4-3')).toHaveStyle({ stroke: '#6f5adf' })
})

test.each(['outgoing', 'self', 'none'] as const)(
  '%s 관계만 있는 유형에는 없는 방향의 범례를 표시하지 않는다',
  (direction) => {
    const edges =
      direction === 'none'
        ? []
        : [
            {
              ...graph.edges[0],
              sourceTypeId: 3,
              targetTypeId: direction === 'self' ? 3 : 4,
            },
          ]
    render(
      <ModelGraph
        graph={{ ...graph, edges }}
        view="graph"
        selectedTypeId={3}
        onSelect={() => {}}
      />,
    )
    expect(screen.queryByText('→ 들어오는 관계')).toBeNull()
    if (direction === 'none') {
      expect(
        screen.queryByRole('group', { name: '선택 유형 기준 관계 방향' }),
      ).toBeNull()
    } else {
      expect(
        screen.getByText(
          direction === 'self' ? '→ 자기 관계' : '→ 나가는 관계',
        ),
      ).toBeVisible()
      expect(document.querySelector('.react-flow__edge-path')).toHaveStyle({
        stroke: '#6f5adf',
      })
    }
  },
)

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
    await user.click(screen.getByRole('button', { name: '읽기 크기' }))
    await waitFor(() => expect(viewport()).toContain('scale(1)'))
    const beforeFullscreen = viewport()
    await user.click(screen.getByRole('button', { name: '전체 화면' }))
    expect(request).toHaveBeenCalledOnce()
    expect(screen.getByRole('button', { name: '전체 화면 닫기' })).toBeVisible()
    expect(
      within(screen.getByRole('group', { name: '관계도 도구' })).getByRole(
        'button',
        { name: '집중 보기' },
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

test('읽기 크기 뒤 전체 맞춤은 갱신 없는 controlled 노드도 30% 이하로 줄여 화면 안에 넣는다', async () => {
  const oldWidth = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    'offsetWidth',
  )!.get!
  const oldHeight = Object.getOwnPropertyDescriptor(
    HTMLElement.prototype,
    'offsetHeight',
  )!.get!
  const widthSpy = vi
    .spyOn(HTMLElement.prototype, 'offsetWidth', 'get')
    .mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('react-flow') ? 720 : oldWidth.call(this)
    })
  const heightSpy = vi
    .spyOn(HTMLElement.prototype, 'offsetHeight', 'get')
    .mockImplementation(function (this: HTMLElement) {
      return this.classList.contains('react-flow') ? 480 : oldHeight.call(this)
    })
  const dense: GraphData = {
    nodes: Array.from({ length: 40 }, (_, i) => ({
      id: i + 1,
      code: `T${i + 1}`,
      name: `유형 ${i + 1}`,
    })),
    edges: Array.from({ length: 39 }, (_, i) => ({
      id: i + 1,
      code: `R${i + 1}`,
      name: `관계 ${i + 1}`,
      sourceTypeId: i + 2,
      targetTypeId: 1,
      cardinality: 'MANY_TO_ONE',
      relationCount: 1,
      activeLinkCount: 1,
    })),
  }
  try {
    render(
      <ModelGraph
        graph={dense}
        view="graph"
        selectedTypeId={null}
        onSelect={() => {}}
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '읽기 크기' }))
    await waitFor(() => expect(viewport()).toContain('scale(1)'))
    await user.click(screen.getByRole('button', { name: '전체 맞춤' }))
    await waitFor(() => {
      const match = viewport()?.match(
        /translate\(([-\d.]+)px,\s*([-\d.]+)px\) scale\(([-\d.eE+]+)\)/,
      )
      expect(match).toBeTruthy()
      const [, x, y, zoom] = match!.map(Number)
      expect(zoom).toBeGreaterThan(0)
      expect(zoom).toBeLessThan(0.3)
      for (const element of document.querySelectorAll<HTMLElement>(
        '.react-flow__node',
      )) {
        const position = element.style.transform.match(
          /translate\(([-\d.]+)px,\s*([-\d.]+)px\)/,
        )!
        const left = Number(position[1]) * zoom + x,
          top = Number(position[2]) * zoom + y
        expect(left).toBeGreaterThanOrEqual(0)
        expect(top).toBeGreaterThanOrEqual(0)
        expect(
          left + parseFloat(element.style.width) * zoom,
        ).toBeLessThanOrEqual(720)
        expect(
          top + parseFloat(element.style.height) * zoom,
        ).toBeLessThanOrEqual(480)
      }
    })
  } finally {
    widthSpy.mockRestore()
    heightSpy.mockRestore()
  }
})
