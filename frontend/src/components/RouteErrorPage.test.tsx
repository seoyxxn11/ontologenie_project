import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { expect, test, vi } from 'vitest'
import RouteErrorPage from './RouteErrorPage'

function openError(error: unknown, reload = vi.fn()) {
  const router = createMemoryRouter(
    [
      {
        path: '*',
        loader: () => {
          throw error
        },
        element: <p>정상 화면</p>,
        errorElement: <RouteErrorPage reload={reload} />,
        hydrateFallbackElement: <p>불러오는 중</p>,
      },
    ],
    { initialEntries: ['/admin/checks?status=FAILED#results'] },
  )
  render(<RouterProvider router={router} />)
  return { router, reload }
}

test.each([
  new TypeError(
    'Failed to fetch dynamically imported module: https://ontologenie.com/assets/CheckRunsPage-old.js',
  ),
  new TypeError(
    'error loading dynamically imported module: https://ontologenie.com/assets/SourcesPage-old.js',
  ),
  new TypeError('Importing a module script failed.'),
  new Error('Unable to preload CSS for https://ontologenie.com/assets/old.css'),
])('화면 파일 로딩 실패는 새 버전 안내로 표시한다: %s', async (error) => {
  openError(error)

  expect(
    await screen.findByRole('heading', { name: '새 버전을 불러와 주세요' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('alert')).toHaveTextContent('일시적인 연결 문제')
  expect(screen.getByRole('button', { name: '새로고침' })).toBeEnabled()
  expect(screen.queryByText(error.message)).not.toBeInTheDocument()
})

test('사용자가 새로고침 버튼을 누를 때만 현재 주소를 다시 불러온다', async () => {
  const user = userEvent.setup()
  const { router, reload } = openError(
    new TypeError('Importing a module script failed.'),
  )

  const button = await screen.findByRole('button', { name: '새로고침' })
  expect(reload).not.toHaveBeenCalled()
  expect(screen.getByRole('alert')).toHaveTextContent(
    '새로고침하면 저장하지 않은 입력 내용이 사라질 수 있습니다.',
  )

  await user.click(button)

  expect(reload).toHaveBeenCalledTimes(1)
  expect(router.state.location).toMatchObject({
    pathname: '/admin/checks',
    search: '?status=FAILED',
    hash: '#results',
  })
})

test.each([
  new Error('내부 렌더링 오류: secret detail'),
  new TypeError('Failed to fetch'),
  { status: 500, message: '서버 내부 정보' },
  null,
])(
  '일반 오류를 배포 오류로 안내하거나 내부 정보를 노출하지 않는다: %s',
  async (error) => {
    openError(error)

    expect(
      await screen.findByRole('heading', { name: '화면을 표시하지 못했어요' }),
    ).toBeInTheDocument()
    expect(screen.queryByText(/새 버전/)).not.toBeInTheDocument()
    expect(
      screen.queryByText(/secret detail|서버 내부 정보|Failed to fetch/),
    ).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '새로고침' })).toBeEnabled()
  },
)
