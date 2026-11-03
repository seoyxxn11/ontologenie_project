import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, expect, test } from 'vitest'
import App from './App'
import { setMockAccount } from './mocks/auth'

function openAt(path: string) {
  window.history.pushState({}, '', path)
  render(
    <QueryClientProvider client={new QueryClient()}>
      <App />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  window.history.pushState({}, '', '/')
})

test('첫 주소(/)로 들어오면 업무 데이터로 이동한다', async () => {
  openAt('/')

  await screen.findByRole('heading', { name: '객체 목록' })
  expect(window.location.pathname).toBe('/explorer')
  expect(screen.getByRole('link', { name: '업무 데이터' })).toHaveAttribute(
    'aria-current',
    'page',
  )
  expect(
    await screen.findByRole('heading', { name: '객체 목록' }),
  ).toBeInTheDocument()
})

test.each([
  ['/agent', 'AI 업무 도우미'],
  ['/audit', '실행 이력'],
  ['/admin/history', '정의·규칙 이력'],
  ['/admin/sources', '데이터 연결'],
  ['/admin/rules', '연결 규칙'],
  ['/admin/accounts', '계정·권한'],
])('주소 %s로 바로 들어오면 %s 화면을 보여준다', async (path, title) => {
  openAt(path)

  expect(
    await screen.findByRole('heading', { name: title }),
  ).toBeInTheDocument()
  expect(screen.getByRole('link', { name: title })).toHaveAttribute(
    'aria-current',
    'page',
  )
})

test('로그인 화면은 공통 메뉴 없이 보여준다', async () => {
  setMockAccount(null)
  openAt('/login')

  expect(
    await screen.findByRole('heading', { name: '워크스페이스에 로그인' }),
  ).toBeInTheDocument()
  expect(
    screen.queryByRole('navigation', { name: '주 메뉴' }),
  ).not.toBeInTheDocument()
})

test('없는 주소로 들어오면 안내와 돌아가기 링크를 보여준다', async () => {
  const user = userEvent.setup()
  openAt('/no-such-page')

  expect(
    await screen.findByRole('heading', { name: '페이지를 찾을 수 없어요' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('navigation', { name: '주 메뉴' }),
  ).toBeInTheDocument()

  await user.click(screen.getByRole('link', { name: '업무 데이터로 가기' }))

  expect(window.location.pathname).toBe('/explorer')
  expect(
    await screen.findByRole('heading', { name: '객체 목록' }),
  ).toBeInTheDocument()
})

test('주 메뉴를 누르면 화면과 선택된 메뉴가 바뀐다', async () => {
  const user = userEvent.setup()
  openAt('/explorer')

  await user.click(await screen.findByRole('link', { name: 'AI 업무 도우미' }))

  expect(window.location.pathname).toBe('/agent')
  expect(
    screen.getByRole('heading', { name: 'AI 업무 도우미' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '업무 데이터' })).not.toHaveAttribute(
    'aria-current',
  )
})
