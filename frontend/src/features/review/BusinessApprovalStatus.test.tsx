import { formatKstDateTime } from '../../utils/dateTime'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  act,
  cleanup,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, expect, test, vi } from 'vitest'
import { ApiError } from '../../api/errors'
import BusinessApprovalStatus from './BusinessApprovalStatus'
import type {
  BusinessApprovalSnapshot,
  BusinessApprovalSource,
} from './businessApproval'

const clients: QueryClient[] = []
afterEach(async () => {
  cleanup()
  await Promise.all(clients.map((client) => client.cancelQueries()))
  clients.forEach((client) => client.clear())
  clients.length = 0
})

function snapshot(
  overrides: Partial<BusinessApprovalSnapshot> = {},
): BusinessApprovalSnapshot {
  return {
    proposalId: 303,
    revision: 2,
    approvalStatus: 'PENDING',
    request: {
      reference: '요청-17',
      requestedBy: '현업 담당자',
      requestedAt: '2026-11-06T03:00:00Z',
      reason: '설비 관계 확인',
    },
    decisions: [],
    application: {
      status: 'NOT_APPLIED',
      appliedBy: null,
      appliedAt: null,
      error: null,
    },
    ...overrides,
  }
}

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => {
    resolve = done
  })
  return { promise, resolve }
}

function open(source?: BusinessApprovalSource, proposalId = 303, revision = 2) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  clients.push(client)
  const view = render(
    <QueryClientProvider client={client}>
      <BusinessApprovalStatus
        proposalId={proposalId}
        revision={revision}
        source={source}
      />
    </QueryClientProvider>,
  )
  return {
    ...view,
    change: (id: number, rev: number) =>
      view.rerender(
        <QueryClientProvider client={client}>
          <BusinessApprovalStatus
            proposalId={id}
            revision={rev}
            source={source}
          />
        </QueryClientProvider>,
      ),
  }
}

test('연결 전에는 네 상태 모두 미확인이고 실행·새로고침이 비활성이다', () => {
  open()
  expect(screen.getByText(/현업 승인 상태 조회는 준비 중/)).toBeInTheDocument()
  expect(screen.getAllByText('미확인')).toHaveLength(4)
  expect(screen.queryByText('미요청')).not.toBeInTheDocument()
  for (const name of [
    '승인 요청',
    '현업 결정',
    '적용 실행',
    '현업 승인 상태 새로고침',
  ]) {
    expect(screen.getByRole('button', { name })).toBeDisabled()
  }
})

test('제안과 개정을 조회하고 대기 요청 정보와 미적용 상태를 구분한다', async () => {
  const load = vi.fn().mockResolvedValue(snapshot())
  open({ load })
  expect(await screen.findByText('승인 대기')).toBeInTheDocument()
  expect(load).toHaveBeenCalledWith(303, 2, expect.any(AbortSignal))
  const request = screen.getByRole('region', { name: '현업 승인 요청 정보' })
  expect(request).toHaveTextContent('요청-17')
  expect(request).toHaveTextContent('현업 담당자')
  expect(request).toHaveTextContent('설비 관계 확인')
  expect(request).toHaveTextContent(formatKstDateTime('2026-11-06T03:00:00Z'))
  expect(
    screen.getByText('제공된 현업 결정 기록이 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getAllByText('미적용')).toHaveLength(2)
  for (const name of ['승인 요청', '현업 결정', '적용 실행']) {
    const button = screen.getByRole('button', { name })
    expect(button).toBeDisabled()
    await userEvent.click(button)
  }
  expect(load).toHaveBeenCalledOnce()
})

test('서버가 명시한 미요청만 미요청으로 표시한다', async () => {
  open({
    load: vi.fn().mockResolvedValue(
      snapshot({
        approvalStatus: 'NOT_REQUESTED',
        request: null,
        application: null,
      }),
    ),
  })
  expect(
    await screen.findByText('현업 승인 요청이 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getAllByText('미요청')).toHaveLength(2)
  expect(screen.getByText('적용 정보 미제공')).toBeInTheDocument()
  expect(screen.queryByText('미적용')).not.toBeInTheDocument()
})

test.each(['NOT_APPLIED', 'APPLYING', 'APPLIED', 'FAILED'])(
  '현업 승인과 실제 %s 적용 상태를 각각 표시한다',
  async (status) => {
    const labels: Record<string, string> = {
      NOT_APPLIED: '미적용',
      APPLYING: '적용 중',
      APPLIED: '적용 완료',
      FAILED: '적용 실패',
    }
    open({
      load: vi.fn().mockResolvedValue(
        snapshot({
          approvalStatus: 'APPROVED',
          decisions: [
            {
              decision: 'APPROVE',
              decidedBy: '현업 검토자',
              decidedAt: '2026-11-06T04:00:00Z',
              reason: '정의 확인 완료',
            },
          ],
          application: {
            status,
            appliedBy: status === 'APPLIED' ? '적용 담당자' : null,
            appliedAt: status === 'APPLIED' ? '2026-11-06T05:00:00Z' : null,
            error: status === 'FAILED' ? '적용 대상 변경' : null,
          },
        }),
      ),
    })
    expect(
      await screen.findByText('현업 승인', { exact: true }),
    ).toBeInTheDocument()
    expect(screen.getAllByText(labels[status])).toHaveLength(2)
    const table = screen.getByRole('table', { name: '현업 결정 기록' })
    expect(table).toHaveTextContent('현업 검토자')
    expect(table).toHaveTextContent('정의 확인 완료')
    if (status === 'FAILED')
      expect(screen.getByRole('alert')).toHaveTextContent('적용 대상 변경')
    if (status === 'APPLIED') {
      expect(
        screen.getByRole('region', { name: '현업 승인 후 적용 기록' }),
      ).toHaveTextContent('적용 담당자')
      expect(screen.getByRole('button', { name: '적용 실행' })).toBeDisabled()
    }
  },
)

test('반려 및 과거 결정 기록은 응답 순서대로 표시한다', async () => {
  open({
    load: vi.fn().mockResolvedValue(
      snapshot({
        approvalStatus: 'REJECTED',
        decisions: [
          {
            decision: 'REJECT',
            decidedBy: '검토자2',
            decidedAt: null,
            reason: '근거 보완 필요',
          },
          {
            decision: 'APPROVE',
            decidedBy: '검토자1',
            decidedAt: null,
            reason: '이전 결정',
          },
        ],
      }),
    ),
  })
  await screen.findByText('현업 반려')
  const rows = within(screen.getByRole('table')).getAllByRole('row')
  expect(rows[1]).toHaveTextContent('반려검토자2제공되지 않음근거 보완 필요')
  expect(rows[2]).toHaveTextContent('이전 결정')
})

test('누락된 기록을 승인·미적용으로 추론하지 않고 알 수 없는 상태·잘못된 시각을 표시한다', async () => {
  open({
    load: vi.fn().mockResolvedValue(
      snapshot({
        approvalStatus: 'constructor',
        request: null,
        application: null,
        decisions: [
          {
            decision: 'ESCALATED',
            decidedBy: null,
            decidedAt: 'invalid',
            reason: '',
          },
        ],
      }),
    ),
  })
  expect(
    await screen.findByText('알 수 없는 상태 (constructor)'),
  ).toBeInTheDocument()
  expect(screen.getByText('알 수 없는 상태 (ESCALATED)')).toBeInTheDocument()
  expect(screen.getByText('시각을 확인할 수 없음')).toBeInTheDocument()
  expect(screen.getByText('빈 값')).toBeInTheDocument()
  expect(screen.getByText('요청 정보 미제공')).toBeInTheDocument()
  expect(screen.getByText('적용 정보 미제공')).toBeInTheDocument()
  expect(screen.queryByText('미적용')).not.toBeInTheDocument()
})

test.each([
  { proposalId: 304 },
  { revision: 1 },
  { request: {} },
  { decisions: null },
  { application: { status: 'APPLIED' } },
  { approvalStatus: '' },
])(
  '대상·개정 또는 응답 형식 오류를 성공 상태로 표시하지 않는다: %j',
  async (overrides) => {
    open({ load: vi.fn().mockResolvedValue({ ...snapshot(), ...overrides }) })
    expect(await screen.findByRole('alert')).toHaveTextContent(
      '응답을 확인할 수 없습니다.',
    )
    expect(screen.queryByText('승인 대기')).not.toBeInTheDocument()
    expect(screen.queryByRole('table')).not.toBeInTheDocument()
  },
)

test.each([
  [0, 2],
  [303, 0],
  [NaN, 2],
])('잘못된 제안 %s·개정 %s는 조회하지 않는다', (id, rev) => {
  const load = vi.fn()
  open({ load }, id, rev)
  expect(screen.getByRole('alert')).toHaveTextContent(
    '올바른 제안과 개정이 필요합니다.',
  )
  expect(load).not.toHaveBeenCalled()
  expect(
    screen.getByRole('button', { name: '현업 승인 상태 새로고침' }),
  ).toBeDisabled()
})

test('새로고침 중 이전 결과를 숨기고 중복 조회를 차단하며 권한 오류 후 재시도하지 않는다', async () => {
  const pending = deferred<BusinessApprovalSnapshot>()
  const load = vi
    .fn()
    .mockResolvedValueOnce(snapshot())
    .mockReturnValueOnce(pending.promise)
  open({ load })
  await screen.findByText('승인 대기')
  const refresh = screen.getByRole('button', {
    name: '현업 승인 상태 새로고침',
  })
  await userEvent.dblClick(refresh)
  expect(refresh).toBeDisabled()
  expect(screen.queryByText('승인 대기')).not.toBeInTheDocument()
  expect(load).toHaveBeenCalledTimes(2)
  await act(async () => {
    pending.resolve(snapshot({ approvalStatus: 'APPROVED' }))
  })
  await screen.findByText('현업 승인', { exact: true })
  load.mockRejectedValueOnce(
    new ApiError('raw private detail', { kind: 'forbidden', status: 403 }),
  )
  await userEvent.click(refresh)
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '요청이 허용되지 않았습니다.',
  )
  expect(
    screen.queryByText('현업 승인', { exact: true }),
  ).not.toBeInTheDocument()
  expect(screen.queryByText('raw private detail')).not.toBeInTheDocument()
  expect(refresh).toBeDisabled()
  expect(
    screen.queryByRole('button', { name: '다시 불러오기' }),
  ).not.toBeInTheDocument()
  expect(load).toHaveBeenCalledTimes(3)
})

test('일시적인 조회 실패는 수동으로 재시도할 수 있다', async () => {
  const load = vi
    .fn()
    .mockRejectedValueOnce(
      new ApiError('raw detail', { kind: 'server', status: 500 }),
    )
    .mockResolvedValueOnce(snapshot())
  open({ load })
  await screen.findByRole('alert')
  expect(load).toHaveBeenCalledOnce()
  await userEvent.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('승인 대기')).toBeInTheDocument()
  expect(load).toHaveBeenCalledTimes(2)
})

test.each([
  [303, 3],
  [304, 2],
])(
  '대상·개정 변경 시 이전 응답을 취소하고 뒤늦은 결과를 표시하지 않는다: %s/%s',
  async (id, rev) => {
    const pending = deferred<BusinessApprovalSnapshot>()
    const load = vi
      .fn()
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce(
        snapshot({ proposalId: id, revision: rev, approvalStatus: 'APPROVED' }),
      )
    const view = open({ load })
    await waitFor(() => expect(load).toHaveBeenCalledOnce())
    const signal = load.mock.calls[0][2] as AbortSignal
    view.change(id, rev)
    await screen.findByText('현업 승인', { exact: true })
    expect(signal.aborted).toBe(true)
    await act(async () => {
      pending.resolve(snapshot())
    })
    expect(screen.queryByText('승인 대기')).not.toBeInTheDocument()
    expect(screen.getByText('현업 승인', { exact: true })).toBeInTheDocument()
  },
)
