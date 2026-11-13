import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { issueFixture } from '../../mocks/issueHandlers'
import { server } from '../../mocks/node'
import type { ObjectInstance } from '../explorer/types'
import IssueManualLinkForm from './IssueManualLinkForm'

const issue = {
  ...issueFixture,
  issueKind: 'UNMATCHED',
  status: 'CHECKING',
  objectInstanceId: 11,
  linkMappingId: 12,
  rowVersion: 3,
}
const target: ObjectInstance = {
  id: 301,
  displayName: '시험 설비 A',
  objectTypeId: 3,
  objectTypeName: '설비',
  status: 'ACTIVE',
  properties: { restricted: '화면에 표시하지 않을 값' },
  restrictedPropertyCodes: ['restricted'],
  firstCollectedAt: '2026-11-13T00:00:00Z',
}
const receipt = {
  issueId: 1,
  linkId: 812,
  outcome: 'CREATED',
  actionExecutionId: 1001,
}

function setup() {
  const post = vi.fn(async ({ request }: { request: Request }) => {
    expect(await request.json()).toEqual({
      targetObjectId: 301,
      reason: '시험 연결 근거',
      rowVersion: 3,
    })
    return HttpResponse.json({ data: receipt })
  })
  const search = vi.fn(({ request }: { request: Request }) => {
    expect(request.method).toBe('GET')
    return HttpResponse.json({
      data: [target],
      meta: { page: 0, size: 8, totalElements: 1, hasNext: false },
    })
  })
  server.use(
    http.get('/api/v1/objects/types', () =>
      HttpResponse.json({
        data: [
          { id: 3, code: 'EQUIPMENT', name: '설비' },
          { id: 4, code: 'ORDER', name: '작업지시' },
        ],
      }),
    ),
    http.get('/api/v1/objects', search),
    http.post('/api/v1/issues/1/manual-links', post),
  )
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const onPendingChange = vi.fn()
  const view = (nextIssue = issue, unavailable = false) => (
    <QueryClientProvider client={client}>
      <IssueManualLinkForm
        issue={nextIssue}
        unavailable={unavailable}
        onPendingChange={onPendingChange}
      />
    </QueryClientProvider>
  )
  const rendered = render(view())
  return {
    user: userEvent.setup(),
    post,
    search,
    client,
    onPendingChange,
    rerender: (nextIssue = issue, unavailable = false) =>
      rendered.rerender(view(nextIssue, unavailable)),
  }
}

async function selectTarget(user: ReturnType<typeof userEvent.setup>) {
  await screen.findByRole('option', { name: '설비' })
  await user.selectOptions(screen.getByLabelText('도착 객체 유형'), '3')
  await user.type(screen.getByLabelText('도착 객체 검색어'), '시험')
  await user.click(screen.getByRole('button', { name: '도착 객체 검색' }))
  await user.click(await screen.findByRole('radio', { name: /시험 설비 A/ }))
}

async function reviewTarget(user: ReturnType<typeof userEvent.setup>) {
  await selectTarget(user)
  await user.type(screen.getByLabelText('연결 사유'), '  시험 연결 근거  ')
  await user.click(screen.getByRole('button', { name: '연결 내용 확인' }))
  return within(screen.getByRole('region', { name: '수동 연결 실행 전 확인' }))
}

test('허용 유형과 검색어로 조회·선택하고 내용 확인 전에는 저장하지 않는다', async () => {
  const { user, post, search } = setup()
  expect(screen.getByRole('button', { name: '연결 내용 확인' })).toBeDisabled()
  const review = await reviewTarget(user)
  expect(search).toHaveBeenCalledTimes(1)
  const url = new URL(search.mock.calls[0][0].request.url)
  expect(Object.fromEntries(url.searchParams)).toEqual({
    objectTypeId: '3',
    q: '시험',
    page: '0',
    size: '8',
  })
  expect(review.getByText(/도착 객체: 시험 설비 A/)).toBeInTheDocument()
  expect(review.getByText('연결 사유: 시험 연결 근거')).toBeInTheDocument()
  expect(screen.queryByText('화면에 표시하지 않을 값')).not.toBeInTheDocument()
  expect(post).not.toHaveBeenCalled()
  await user.click(review.getByRole('button', { name: '입력 수정' }))
  expect(screen.getByLabelText('연결 사유')).toHaveValue('  시험 연결 근거  ')
  expect(screen.getByRole('radio', { name: /시험 설비 A/ })).toBeChecked()
})

test('최종 확인으로 한 번 저장하고 결과·관련 캐시를 갱신한다', async () => {
  const { user, post, client, onPendingChange } = setup()
  const invalidate = vi.spyOn(client, 'invalidateQueries')
  const review = await reviewTarget(user)
  await user.dblClick(review.getByRole('button', { name: '수동 연결 저장' }))
  expect(
    await screen.findByText(
      '수동 연결을 저장했습니다. 조치완료로 바꾼 뒤 재검증하세요.',
    ),
  ).toBeInTheDocument()
  expect(screen.getByText('연결 #812 · 감사 실행 #1001')).toBeInTheDocument()
  expect(post).toHaveBeenCalledTimes(1)
  for (const key of [
    ['issues', 'detail', 1],
    ['issues', 'list'],
    ['issues', 'history', 1],
    ['issues', 'verifications', 1],
    ['objects'],
    ['audit'],
  ]) {
    expect(invalidate).toHaveBeenCalledWith({ queryKey: key })
  }
  expect(onPendingChange.mock.calls).toEqual([[true], [false]])
  expect(
    screen.queryByRole('button', { name: '수동 연결 저장' }),
  ).not.toBeInTheDocument()
})

test('검색·유형·페이지 변경은 기존 선택을 지운다', async () => {
  const { user } = setup()
  await selectTarget(user)
  await user.type(screen.getByLabelText('연결 사유'), '시험 연결 근거')
  await user.type(screen.getByLabelText('도착 객체 검색어'), '다른 검색')
  expect(screen.getByRole('button', { name: '연결 내용 확인' })).toBeDisabled()
  expect(screen.queryByRole('radio')).not.toBeInTheDocument()
  await user.selectOptions(screen.getByLabelText('도착 객체 유형'), '4')
  expect(screen.queryByText(/선택한 도착 객체:/)).not.toBeInTheDocument()
})

test('페이지를 넘겨 대상을 선택하고 출발 객체·삭제 객체는 선택하지 못한다', async () => {
  const { user } = setup()
  server.use(
    http.get('/api/v1/objects', ({ request }) => {
      const page = Number(new URL(request.url).searchParams.get('page'))
      return HttpResponse.json({
        data:
          page === 0
            ? [
                { ...target, id: 11, displayName: '출발 객체' },
                {
                  ...target,
                  id: 12,
                  displayName: '삭제 객체',
                  status: 'DELETED_AT_SOURCE',
                },
                target,
              ]
            : [{ ...target, id: 302, displayName: '시험 설비 B' }],
        meta: { page, size: 8, totalElements: 9, hasNext: page === 0 },
      })
    }),
  )
  await selectTarget(user)
  expect(screen.getByRole('radio', { name: /출발 객체/ })).toBeDisabled()
  expect(screen.getByRole('radio', { name: /삭제 객체/ })).toBeDisabled()
  await user.click(screen.getByRole('button', { name: '다음 페이지' }))
  expect(
    await screen.findByRole('radio', { name: /시험 설비 B/ }),
  ).not.toBeChecked()
  expect(screen.queryByText(/선택한 도착 객체:/)).not.toBeInTheDocument()
})

test.each([
  [
    422,
    'LINK_END_TYPE_MISMATCH',
    '관계 유형에 맞는 도착 객체를 다시 선택해 주세요.',
  ],
  [
    422,
    'LINK_CARDINALITY_EXCEEDED',
    '허용된 연결 수를 초과합니다. 기존 연결을 확인해 주세요.',
  ],
  [
    422,
    'LINK_END_NOT_ACTIVE',
    '삭제됐거나 사용할 수 없는 객체입니다. 도착 객체를 다시 검색해 주세요.',
  ],
  [
    409,
    'LINK_ALREADY_EXISTS',
    '이미 연결된 객체입니다. 기존 연결을 확인해 주세요.',
  ],
  [
    409,
    'LINK_SOURCE_LINKED_BY_RULE',
    '규칙이 이미 출발 객체를 연결했습니다. 최신 연결을 확인해 주세요.',
  ],
  [
    409,
    'VERSION_CONFLICT',
    '저장하지 못했거나 저장 결과를 확인할 수 없습니다. 재전송 전에 최신 문제·연결·실행 이력을 확인해 주세요.',
  ],
  [401, 'UNAUTHENTICATED', null],
  [403, 'FORBIDDEN', null],
  [404, 'RESOURCE_NOT_FOUND', null],
] as const)(
  '%s %s는 안내하고 무조건 재실행하지 않는다',
  async (status, code, message) => {
    const { user, post } = setup()
    const failed = vi.fn(() =>
      HttpResponse.json(
        {
          error: {
            code,
            message: '원천 오류 원문',
            details: {},
            requestId: 'request-test',
          },
        },
        { status },
      ),
    )
    server.use(http.post('/api/v1/issues/1/manual-links', failed))
    const review = await reviewTarget(user)
    await user.click(review.getByRole('button', { name: '수동 연결 저장' }))
    await screen.findByRole('alert')
    if (message) expect(screen.getByText(message)).toBeInTheDocument()
    expect(screen.queryByText('원천 오류 원문')).not.toBeInTheDocument()
    expect(failed).toHaveBeenCalledTimes(1)
    expect(post).not.toHaveBeenCalled()
    if (status === 422) {
      expect(screen.getByLabelText('연결 사유')).toHaveValue(
        '  시험 연결 근거  ',
      )
      expect(
        screen.getByRole('button', { name: '연결 내용 확인' }),
      ).toBeDisabled()
    } else {
      await user.click(
        screen.getByRole('button', { name: '최신 문제 다시 조회' }),
      )
      expect(
        screen.queryByRole('button', { name: '수동 연결 저장' }),
      ).not.toBeInTheDocument()
      expect(
        screen.queryByRole('button', { name: '연결 내용 확인' }),
      ).not.toBeInTheDocument()
    }
  },
)

test.each([
  'network',
  'empty',
  'wrong-issue',
  'bad-link',
  'bad-audit',
  'bad-outcome',
] as const)(
  '%s 결과는 성공으로 표시하지 않고 재전송을 막는다',
  async (mode) => {
    const { user } = setup()
    const failed = vi.fn(() => {
      if (mode === 'network') return HttpResponse.error()
      if (mode === 'empty') return new HttpResponse(null, { status: 204 })
      return HttpResponse.json({
        data: {
          ...receipt,
          ...(mode === 'wrong-issue' && { issueId: 2 }),
          ...(mode === 'bad-link' && { linkId: 0 }),
          ...(mode === 'bad-audit' && { actionExecutionId: '1001' }),
          ...(mode === 'bad-outcome' && { outcome: 'UNKNOWN' }),
        },
      })
    })
    server.use(http.post('/api/v1/issues/1/manual-links', failed))
    const review = await reviewTarget(user)
    await user.click(review.getByRole('button', { name: '수동 연결 저장' }))
    expect(await screen.findByText(/재전송을 막았습니다/)).toBeInTheDocument()
    expect(
      screen.queryByText(/수동 연결을 저장했습니다/),
    ).not.toBeInTheDocument()
    expect(failed).toHaveBeenCalledTimes(1)
  },
)

test('실행 전 버전 변경·상세 재조회 중에는 저장하지 못한다', async () => {
  const { user, post, rerender } = setup()
  await reviewTarget(user)
  rerender({ ...issue, rowVersion: 4 })
  expect(screen.getByRole('button', { name: '수동 연결 저장' })).toBeDisabled()
  expect(
    screen.getByText('문제가 변경됐습니다. 입력 내용을 다시 확인해 주세요.'),
  ).toBeInTheDocument()
  rerender(issue, true)
  expect(screen.getByRole('button', { name: '수동 연결 저장' })).toBeDisabled()
  expect(post).not.toHaveBeenCalled()
})

test('저장 중에는 수정·중복 클릭을 막고 완료까지 대기한다', async () => {
  const { user, onPendingChange } = setup()
  let finish!: () => void
  const waiting = new Promise<void>((resolve) => {
    finish = resolve
  })
  const post = vi.fn(async () => {
    await waiting
    return HttpResponse.json({ data: receipt })
  })
  server.use(http.post('/api/v1/issues/1/manual-links', post))
  const review = await reviewTarget(user)
  await user.click(review.getByRole('button', { name: '수동 연결 저장' }))
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1))
  expect(screen.getByRole('button', { name: '입력 수정' })).toBeDisabled()
  expect(screen.getByRole('button', { name: '수동 연결 저장' })).toBeDisabled()
  expect(onPendingChange).toHaveBeenCalledWith(true)
  await act(async () => {
    finish()
    await waiting
  })
  await screen.findByText(/수동 연결을 저장했습니다/)
})

test('빈 검색 결과에서는 저장을 허용하지 않는다', async () => {
  const { user, post } = setup()
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 8, totalElements: 0, hasNext: false },
      }),
    ),
  )
  await screen.findByRole('option', { name: '설비' })
  await user.selectOptions(screen.getByLabelText('도착 객체 유형'), '3')
  await user.click(screen.getByRole('button', { name: '도착 객체 검색' }))
  expect(
    await screen.findByText('조건에 맞는 도착 객체가 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '연결 내용 확인' })).toBeDisabled()
  expect(post).not.toHaveBeenCalled()
})

test('검색 요청 실패를 빈 결과와 구분하고 대상을 선택하지 못한다', async () => {
  const { user, post } = setup()
  server.use(
    http.get('/api/v1/objects', () =>
      HttpResponse.json(
        { error: { code: 'SEARCH_ERROR', message: '검색 요청 실패' } },
        { status: 500 },
      ),
    ),
  )
  await screen.findByRole('option', { name: '설비' })
  await user.selectOptions(screen.getByLabelText('도착 객체 유형'), '3')
  await user.click(screen.getByRole('button', { name: '도착 객체 검색' }))
  await screen.findByRole('alert')
  expect(
    screen.queryByText('조건에 맞는 도착 객체가 없습니다.'),
  ).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '연결 내용 확인' })).toBeDisabled()
  expect(post).not.toHaveBeenCalled()
})

test.each(['empty', 'forbidden', 'failure'] as const)(
  '유형 %s 상태를 표시하고 저장은 허용하지 않는다',
  async (mode) => {
    const { post } = setup()
    server.use(
      http.get('/api/v1/objects/types', () =>
        mode === 'empty'
          ? HttpResponse.json({ data: [] })
          : HttpResponse.json(
              { error: { code: 'TYPE_ERROR', message: '유형 조회 실패' } },
              { status: mode === 'forbidden' ? 403 : 500 },
            ),
      ),
    )
    if (mode === 'empty')
      await screen.findByText('조회할 수 있는 객체 유형이 없습니다.')
    else await screen.findByRole('alert')
    expect(screen.getByLabelText('도착 객체 유형')).toBeDisabled()
    expect(
      screen.getByRole('button', { name: '연결 내용 확인' }),
    ).toBeDisabled()
    expect(post).not.toHaveBeenCalled()
  },
)
