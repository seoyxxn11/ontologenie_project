import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { proposalFixtures } from '../../mocks/proposalHandlers'
import ProposalComparison from './ProposalComparison'
import type { ProposalOption } from './types'

const proposal = proposalFixtures[0]
const options: ProposalOption[] = [
  {
    id: 1,
    optionNo: 1,
    revision: 2,
    payload: {
      name: '설비 코드 대안 A',
      conditions: [
        {
          sourceProperty: 'WORK_ORDER.code',
          targetProperty: 'EQUIPMENT.code',
          operator: 'EQ',
          sourceTransforms: [
            { op: 'TRIM', arg: null },
            { op: 'PAD_ZEROS', arg: 4 },
          ],
          targetTransforms: [],
        },
      ],
    },
    evidence: { reason: '앞뒤 공백을 제거하고 네 자리를 맞춥니다.' },
    aiConfidence: 0.9,
  },
  {
    id: 2,
    optionNo: 2,
    revision: 2,
    payload: { name: '설비 코드 대안 B' },
    evidence: {},
    aiConfidence: null,
  },
]
function open() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <ProposalComparison proposal={proposal} />
    </QueryClientProvider>,
  )
  return userEvent.setup()
}
function respond(values = options) {
  server.use(
    http.get('/api/v1/proposals/:id/options', ({ request }) => {
      expect(new URL(request.url).searchParams.get('revision')).toBe('2')
      return HttpResponse.json({ data: values })
    }),
  )
}

test('목업의 이전·다음 후보 이동으로 근거와 신뢰도·변환 순서를 비교한다', async () => {
  respond()
  const user = open()
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  const dialog = await screen.findByRole('dialog', { name: '대안 후보 비교' })
  expect(
    await within(dialog).findByText('설비 코드 대안 A'),
  ).toBeInTheDocument()
  expect(within(dialog).getByText('90.0%')).toBeInTheDocument()
  expect(
    within(dialog).getByText(/앞뒤 공백 제거 → 앞에 0 채우기/),
  ).toBeInTheDocument()
  expect(
    within(dialog).getByRole('button', { name: '← 이전 후보' }),
  ).toBeDisabled()
  await user.type(within(dialog).getByRole('textbox'), '임시 사유')
  await user.click(within(dialog).getByRole('button', { name: '다음 후보 →' }))
  expect(within(dialog).getByText('설비 코드 대안 B')).toBeInTheDocument()
  expect(within(dialog).getByRole('textbox')).toHaveValue('')
  expect(
    within(dialog).getByRole('button', { name: '다음 후보 →' }),
  ).toBeDisabled()
  await user.click(within(dialog).getByRole('button', { name: '← 이전 후보' }))
  expect(within(dialog).getByText('설비 코드 대안 A')).toBeInTheDocument()
  expect(
    within(dialog).getByText(/개별 대안의 검사 결과는 아직/),
  ).toBeInTheDocument()
})

test('저장 API 연결 전 선택·보류를 비활성화하고 쓰기 요청을 보내지 않는다', async () => {
  respond()
  const write = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.post('/api/v1/*', write), http.patch('/api/v1/*', write))
  const user = open()
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  await screen.findByText('설비 코드 대안 A')
  for (const name of ['이 후보 선택', '이 후보 보류', '이 관계 판단 보류'])
    expect(screen.getByRole('button', { name })).toBeDisabled()
  await user.type(screen.getByRole('textbox'), '비교 중')
  expect(screen.getByText(/입력한 사유는 저장되지 않으며/)).toBeInTheDocument()
  expect(screen.getByText(/개별 대안의 검사 결과는 아직/)).toBeInTheDocument()
  expect(write).not.toHaveBeenCalled()
  await user.keyboard('{Escape}')
  expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '대안 후보 비교' })).toHaveFocus()
})

test('최신 제안 개정을 먼저 조회하고 그 개정의 후보만 표시한다', async () => {
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({ data: { ...proposal, revision: 3 } }),
    ),
    http.get('/api/v1/proposals/:id/options', ({ request }) => {
      expect(new URL(request.url).searchParams.get('revision')).toBe('3')
      return HttpResponse.json({ data: [{ ...options[0], revision: 3 }] })
    }),
  )
  const user = open()
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  expect(
    await screen.findByText(/제안이 변경되어 최신 개정 3/),
  ).toBeInTheDocument()
  expect(screen.getByText('후보 번호 1 · 개정 3')).toBeInTheDocument()
})

test('다른 개정 후보가 섞이면 표시하지 않고 충돌 안내를 보여준다', async () => {
  respond([{ ...options[0], revision: 1 }])
  const user = open()
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  expect(await screen.findByRole('alert')).toHaveTextContent(
    '후보 개정이 일치하지 않습니다.',
  )
  expect(screen.queryByText('설비 코드 대안 A')).not.toBeInTheDocument()
})

test('후보가 없는 개정은 빈 상태로 표시한다', async () => {
  respond([])
  const user = open()
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  expect(
    await screen.findByText('이 개정에 비교할 대안 후보가 없습니다.'),
  ).toBeInTheDocument()
})

test('조회 실패는 자동 재시도하지 않고 사용자가 다시 불러올 수 있다', async () => {
  const request = vi.fn(() => HttpResponse.error())
  server.use(http.get('/api/v1/proposals/:id/options', request))
  const user = open()
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  await screen.findByRole('alert')
  expect(request).toHaveBeenCalledTimes(1)
  respond()
  await user.click(screen.getByRole('button', { name: '다시 불러오기' }))
  expect(await screen.findByText('설비 코드 대안 A')).toBeInTheDocument()
})

test('창을 다시 열면 캐시된 후보 대신 최신 조회 완료를 기다린다', async () => {
  respond()
  const user = open()
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  await screen.findByText('설비 코드 대안 A')
  await user.click(screen.getByRole('button', { name: '닫기' }))
  let release!: () => void
  const gate = new Promise<void>((resolve) => {
    release = resolve
  })
  const request = vi.fn(async () => {
    await gate
    return HttpResponse.json({ data: [] })
  })
  server.use(http.get('/api/v1/proposals/:id/options', request))
  await user.click(screen.getByRole('button', { name: '대안 후보 비교' }))
  try {
    await waitFor(() => expect(request).toHaveBeenCalledOnce())
    expect(
      screen.getByText('최신 개정의 후보를 불러오는 중…'),
    ).toBeInTheDocument()
    expect(screen.queryByText('설비 코드 대안 A')).not.toBeInTheDocument()
  } finally {
    release()
  }
  await screen.findByText('이 개정에 비교할 대안 후보가 없습니다.')
})
