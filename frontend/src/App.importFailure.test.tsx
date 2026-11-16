import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, expect, test, vi } from 'vitest'
import App from './App'
import { demoAccount, setMockAccount } from './mocks/auth'

vi.mock('./features/checks/CheckRunsPage', async () => {
  const { lazy, createElement } = await import('react')
  const UnavailablePage = lazy(async () => {
    throw new TypeError(
      'Failed to fetch dynamically imported module: https://ontologenie.com/assets/CheckRunsPage-old.js',
    )
  })
  return { default: () => createElement(UnavailablePage) }
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
  window.history.replaceState({}, '', '/')
})

test('검사 화면 파일 로딩 실패 시 영문 오류 대신 새 버전 안내를 표시한다', async () => {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
  const path = '/admin/checks?status=FAILED#results'
  window.history.replaceState({}, '', path)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })

  try {
    render(
      <QueryClientProvider client={client}>
        <App />
      </QueryClientProvider>,
    )

    expect(
      await screen.findByRole('heading', { name: '새 버전을 불러와 주세요' }),
    ).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '새로고침' })).toBeEnabled()
    expect(
      screen.queryByText('Unexpected Application Error!'),
    ).not.toBeInTheDocument()
    expect(screen.queryByText(/CheckRunsPage-old/)).not.toBeInTheDocument()
    expect(
      window.location.pathname + window.location.search + window.location.hash,
    ).toBe(path)
  } finally {
    cleanup()
    await client.cancelQueries()
    client.clear()
  }
})
