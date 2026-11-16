import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  act,
  fireEvent,
  render,
  screen,
  within,
  waitFor,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { createMemoryRouter, RouterProvider } from 'react-router'
import { afterEach, beforeEach, expect, test, vi, onTestFinished } from 'vitest'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import { resetSourceMocks } from '../../mocks/sourceHandlers'
import { CHECK_POLL_LIMIT_MS, CHECK_POLL_MS } from './ConnectionInfo'
import { parseEndpoint } from './endpoint'
import SourcesPage from './SourcesPage'
import type {
  ConnectionCheck,
  SourceDetail,
  SourceUpdateRequest,
} from './types'
import type { SyncRun } from './syncApi'

beforeEach(() => {
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
})

afterEach(() => {
  resetSourceMocks()
})

async function findConnectionStatus(
  label: string,
  waitOptions?: { timeout: number },
) {
  await screen.findByRole('button', { name: '정보 수정' })
  const summary = within(
    await screen.findByRole('region', { name: / 상태 요약$/ }),
  )
  return summary.findByText(label, {}, waitOptions)
}

function renderSourcesPage(path = '/admin/sources') {
  const router = createMemoryRouter(
    [
      { path: '/admin/sources', element: <SourcesPage /> },
      { path: '/explorer', element: <p>업무 데이터 화면</p> },
    ],
    { initialEntries: ['/explorer', path], initialIndex: 1 },
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return router
}

test('미저장 원천 폼의 뒤로 가기는 폐기를 확인하고 편집 계속 시 URL과 입력을 유지한다', async () => {
  const user = userEvent.setup()
  const router = renderSourcesPage('/admin/sources?register=1')
  const name = screen.getByRole('textbox', { name: '원천 이름' })
  await user.type(name, '미저장 입력')
  await act(async () => {
    await router.navigate(-1)
  })
  expect(screen.getByText('변경사항을 버릴까요?')).toBeVisible()
  expect(screen.getAllByRole('dialog')).toHaveLength(1)
  await user.click(screen.getByRole('button', { name: '계속 편집' }))
  expect(router.state.location.pathname).toBe('/admin/sources')
  expect(screen.getByRole('textbox', { name: '원천 이름' })).toHaveValue(
    '미저장 입력',
  )
  await act(async () => {
    await router.navigate(-1)
  })
  await user.click(screen.getByRole('button', { name: '변경사항 버리기' }))
  expect(await screen.findByText('업무 데이터 화면')).toBeVisible()
  expect(router.state.location.pathname).toBe('/explorer')
})

test('필수 원천 입력 오류는 해당 필드와 연결하고 입력을 고치면 해제한다', async () => {
  const user = userEvent.setup()
  renderSourcesPage('/admin/sources?register=1')
  await user.click(screen.getByRole('button', { name: '등록' }))
  const name = screen.getByRole('textbox', { name: '원천 이름' })
  const code = screen.getByRole('textbox', { name: '원천 코드' })
  expect(name).toHaveAttribute('aria-invalid', 'true')
  expect(name).toHaveAccessibleDescription('원천 이름을 입력해 주세요.')
  expect(code).toHaveAttribute('aria-invalid', 'true')
  expect(code).toHaveAccessibleDescription('원천 코드를 입력해 주세요.')
  await user.type(name, '새 원천')
  expect(name).not.toHaveAttribute('aria-invalid', 'true')
  expect(code).toHaveAttribute('aria-invalid', 'true')
})

test('미저장 폼에서 같은 화면의 다른 원천으로 이동할 때도 폐기를 확인한다', async () => {
  const user = userEvent.setup()
  const router = renderSourcesPage('/admin/sources?register=1')
  await user.type(screen.getByRole('textbox', { name: '원천 이름' }), '편집 중')
  await act(async () => {
    await router.navigate('/admin/sources?source=2')
  })
  await user.keyboard('{Escape}')
  expect(screen.getByRole('textbox', { name: '원천 이름' })).toHaveValue(
    '편집 중',
  )
  expect(router.state.location.search).toBe('?register=1')
  await act(async () => {
    await router.navigate('/admin/sources?source=2')
  })
  await user.click(screen.getByRole('button', { name: '변경사항 버리기' }))
  expect(router.state.location.search).toBe('?source=2')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(
    await screen.findByRole('heading', { name: 'MES 운영 DB 상세' }),
  ).toBeVisible()
})

test('브라우저 이탈 경고는 미저장 입력이 있을 때만 요청한다', async () => {
  const user = userEvent.setup()
  renderSourcesPage('/admin/sources?register=1')
  expect(
    window.dispatchEvent(new Event('beforeunload', { cancelable: true })),
  ).toBe(true)
  const name = screen.getByRole('textbox', { name: '원천 이름' })
  await user.type(name, '미저장')
  expect(
    window.dispatchEvent(new Event('beforeunload', { cancelable: true })),
  ).toBe(false)
  await user.clear(name)
  expect(
    window.dispatchEvent(new Event('beforeunload', { cancelable: true })),
  ).toBe(true)
})

test('원천 표본 탭은 실제 016·102 어댑터를 사용하고 서버가 준 표본만 표시한다', async () => {
  const user = userEvent.setup()
  renderSourcesPage('/admin/sources?tab=samples')
  await screen.findByRole('region', { name: '원천 표본 조회' })
  expect(screen.getByRole('button', { name: '원천 표본' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  expect(screen.queryByText(/표본 조회는 준비 중/)).not.toBeInTheDocument()
  const select = await screen.findByRole('combobox', { name: '표본 조회 대상' })
  const table = Array.from((select as HTMLSelectElement).options).find(
    (option) => option.value && !option.disabled,
  )!
  await user.selectOptions(select, table.value)
  await user.click(screen.getByRole('button', { name: '표본 조회 요청' }))
  expect(
    await screen.findByRole('table', { name: '원천 행 표본' }),
  ).toHaveTextContent('SAMPLE-001')
  expect(screen.getByText(/금지·민감 컬럼은 제외/)).toBeVisible()
})

test('수집 이력 탭에서 실제 어댑터로 073 요청·074 이력·075 결과를 연결한다', async () => {
  const user = userEvent.setup()
  const run: SyncRun = {
    id: 21,
    syncRunId: 21,
    dataSourceId: 1,
    triggerKind: 'MANUAL',
    status: 'SUCCEEDED',
    requestedBy: 1,
    requestedAt: '2026-11-11T01:00:00Z',
    startedAt: '2026-11-11T01:00:01Z',
    finishedAt: '2026-11-11T01:00:02Z',
    errorMessage: null,
    resources: [
      {
        syncRunId: 21,
        dataSourceId: 1,
        sourceResourceId: 11,
        syncStrategy: 'CDC',
        mode: 'FULL',
        status: 'SUCCEEDED',
        fromLsn: null,
        toLsn: 100,
        rowsRead: 12,
        rowsUpserted: 10,
        rowsDeleted: 2,
        startedAt: null,
        finishedAt: null,
        errorMessage: null,
      },
    ],
  }
  let requested = false
  server.use(
    http.get('/api/v1/sources/1/sync-runs', ({ request }) => {
      const params = new URL(request.url).searchParams
      expect(params.get('page')).toBe('0')
      expect(['1', '10']).toContain(params.get('size'))
      expect(params.has('triggerKind')).toBe(false)
      return HttpResponse.json({
        data: requested ? [run] : [],
        meta: {
          page: 0,
          size: Number(params.get('size')),
          totalElements: requested ? 1 : 0,
          hasNext: false,
        },
      })
    }),
    http.post('/api/v1/sources/1/sync-runs', async ({ request }) => {
      expect(await request.json()).toEqual({})
      requested = true
      return HttpResponse.json(
        {
          data: {
            ...run,
            status: 'QUEUED',
            joinedExisting: false,
            resources: run.resources.map((resource) => ({
              ...resource,
              joined: false,
            })),
          },
        },
        { status: 202 },
      )
    }),
    http.get('/api/v1/sync-runs/21', () => HttpResponse.json({ data: run })),
  )
  renderSourcesPage('/admin/sources?tab=sync')
  await screen.findByRole('region', { name: '수동 수집' })
  expect(screen.getByRole('button', { name: '수집 이력' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await screen.findByText('수집 실행 이력이 없습니다.')
  expect(screen.getByRole('button', { name: '수동 수집 요청' })).toBeEnabled()
  await user.click(screen.getByRole('button', { name: '수동 수집 요청' }))
  await screen.findByText('수집 실행 #21을 접수했습니다.')
  await screen.findByRole('button', { name: '수집 실행 #21' })
  const summary = within(
    screen.getByRole('region', { name: 'ERP 운영 DB 상태 요약' }),
  )
  expect(await summary.findByText('완료')).toBeInTheDocument()
  const detail = within(
    await screen.findByRole('region', { name: '수집 실행 #21 상세' }),
  )
  expect(
    detail.getByText('읽음 12 · 생성·갱신 10 · 삭제 2'),
  ).toBeInTheDocument()
  expect(
    await detail.findByText(/erp.item · CDC · 전체 읽기 · 완료/),
  ).toBeInTheDocument()
  expect(
    screen.queryByText('수동 수집과 실행 이력 조회는 아직 사용할 수 없습니다.'),
  ).not.toBeInTheDocument()
})

test.each(['CDC', 'FULL_COMPARE'] as const)(
  '수집 주기 설정은 FULL_COMPARE 자원이 있을 때만 표시한다 (%s)',
  async (strategy) => {
    server.use(
      http.get('/api/v1/sources/:id/resources', ({ params, request }) => {
        const page = Number(new URL(request.url).searchParams.get('page'))
        return HttpResponse.json({
          data: [
            {
              id: page + 1,
              dataSourceId: Number(params.id),
              resourceKey: page ? 'target_view' : 'cdc_table',
              resourceKind: page ? 'VIEW' : 'TABLE',
              isReadable: true,
              sourceMetadata: {},
              syncStrategy: page ? strategy : 'CDC',
            },
          ],
          meta: { page, size: 200, totalElements: 2, hasNext: page === 0 },
        })
      }),
    )
    const user = userEvent.setup()
    const router = renderSourcesPage('/admin/sources?tab=scope')
    await screen.findByText('target_view')
    if (strategy === 'CDC') {
      expect(
        screen.queryByRole('region', { name: '자동 수집 주기' }),
      ).not.toBeInTheDocument()
      return
    }
    const schedule = within(
      await screen.findByRole('region', { name: '자동 수집 주기' }),
    )
    const enabled = schedule.getByRole('checkbox', {
      name: '자동 수집 사용',
    }) as HTMLInputElement
    if (!enabled.checked)
      await act(async () => {
        await user.click(enabled)
      })
    const interval = schedule.getByRole('spinbutton', { name: '수집 주기(분)' })
    await act(async () => {
      await user.clear(interval)
      await user.type(interval, '0')
    })
    expect(interval).toHaveAttribute('aria-invalid', 'true')
    await act(async () => {
      await user.clear(interval)
      await user.type(interval, '15')
    })
    expect(interval).toHaveAttribute('aria-invalid', 'false')
    expect(
      schedule.getByRole('button', { name: '수집 주기 저장' }),
    ).toBeEnabled()
    // 브라우저 뒤로 가기 조작 전에 입력의 blur/change를 마친다.
    // Dialog의 포커스 이동 중 user-event가 change를 재전송하지 않게 한다.
    await user.tab()
    await act(async () => {
      await router.navigate(-1)
    })
    expect(
      screen.getByRole('dialog', { name: '변경사항을 버릴까요?' }),
    ).toBeVisible()
    await act(async () => {
      await user.click(screen.getByRole('button', { name: '계속 편집' }))
    })
    expect(interval).toHaveValue(15)
    await act(async () => {
      await user.click(screen.getByRole('button', { name: '+ 원천 등록' }))
    })
    expect(
      screen.queryByRole('dialog', { name: '원천 등록' }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('dialog', { name: '변경사항을 버릴까요?' }),
    ).toBeVisible()
    await act(async () => {
      await user.click(screen.getByRole('button', { name: '계속 편집' }))
    })
    expect(interval).toHaveValue(15)
    await act(async () => {
      await user.click(screen.getByRole('button', { name: '+ 원천 등록' }))
    })
    await act(async () => {
      await user.click(screen.getByRole('button', { name: '변경사항 버리기' }))
    })
    expect(
      await screen.findByRole('dialog', { name: '원천 등록' }),
    ).toBeVisible()
    expect(
      screen.queryByRole('region', { name: '자동 수집 주기' }),
    ).not.toBeInTheDocument()
  },
)

function checkResult(overrides: Partial<ConnectionCheck>): ConnectionCheck {
  return {
    checkId: 101,
    dataSourceId: 1,
    status: 'SUCCEEDED',
    requestedBy: 1,
    requestedAt: '2026-11-03T09:00:00+09:00',
    startedAt: '2026-11-03T09:00:00+09:00',
    finishedAt: '2026-11-03T09:00:03+09:00',
    errorMessage: null,
    result: { accounts: [] },
    ...overrides,
  }
}

test('상단 원천 버튼에 접속 상태를 표시하고 선택 원천의 상세를 연다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  const erp = await screen.findByRole('button', { name: 'ERP 운영 DB' })
  const mes = screen.getByRole('button', { name: 'MES 운영 DB' })
  expect(erp).toHaveTextContent('수집 접속 확인됨')
  expect(erp).toHaveAttribute('aria-pressed', 'true')
  expect(mes).toHaveTextContent('수집 확인 필요')
  expect(mes).toHaveAttribute('aria-pressed', 'false')
  expect(erp).toHaveAttribute('aria-controls', 'source-detail')
  expect(
    screen.queryByRole('table', { name: '등록된 원천 목록' }),
  ).not.toBeInTheDocument()
  await user.click(mes)
  expect(mes).toHaveAttribute('aria-pressed', 'true')
  expect(erp).toHaveAttribute('aria-pressed', 'false')
  expect(
    await screen.findByRole('heading', { name: 'MES 운영 DB 상세' }),
  ).toBeVisible()
  expect(
    screen.getByRole('button', { name: 'AI 관계 제안 받기' }),
  ).toBeEnabled()
})

test('5개 상세 탭과 선택 원천의 AI 제안 진입 및 URL 복원을 유지한다', async () => {
  const user = userEvent.setup()
  const router = renderSourcesPage('/admin/sources?source=2&tab=samples')
  await screen.findByRole('heading', { name: 'MES 운영 DB 상세' })
  const tabs = within(screen.getByRole('group', { name: '상세 항목' }))
  expect(
    tabs.getAllByRole('button').map((button) => button.textContent),
  ).toEqual(['접속 정보', '수집 대상', '원천 표본', '원천 매핑', '수집 이력'])
  expect(tabs.getByRole('button', { name: '원천 표본' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  await user.click(screen.getByRole('button', { name: 'AI 관계 제안 받기' }))
  const dialog = within(
    await screen.findByRole('dialog', { name: '분석 대상 선택' }),
  )
  expect(
    await dialog.findByRole('combobox', { name: '등록된 원천' }),
  ).toHaveValue('2')
  await user.click(dialog.getByRole('button', { name: '닫기' }))
  await user.click(screen.getByRole('button', { name: 'ERP 운영 DB' }))
  expect(router.state.location.search).toBe('?source=1&tab=connection')
  await act(async () => {
    await router.navigate(-1)
  })
  expect(
    screen.getByRole('button', { name: 'MES 운영 DB', pressed: true }),
  ).toBeInTheDocument()
  expect(tabs.getByRole('button', { name: '원천 표본' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
})

test('등록된 원천과 첫 원천의 접속 정보를 연다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()

  expect(
    screen.getByRole('heading', { name: '데이터 연결' }),
  ).toBeInTheDocument()
  const sources = within(
    await screen.findByRole('group', { name: '등록된 원천' }),
  )
  const erp = sources.getByRole('button', { name: 'ERP 운영 DB' })
  expect(erp).toHaveTextContent('수집 접속 확인됨')
  expect(erp).toHaveAccessibleDescription('구조화 데이터 수집 접속 확인됨')
  expect(
    sources.getByRole('button', { name: 'MES 운영 DB' }),
  ).toHaveTextContent('수집 확인 필요')
  expect(
    screen.getByRole('heading', { name: 'ERP 운영 DB 상세' }),
  ).toBeInTheDocument()

  expect(
    await screen.findByText('erp-db.internal:5432 · erp'),
  ).toBeInTheDocument()
  // 연결 검사는 쓰기 권한을 검사하지 않으므로 읽기 전용이라고 단정하지 않는다
  expect(
    within(
      screen.getByRole('region', { name: 'ERP 운영 DB 상태 요약' }),
    ).getByText('수집 접속 확인됨'),
  ).toBeInTheDocument()
  expect(screen.queryByText(/SELECT only/)).not.toBeInTheDocument()
  expect(
    await screen.findByText('수집 계정 · AI 분석 계정 접속 확인됨'),
  ).toBeVisible()
  expect(screen.getByText('platform_ro')).not.toBeVisible()
  expect(screen.getByText('platform_ai')).not.toBeVisible()
  const details = screen.getByText('계정별 상세').closest('details')!
  expect(details).not.toHaveAttribute('open')
  await user.click(screen.getByText('계정별 상세'))
  const results = screen.getByRole('list', { name: '계정별 결과' })
  expect(results).toBeVisible()
  expect(results).toHaveTextContent('수집 계정')
  expect(results).toHaveTextContent('platform_ro')
  expect(results).toHaveTextContent('AI 분석 계정')
  expect(results).toHaveTextContent('platform_ai')
  expect(results).toHaveTextContent('읽을 수 있는 표 18개 · 컬럼 127개')
  await user.click(screen.getByText('계정별 상세'))
  expect(results).not.toBeVisible()
})

test('검사한 적 없는 원천은 미검증으로 보이고 결과가 없다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await screen.findByText('erp-db.internal:5432 · erp')

  await user.click(screen.getByRole('button', { name: 'MES 운영 DB' }))

  expect(
    screen.getByRole('heading', { name: 'MES 운영 DB 상세' }),
  ).toBeInTheDocument()
  expect(await findConnectionStatus('수집 확인 필요')).toBeInTheDocument()
  expect(
    screen.queryByRole('region', { name: '연결 확인 결과' }),
  ).not.toBeInTheDocument()
})

test('중복 바로 가기 없이 수집 이력 탭에서 선택한 원천의 이력을 연다', async () => {
  const user = userEvent.setup()
  const requested: string[] = []
  server.use(
    http.get('/api/v1/sources/:id/sync-runs', ({ params, request }) => {
      const size = Number(new URL(request.url).searchParams.get('size'))
      // 요약(size=1)과 전체 이력(size=10) 요청을 구분한다.
      if (size === 10) requested.push(String(params.id))
      return HttpResponse.json({
        data: [],
        meta: {
          page: 0,
          size,
          totalElements: 0,
          hasNext: false,
          cdcLastAppliedAt: null,
        },
      })
    }),
  )
  renderSourcesPage()
  await user.click(await screen.findByRole('button', { name: 'MES 운영 DB' }))
  expect(
    screen.queryByRole('button', { name: '수집 이력 보기' }),
  ).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '수집 이력' }))
  expect(
    screen.getByRole('button', { name: '수집 이력', pressed: true }),
  ).toBeInTheDocument()
  await screen.findByText('수집 실행 이력이 없습니다.')
  expect(requested).toContain('2')
  expect(requested).not.toContain('1')
})

test('연결 다시 확인을 누르면 끝날 때까지 다시 조회하고 계정별 결과를 보여준다', async () => {
  const user = userEvent.setup()
  let calls = 0
  server.use(
    http.get('/api/v1/sources/:id/connection-checks/:checkId', () => {
      calls += 1
      return HttpResponse.json({
        data:
          calls === 1
            ? checkResult({ status: 'RUNNING', finishedAt: null })
            : checkResult({
                result: {
                  accounts: [
                    {
                      purpose: 'SYNC',
                      status: 'SUCCEEDED',
                      readableTableCount: 15,
                      readableColumnCount: 120,
                      errorMessage: null,
                    },
                  ],
                },
              }),
      })
    }),
  )
  renderSourcesPage('/admin/sources?source=2')
  await findConnectionStatus('수집 확인 필요')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(await screen.findByText('검사 중')).toBeInTheDocument()
  const announcement = screen.getByText('검사 중').closest('[role="status"]')
  expect(announcement).toHaveAttribute('aria-live', 'polite')
  expect(announcement).toHaveAttribute('aria-atomic', 'true')
  expect(screen.getByRole('button', { name: '확인 중…' })).toBeDisabled()
  expect(
    await screen.findByText('수집 계정 접속 확인됨', {}, { timeout: 4000 }),
  ).toBeVisible()
  expect(
    screen.getByText('수집 계정 접속 확인됨').closest('[role="status"]'),
  ).toBe(announcement)
  expect(
    screen.queryByText('수집 계정 · AI 분석 계정 접속 확인됨'),
  ).not.toBeInTheDocument()
  expect(
    screen.getByText('읽을 수 있는 표 15개 · 컬럼 120개'),
  ).not.toBeVisible()
  await user.click(screen.getByText('계정별 상세'))
  expect(screen.getByText('읽을 수 있는 표 15개 · 컬럼 120개')).toBeVisible()
  expect(screen.getByRole('button', { name: '연결 다시 확인' })).toBeEnabled()
})

test('계정 접속에 실패하면 계정별 실패 사유를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.get('/api/v1/sources/:id/connection-checks/:checkId', () =>
      HttpResponse.json({
        data: checkResult({
          status: 'FAILED',
          errorMessage: '일부 계정 접속에 실패했습니다.',
          result: {
            accounts: [
              {
                purpose: 'SYNC',
                status: 'SUCCEEDED',
                readableTableCount: 18,
                readableColumnCount: 140,
                errorMessage: null,
              },
              {
                purpose: 'AI',
                status: 'FAILED',
                readableTableCount: null,
                readableColumnCount: null,
                errorMessage: '원천 계정 인증에 실패했습니다',
              },
            ],
          },
        }),
      }),
    ),
  )
  renderSourcesPage('/admin/sources?source=2')
  await findConnectionStatus('수집 확인 필요')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '일부 계정 접속에 실패했습니다.',
  )
  expect(
    screen.getByText('AI 분석 계정: 실패 · 원천 계정 인증에 실패했습니다'),
  ).toBeVisible()
  expect(
    screen.getByText('계정별 상세').closest('details'),
  ).not.toHaveAttribute('open')
})

test('수집 접속이 성공해도 AI 계정 실패를 상세 밖에 표시한다', async () => {
  server.use(
    http.get('/api/v1/sources/:id/connection-checks/:checkId', () =>
      HttpResponse.json({
        data: checkResult({
          status: 'SUCCEEDED',
          errorMessage: null,
          result: {
            accounts: [
              {
                purpose: 'SYNC',
                status: 'SUCCEEDED',
                readableTableCount: 18,
                readableColumnCount: 140,
                errorMessage: null,
              },
              {
                purpose: 'AI',
                status: 'FAILED',
                readableTableCount: null,
                readableColumnCount: null,
                errorMessage: 'AI 분석 계정의 접속 권한을 확인해 주세요.',
              },
            ],
          },
        }),
      }),
    ),
  )
  renderSourcesPage()
  const failure = await screen.findByRole('alert')
  expect(failure).toBeVisible()
  expect(failure).toHaveTextContent(
    'AI 분석 계정: 실패 · AI 분석 계정의 접속 권한을 확인해 주세요.',
  )
  expect(failure.closest('details')).toBeNull()
  expect(screen.getByText('일부 계정 실패')).toBeVisible()
  expect(
    screen.getByText('계정별 상세').closest('details'),
  ).not.toHaveAttribute('open')
  const erp = screen.getByRole('button', { name: 'ERP 운영 DB' })
  expect(erp).toHaveTextContent('수집 접속 확인됨')
  expect(erp).not.toHaveTextContent('AI')
  expect(
    screen.queryByText('수집 계정 · AI 분석 계정 접속 확인됨'),
  ).not.toBeInTheDocument()
})

test('검사 결과 갱신 실패 뒤 이전 계정 성공 요약과 수치를 남기지 않는다', async () => {
  const user = userEvent.setup()
  let fail = false
  server.use(
    http.get('/api/v1/sources/:id/connection-checks/:checkId', () =>
      fail
        ? HttpResponse.json(
            {
              error: { code: 'INTERNAL_ERROR', message: '검사 결과 조회 실패' },
            },
            { status: 500 },
          )
        : HttpResponse.json({
            data: checkResult({
              result: {
                accounts: [
                  {
                    purpose: 'SYNC',
                    status: 'SUCCEEDED',
                    readableTableCount: 15,
                    readableColumnCount: 120,
                    errorMessage: null,
                  },
                ],
              },
            }),
          }),
    ),
  )
  renderSourcesPage()
  await screen.findByText('수집 계정 접속 확인됨')
  fail = true
  await user.click(screen.getByRole('button', { name: '수집 대상' }))
  await user.click(screen.getByRole('button', { name: '접속 정보' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '검사 결과 조회 실패',
  )
  expect(screen.queryByText('수집 계정 접속 확인됨')).not.toBeInTheDocument()
  await user.click(screen.getByText('계정별 상세'))
  expect(screen.getByText('platform_ro')).toBeVisible()
  expect(
    screen.queryByText('읽을 수 있는 표 15개 · 컬럼 120개'),
  ).not.toBeInTheDocument()
})

test('같은 원천의 확인이 진행 중이면 합류했다고 알려준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.post('/api/v1/sources/:id/test-connection', () =>
      HttpResponse.json(
        {
          data: {
            checkId: 77,
            status: 'RUNNING',
            joined: true,
            requestedAt: '2026-11-03T09:00:00+09:00',
            startedAt: '2026-11-03T09:00:00+09:00',
            finishedAt: null,
            errorMessage: null,
          },
        },
        { status: 202 },
      ),
    ),
  )
  renderSourcesPage('/admin/sources?source=2')
  await findConnectionStatus('수집 확인 필요')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(
    await screen.findByText(
      '이미 진행 중인 확인이 있어 그 결과를 함께 보여 줍니다.',
    ),
  ).toBeInTheDocument()
})

test('연결 확인 요청 권한이 없으면 서버가 준 안내를 보여준다', async () => {
  const user = userEvent.setup()
  server.use(
    http.post('/api/v1/sources/:id/test-connection', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '요청을 실행할 권한이 없습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 403 },
      ),
    ),
  )
  renderSourcesPage('/admin/sources?source=2')
  await findConnectionStatus('수집 확인 필요')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '요청을 실행할 권한이 없습니다.',
  )
})

test('수집 대상 탭에서 읽을 수 있는 표를 펼치면 컬럼과 기본키를 보여준다', async () => {
  const user = userEvent.setup()
  renderSourcesPage('/admin/sources?source=2')
  await findConnectionStatus('수집 확인 필요')

  await user.click(screen.getByRole('button', { name: '수집 대상' }))

  expect(await screen.findByText('표 15개')).toBeInTheDocument()
  await user.click(screen.getByText('mes.work_order · 작업지시'))

  const columns = await screen.findByRole('list', {
    name: 'mes.work_order 컬럼',
  })
  const key = within(columns).getByText('wo_no').closest('li')!
  expect(key).toHaveTextContent('wo_no · varchar(20) · 기본키')
})

test('원천 목록을 불러오지 못하면 다시 불러올 수 있게 안내한다', async () => {
  server.use(
    http.get('/api/v1/sources', () =>
      HttpResponse.json(
        {
          error: {
            code: 'INTERNAL_ERROR',
            message: '일시적인 오류입니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 500 },
      ),
    ),
  )
  renderSourcesPage()

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '일시적인 오류입니다.',
  )
  expect(
    screen.getByRole('button', { name: '다시 불러오기' }),
  ).toBeInTheDocument()
})

test('접속 대상은 주소:포트/데이터베이스로 나누고 포트를 빼면 5432로 본다', () => {
  expect(parseEndpoint('erp-db.internal:15432/erp')).toEqual({
    host: 'erp-db.internal',
    port: 15432,
    database: 'erp',
  })
  expect(parseEndpoint('erp-db.internal/erp')).toEqual({
    host: 'erp-db.internal',
    port: 5432,
    database: 'erp',
  })
  expect(parseEndpoint('erp-db.internal')).toBeNull()
  expect(parseEndpoint('erp-db.internal:70000/erp')).toBeNull()
})

async function fillNewSource(user: ReturnType<typeof userEvent.setup>) {
  const dialog = screen.getByRole('dialog', { name: '원천 등록' })
  const field = (label: string) => within(dialog).getByLabelText(label)
  await user.type(field('원천 이름'), '품질 DB')
  await user.type(field('원천 코드'), 'qms_main')
  await user.type(field('접속 대상'), 'qms-db.internal:5432/qms')
  await user.type(field('수집 계정 이름'), 'platform_ro')
  await user.type(field('수집 계정 비밀번호'), 'sync-pw')
  await user.type(field('AI 분석 계정 이름'), 'platform_ai')
  await user.type(field('AI 분석 계정 비밀번호'), 'ai-pw')
  return dialog
}

test('미저장 원천 입력은 배경 클릭으로 닫히지 않고 폐기 확인에서 편집을 계속할 수 있다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await user.click(screen.getByRole('button', { name: '+ 원천 등록' }))
  const dialog = screen.getByRole('dialog', { name: '원천 등록' })
  await user.type(within(dialog).getByLabelText('원천 이름'), '미저장 원천')
  fireEvent.click(dialog.parentElement!)
  expect(dialog).toBeInTheDocument()
  expect(screen.queryByText('변경사항을 버릴까요?')).not.toBeInTheDocument()
  await user.keyboard('{Escape}')
  expect(screen.getByText('변경사항을 버릴까요?')).toBeVisible()
  expect(screen.getAllByRole('dialog')).toHaveLength(1)
  await user.click(screen.getByRole('button', { name: '계속 편집' }))
  expect(within(dialog).getByLabelText('원천 이름')).toHaveValue('미저장 원천')
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  await user.click(screen.getByRole('button', { name: '변경사항 버리기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('원천 비밀번호만 수정해도 취소 시 폐기를 확인하고 Escape로 편집에 복귀한다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await user.click(await screen.findByRole('button', { name: '정보 수정' }))
  const dialog = screen.getByRole('dialog', { name: '원천 정보 수정' })
  await user.type(
    within(dialog).getByLabelText('수집 계정 비밀번호'),
    'test-only-password',
  )
  await user.click(within(dialog).getByRole('button', { name: '취소' }))
  expect(screen.getByText('변경사항을 버릴까요?')).toBeVisible()
  await user.keyboard('{Escape}')
  expect(within(dialog).getByLabelText('수집 계정 비밀번호')).toHaveValue(
    'test-only-password',
  )
})

test('원천을 등록하면 목록에 더하고 연결을 확인하라고 안내한다', async () => {
  const user = userEvent.setup()
  let sent: Record<string, unknown> | undefined
  server.events.on('request:start', async ({ request }) => {
    if (
      request.method === 'POST' &&
      new URL(request.url).pathname === '/api/v1/sources'
    ) {
      sent = (await request.clone().json()) as Record<string, unknown>
    }
  })
  renderSourcesPage()
  await screen.findByText('erp-db.internal:5432 · erp')

  await user.click(screen.getByRole('button', { name: '+ 원천 등록' }))
  const dialog = await fillNewSource(user)
  await user.click(within(dialog).getByRole('button', { name: '등록' }))

  expect(
    await screen.findByRole('heading', { name: '품질 DB 상세' }),
  ).toBeInTheDocument()
  expect(screen.getByText('3개')).toBeInTheDocument()
  expect(
    await screen.findByText(/원천을 등록했습니다. 연결 다시 확인을 눌러/),
  ).toBeInTheDocument()
  expect(sent).toMatchObject({
    code: 'QMS_MAIN',
    sourceKind: 'POSTGRES',
    connectionConfig: { host: 'qms-db.internal', port: 5432, database: 'qms' },
    credentials: [
      { purpose: 'SYNC', username: 'platform_ro', password: 'sync-pw' },
      { purpose: 'AI', username: 'platform_ai', password: 'ai-pw' },
    ],
  })
  server.events.removeAllListeners()
})

test('비밀번호가 빠지면 등록하지 않고 안내한다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await screen.findByText('erp-db.internal:5432 · erp')

  await user.click(screen.getByRole('button', { name: '+ 원천 등록' }))
  const dialog = await fillNewSource(user)
  await user.clear(within(dialog).getByLabelText('AI 분석 계정 비밀번호'))
  await user.click(within(dialog).getByRole('button', { name: '등록' }))

  expect(within(dialog).getByRole('alert')).toHaveTextContent(
    'AI 분석 계정의 이름과 비밀번호를 입력해 주세요.',
  )
})

test('이미 있는 원천 코드면 서버가 준 안내를 보여준다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await screen.findByText('erp-db.internal:5432 · erp')

  await user.click(screen.getByRole('button', { name: '+ 원천 등록' }))
  const dialog = await fillNewSource(user)
  await user.clear(within(dialog).getByLabelText('원천 코드'))
  await user.type(within(dialog).getByLabelText('원천 코드'), 'ERP_MAIN')
  await user.click(within(dialog).getByRole('button', { name: '등록' }))

  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    '이미 등록된 원천 코드입니다.',
  )
})

test('접속 대상을 바꾸면 미검증으로 돌아가고 다시 확인하라고 안내한다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await findConnectionStatus('수집 접속 확인됨')

  await user.click(screen.getByRole('button', { name: '정보 수정' }))
  const dialog = screen.getByRole('dialog', { name: '원천 정보 수정' })
  expect(within(dialog).getByLabelText('원천 코드')).toBeDisabled()
  const endpoint = within(dialog).getByLabelText('접속 대상')
  await user.clear(endpoint)
  await user.type(endpoint, 'erp-db2.internal:5432/erp')
  await user.click(within(dialog).getByRole('button', { name: '저장' }))

  // 접속 대상이 바뀌면 서버가 이 원천의 CDC 반영을 멈춘다(SOURCE_CHANGED)
  expect(
    await screen.findByText(
      '접속 대상이 바뀌었습니다. CDC로 반영하던 표는 CDC 재연결 전까지 반영을 멈춥니다. 연결 다시 확인을 눌러 주세요.',
    ),
  ).toBeInTheDocument()
  expect(
    await screen.findByText('erp-db2.internal:5432 · erp'),
  ).toBeInTheDocument()
  expect(
    within(screen.getByRole('region', { name: / 상태 요약$/ })).getByText(
      '수집 확인 필요',
    ),
  ).toBeInTheDocument()
})

test('계정 이름만 바꾸고 비밀번호를 넣지 않으면 저장하지 않는다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await findConnectionStatus('수집 접속 확인됨')

  await user.click(screen.getByRole('button', { name: '정보 수정' }))
  const dialog = screen.getByRole('dialog', { name: '원천 정보 수정' })
  const username = within(dialog).getByLabelText('AI 분석 계정 이름')
  await user.clear(username)
  await user.type(username, 'platform_ai2')
  await user.click(within(dialog).getByRole('button', { name: '저장' }))

  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    'AI 분석 계정 이름을 바꾸려면 비밀번호도 입력해 주세요.',
  )
})

test('다른 사람이 먼저 수정했으면 최신 상태를 확인하라고 안내한다', async () => {
  const user = userEvent.setup()
  server.use(
    http.patch('/api/v1/sources/:id', () =>
      HttpResponse.json(
        {
          error: {
            code: 'CONFLICT',
            message: '다른 사용자가 먼저 수정했습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 409 },
      ),
    ),
  )
  renderSourcesPage()
  await findConnectionStatus('수집 접속 확인됨')

  await user.click(screen.getByRole('button', { name: '정보 수정' }))
  const dialog = screen.getByRole('dialog', { name: '원천 정보 수정' })
  await user.type(within(dialog).getByLabelText('원천 이름'), ' 2')
  await user.click(within(dialog).getByRole('button', { name: '저장' }))

  expect(await within(dialog).findByRole('alert')).toHaveTextContent(
    '최신 상태를 확인해 주세요.',
  )
})

function sourceDetail(overrides: Partial<SourceDetail> = {}): SourceDetail {
  return {
    id: 1,
    code: 'ERP_MAIN',
    name: 'ERP 운영 DB',
    sourceKind: 'POSTGRES',
    connectionStatus: 'CONNECTED',
    lastCheckedAt: null,
    syncEnabled: true,
    syncIntervalMinutes: null,
    rowVersion: 1,
    connectionConfig: { host: 'erp-db.internal', port: 5432, database: 'erp' },
    credentials: [
      { purpose: 'SYNC', username: 'platform_ro' },
      { purpose: 'AI', username: 'platform_ai' },
    ],
    latestConnectionCheck: null,
    ...overrides,
  }
}

test('다른 곳에서 먼저 저장해 409가 나면 최신 내용을 불러와 같은 창에서 다시 저장할 수 있다', async () => {
  const user = userEvent.setup()
  let version = 1
  const sentVersions: number[] = []
  server.use(
    http.get('/api/v1/sources/1', () =>
      HttpResponse.json({ data: sourceDetail({ rowVersion: version }) }),
    ),
    http.patch('/api/v1/sources/1', async ({ request }) => {
      const body = (await request.json()) as SourceUpdateRequest
      sentVersions.push(body.rowVersion)
      if (body.rowVersion !== version)
        return HttpResponse.json(
          {
            error: {
              code: 'VERSION_CONFLICT',
              message: '다른 사용자가 먼저 수정했습니다.',
              details: {},
              requestId: 'mock-request-id',
            },
          },
          { status: 409 },
        )
      version += 1
      return HttpResponse.json({
        data: sourceDetail({ rowVersion: version, name: body.name }),
      })
    }),
  )
  renderSourcesPage()
  await findConnectionStatus('수집 접속 확인됨')

  await user.click(screen.getByRole('button', { name: '정보 수정' }))
  const dialog = screen.getByRole('dialog', { name: '원천 정보 수정' })
  await user.type(within(dialog).getByLabelText('원천 이름'), ' 2')
  // 창을 연 사이 다른 사람이 저장했다
  version = 2
  await user.click(within(dialog).getByRole('button', { name: '저장' }))

  expect(
    await within(dialog).findByText(
      '다른 곳에서 바뀐 내용을 불러왔습니다. 입력한 내용을 확인하고 다시 저장해 주세요.',
    ),
  ).toBeInTheDocument()
  expect(within(dialog).getByLabelText('원천 이름')).toHaveValue(
    'ERP 운영 DB 2',
  )
  await user.click(within(dialog).getByRole('button', { name: '저장' }))

  expect(await screen.findByText('저장했습니다.')).toBeInTheDocument()
  expect(sentVersions).toEqual([1, 2])
})

test('409 뒤 다시 저장해도 다른 곳에서 바꾼 접속 대상을 옛 값으로 되돌리지 않는다', async () => {
  const user = userEvent.setup()
  let latest = sourceDetail()
  const sent: SourceUpdateRequest[] = []
  server.use(
    http.get('/api/v1/sources/1', () => HttpResponse.json({ data: latest })),
    http.patch('/api/v1/sources/1', async ({ request }) => {
      const body = (await request.json()) as SourceUpdateRequest
      sent.push(body)
      if (body.rowVersion !== latest.rowVersion)
        return HttpResponse.json(
          {
            error: {
              code: 'VERSION_CONFLICT',
              message: '다른 사용자가 먼저 수정했습니다.',
              details: {},
              requestId: 'mock-request-id',
            },
          },
          { status: 409 },
        )
      latest = sourceDetail({
        ...latest,
        rowVersion: latest.rowVersion + 1,
        name: body.name ?? latest.name,
      })
      return HttpResponse.json({ data: latest })
    }),
  )
  renderSourcesPage()
  await findConnectionStatus('수집 접속 확인됨')

  await user.click(screen.getByRole('button', { name: '정보 수정' }))
  const dialog = screen.getByRole('dialog', { name: '원천 정보 수정' })
  await user.type(within(dialog).getByLabelText('원천 이름'), ' 2')
  // 창을 연 사이 다른 사람이 접속 대상을 바꿨다
  latest = sourceDetail({
    rowVersion: 2,
    connectionConfig: { host: 'erp-db2.internal', port: 5432, database: 'erp' },
  })
  await user.click(within(dialog).getByRole('button', { name: '저장' }))
  await within(dialog).findByText(
    '다른 곳에서 바뀐 내용을 불러왔습니다. 입력한 내용을 확인하고 다시 저장해 주세요.',
  )

  // 손대지 않은 칸은 최신 값, 고친 칸은 입력한 값
  expect(within(dialog).getByLabelText('접속 대상')).toHaveValue(
    'erp-db2.internal:5432/erp',
  )
  expect(within(dialog).getByLabelText('원천 이름')).toHaveValue(
    'ERP 운영 DB 2',
  )
  await user.click(within(dialog).getByRole('button', { name: '저장' }))

  expect(await screen.findByText('저장했습니다.')).toBeInTheDocument()
  expect(sent.at(-1)).toEqual({ rowVersion: 2, name: 'ERP 운영 DB 2' })
})

test('계정 이름은 앞뒤 공백을 빼고 보낸다', async () => {
  const user = userEvent.setup()
  let sent: SourceUpdateRequest | undefined
  server.events.on('request:start', async ({ request }) => {
    if (request.method === 'PATCH')
      sent = (await request.clone().json()) as SourceUpdateRequest
  })
  renderSourcesPage()
  await findConnectionStatus('수집 접속 확인됨')

  await user.click(screen.getByRole('button', { name: '정보 수정' }))
  const dialog = screen.getByRole('dialog', { name: '원천 정보 수정' })
  const username = within(dialog).getByLabelText('AI 분석 계정 이름')
  await user.clear(username)
  await user.type(username, '  platform_ai2  ')
  await user.type(
    within(dialog).getByLabelText('AI 분석 계정 비밀번호'),
    'new-pw',
  )
  await user.click(within(dialog).getByRole('button', { name: '저장' }))

  expect(
    await screen.findByText(
      '접속 정보가 바뀌었습니다. 연결 다시 확인을 눌러 주세요.',
    ),
  ).toBeInTheDocument()
  expect(sent?.credentials).toEqual([
    { purpose: 'AI', username: 'platform_ai2', password: 'new-pw' },
  ])
  server.events.removeAllListeners()
})

test('다른 곳에서 요청한 검사가 끝나도 원천 상태를 다시 불러온다', async () => {
  let finished = false
  let checkCalls = 0
  server.use(
    http.get('/api/v1/sources/1', () =>
      HttpResponse.json({
        data: sourceDetail({
          connectionStatus: finished ? 'CONNECTED' : 'UNVERIFIED',
          latestConnectionCheck: {
            checkId: 120,
            status: finished ? 'SUCCEEDED' : 'RUNNING',
            finishedAt: null,
            errorMessage: null,
          },
        }),
      }),
    ),
    http.get('/api/v1/sources/1/connection-checks/120', () => {
      checkCalls += 1
      if (checkCalls >= 2) finished = true
      return HttpResponse.json({
        data: checkResult({
          checkId: 120,
          status: finished ? 'SUCCEEDED' : 'RUNNING',
        }),
      })
    }),
  )
  renderSourcesPage()

  expect(await findConnectionStatus('수집 확인 필요')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'ERP 운영 DB' })).toHaveTextContent(
    '수집 확인 필요',
  )
  expect(await screen.findByText('검사 중')).toBeInTheDocument()
  expect(
    await findConnectionStatus('수집 접속 확인됨', { timeout: 5000 }),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'ERP 운영 DB' })).toHaveTextContent(
    '수집 접속 확인됨',
  )
})

test('검사 결과를 불러오지 못하면 확인 중 상태로 막아 두지 않는다', async () => {
  const user = userEvent.setup()
  server.use(
    http.get('/api/v1/sources/:id/connection-checks/:checkId', () =>
      HttpResponse.json(
        {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: '연결 검사를 찾을 수 없습니다.',
            details: {},
            requestId: 'mock-request-id',
          },
        },
        { status: 404 },
      ),
    ),
  )
  renderSourcesPage('/admin/sources?source=2')
  await findConnectionStatus('수집 확인 필요')

  await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '연결 검사를 찾을 수 없습니다.',
  )
  expect(screen.getByRole('button', { name: '연결 다시 확인' })).toBeEnabled()
})

test('검사가 시간 상한까지 끝나지 않으면 다시 불러오기를 멈추고 버튼을 푼다', async () => {
  vi.useFakeTimers({ shouldAdvanceTime: true })
  try {
    let calls = 0
    server.use(
      http.get('/api/v1/sources/:id/connection-checks/:checkId', () => {
        calls += 1
        return HttpResponse.json({
          data: checkResult({ status: 'RUNNING', finishedAt: null }),
        })
      }),
    )
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    renderSourcesPage('/admin/sources?source=2')
    await findConnectionStatus('수집 확인 필요')

    await user.click(screen.getByRole('button', { name: '연결 다시 확인' }))
    expect(
      await screen.findByRole('button', { name: '확인 중…' }),
    ).toBeDisabled()

    await act(() => vi.advanceTimersByTimeAsync(CHECK_POLL_LIMIT_MS))
    expect(
      await screen.findByRole('button', { name: '연결 다시 확인' }),
    ).toBeEnabled()
    expect(
      screen.getByText(
        '검사 결과를 아직 받지 못해 더 기다리지 않습니다. 잠시 뒤 연결 다시 확인을 눌러 주세요.',
      ),
    ).toBeInTheDocument()
    const stopped = calls
    await act(() => vi.advanceTimersByTimeAsync(CHECK_POLL_MS * 3))
    expect(calls).toBe(stopped)
  } finally {
    vi.useRealTimers()
  }
})

test('원천이 100개가 넘어도 모든 쪽을 받아 목록과 선택에 쓴다', async () => {
  const many = Array.from({ length: 120 }, (_, index) =>
    sourceDetail({
      id: index + 1,
      code: `SRC_${index + 1}`,
      name: `원천 ${index + 1}`,
    }),
  )
  const pages: string[] = []
  server.use(
    http.get('/api/v1/sources', ({ request }) => {
      const params = new URL(request.url).searchParams
      const page = Number(params.get('page'))
      const size = Number(params.get('size'))
      pages.push(`${page}:${size}`)
      return HttpResponse.json({
        data: many.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalElements: many.length,
          hasNext: (page + 1) * size < many.length,
        },
      })
    }),
  )
  renderSourcesPage('/admin/sources?source=110')

  expect(
    await screen.findByRole('heading', { name: '원천 110 상세' }),
  ).toBeInTheDocument()
  expect(screen.getByText('120개')).toBeInTheDocument()
  expect(pages).toEqual(['0:100', '1:100'])
})

test('주소에 register=1이 있으면 원천 등록 창을 바로 연다', async () => {
  const user = userEvent.setup()
  renderSourcesPage('/admin/sources?register=1')

  const dialog = await screen.findByRole('dialog', { name: '원천 등록' })
  await user.click(within(dialog).getByRole('button', { name: '취소' }))
  expect(
    screen.queryByRole('dialog', { name: '원천 등록' }),
  ).not.toBeInTheDocument()
})

test('원천 등록의 필수 표시와 입력 의미를 유형·편집 조건에 맞게 제공한다', async () => {
  renderSourcesPage('/admin/sources?register=1')
  const dialog = within(screen.getByRole('dialog', { name: '원천 등록' }))
  expect(dialog.getByRole('textbox', { name: '원천 이름' })).toBeRequired()
  expect(dialog.getByRole('textbox', { name: '원천 코드' })).toBeRequired()
  expect(dialog.getByRole('textbox', { name: '접속 대상' })).toBeRequired()
  expect(dialog.getByLabelText('수집 계정 비밀번호')).toBeRequired()
  expect(dialog.getByText('원천 이름 (필수)')).toBeVisible()
  expect(dialog.getAllByRole('option')).toHaveLength(1)
  expect(dialog.getByRole('option', { name: 'PostgreSQL' })).toBeVisible()
  expect(
    dialog.queryByText(/파일 업로드와 수집은 현재 지원하지 않습니다/),
  ).not.toBeInTheDocument()
})

test.each(['CSV', 'DOCUMENT'])(
  '신규 등록에서 미지원 %s 선택지를 제공하지 않고 조작된 유형 변경도 무시한다',
  async (kind) => {
    const post = vi.fn(() => HttpResponse.json({ data: { id: 99 } }))
    server.use(http.post('/api/v1/sources', post))
    const user = userEvent.setup()
    renderSourcesPage('/admin/sources?register=1')
    const dialog = within(screen.getByRole('dialog', { name: '원천 등록' }))
    await user.type(
      dialog.getByRole('textbox', { name: '원천 이름' }),
      '합성 원천',
    )
    await user.type(
      dialog.getByRole('textbox', { name: '원천 코드' }),
      'TEST_SOURCE',
    )
    const select = dialog.getByRole('combobox', { name: '유형' })
    expect(
      dialog.queryByRole('option', {
        name: kind === 'CSV' ? 'CSV' : '문서·기록',
      }),
    ).not.toBeInTheDocument()
    fireEvent.change(select, { target: { value: kind } })
    expect(select).toHaveValue('POSTGRES')
    const button = dialog.getByRole('button', { name: '등록' })
    fireEvent.submit(button.closest('form')!)
    expect(post).not.toHaveBeenCalled()
  },
)

test('원천 저장 중 닫아도 요청은 유지되고 응답 후에는 완료 상태와 대상 경로를 제공한다', async () => {
  let release!: () => void
  const response = new Promise<void>((resolve) => {
    release = resolve
  })
  onTestFinished(() => release())
  let calls = 0
  server.use(
    http.post('/api/v1/sources', async ({ request }) => {
      calls++
      const input = (await request.json()) as Record<string, unknown>
      await response
      return HttpResponse.json({
        data: {
          ...input,
          id: 45,
          connectionStatus: 'UNVERIFIED',
          lastCheckedAt: null,
          syncEnabled: false,
          rowVersion: 1,
        },
      })
    }),
  )
  const user = userEvent.setup()
  const router = renderSourcesPage('/admin/sources?register=1')
  let dialog = screen.getByRole('dialog', { name: '원천 등록' })
  await user.type(
    within(dialog).getByRole('textbox', { name: '접속 대상' }),
    'test.invalid:5432/test',
  )
  await user.type(
    within(dialog).getByLabelText('수집 계정 이름'),
    'synthetic-sync',
  )
  await user.type(
    within(dialog).getByLabelText('수집 계정 비밀번호'),
    'test-only',
  )
  await user.type(
    within(dialog).getByLabelText('AI 분석 계정 이름'),
    'synthetic-ai',
  )
  await user.type(
    within(dialog).getByLabelText('AI 분석 계정 비밀번호'),
    'test-only',
  )
  await user.type(
    within(dialog).getByRole('textbox', { name: '원천 이름' }),
    'UI 저장 확인',
  )
  await user.type(
    within(dialog).getByRole('textbox', { name: '원천 코드' }),
    'UI_SAVE',
  )
  await user.click(within(dialog).getByRole('button', { name: '등록' }))
  await waitFor(() => expect(calls).toBe(1))
  await user.click(within(dialog).getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  await user.click(screen.getByRole('button', { name: '저장 상태 보기' }))
  dialog = screen.getByRole('dialog', { name: '원천 등록' })
  expect(
    within(dialog).getByRole('textbox', { name: '원천 이름' }),
  ).toBeDisabled()
  expect(within(dialog).getByRole('button', { name: '등록' })).toBeDisabled()
  await act(async () => {
    await router.navigate('/explorer')
  })
  await act(async () => release())
  await screen.findByText('업무 데이터 화면')
  expect(router.state.location.pathname).toBe('/explorer')
  await act(async () => {
    await router.navigate('/admin/sources')
  })
  const status = await screen.findByRole('region', { name: '저장 요청 상태' })
  await waitFor(() =>
    expect(status).toHaveTextContent('저장 응답을 받았습니다.'),
  )
  expect(
    within(status).getByRole('link', { name: '저장 대상 확인' }),
  ).toHaveAttribute('href', '/admin/sources?source=45&tab=connection')
  expect(calls).toBe(1)
})

test('AI 제안에서 등록으로 전환하고 닫아도 이전 분석 창이 다시 열리지 않는다', async () => {
  const user = userEvent.setup()
  renderSourcesPage()
  await screen.findByRole('heading', { name: 'ERP 운영 DB 상세' })
  await user.click(screen.getByRole('button', { name: 'AI 관계 제안 받기' }))
  const analysis = within(
    await screen.findByRole('dialog', { name: '분석 대상 선택' }),
  )
  await user.click(analysis.getByRole('button', { name: '+ 원천 등록' }))
  const registration = within(
    await screen.findByRole('dialog', { name: '원천 등록' }),
  )
  expect(screen.getAllByRole('dialog')).toHaveLength(1)
  expect(document.body.style.overflow).toBe('hidden')
  await user.click(registration.getByRole('button', { name: '닫기' }))
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})

test('AI 제안에서 등록한 뒤 브라우저 뒤로가기로 닫아도 분석 창이 다시 열리지 않는다', async () => {
  const user = userEvent.setup()
  const router = renderSourcesPage()
  await screen.findByRole('heading', { name: 'ERP 운영 DB 상세' })
  await user.click(screen.getByRole('button', { name: 'AI 관계 제안 받기' }))
  const analysis = within(
    await screen.findByRole('dialog', { name: '분석 대상 선택' }),
  )
  await user.click(analysis.getByRole('button', { name: '+ 원천 등록' }))
  await screen.findByRole('dialog', { name: '원천 등록' })
  await act(async () => {
    await router.navigate(-1)
  })
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
})
