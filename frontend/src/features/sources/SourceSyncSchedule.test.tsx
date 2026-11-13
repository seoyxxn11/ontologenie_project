import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { createMemoryRouter, Link, RouterProvider } from 'react-router'
import { server } from '../../mocks/node'
import SourceSyncSchedule from './SourceSyncSchedule'
import type { SyncSchedule } from './scheduleApi'
import FormSaveStatus from './FormSaveStatus'

function Navigation() {
  return (
    <nav>
      <Link to="/?tab=connection">탭 이동</Link>
      <Link to="/?source=2">원천 이동</Link>
    </nav>
  )
}

function open(status = 200, initial?: SyncSchedule) {
  const state = {
    schedule:
      initial ??
      ({
        syncEnabled: false,
        syncIntervalMinutes: null,
        rowVersion: 7,
      } as SyncSchedule),
  }
  const get = vi.fn(() =>
    status === 200
      ? HttpResponse.json({ data: state.schedule })
      : HttpResponse.json(
          { error: { code: 'FORBIDDEN', message: '조회 권한 없음' } },
          { status },
        ),
  )
  server.use(http.get('/api/v1/sources/1/sync-schedule', get))
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const router = createMemoryRouter(
    [
      {
        path: '/',
        element: (
          <>
            <Navigation />
            <FormSaveStatus />
            <SourceSyncSchedule sourceId={1} />
          </>
        ),
      },
      {
        path: '/other',
        element: (
          <>
            <FormSaveStatus />
            <p>다른 화면</p>
          </>
        ),
      },
    ],
    { initialEntries: ['/other', '/'], initialIndex: 1 },
  )
  render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { state, get, client, router }
}
async function edit(minutes = '15') {
  const user = userEvent.setup()
  await user.click(
    await screen.findByRole('checkbox', { name: '자동 수집 사용' }),
  )
  await user.type(
    screen.getByRole('spinbutton', { name: '수집 주기(분)' }),
    minutes,
  )
  return user
}

test('071 최신 버전을 사용해 072로 저장하고 재조회하며 중복 제출을 막는다', async () => {
  const { state, get } = open()
  const put = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      syncEnabled: true,
      syncIntervalMinutes: 15,
      rowVersion: 7,
    })
    await delay(60)
    state.schedule = {
      syncEnabled: true,
      syncIntervalMinutes: 15,
      rowVersion: 8,
    }
    return HttpResponse.json({ data: state.schedule })
  })
  server.use(http.put('/api/v1/sources/1/sync-schedule', put))
  const user = await edit()
  await user.dblClick(screen.getByRole('button', { name: '수집 주기 저장' }))
  expect(await screen.findByText('수집 주기를 저장했습니다.')).toBeVisible()
  expect(screen.getByRole('spinbutton')).toHaveValue(15)
  expect(put).toHaveBeenCalledTimes(1)
  expect(get.mock.calls.length).toBeGreaterThan(1)
  expect(
    screen.getByText(/CDC 변경 반영 간격을 바꾸는 설정이 아닙니다/),
  ).toBeVisible()
})

test('자동 수집을 끄면 비어 있는 주기를 null로 저장한다', async () => {
  open()
  const put = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      syncEnabled: false,
      syncIntervalMinutes: null,
      rowVersion: 7,
    })
    return HttpResponse.json({
      data: { syncEnabled: false, syncIntervalMinutes: null, rowVersion: 7 },
    })
  })
  server.use(http.put('/api/v1/sources/1/sync-schedule', put))
  const user = userEvent.setup()
  await user.click(
    await screen.findByRole('button', { name: '수집 주기 저장' }),
  )
  await screen.findByText('수집 주기를 저장했습니다.')
  expect(put).toHaveBeenCalledTimes(1)
})

test('409 이후에는 최신 설정을 명시적으로 다시 읽어야 저장할 수 있다', async () => {
  const { state } = open()
  const put = vi.fn(() =>
    HttpResponse.json(
      { error: { code: 'VERSION_CONFLICT', message: '버전 충돌' } },
      { status: 409 },
    ),
  )
  server.use(http.put('/api/v1/sources/1/sync-schedule', put))
  const user = await edit()
  await user.click(screen.getByRole('button', { name: '수집 주기 저장' }))
  await screen.findByText('버전 충돌')
  expect(screen.getByRole('button', { name: '수집 주기 저장' })).toBeDisabled()
  expect(screen.getByRole('spinbutton')).toHaveValue(15)
  state.schedule = { syncEnabled: true, syncIntervalMinutes: 30, rowVersion: 9 }
  await user.click(
    screen.getByRole('button', { name: '최신 설정 다시 불러오기' }),
  )
  await waitFor(() => expect(screen.getByRole('spinbutton')).toHaveValue(30))
  expect(screen.getByRole('button', { name: '수집 주기 저장' })).toBeEnabled()
  expect(put).toHaveBeenCalledTimes(1)
})

test.each([401, 403, 404, 500, 204])(
  '저장 %s 오류·응답 유실은 성공으로 표시하거나 자동 재전송하지 않는다',
  async (status) => {
    open()
    const put = vi.fn(() =>
      status === 204
        ? new HttpResponse(null, { status })
        : HttpResponse.json(
            { error: { code: 'SAVE_ERROR', message: '저장 실패' } },
            { status },
          ),
    )
    server.use(http.put('/api/v1/sources/1/sync-schedule', put))
    const user = await edit()
    await user.click(screen.getByRole('button', { name: '수집 주기 저장' }))
    await within(
      screen.getByRole('region', { name: '자동 수집 주기' }),
    ).findByRole('alert')
    expect(
      screen.queryByText('수집 주기를 저장했습니다.'),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('button', { name: '수집 주기 저장' }),
    ).toBeDisabled()
    expect(put).toHaveBeenCalledTimes(1)
  },
)

test.each([400, 422])(
  '입력 오류 %s는 입력을 유지하며 수정할 수 있다',
  async (status) => {
    open()
    server.use(
      http.put('/api/v1/sources/1/sync-schedule', () =>
        HttpResponse.json(
          { error: { code: 'INVALID', message: '주기를 확인해 주세요.' } },
          { status },
        ),
      ),
    )
    const user = await edit()
    await user.click(screen.getByRole('button', { name: '수집 주기 저장' }))
    await within(
      screen.getByRole('region', { name: '자동 수집 주기' }),
    ).findByRole('alert')
    expect(screen.getByRole('spinbutton')).toHaveValue(15)
    expect(screen.getByRole('spinbutton')).toBeEnabled()
  },
)

test('조회 권한이 없으면 저장 가능한 설정을 표시하지 않는다', async () => {
  open(403)
  await screen.findByRole('alert')
  expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  expect(
    screen.queryByRole('button', { name: '수집 주기 저장' }),
  ).not.toBeInTheDocument()
})

test('저장 중 네트워크가 끊기면 결과 미확인으로 잠그고 입력을 유지한다', async () => {
  open()
  const put = vi.fn(() => HttpResponse.error())
  server.use(http.put('/api/v1/sources/1/sync-schedule', put))
  const user = await edit()
  await user.click(screen.getByRole('button', { name: '수집 주기 저장' }))
  await within(
    screen.getByRole('region', { name: '자동 수집 주기' }),
  ).findByRole('alert')
  expect(screen.getByRole('spinbutton')).toHaveValue(15)
  expect(screen.getByRole('button', { name: '수집 주기 저장' })).toBeDisabled()
  expect(put).toHaveBeenCalledTimes(1)
})

test('같은 주기를 015로 저장해 버전이 유지되어도 성공한 입력에 폐기 경고를 띄우지 않는다', async () => {
  const initial = { syncEnabled: true, syncIntervalMinutes: 15, rowVersion: 7 }
  const { router } = open(200, initial)
  const put = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual(initial)
    return HttpResponse.json({ data: initial })
  })
  server.use(http.put('/api/v1/sources/1/sync-schedule', put))
  const user = userEvent.setup()
  const input = await screen.findByRole('spinbutton')
  await user.clear(input)
  await user.type(input, '015')
  await user.click(screen.getByRole('button', { name: '수집 주기 저장' }))
  await screen.findByText('수집 주기를 저장했습니다.')
  await waitFor(() =>
    expect(
      screen.getByRole('button', { name: '수집 주기 저장' }),
    ).toBeEnabled(),
  )
  const unload = new Event('beforeunload', { cancelable: true })
  await act(async () => {
    window.dispatchEvent(unload)
  })
  expect(unload.defaultPrevented).toBe(false)
  await act(async () => {
    await router.navigate('/other')
  })
  expect(await screen.findByText('다른 화면')).toBeVisible()
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(put).toHaveBeenCalledTimes(1)
})

test.each(['탭', '원천'])(
  '미저장 입력은 %s 이동 폐기 확인과 브라우저 이탈 경고로 보호한다',
  async (destination) => {
    const { router } = open()
    const user = await edit()
    const unload = new Event('beforeunload', { cancelable: true })
    await act(async () => {
      window.dispatchEvent(unload)
    })
    expect(unload.defaultPrevented).toBe(true)
    const navigation = screen.getByRole('link', { name: `${destination} 이동` })
    await act(async () => {
      await user.click(navigation)
    })
    expect(
      await screen.findByRole('dialog', { name: '변경사항을 버릴까요?' }),
    ).toBeVisible()
    await act(async () => {
      await user.click(screen.getByRole('button', { name: '계속 편집' }))
    })
    expect(screen.getByRole('spinbutton')).toHaveValue(15)
    expect(router.state.location.pathname).toBe('/')
    await act(async () => {
      await user.click(navigation)
    })
    await act(async () => {
      const moved = new Promise<void>((resolve) => {
        const unsubscribe = router.subscribe((state) => {
          if (state.location.pathname !== '/' || state.location.search !== '') {
            unsubscribe()
            resolve()
          }
        })
      })
      await user.click(screen.getByRole('button', { name: '변경사항 버리기' }))
      await moved
    })
    await waitFor(() =>
      expect(
        router.state.location.pathname + router.state.location.search,
      ).toBe(destination === '탭' ? '/?tab=connection' : '/?source=2'),
    )
  },
)

test.each([200, 409, 500])(
  '저장 중 이동 후 복귀해도 중복 요청과 %s 결과를 추적한다',
  async (status) => {
    const { router, state } = open()
    let finish!: () => void
    const released = new Promise<void>((resolve) => {
      finish = resolve
    })
    const put = vi.fn(async () => {
      await released
      if (status !== 200)
        return HttpResponse.json(
          { error: { code: 'SAVE_ERROR', message: '지연 저장 실패' } },
          { status },
        )
      state.schedule = {
        syncEnabled: true,
        syncIntervalMinutes: 15,
        rowVersion: 8,
      }
      return HttpResponse.json({ data: state.schedule })
    })
    server.use(http.put('/api/v1/sources/1/sync-schedule', put))
    const user = await edit()
    await user.click(screen.getByRole('button', { name: '수집 주기 저장' }))
    await waitFor(() => expect(put).toHaveBeenCalledTimes(1))
    await act(async () => {
      await router.navigate('/other')
    })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(
      screen.getByText(/창을 닫아도 요청은 취소되지 않습니다/),
    ).toBeVisible()
    await act(async () => {
      await router.navigate('/')
    })
    expect(await screen.findByRole('spinbutton')).toHaveValue(15)
    expect(screen.getByRole('button', { name: '저장 중…' })).toBeDisabled()
    await act(async () => {
      await router.navigate('/other')
      finish()
    })
    await screen.findByText(
      status === 200
        ? /저장 응답을 받았습니다/
        : status === 409
          ? /저장하지 못했습니다/
          : /저장 여부가 불확실합니다/,
    )
    await act(async () => {
      await router.navigate('/')
    })
    if (status === 200) {
      expect(await screen.findByText('수집 주기를 저장했습니다.')).toBeVisible()
      await waitFor(() =>
        expect(
          screen.getByRole('button', { name: '수집 주기 저장' }),
        ).toBeEnabled(),
      )
      const unload = new Event('beforeunload', { cancelable: true })
      window.dispatchEvent(unload)
      expect(unload.defaultPrevented).toBe(false)
    } else {
      expect(await screen.findByText('지연 저장 실패')).toBeVisible()
      expect(screen.getByRole('spinbutton')).toHaveValue(15)
      expect(
        await screen.findByRole('button', { name: '수집 주기 저장' }),
      ).toBeDisabled()
      await user.click(
        screen.getByRole('button', { name: '최신 설정 다시 불러오기' }),
      )
      await waitFor(() =>
        expect(screen.getByRole('checkbox')).not.toBeChecked(),
      )
      expect(
        screen.getByRole('button', { name: '수집 주기 저장' }),
      ).toBeEnabled()
    }
    expect(put).toHaveBeenCalledTimes(1)
  },
)
