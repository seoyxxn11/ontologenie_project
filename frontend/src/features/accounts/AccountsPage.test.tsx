import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import App from '../../App'
import { accountFixtures } from '../../mocks/accountHandlers'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { temporaryPasswordError } from './api'

const testPassword = 'OnlyTest!2468'
function open(
  path = '/admin/accounts',
  roleCode: typeof demoAccount.roleCode = 'ADMIN',
  client = new QueryClient({ defaultOptions: { queries: { retry: false } } }),
) {
  setMockAccount({ ...demoAccount, roleCode })
  window.history.pushState({}, '', path)
  render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
  return client
}
async function fillCreate(loginId = 'viewer-test') {
  const user = userEvent.setup()
  await user.type(await screen.findByLabelText('로그인 ID'), loginId)
  await user.type(screen.getByLabelText('표시 이름'), '검사용 조회 사용자')
  await user.type(
    screen.getByLabelText('임시 비밀번호', { exact: true }),
    testPassword,
  )
  await user.type(screen.getByLabelText('임시 비밀번호 확인'), testPassword)
  return user
}

test('계정 목록을 표시하고 역할 필터·검색·초기화를 서버에 반영한다', async () => {
  const user = userEvent.setup()
  open()
  await screen.findByRole('table', { name: '계정 목록' })
  expect(screen.getAllByRole('columnheader')).toHaveLength(4)
  expect(
    screen.queryByRole('columnheader', { name: '비밀번호 변경' }),
  ).not.toBeInTheDocument()
  expect(screen.getByRole('link', { name: 'demo-viewer' })).toBeInTheDocument()
  await user.selectOptions(screen.getByLabelText('역할'), 'VIEWER')
  await user.click(screen.getByRole('button', { name: '검색' }))
  await screen.findByText('총 1건')
  expect(
    screen.queryByRole('link', { name: 'demo-admin' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '초기화' }))
  await screen.findByText('총 3건')
})

test('Viewer 계정 생성 후 임시 비밀번호를 표시·캐시하지 않고 첫 로그인 변경을 안내한다', async () => {
  const client = open('/admin/accounts/new')
  const user = await fillCreate()
  expect(screen.getByLabelText('역할')).toHaveValue('VIEWER')
  await user.click(screen.getByRole('button', { name: '계정 생성' }))
  await screen.findByRole('heading', { name: '계정을 생성했습니다.' })
  expect(screen.getByText(/첫 로그인 후 비밀번호를 변경/)).toBeInTheDocument()
  expect(document.body.textContent).not.toContain(testPassword)
  expect(
    JSON.stringify(
      client
        .getQueryCache()
        .getAll()
        .map((query) => query.state.data),
    ),
  ).not.toContain(testPassword)
  expect(client.getMutationCache().getAll()).toHaveLength(0)
  await user.click(screen.getByRole('link', { name: '계정 상세 확인' }))
  expect(await screen.findByLabelText('로그인 ID')).toHaveValue('viewer-test')
  expect(screen.getByLabelText('역할')).toHaveValue('VIEWER')
})

test('선택한 역할의 권한 개수를 표시하고 필수 입력을 알린다', async () => {
  open('/admin/accounts/new')
  const user = userEvent.setup()
  const role = await screen.findByLabelText('역할')
  expect(
    screen.getByRole('heading', { name: '조회 사용자 권한' }),
  ).toBeInTheDocument()
  expect(screen.getByText('등록된 권한 코드가 없습니다.')).toBeInTheDocument()
  await user.selectOptions(role, 'ADMIN')
  expect(
    screen.getByRole('heading', { name: '관리자 권한 1개' }),
  ).toBeInTheDocument()
  for (const label of ['로그인 ID', '표시 이름', '역할', '임시 비밀번호 확인'])
    expect(screen.getByLabelText(label)).toBeRequired()
})

test('임시 비밀번호 확인이 다르면 생성 요청을 보내지 않는다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: accountFixtures[1] }))
  server.use(http.post('/api/v1/admin/accounts', request))
  open('/admin/accounts/new')
  const user = await fillCreate()
  await user.clear(screen.getByLabelText('임시 비밀번호 확인'))
  await user.type(screen.getByLabelText('임시 비밀번호 확인'), 'Mismatch!2468')
  await user.click(screen.getByRole('button', { name: '계정 생성' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '확인이 일치하지 않습니다.',
  )
  expect(request).not.toHaveBeenCalled()
})

test('중복 ID 생성 실패 시 다른 입력은 보존하고 비밀번호만 지운다', async () => {
  open('/admin/accounts/new')
  const user = await fillCreate('demo-viewer')
  await user.click(screen.getByRole('button', { name: '계정 생성' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '이미 등록된 로그인 ID',
  )
  expect(screen.getByLabelText('로그인 ID')).toHaveValue('demo-viewer')
  expect(screen.getByLabelText('임시 비밀번호', { exact: true })).toHaveValue(
    '',
  )
  expect(screen.getByLabelText('임시 비밀번호 확인')).toHaveValue('')
})

test('생성 제출 중 비밀번호를 지우고 중복 요청을 막는다', async () => {
  const request = vi.fn(async () => {
    await delay(150)
    return HttpResponse.json(
      { data: { ...accountFixtures[1], id: 20, mustChangePassword: true } },
      { status: 201 },
    )
  })
  server.use(http.post('/api/v1/admin/accounts', request))
  open('/admin/accounts/new')
  await fillCreate()
  const form = screen
    .getByRole('button', { name: '계정 생성' })
    .closest('form')!
  fireEvent.submit(form)
  fireEvent.submit(form)
  expect(screen.getByLabelText('임시 비밀번호', { exact: true })).toHaveValue(
    '',
  )
  await screen.findByRole('heading', { name: '계정을 생성했습니다.' })
  expect(request).toHaveBeenCalledTimes(1)
})

test('수정한 필드와 기준 버전만 전송한다', async () => {
  const request = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      roleCode: 'MAINTENANCE',
      rowVersion: 0,
    })
    return HttpResponse.json({
      data: { ...accountFixtures[1], roleCode: 'MAINTENANCE', rowVersion: 1 },
    })
  })
  server.use(http.patch('/api/v1/admin/accounts/:id', request))
  open('/admin/accounts/8')
  const user = userEvent.setup()
  await screen.findByLabelText('로그인 ID')
  await user.selectOptions(screen.getByLabelText('역할'), 'MAINTENANCE')
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  await screen.findByRole('heading', { name: '계정 정보를 변경했습니다.' })
  expect(request).toHaveBeenCalledTimes(1)
})

test('버전 충돌은 입력을 보존하고 최신 정보 재조회 전 저장을 막는다', async () => {
  server.use(
    http.patch('/api/v1/admin/accounts/:id', () =>
      HttpResponse.json(
        {
          error: {
            code: 'VERSION_CONFLICT',
            message: '먼저 수정된 계정입니다.',
            details: {},
            requestId: 'account-test',
          },
        },
        { status: 409 },
      ),
    ),
  )
  open('/admin/accounts/8')
  const user = userEvent.setup()
  const name = await screen.findByLabelText('표시 이름')
  await user.clear(name)
  await user.type(name, '내 수정 내용')
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('먼저 수정된 계정')
  expect(name).toHaveValue('내 수정 내용')
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeDisabled()
  await user.click(
    screen.getByRole('button', { name: '최신 정보 다시 조회·입력 초기화' }),
  )
  expect(await screen.findByLabelText('표시 이름')).toHaveValue('조회 담당')
})

test('마지막 관리자 변경 거절과 본인 세션 종료 확인을 처리한다', async () => {
  open('/admin/accounts/7')
  const user = userEvent.setup()
  await screen.findByLabelText('역할')
  await user.selectOptions(screen.getByLabelText('역할'), 'VIEWER')
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent('세션 종료 안내')
  await user.click(screen.getByRole('checkbox'))
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '마지막 활성 관리자',
  )
  expect(screen.getByRole('button', { name: '변경 저장' })).toBeEnabled()
})

test('본인 역할 변경으로 서버 세션이 종료되면 로그인 화면으로 이동한다', async () => {
  server.use(
    http.patch('/api/v1/admin/accounts/:id', () => {
      setMockAccount(null)
      return HttpResponse.json({
        data: { ...accountFixtures[0], roleCode: 'VIEWER', rowVersion: 1 },
      })
    }),
  )
  open('/admin/accounts/7')
  const user = userEvent.setup()
  await screen.findByLabelText('역할')
  await user.selectOptions(screen.getByLabelText('역할'), 'VIEWER')
  await user.click(screen.getByRole('checkbox'))
  await user.click(screen.getByRole('button', { name: '변경 저장' }))
  await screen.findByRole('heading', { name: '워크스페이스에 로그인' })
  expect(window.location.pathname).toBe('/login')
  expect(
    screen.queryByRole('button', { name: '변경 저장' }),
  ).not.toBeInTheDocument()
})

test('역할 조회 실패 시 계정 생성 입력창을 열지 않는다', async () => {
  server.use(http.get('/api/v1/admin/roles', () => HttpResponse.error()))
  open('/admin/accounts/new')
  await screen.findByRole('alert')
  expect(
    screen.queryByLabelText('임시 비밀번호', { exact: true }),
  ).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '계정 생성' }),
  ).not.toBeInTheDocument()
})

test('이전 캐시가 있어도 최신 상세 조회 완료 전 편집을 허용하지 않는다', async () => {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, staleTime: Infinity } },
  })
  client.setQueryData(['accounts', 'editor', 8, 0], {
    account: accountFixtures[1],
    roles: [],
  })
  server.use(
    http.get('/api/v1/admin/accounts/:id', async () => {
      await delay(100)
      return HttpResponse.json({
        data: {
          ...accountFixtures[1],
          displayName: '최신 이름',
          rowVersion: 2,
        },
      })
    }),
  )
  open('/admin/accounts/8', 'ADMIN', client)
  await screen.findByText('최신 계정·역할 정보를 불러오는 중…')
  expect(screen.queryByLabelText('표시 이름')).not.toBeInTheDocument()
  expect(await screen.findByLabelText('표시 이름')).toHaveValue('최신 이름')
})

test.each([403, 404, 500])(
  '계정 상세 HTTP %s 오류를 표시한다',
  async (status) => {
    server.use(
      http.get('/api/v1/admin/accounts/:id', () =>
        HttpResponse.json(
          {
            error: {
              code: 'TEST_ERROR',
              message: '검사용 조회 실패',
              details: {},
              requestId: 'account-test',
            },
          },
          { status },
        ),
      ),
    )
    open('/admin/accounts/8')
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '검사용 조회 실패',
    )
    expect(
      screen.queryByRole('button', { name: '변경 저장' }),
    ).not.toBeInTheDocument()
  },
)

test('잘못된 상세 ID는 조회 요청 없이 안내한다', async () => {
  const request = vi.fn(() => HttpResponse.json({ data: accountFixtures[1] }))
  server.use(http.get('/api/v1/admin/accounts/:id', request))
  open('/admin/accounts/not-an-id')
  await screen.findByText('올바른 계정 주소가 아닙니다.')
  expect(request).not.toHaveBeenCalled()
})

test('Viewer는 계정 생성 주소에서도 화면과 관리 API에 접근하지 못한다', async () => {
  const roles = vi.fn(() => HttpResponse.json({ data: [] }))
  server.use(http.get('/api/v1/admin/roles', roles))
  open('/admin/accounts/new', 'VIEWER')
  await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })
  expect(roles).not.toHaveBeenCalled()
})

test('계정 목록의 빈 결과는 오류와 구별한다', async () => {
  server.use(
    http.get('/api/v1/admin/accounts', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      }),
    ),
  )
  open()
  await screen.findByText('조건에 맞는 계정이 없습니다.')
  expect(screen.queryByRole('alert')).not.toBeInTheDocument()
})

test('계정 목록의 다음 페이지 번호를 서버에 전달한다', async () => {
  const pages: string[] = []
  server.use(
    http.get('/api/v1/admin/accounts', ({ request }) => {
      const page = new URL(request.url).searchParams.get('page') ?? '0'
      pages.push(page)
      return HttpResponse.json({
        data: [accountFixtures[1]],
        meta: {
          page: Number(page),
          size: 20,
          totalElements: 21,
          hasNext: page === '0',
        },
      })
    }),
  )
  open()
  await screen.findByRole('table')
  await userEvent.click(screen.getByRole('button', { name: '다음 페이지' }))
  await screen.findByText('2 / 2쪽 · 21건')
  expect(pages).toEqual(['0', '1'])
  expect(
    within(screen.getByRole('navigation', { name: '페이지 이동' })).getByRole(
      'button',
      { name: '다음 페이지' },
    ),
  ).toBeDisabled()
})

test('임시 비밀번호 정책은 문자 수·바이트·영문·숫자·특수문자·ID 일치를 검사한다', () => {
  for (const password of [
    'short',
    'password123',
    'A!'.repeat(40),
    '가'.repeat(25) + 'A1!',
  ])
    expect(temporaryPasswordError('viewer', password)).not.toBe('')
  expect(temporaryPasswordError(testPassword, testPassword)).not.toBe('')
  expect(temporaryPasswordError('viewer', testPassword)).toBe('')
})
