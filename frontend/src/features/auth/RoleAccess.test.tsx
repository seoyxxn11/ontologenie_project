import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, cleanup, render, screen, waitFor } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { afterEach, expect, test, vi } from 'vitest'
import App from '../../App'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { canAccess } from './access'
import AuthProvider from './AuthProvider'
import PermissionGate from './PermissionGate'
import type { Account } from './types'

const roles: Account['roleCode'][] = [
  'ADMIN',
  'PLANNER',
  'MAINTENANCE',
  'VIEWER',
]
const nonAdminRoles: Account['roleCode'][] = [
  'PLANNER',
  'MAINTENANCE',
  'VIEWER',
]
const adminPages = [
  ['/admin/sources', '데이터 연결'],
  ['/admin/rules', '연결 규칙'],
  ['/admin/history', '정의·규칙 이력'],
  ['/admin/accounts', '계정·권한'],
] as const

const clients: QueryClient[] = []

afterEach(async () => {
  cleanup()
  await Promise.all(clients.map((client) => client.cancelQueries()))
  for (const client of clients) client.clear()
  clients.length = 0
})

function openAt(path: string, roleCode: Account['roleCode']) {
  setMockAccount({ ...demoAccount, roleCode })
  window.history.pushState({}, '', path)
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  render(
    <QueryClientProvider client={client}>
      <App />
    </QueryClientProvider>,
  )
  return client
}

test.each(roles)(
  '%s는 업무 메뉴와 본인 실행 이력 메뉴를 사용할 수 있다',
  async (role) => {
    const client = openAt('/explorer', role)
    await screen.findByRole('heading', { name: '업무 데이터' })
    for (const name of ['업무 데이터', 'AI 업무 도우미', '실행 이력']) {
      expect(screen.getByRole('link', { name })).toBeInTheDocument()
    }
    for (const [, name] of adminPages) {
      if (role === 'ADMIN')
        expect(screen.getByRole('link', { name })).toBeInTheDocument()
      else expect(screen.queryByRole('link', { name })).not.toBeInTheDocument()
    }
    await waitFor(() => expect(client.isFetching()).toBe(0))
  },
)

test.each(
  nonAdminRoles.flatMap((role) =>
    adminPages.map(([path]) => [role, path] as const),
  ),
)(
  '%s가 %s로 직접 진입해도 화면과 관리 API를 열지 않는다',
  async (role, path) => {
    const sources = vi.fn(() => HttpResponse.json({ data: [] }))
    const changes = vi.fn(() => HttpResponse.json({ data: [] }))
    server.use(
      http.get('/api/v1/sources', sources),
      http.get('/api/v1/ontology/changes', changes),
    )
    openAt(path, role)
    await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })
    expect(window.location.pathname).toBe(path)
    expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: '업무 데이터로 돌아가기' }),
    ).toHaveAttribute('href', '/explorer')
    expect(sources).not.toHaveBeenCalled()
    expect(changes).not.toHaveBeenCalled()
  },
)

test.each(adminPages)(
  'ADMIN은 %s의 관리 화면을 사용할 수 있다',
  async (path, title) => {
    const client = openAt(path, 'ADMIN')
    await screen.findByRole('heading', { name: title })
    expect(
      screen.queryByRole('heading', { name: '접근 권한이 없습니다.' }),
    ).not.toBeInTheDocument()
    await waitFor(() => expect(client.isFetching()).toBe(0))
  },
)

test('역할을 새로 조회해 ADMIN에서 VIEWER로 바뀌면 관리 화면·메뉴·이전 캐시를 제거한다', async () => {
  const historyRequest = vi.fn(() =>
    HttpResponse.json({
      data: [
        {
          id: 991,
          actionExecutionId: 992,
          tableName: 'object_type',
          rowKey: { id: 993 },
          changeKind: 'UPDATE',
          beforeData: { name: '수정 전' },
          afterData: { name: '수정 후' },
          actorId: 7,
          actorType: 'USER',
          requestedAt: '2026-11-03T00:00:00Z',
          actionCode: 'UPDATE_OBJECT_TYPE',
        },
      ],
      meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
    }),
  )
  server.use(http.get('/api/v1/ontology/changes', historyRequest))
  const client = openAt('/admin/history', 'ADMIN')
  await screen.findByText('1건')
  expect(historyRequest).toHaveBeenCalledTimes(1)
  client.setQueryData(['restricted-record'], {
    secret: '검사용 이전 역할 데이터',
  })
  setMockAccount({ ...demoAccount, roleCode: 'VIEWER' })
  await act(async () => {
    await client.refetchQueries({ queryKey: ['auth', 'me'] })
  })
  await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })
  expect(
    screen.queryByRole('link', { name: '정의·규칙 이력' }),
  ).not.toBeInTheDocument()
  expect(screen.queryByText('1건')).not.toBeInTheDocument()
  expect(client.getQueryData(['restricted-record'])).toBeUndefined()
  expect(
    client.getQueriesData({ queryKey: ['ontology', 'changes'] }),
  ).toHaveLength(0)
})

test('관리자라도 서버가 403을 반환하면 실패 안내를 표시하고 세션은 유지한다', async () => {
  server.use(
    http.get('/api/v1/sources', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '서버에서 원천 조회를 허용하지 않았습니다.',
            details: {},
            requestId: 'role-test',
          },
        },
        { status: 403 },
      ),
    ),
  )
  openAt('/admin/sources', 'ADMIN')
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '서버에서 원천 조회를 허용하지 않았습니다.',
  )
  expect(screen.getByRole('button', { name: '로그아웃' })).toBeInTheDocument()
  expect(window.location.pathname).toBe('/admin/sources')
})

test.each(roles)(
  '%s의 관리 버튼도 동일한 접근 기준을 따른다',
  async (roleCode) => {
    setMockAccount({ ...demoAccount, roleCode })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    clients.push(client)
    render(
      <QueryClientProvider client={client}>
        <AuthProvider>
          <PermissionGate access="administration">
            <button type="button">관리 작업</button>
          </PermissionGate>
        </AuthProvider>
      </QueryClientProvider>,
    )
    await waitFor(() =>
      expect(client.getQueryData(['auth', 'me'])).toMatchObject({ roleCode }),
    )
    if (roleCode === 'ADMIN')
      expect(
        await screen.findByRole('button', { name: '관리 작업' }),
      ).toBeInTheDocument()
    else
      expect(
        screen.queryByRole('button', { name: '관리 작업' }),
      ).not.toBeInTheDocument()
  },
)

test('인증이 없거나 임시 비밀번호·비활성·미등록 역할이면 권한을 부여하지 않는다', () => {
  const accounts = [
    null,
    undefined,
    { ...demoAccount, mustChangePassword: true },
    { ...demoAccount, status: 'LOCKED' as const },
    { ...demoAccount, status: 'DISABLED' as const },
    { ...demoAccount, roleCode: 'UNKNOWN' as Account['roleCode'] },
  ]
  for (const account of accounts) {
    expect(canAccess(account, 'workspace')).toBe(false)
    expect(canAccess(account, 'administration')).toBe(false)
  }
})

test('미등록 역할은 보호 화면 API를 호출하지 않는다', async () => {
  const objects = vi.fn(() => HttpResponse.json({ data: [] }))
  server.use(http.get('/api/v1/objects', objects))
  openAt('/explorer', 'UNKNOWN' as Account['roleCode'])
  await screen.findByRole('heading', { name: '접근 권한이 없습니다.' })
  expect(objects).not.toHaveBeenCalled()
  expect(
    screen.queryByRole('link', { name: '업무 데이터로 돌아가기' }),
  ).not.toBeInTheDocument()
})
