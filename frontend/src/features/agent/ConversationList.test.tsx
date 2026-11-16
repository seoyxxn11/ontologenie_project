import { act, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'
import ConversationList from './ConversationList'
import type { AgentConversation } from './types'

const conversations: AgentConversation[] = Array.from(
  { length: 8 },
  (_, index) => ({
    id: index + 1,
    title: `대화 ${index + 1}`,
    startedAt: '2026-11-12T00:00:00Z',
    lastMessageAt: '2026-11-12T00:00:00Z',
  }),
)

const observers = new Set<{
  callback: ResizeObserverCallback
  targets: Element[]
}>()
let rowHeight = 36

beforeEach(() => {
  rowHeight = 36
  vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockImplementation(
    () => ({ ...new DOMRect(), height: rowHeight }) as DOMRect,
  )
  vi.stubGlobal(
    'ResizeObserver',
    class {
      targets: Element[] = []
      callback: ResizeObserverCallback
      constructor(callback: ResizeObserverCallback) {
        this.callback = callback
        observers.add(this)
      }
      observe(target: Element) {
        this.targets.push(target)
      }
      disconnect() {
        observers.delete(this)
      }
    },
  )
})

afterEach(() => {
  observers.clear()
  vi.restoreAllMocks()
  vi.unstubAllGlobals()
})

function resize(availableHeight: number) {
  act(() => {
    for (const observer of [...observers]) {
      observer.callback(
        observer.targets.map((target) => ({
          target,
          contentRect: {
            height: target.tagName === 'LI' ? rowHeight : availableHeight,
          },
        })) as ResizeObserverEntry[],
        observer as unknown as ResizeObserver,
      )
    }
  })
}

function props(selectedId = 1) {
  return {
    conversations,
    selectedId,
    creating: false,
    deleting: false,
    onSelect: vi.fn(),
    onCreate: vi.fn(),
    onDelete: vi.fn(),
  }
}

function visibleTitles() {
  return screen.getAllByRole('listitem').map(
    (item) =>
      within(item).getByRole('button', {
        pressed: undefined,
        name: /^대화 \d+$/,
      }).textContent,
  )
}

test('목록 높이에 들어가는 개수만 표시하고 대화 선택 없이 페이지를 넘긴다', async () => {
  const user = userEvent.setup()
  const handlers = props()
  render(<ConversationList {...handlers} />)
  resize(110)

  expect(visibleTitles()).toEqual(['대화 1', '대화 2', '대화 3'])
  expect(
    screen.getByRole('button', { name: '1페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
  expect(screen.getByRole('button', { name: '이전 페이지' })).toBeDisabled()

  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  expect(visibleTitles()).toEqual(['대화 4', '대화 5', '대화 6'])
  expect(handlers.onSelect).not.toHaveBeenCalled()
  await user.click(screen.getByRole('button', { name: '대화 5' }))
  expect(handlers.onSelect).toHaveBeenCalledWith(5)

  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  expect(visibleTitles()).toEqual(['대화 7', '대화 8'])
  expect(screen.getByRole('button', { name: '다음 페이지' })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '이전 페이지' }))
  expect(visibleTitles()).toEqual(['대화 4', '대화 5', '대화 6'])
})

test('높이와 글자 크기가 바뀌면 보던 항목을 포함하는 페이지로 나눈다', async () => {
  render(<ConversationList {...props()} />)
  resize(108)
  await userEvent.click(screen.getByRole('button', { name: '다음 페이지' }))

  resize(72)
  expect(visibleTitles()).toEqual(['대화 3', '대화 4'])
  expect(
    screen.getByRole('button', { name: '2페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')

  rowHeight = 48
  resize(144)
  expect(visibleTitles()).toEqual(['대화 1', '대화 2', '대화 3'])
  resize(20)
  expect(screen.getAllByRole('listitem')).toHaveLength(1)
})

test('주소로 선택된 대화와 새로 생성된 대화가 있는 페이지를 연다', () => {
  const handlers = props(8)
  const { rerender } = render(<ConversationList {...handlers} />)
  resize(108)
  expect(
    screen.getByRole('button', { name: '대화 8', pressed: true }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '3페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')

  rerender(<ConversationList {...handlers} selectedId={2} />)
  expect(
    screen.getByRole('button', { name: '대화 2', pressed: true }),
  ).toBeInTheDocument()

  rerender(
    <ConversationList
      {...handlers}
      selectedId={99}
      conversations={[
        { ...conversations[0], id: 99, title: '새 대화' },
        ...conversations,
      ]}
    />,
  )
  expect(
    screen.getByRole('button', { name: '새 대화', pressed: true }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '1페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
})

test('마지막 페이지의 대화가 사라지면 남아 있는 마지막 페이지로 돌아간다', async () => {
  const handlers = props()
  const { rerender } = render(<ConversationList {...handlers} />)
  resize(108)
  await userEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
  await userEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
  rerender(
    <ConversationList
      {...handlers}
      conversations={conversations.slice(0, 6)}
    />,
  )
  expect(visibleTitles()).toEqual(['대화 4', '대화 5', '대화 6'])
  expect(
    screen.getByRole('button', { name: '2페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
})

test('대화가 없으면 빈 안내를, 한 페이지면 페이지 이동을 숨긴다', () => {
  const handlers = props()
  const { rerender } = render(
    <ConversationList {...handlers} conversations={[]} />,
  )
  expect(screen.getByText('아직 대화가 없어요.')).toBeInTheDocument()
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
  rerender(
    <ConversationList
      {...handlers}
      conversations={conversations.slice(0, 2)}
    />,
  )
  resize(108)
  expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
})

test('늘어난 표시 행 높이를 페이지 크기 계산에 다시 사용하지 않는다', () => {
  const original = window.getComputedStyle.bind(window)
  vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => {
    const style = original(element)
    if (element.classList.contains('agent-conversation-select')) {
      Object.defineProperty(style, 'minHeight', { value: '36px' })
    }
    return style
  })
  render(<ConversationList {...props()} />)
  resize(110)
  expect(visibleTitles()).toHaveLength(3)
  rowHeight = 55
  resize(110)
  expect(visibleTitles()).toHaveLength(3)
})

test('가득 찬 페이지만 늘리고 일부만 있는 마지막 페이지는 기본 높이를 유지한다', async () => {
  render(<ConversationList {...props()} />)
  resize(110)
  expect(screen.getByRole('list')).toHaveAttribute('data-page-full', 'true')
  await userEvent.click(screen.getByRole('button', { name: '마지막 페이지' }))
  expect(visibleTitles()).toEqual(['대화 7', '대화 8'])
  expect(screen.getByRole('list')).toHaveAttribute('data-page-full', 'false')
})
