import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import ExecutionHistory from './ExecutionHistory'
import type { Execution, ExecutionDetail } from './types'

const summary: Execution = {
  id: 9100,
  requestId: 'summary-only',
  eventKind: 'ANALYSIS_RUN',
  category: 'ANALYSIS',
  actorType: 'USER',
  actorName: '목록의 실행자',
  targetLabel: '목록의 대상',
  entryPoint: 'WEB',
  status: 'FAILED',
  requestedAt: '2026-11-11T01:00:00Z',
  changeCount: 1,
}
const detail: ExecutionDetail = {
  id: 9100,
  status: 'FAILED',
  input: { inputSecret: null, changeSecret: null },
  result: { reason: 'TIMEOUT', previousStatus: 'RUNNING', inputSecret: null },
  blockedReason: null,
  failureReason: 'TIMEOUT',
  restrictedPropertyCodes: ['inputSecret'],
}

function renderHistory(path: string, item: Execution = summary) {
  server.use(
    http.get(path, () =>
      HttpResponse.json({
        data: [item],
        meta: {
          page: 0,
          size: 20,
          totalElements: 1,
          hasNext: false,
          scope: 'MINE',
        },
      }),
    ),
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <ExecutionHistory
        objectId={path.includes('/objects/') ? 301 : undefined}
      />
    </QueryClientProvider>,
  )
}

test.each(['/api/v1/audit/actions', '/api/v1/objects/301/executions'])(
  '%s는 상세 필드 없는 목록에서 펼칠 때만 097·098을 조회하고 가림 안내를 분리한다',
  async (path) => {
    const calls = { detail: 0, changes: 0 }
    server.use(
      http.get('/api/v1/audit/actions/9100', async () => {
        calls.detail += 1
        await delay(80)
        return HttpResponse.json({ data: detail })
      }),
      http.get('/api/v1/audit/actions/9100/changes', async () => {
        calls.changes += 1
        await delay(80)
        return HttpResponse.json({
          data: {
            changes: [
              {
                tableName: 'object_instance',
                rowKey: { id: 301 },
                changeKind: 'UPDATE',
                beforeData: { inputSecret: null, changeSecret: null },
                afterData: { inputSecret: null, changeSecret: null },
              },
            ],
            restrictedPropertyCodes: ['changeSecret'],
          },
        })
      }),
    )
    const user = userEvent.setup()
    renderHistory(path)
    await screen.findByText('1건')
    expect(screen.getByText('사용자 · 목록의 실행자')).toBeInTheDocument()
    expect(screen.getByText('목록의 대상')).toBeInTheDocument()
    expect(calls).toEqual({ detail: 0, changes: 0 })

    await user.click(screen.getByText('내용 보기'))
    expect(await screen.findByText('실행 내용을 불러오는 중…')).toBeVisible()
    expect(await screen.findByText('실행 시간이 초과되었습니다.')).toBeVisible()
    expect(
      screen.getByText(/"inputSecret": "\(권한으로 가림\)"/),
    ).toHaveTextContent('"changeSecret": null')
    expect(screen.getByText(/"previousStatus": "RUNNING"/)).toHaveTextContent(
      '"inputSecret": null',
    )
    expect(screen.queryByText(/"reason"/)).not.toBeInTheDocument()
    expect(
      screen.getByText('입력값 중 권한으로 가린 속성이 있습니다.'),
    ).toBeVisible()
    expect(calls).toEqual({ detail: 1, changes: 0 })

    await user.click(screen.getByText('실행 전후 비교 1건'))
    expect(await screen.findByText('변경 내역을 불러오는 중…')).toBeVisible()
    expect(
      await screen.findByText('object_instance · id: 301 · 수정'),
    ).toBeVisible()
    expect(
      screen.getByText('변경 내역 중 권한으로 가린 속성이 있습니다.'),
    ).toBeVisible()
    // 097에서 가린 inputSecret은 098에서는 일반 null이고, changeSecret만 가린다.
    const changesTable = within(
      screen.getByRole('table', { name: '변경 전후 비교' }),
    )
    expect(changesTable.getAllByText('권한으로 가림')).toHaveLength(2)
    expect(changesTable.getAllByText('—')).toHaveLength(2)
    expect(screen.getAllByText('권한으로 가림')).toHaveLength(3)
    expect(calls).toEqual({ detail: 1, changes: 1 })
  },
)

test('changeCount가 0이면 098을 호출하지 않고 null 내용을 안전하게 표시한다', async () => {
  let changes = 0
  server.use(
    http.get('/api/v1/audit/actions/9100', () =>
      HttpResponse.json({
        data: {
          ...detail,
          input: null,
          result: null,
          failureReason: null,
          restrictedPropertyCodes: [],
        },
      }),
    ),
    http.get('/api/v1/audit/actions/9100/changes', () => {
      changes += 1
      return HttpResponse.json({
        data: { changes: [], restrictedPropertyCodes: [] },
      })
    }),
  )
  const user = userEvent.setup()
  renderHistory('/api/v1/audit/actions', { ...summary, changeCount: 0 })
  await screen.findByText('1건')
  await user.click(screen.getByText('내용 보기'))
  expect(await screen.findByText('기록된 항목 없음')).toBeVisible()
  expect(screen.getByText('기록된 입력값 없음')).not.toBeVisible()
  expect(screen.getByText('기록된 이유가 없습니다.')).toBeVisible()
  expect(screen.getByText('변경 전후: 기록된 변경 없음')).toBeVisible()
  expect(screen.queryByText(/^실행 전후 비교/)).not.toBeInTheDocument()
  expect(changes).toBe(0)
})

test('실패 사유는 result에서 추출하지 않고 097 failureReason을 사용한다', async () => {
  server.use(
    http.get('/api/v1/audit/actions/9100', () =>
      HttpResponse.json({
        data: { ...detail, failureReason: 'UPSTREAM_UNAVAILABLE' },
      }),
    ),
  )
  const user = userEvent.setup()
  renderHistory('/api/v1/audit/actions')
  await screen.findByText('1건')
  await user.click(screen.getByText('내용 보기'))
  expect(
    await screen.findByText(
      '실행에 실패했습니다. (오류 코드: UPSTREAM_UNAVAILABLE)',
    ),
  ).toBeVisible()
  expect(
    screen.queryByText('실행 시간이 초과되었습니다.'),
  ).not.toBeInTheDocument()
})

test.each(['SUCCEEDED', 'BLOCKED'] as const)(
  '%s에서는 실패 사유를 표시하지 않는다',
  async (status) => {
    server.use(
      http.get('/api/v1/audit/actions/9100', () =>
        HttpResponse.json({
          data: { ...detail, status, blockedReason: '대상 상태 변경' },
        }),
      ),
    )
    const user = userEvent.setup()
    renderHistory('/api/v1/audit/actions', { ...summary, status })
    await screen.findByText('1건')
    await user.click(screen.getByText('내용 보기'))
    await screen.findByText('입력값')
    expect(screen.queryByText('실패 이유')).not.toBeInTheDocument()
    if (status === 'BLOCKED')
      expect(screen.getByText('대상 상태 변경')).toBeVisible()
  },
)

test.each(['097', '098'] as const)(
  '%s의 404는 목록 상세값으로 대체하지 않고 접근 불가를 표시한다',
  async (api) => {
    server.use(
      http.get('/api/v1/audit/actions/9100', () =>
        api === '097'
          ? HttpResponse.json(
              {
                error: {
                  code: 'RESOURCE_NOT_FOUND',
                  message: '실행 기록을 찾을 수 없습니다.',
                },
              },
              { status: 404 },
            )
          : HttpResponse.json({ data: detail }),
      ),
      http.get(
        api === '097'
          ? '/api/v1/audit/actions/9100'
          : '/api/v1/audit/actions/9100/changes',
        () =>
          HttpResponse.json(
            {
              error: {
                code: 'RESOURCE_NOT_FOUND',
                message: '실행 기록을 찾을 수 없습니다.',
                details: {},
                requestId: 'not-found',
              },
            },
            { status: 404 },
          ),
      ),
    )
    const user = userEvent.setup()
    renderHistory('/api/v1/audit/actions')
    await screen.findByText('1건')
    await user.click(screen.getByText('내용 보기'))
    if (api === '098')
      await user.click(await screen.findByText('실행 전후 비교 1건'))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '대상을 찾을 수 없습니다.',
    )
    expect(
      screen.queryByRole('button', { name: '다시 불러오기' }),
    ).not.toBeInTheDocument()
    expect(screen.getByText('목록의 대상')).toBeInTheDocument()
  },
)

test.each(['097', '098'] as const)(
  '%s 서버 실패는 해당 상세 API만 재시도한다',
  async (api) => {
    const calls = { detail: 0, changes: 0 }
    server.use(
      http.get('/api/v1/audit/actions/9100', () => {
        calls.detail += 1
        return api === '097' && calls.detail === 1
          ? HttpResponse.json(
              { error: { code: 'INTERNAL_ERROR', message: '내용 조회 실패' } },
              { status: 500 },
            )
          : HttpResponse.json({ data: detail })
      }),
      http.get('/api/v1/audit/actions/9100/changes', () => {
        calls.changes += 1
        return calls.changes === 1
          ? HttpResponse.json(
              { error: { code: 'INTERNAL_ERROR', message: '변경 조회 실패' } },
              { status: 500 },
            )
          : HttpResponse.json({
              data: { changes: [], restrictedPropertyCodes: [] },
            })
      }),
    )
    const user = userEvent.setup()
    renderHistory('/api/v1/audit/actions')
    await screen.findByText('1건')
    await user.click(screen.getByText('내용 보기'))
    if (api === '098')
      await user.click(await screen.findByText('실행 전후 비교 1건'))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
    if (api === '097') {
      expect(
        await screen.findByText('실행 시간이 초과되었습니다.'),
      ).toBeVisible()
      expect(calls).toEqual({ detail: 2, changes: 0 })
    } else {
      expect(await screen.findByText('기록된 변경 없음')).toBeVisible()
      expect(calls).toEqual({ detail: 1, changes: 2 })
    }
  },
)

test('행을 닫고 다른 이력으로 바꾸면 이전 상세를 표시하지 않는다', async () => {
  server.use(
    http.get('/api/v1/audit/actions/:id', ({ params }) =>
      HttpResponse.json({
        data: { ...detail, input: { marker: `기록 ${params.id}` } },
      }),
    ),
  )
  const user = userEvent.setup()
  renderHistory('/api/v1/audit/actions')
  await screen.findByText('1건')
  await user.click(screen.getByText('내용 보기'))
  expect(await screen.findByText('기록 9100', { exact: true })).toBeVisible()
  server.use(
    http.get('/api/v1/audit/actions', () =>
      HttpResponse.json({
        data: [{ ...summary, id: 9200 }],
        meta: { page: 0, size: 20, totalElements: 1, hasNext: false },
      }),
    ),
  )
  await user.click(screen.getByRole('button', { name: '분석' }))
  await waitFor(() =>
    expect(screen.queryByText(/기록 9100/)).not.toBeInTheDocument(),
  )
  await user.click(screen.getByText('내용 보기'))
  expect(await screen.findByText('기록 9200', { exact: true })).toBeVisible()
})
