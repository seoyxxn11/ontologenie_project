import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, Link, RouterProvider } from 'react-router'
import { expect, onTestFinished, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import ExplorerPage from './ExplorerPage'
import { positiveId, readSearchState, writeSearchState } from './searchState'

const objects = [301, 302].map((id) => ({
  id,
  objectTypeId: 3,
  objectTypeName: '설비',
  displayName: id === 301 ? 'P101' : 'P103',
  properties: { STATUS: '가동', PRICE: 100000 },
  propertyNames: { STATUS: '상태', PRICE: '가격' },
  restrictedPropertyCodes: ['PRICE'],
  status: 'ACTIVE',
  firstCollectedAt: '2026-11-01T00:00:00Z',
  sourceKey: { equipment_code: id === 301 ? 'P101' : 'P103' },
}))
function fixtures() {
  const calls = vi.fn(({ request }: { request: Request }) => {
    const page = Number(new URL(request.url).searchParams.get('page'))
    return HttpResponse.json({
      data: objects,
      meta: { page, size: 8, totalElements: 24, hasNext: page < 2 },
    })
  })
  server.use(
    http.get('/api/v1/objects', calls),
    http.get('/api/v1/objects/:id', ({ params }) => {
      const object = objects.find((o) => o.id === Number(params.id))
      if (object) return HttpResponse.json({ data: object })
    }),
    http.get('/api/v1/objects/:id/link-groups', () =>
      HttpResponse.json({ data: [] }),
    ),
    http.get('/api/v1/ontology/object-types/3/properties', () =>
      HttpResponse.json({
        data: [{ id: 31, code: 'STATUS', name: '상태', dataType: 'STRING' }],
      }),
    ),
  )
  return calls
}
function open(path = '/explorer') {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const router = createMemoryRouter(
    [
      {
        path: '/explorer',
        element: (
          <>
            <ExplorerPage />
            <Link to="/audit">다른 화면</Link>
          </>
        ),
      },
      { path: '/audit', element: <p>실행 이력 화면</p> },
    ],
    { initialEntries: ['/audit', path], initialIndex: 1 },
  )
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { router, client, ...view }
}
const detail = () => within(screen.getByRole('region', { name: '상세 정보' }))

test.each([false, true])(
  '연결 목록 자동 보정 후 실패=%s여도 이전 화면 기록을 추가하지 않는다',
  async (failCorrectedPage) => {
    fixtures()
    server.use(
      http.get('/api/v1/objects/301/link-groups', () =>
        HttpResponse.json({
          data: [
            {
              linkTypeId: 7,
              linkTypeName: '발생 설비',
              relatedObjectTypeId: 19,
              relatedObjectTypeName: '불량 기록',
              direction: 'IN',
              linkCount: 3,
            },
          ],
        }),
      ),
      http.get('/api/v1/objects/301/links', ({ request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        if (page === 0 && failCorrectedPage)
          return HttpResponse.json(
            { error: { code: 'INTERNAL_ERROR', message: '연결 조회 실패' } },
            { status: 500 },
          )
        return HttpResponse.json({
          data: [],
          meta: { page, size: 8, totalElements: 3, hasNext: false },
        })
      }),
    )
    const { router, client } = open(
      '/explorer?object=301&anchor=301&relation=7:in&relatedPage=2',
    )
    await waitFor(() =>
      expect(router.state.location.search).not.toContain('relatedPage'),
    )
    await waitFor(() => expect(client.isFetching()).toBe(0))
    if (failCorrectedPage)
      expect(await screen.findByText('연결 조회 실패')).toBeVisible()
    await act(async () => {
      await router.navigate(-1)
    })
    expect(await screen.findByText('실행 이력 화면')).toBeVisible()
    expect(router.state.location.pathname).toBe('/audit')
  },
)

test('주소의 검색어·유형·속성·페이지를 복원하고 새 컴포넌트에서도 같은 요청과 상세를 연다', async () => {
  const calls = fixtures()
  const path =
    '/explorer?q=P&objectTypeId=3&propertyValues%5BSTATUS%5D=가동&page=1&object=301'
  const first = open(path)
  expect(await detail().findByRole('heading', { name: 'P101' })).toBeVisible()
  expect(screen.getByLabelText('ID · 이름 검색')).toHaveValue('P')
  await waitFor(() =>
    expect(screen.getByLabelText('객체 유형')).toHaveValue('3'),
  )
  await waitFor(() =>
    expect(screen.getByLabelText('속성 조건')).toHaveValue('STATUS'),
  )
  expect(screen.getByLabelText('속성 값 · 정확 일치')).toHaveValue('가동')
  expect(
    screen.getByRole('button', { name: '2페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
  const params = new URL(calls.mock.calls[0][0].request.url).searchParams
  expect(Object.fromEntries(params)).toMatchObject({
    q: 'P',
    objectTypeId: '3',
    'propertyValues[STATUS]': '가동',
    page: '1',
  })
  await waitFor(() => expect(first.client.isFetching()).toBe(0))
  first.unmount()
  open(path)
  expect(await detail().findByRole('heading', { name: 'P101' })).toBeVisible()
  expect(
    screen.getByRole('button', { name: '2페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
})

test('P101에서 P103 선택 후 뒤로·앞으로 이동하고 다른 화면 복귀에도 검색·페이지를 유지한다', async () => {
  fixtures()
  const { router } = open('/explorer?q=P&page=1&object=301')
  const user = userEvent.setup()
  await detail().findByRole('heading', { name: 'P101' })
  await user.click(
    within(screen.getByRole('region', { name: '객체 목록' })).getByRole(
      'button',
      { name: /P103/ },
    ),
  )
  expect(await detail().findByRole('heading', { name: 'P103' })).toBeVisible()
  await act(async () => {
    await router.navigate(-1)
  })
  expect(await detail().findByRole('heading', { name: 'P101' })).toBeVisible()
  expect(router.state.location.search).toBe('?q=P&page=1&object=301')
  await act(async () => {
    await router.navigate(1)
  })
  await detail().findByRole('heading', { name: 'P103' })
  await user.click(screen.getByRole('link', { name: '다른 화면' }))
  expect(await screen.findByText('실행 이력 화면')).toBeVisible()
  await act(async () => {
    await router.navigate(-1)
  })
  expect(await detail().findByRole('heading', { name: 'P103' })).toBeVisible()
  expect(screen.getByLabelText('ID · 이름 검색')).toHaveValue('P')
  expect(
    screen.getByRole('button', { name: '2페이지로 이동' }),
  ).toHaveAttribute('aria-current', 'page')
})

test.each(['ADMIN', 'VIEWER'] as const)(
  '%s는 상세 바로 보기에서 확장을 해제하고 가림을 유지한 상세에 포커스를 둔다',
  async (role) => {
    fixtures()
    setMockAccount({ ...demoAccount, roleCode: role })
    const original = Object.getOwnPropertyDescriptor(
      HTMLElement.prototype,
      'scrollIntoView',
    )
    const scroll = vi.fn()
    Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', {
      configurable: true,
      value: scroll,
    })
    onTestFinished(() => {
      if (original)
        Object.defineProperty(HTMLElement.prototype, 'scrollIntoView', original)
      else
        delete (HTMLElement.prototype as unknown as Record<string, unknown>)
          .scrollIntoView
    })
    open('/explorer?object=301')
    const user = userEvent.setup()
    await detail().findByRole('heading', { name: 'P101' })
    expect(detail().getByText('권한 없음')).toBeVisible()
    expect(screen.queryByText('100000')).not.toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: '관계도 확장' }))
    await user.click(
      screen.getByRole('button', { name: '선택 객체 상세 바로 보기' }),
    )
    await waitFor(() =>
      expect(screen.getByRole('region', { name: '상세 정보' })).toHaveFocus(),
    )
    expect(scroll).toHaveBeenCalled()
    expect(screen.getByRole('button', { name: '관계도 확장' })).toHaveAttribute(
      'aria-pressed',
      'false',
    )
  },
)

test('잘못된 주소의 ID·페이지는 기본값으로 처리하며 별도 응답 저장소를 만들지 않는다', () => {
  for (const invalid of [
    '-1',
    'NaN',
    'Infinity',
    '1.2',
    '1e3',
    '9007199254740992',
  ])
    expect(positiveId(invalid)).toBeNull()
  expect(
    readSearchState(
      new URLSearchParams('objectTypeId=-1&page=NaN&propertyValues[X]=secret'),
    ),
  ).toEqual({ filters: {}, page: 0 })
  const original = new URLSearchParams(
    'object=301&anchor=301&relation=2:in&q=old&page=2',
  )
  const next = writeSearchState(
    original,
    { q: '새 검색', objectTypeId: 3, propertyValues: { STATUS: '가동' } },
    0,
  )
  expect(next.get('object')).toBe('301')
  expect(next.get('q')).toBe('새 검색')
  expect(next.has('page')).toBe(false)
  expect(original.get('q')).toBe('old')
})

test('조회 역할의 속성·상세 권한 오류를 자동 재시도하거나 가려진 값으로 우회하지 않는다', async () => {
  fixtures()
  setMockAccount({ ...demoAccount, roleCode: 'VIEWER' })
  const denied = vi.fn(() =>
    HttpResponse.json(
      { error: { code: 'FORBIDDEN', message: '조회할 권한이 없습니다.' } },
      { status: 403 },
    ),
  )
  server.use(
    http.get('/api/v1/objects/301', denied),
    http.get('/api/v1/ontology/object-types/3/properties', denied),
  )
  const { client } = open(
    '/explorer?object=301&objectTypeId=3&propertyValues%5BSTATUS%5D=가동',
  )
  expect(
    await screen.findByText('이 계정은 속성 목록 조회 권한이 없습니다.'),
  ).toBeVisible()
  expect(await detail().findByText('조회할 권한이 없습니다.')).toBeVisible()
  await waitFor(() => expect(client.isFetching()).toBe(0))
  expect(denied).toHaveBeenCalledTimes(2)
  expect(
    detail().queryByRole('heading', { name: 'P101' }),
  ).not.toBeInTheDocument()
  expect(screen.queryByText('100000')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '검색' })).toBeDisabled()
})
