import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import {
  Link,
  MemoryRouter,
  Route,
  Routes,
  useSearchParams,
  useLocation,
} from 'react-router'
import { expect, test } from 'vitest'
import HistoryBack from './HistoryBack'

function History() {
  const [, setParams] = useSearchParams()
  const location = useLocation()
  return (
    <>
      <HistoryBack className="back" dividerClassName="divider" />
      <button
        onClick={() => setParams({ page: '2' }, { state: location.state })}
      >
        페이지 변경
      </button>
      <Link to="/audit">실행 이력 메뉴</Link>
    </>
  )
}

function open(state?: unknown) {
  render(
    <MemoryRouter initialEntries={[{ pathname: '/audit', state }]}>
      <Routes>
        <Route path="/audit" element={<History />} />
        <Route path="/explorer" element={<p>원래 객체 화면</p>} />
      </Routes>
    </MemoryRouter>,
  )
}

test('출발 화면 없이 직접 접속하면 화살표와 구분선이 없다', () => {
  open()
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
  expect(document.querySelector('.divider')).toBeNull()
})

test('검색·페이지 변경 후에도 출발 화면의 객체와 필터로 복귀한다', async () => {
  open({ historyReturnTo: '/explorer?object=301&type=equipment' })
  await userEvent.click(screen.getByRole('button', { name: '페이지 변경' }))
  expect(screen.getByRole('link', { name: '이전 화면으로' })).toHaveAttribute(
    'href',
    '/explorer?object=301&type=equipment',
  )
  await userEvent.click(screen.getByRole('link', { name: '이전 화면으로' }))
  expect(screen.getByText('원래 객체 화면')).toBeInTheDocument()
})

test.each([
  'https://example.com',
  '//example.com',
  '/audit?page=1',
  '/explorer\\example.com',
])('유효하지 않은 복귀 경로 %s는 숨긴다', (value) => {
  open({ historyReturnTo: value })
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
})

test('같은 화면에서 메뉴로 다시 진입하면 기존 출발 정보를 지운다', async () => {
  open({ historyReturnTo: '/explorer?object=301' })
  expect(
    screen.getByRole('link', { name: '이전 화면으로' }),
  ).toBeInTheDocument()
  await userEvent.click(screen.getByRole('link', { name: '실행 이력 메뉴' }))
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
  expect(document.querySelector('.divider')).toBeNull()
})

test('메뉴에서 방문 기록이 생겨도 출발 정보 없으면 복귀를 만들지 않는다', async () => {
  render(
    <MemoryRouter initialEntries={['/explorer']}>
      <Routes>
        <Route
          path="/explorer"
          element={<Link to="/audit">실행 이력 메뉴</Link>}
        />
        <Route path="/audit" element={<History />} />
      </Routes>
    </MemoryRouter>,
  )
  await userEvent.click(screen.getByRole('link', { name: '실행 이력 메뉴' }))
  expect(
    screen.queryByRole('link', { name: '이전 화면으로' }),
  ).not.toBeInTheDocument()
})
