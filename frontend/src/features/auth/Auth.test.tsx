import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { beforeEach, expect, test, vi } from 'vitest'
import App from '../../App'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { ApiError } from '../../api/errors'
import { trackUiMotionInput } from '../../components/uiMotion'

function openAt(path: string) {
  window.history.pushState({}, '', path)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
  return client
}

beforeEach(() => {
  setMockAccount(null)
})

async function fillLogin(password = 'demo-only') {
  const user = userEvent.setup()
  await screen.findByRole('textbox', { name: '계정 ID' })
  await user.type(screen.getByLabelText('계정 ID'), 'demo-user')
  await user.type(screen.getByLabelText('비밀번호', { exact: true }), password)
  return user
}

test('익명 사용자는 업무 데이터를 요청하기 전에 로그인 화면으로 이동한다', async () => {
  const requests = vi.fn(() => HttpResponse.json({ data: [] }))
  server.use(http.get('/api/v1/objects', requests))
  openAt('/explorer')
  await screen.findByRole('textbox', { name: '계정 ID' })
  expect(window.location.pathname).toBe('/login')
  expect(requests).not.toHaveBeenCalled()
  expect(
    screen.queryByRole('navigation', { name: '주 메뉴' }),
  ).not.toBeInTheDocument()
})

test('로그인 입력을 시작한 뒤 마우스로 필드를 이동해도 첫 등장 모션을 재실행하지 않는다', async () => {
  const stopTracking = trackUiMotionInput()
  try {
    openAt('/login')
    const loginId = await screen.findByRole('textbox', { name: '계정 ID' })
    const card = screen.getByRole('region', { name: '워크스페이스에 로그인' })
    expect(card).toHaveClass('auth-card--ready')
    const user = userEvent.setup()
    await user.type(loginId, 'demo-user')
    await user.click(screen.getByLabelText('비밀번호', { exact: true }))
    expect(document.documentElement.dataset.uiInput).toBe('pointer')
    expect(card).not.toHaveClass('auth-card--ready')
    expect(loginId).toHaveValue('demo-user')
  } finally {
    stopTracking()
  }
})

test('로그인 첫 등장 완료 후에는 입력 방식 전환으로 모션을 다시 시작하지 않는다', async () => {
  const stopTracking = trackUiMotionInput()
  try {
    openAt('/login')
    await screen.findByRole('textbox', { name: '계정 ID' })
    const card = screen.getByRole('region', { name: '워크스페이스에 로그인' })
    // jsdom exposes WebKit animation styles without AnimationEvent support.
    const endEvent =
      'AnimationEvent' in window ? 'animationend' : 'webkitAnimationEnd'
    fireEvent(card, new Event(endEvent, { bubbles: true }))
    expect(card).not.toHaveClass('auth-card--ready')
    const user = await fillLogin()
    await user.click(screen.getByLabelText('계정 ID'))
    expect(card).not.toHaveClass('auth-card--ready')
  } finally {
    stopTracking()
  }
})

test('키보드로 진입한 로그인 화면은 마우스를 사용해도 첫 등장 효과를 뒤늦게 실행하지 않는다', async () => {
  const stopTracking = trackUiMotionInput()
  try {
    fireEvent.keyDown(document.body, { key: 'Tab' })
    openAt('/login')
    const loginId = await screen.findByRole('textbox', { name: '계정 ID' })
    const user = userEvent.setup()
    await user.click(loginId)
    expect(
      screen.getByRole('region', { name: '워크스페이스에 로그인' }),
    ).not.toHaveClass('auth-card--ready')
  } finally {
    stopTracking()
  }
})

test('인증 확인 중에는 보호 화면을 먼저 표시하지 않는다', async () => {
  server.use(
    http.get('/api/v1/auth/me', async () => {
      await delay(100)
      return HttpResponse.json({ data: demoAccount })
    }),
  )
  openAt('/explorer')
  expect(screen.getByRole('status')).toHaveTextContent(
    '인증 상태를 확인하는 중…',
  )
  expect(
    screen.queryByRole('heading', { name: '업무 데이터' }),
  ).not.toBeInTheDocument()
  await screen.findByRole('heading', { name: '업무 데이터' })
})

test('로그인 성공 시 원래 요청한 내부 화면으로 이동하고 이전 조회 캐시를 비운다', async () => {
  const client = openAt('/audit')
  const user = await fillLogin()
  client.setQueryData(['old-account-data'], { value: '이전 계정 데이터' })
  await user.click(screen.getByRole('button', { name: '로그인' }))
  await screen.findByRole('heading', { name: '실행 이력' })
  expect(window.location.pathname).toBe('/audit')
  expect(screen.getByText('체험 사용자')).toBeInTheDocument()
  expect(client.getQueryData(['old-account-data'])).toBeUndefined()
})

test('로그인 실패는 서버 안내를 보여주고 비밀번호를 지운다', async () => {
  openAt('/login')
  const user = await fillLogin('incorrect-test-only')
  await user.click(screen.getByRole('button', { name: '로그인' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '로그인 ID 또는 비밀번호가 올바르지 않습니다.',
  )
  expect(screen.getByLabelText('비밀번호', { exact: true })).toHaveValue('')
  expect(window.location.pathname).toBe('/login')
})

test('필수 입력이 없으면 로그인 요청을 보내지 않는다', async () => {
  const requests = vi.fn(() => HttpResponse.json({ data: demoAccount }))
  server.use(http.post('/api/v1/auth/login', requests))
  openAt('/login')
  await screen.findByRole('textbox', { name: '계정 ID' })
  await userEvent.click(screen.getByRole('button', { name: '로그인' }))
  expect(requests).not.toHaveBeenCalled()
})

test('로그인 처리 중에는 연속 클릭해도 한 번만 요청한다', async () => {
  const requests = vi.fn(async () => {
    await delay(150)
    return HttpResponse.json({ data: demoAccount })
  })
  server.use(http.post('/api/v1/auth/login', requests))
  openAt('/login')
  const user = await fillLogin()
  await user.dblClick(screen.getByRole('button', { name: '로그인' }))
  await screen.findByRole('heading', { name: '업무 데이터' })
  expect(requests).toHaveBeenCalledTimes(1)
})

test('기존 세션의 내 계정 정보로 인증 상태를 복원한다', async () => {
  setMockAccount({ ...demoAccount, displayName: '복원된 사용자' })
  openAt('/explorer')
  expect(await screen.findByText('복원된 사용자')).toBeInTheDocument()
  expect(screen.queryByLabelText('비밀번호')).not.toBeInTheDocument()
})

test('이전 계정의 늦은 변경 요청 실패가 새 로그인 세션을 만료시키지 않는다', async () => {
  const client = openAt('/login')
  const user = await fillLogin()
  let rejectPrevious!: (error: Error) => void
  const previousResult = new Promise<never>((_resolve, reject) => {
    rejectPrevious = reject
  })
  const previousMutation = client.getMutationCache().build(client, {
    mutationFn: () => previousResult,
  })
  const settled = previousMutation.execute(undefined).catch(() => undefined)
  try {
    await user.click(screen.getByRole('button', { name: '로그인' }))
    await screen.findByRole('heading', { name: '업무 데이터' })
    expect(client.getMutationCache().getAll()).not.toContain(previousMutation)
    rejectPrevious(
      new ApiError('이전 세션 만료', { status: 401, kind: 'unauthenticated' }),
    )
    await settled
    expect(client.getQueryData(['auth', 'me'])).toMatchObject({
      id: demoAccount.id,
    })
    expect(window.location.pathname).toBe('/explorer')
  } finally {
    rejectPrevious(new Error('검사 종료'))
    await settled
  }
})

test('내 계정 조회의 서버 오류를 로그인 만료로 위장하지 않는다', async () => {
  server.use(
    http.get('/api/v1/auth/me', () => new HttpResponse(null, { status: 503 })),
  )
  openAt('/explorer')
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '서버 요청을 처리하지 못했습니다.',
  )
  expect(window.location.pathname).toBe('/explorer')
  expect(
    screen.queryByRole('textbox', { name: '계정 ID' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '다시 불러오기' }),
  ).toBeInTheDocument()
})

test('보호 API의 401이면 로그인으로 이동하고 이전 계정 캐시를 제거한다', async () => {
  setMockAccount({ ...demoAccount })
  server.use(
    http.get(
      '/api/v1/audit/actions',
      () => new HttpResponse(null, { status: 401 }),
    ),
  )
  const client = openAt('/audit')
  await screen.findByRole('textbox', { name: '계정 ID' })
  expect(window.location.pathname).toBe('/login')
  expect(client.getQueryData(['auth', 'me'])).toBeNull()
  expect(client.getQueryCache().findAll({ queryKey: ['audit'] })).toHaveLength(
    0,
  )
})

test('보호 API의 403은 권한 안내를 유지하며 로그아웃하지 않는다', async () => {
  setMockAccount({ ...demoAccount })
  server.use(
    http.get(
      '/api/v1/audit/actions',
      () => new HttpResponse(null, { status: 403 }),
    ),
  )
  openAt('/audit')
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '요청이 허용되지 않았습니다.',
  )
  expect(window.location.pathname).toBe('/audit')
  await userEvent.click(screen.getByRole('button', { name: '메뉴 패널 열기' }))
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
})

test('로그아웃의 204 처리 후 캐시를 비우고 보호 주소 재진입을 막는다', async () => {
  setMockAccount({ ...demoAccount })
  const client = openAt('/explorer')
  await userEvent.click(
    await screen.findByRole('button', { name: '메뉴 패널 열기' }),
  )
  const button = await screen.findByRole('button', { name: '로그아웃' })
  await userEvent.click(button)
  await screen.findByRole('textbox', { name: '계정 ID' })
  expect(client.getQueryData(['auth', 'me'])).toBeNull()
  expect(
    client.getQueryCache().findAll({ queryKey: ['objects'] }),
  ).toHaveLength(0)
})

test('로그아웃 요청이 403이면 세션을 종료했다고 표시하지 않는다', async () => {
  setMockAccount({ ...demoAccount })
  server.use(
    http.post(
      '/api/v1/auth/logout',
      () => new HttpResponse(null, { status: 403 }),
    ),
  )
  openAt('/explorer')
  await userEvent.click(
    await screen.findByRole('button', { name: '메뉴 패널 열기' }),
  )
  await userEvent.click(await screen.findByRole('button', { name: '로그아웃' }))
  await screen.findByRole('alert')
  expect(window.location.pathname).toBe('/explorer')
  expect(screen.getByText('체험 사용자')).toBeInTheDocument()
})

test('임시 비밀번호 계정은 업무 화면 대신 변경 화면을 연다', async () => {
  setMockAccount({ ...demoAccount, mustChangePassword: true })
  openAt('/audit')
  await screen.findByRole('heading', { name: '비밀번호 변경' })
  expect(window.location.pathname).toBe('/change-password')
  expect(
    screen.queryByRole('navigation', { name: '주 메뉴' }),
  ).not.toBeInTheDocument()
})

test('비밀번호 확인 불일치는 전송하지 않고 안내한다', async () => {
  setMockAccount({ ...demoAccount, mustChangePassword: true })
  const requests = vi.fn(() => new HttpResponse(null, { status: 204 }))
  server.use(http.patch('/api/v1/auth/password', requests))
  openAt('/change-password')
  await screen.findByRole('heading', { name: '비밀번호 변경' })
  const user = userEvent.setup()
  await user.type(screen.getByLabelText('현재 비밀번호'), 'old-test-only')
  await user.type(
    screen.getByLabelText('새 비밀번호', { exact: true }),
    'new-test-only',
  )
  await user.type(
    screen.getByLabelText('새 비밀번호 확인'),
    'different-test-only',
  )
  await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
  expect(screen.getByRole('alert')).toHaveTextContent(
    '새 비밀번호 확인이 일치하지 않습니다.',
  )
  expect(requests).not.toHaveBeenCalled()
})

test('비밀번호 변경은 현재 CSRF 쿠키를 전달하고 내 계정을 재조회한다', async () => {
  setMockAccount({ ...demoAccount, mustChangePassword: true })
  let csrf: string | null = null
  server.use(
    http.patch('/api/v1/auth/password', ({ request }) => {
      csrf = request.headers.get('X-XSRF-TOKEN')
      setMockAccount({ ...demoAccount })
      return new HttpResponse(null, { status: 204 })
    }),
  )
  document.cookie = 'XSRF-TOKEN=auth-test-csrf; Path=/'
  try {
    openAt('/change-password')
    await screen.findByRole('heading', { name: '비밀번호 변경' })
    const user = userEvent.setup()
    await user.type(screen.getByLabelText('현재 비밀번호'), 'old-test-only')
    await user.type(
      screen.getByLabelText('새 비밀번호', { exact: true }),
      'new-test-only',
    )
    await user.type(screen.getByLabelText('새 비밀번호 확인'), 'new-test-only')
    await user.click(screen.getByRole('button', { name: '비밀번호 변경' }))
    await waitFor(() => expect(window.location.pathname).toBe('/explorer'))
    expect(csrf).toBe('auth-test-csrf')
  } finally {
    document.cookie = 'XSRF-TOKEN=; Path=/; Max-Age=0'
  }
})
