import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, expect, test, vi } from 'vitest'
import { AuthContext } from '../features/auth/auth-context'
import type { Account } from '../features/auth/types'
import { demoAccount } from '../mocks/auth'
import AppLayout from './AppLayout'

afterEach(() => vi.unstubAllGlobals())

function openLayout(roleCode: Account['roleCode'] = 'ADMIN') {
  render(
    <AuthContext.Provider
      value={{
        account: { ...demoAccount, roleCode },
        pending: false,
        error: null,
        refresh: vi.fn(),
        signIn: vi.fn(),
        signOut: vi.fn(),
      }}
    >
      <MemoryRouter initialEntries={['/explorer']}>
        <Routes>
          <Route element={<AppLayout />}>
            <Route path="/explorer" element={<h1>업무 데이터</h1>} />
            <Route path="/audit" element={<h1>실행 이력</h1>} />
            <Route path="/admin/object-types" element={<h1>객체 유형</h1>} />
          </Route>
        </Routes>
      </MemoryRouter>
    </AuthContext.Provider>,
  )
}

test('처음에는 패널을 접고 로고와 주 메뉴만 상단에 표시한다', () => {
  openLayout()
  const header = screen.getByRole('banner')
  expect(
    within(header).getByRole('link', { name: 'OntoloGenie' }),
  ).toBeVisible()
  expect(
    within(header).getByRole('navigation', { name: '주 메뉴' }),
  ).toBeVisible()
  expect(
    within(header).queryByRole('link', { name: '객체 유형' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '메뉴 패널 열기' }),
  ).toHaveAttribute('aria-expanded', 'false')
  expect(
    screen.queryByRole('navigation', { name: '계정 메뉴' }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '로그아웃' }),
  ).not.toBeInTheDocument()
})

test('운영·관리 패널에서 객체 유형을 열면 선택 표시와 열린 상태를 유지한다', async () => {
  const user = userEvent.setup()
  openLayout()
  await user.click(screen.getByRole('button', { name: '메뉴 패널 열기' }))
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeVisible()
  expect(screen.getByRole('link', { name: '정합성 검사' })).toBeVisible()
  expect(screen.getByRole('heading', { name: '운영·관리' })).toBeVisible()
  const objectTypes = within(
    screen.getByRole('navigation', { name: '계정 메뉴' }),
  ).getByRole('link', { name: '객체 유형' })
  await user.click(objectTypes)
  expect(screen.getByRole('heading', { name: '객체 유형' })).toBeVisible()
  expect(
    screen.getByRole('button', { name: '메뉴 패널 접기' }),
  ).toHaveAttribute('aria-expanded', 'true')
  expect(objectTypes).toHaveAttribute('aria-current', 'page')
  await user.keyboard('{Escape}')
  expect(screen.getByRole('button', { name: '메뉴 패널 열기' })).toHaveFocus()
  expect(
    screen.queryByRole('button', { name: '로그아웃' }),
  ).not.toBeInTheDocument()
})

test.each(['PLANNER', 'MAINTENANCE', 'VIEWER'] as const)(
  '%s는 관리 제목 없이 내 메뉴와 실행 이력만 표시한다',
  async (roleCode) => {
    const user = userEvent.setup()
    openLayout(roleCode)
    expect(
      within(screen.getByRole('navigation', { name: '주 메뉴' })).getAllByRole(
        'link',
      ),
    ).toHaveLength(2)
    expect(
      screen.getByRole('button', { name: '메뉴 패널 열기' }),
    ).toHaveAttribute('title', '내 메뉴')
    await user.click(screen.getByRole('button', { name: '메뉴 패널 열기' }))
    expect(screen.getByRole('heading', { name: '내 메뉴' })).toBeVisible()
    expect(screen.queryByText('운영·관리')).not.toBeInTheDocument()
    const links = within(
      screen.getByRole('navigation', { name: '계정 메뉴' }),
    ).getAllByRole('link')
    expect(links).toHaveLength(1)
    expect(links[0]).toHaveTextContent('실행 이력')
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeVisible()
  },
)

test('좁은 화면에서는 한 패널에 주 메뉴를 모으고 키보드 초점을 가둔 뒤 닫을 때 복원한다', async () => {
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => ({
      matches: true,
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
    })),
  )
  const user = userEvent.setup()
  openLayout()
  expect(
    screen.queryByRole('navigation', { name: '주 메뉴' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '메뉴 패널 열기' }))
  const panel = screen.getByRole('dialog', { name: '운영·관리' })
  expect(panel).toHaveFocus()
  expect(panel).toHaveAttribute('aria-modal', 'true')
  expect(screen.getByRole('banner')).toHaveAttribute('inert')
  expect(screen.getByRole('main')).toHaveAttribute('inert')
  expect(document.body.style.overflow).toBe('hidden')
  await user.tab({ shift: true })
  expect(screen.getByRole('button', { name: '로그아웃' })).toHaveFocus()
  await user.tab()
  expect(screen.getByRole('button', { name: '메뉴 패널 닫기' })).toHaveFocus()
  expect(
    within(panel).getAllByRole('link', { name: '객체 유형' }),
  ).toHaveLength(1)
  await user.click(within(panel).getByRole('link', { name: '객체 유형' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('heading', { name: '객체 유형' })).toBeVisible()
  expect(screen.getByRole('button', { name: '메뉴 패널 열기' })).toHaveFocus()
  expect(screen.getByRole('main')).not.toHaveAttribute('inert')
  expect(document.body.style.overflow).not.toBe('hidden')
})
