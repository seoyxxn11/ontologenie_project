import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import ProposalDetail from '../review/ProposalDetail'
import type { Proposal, ProposalOption } from '../review/types'
import { splitOriginal } from './documentLink'

const note = '야간에 3호기 진동이 커진 것 같다. 누유는 없음.'

function documentLink(overrides: Partial<Proposal> = {}): Proposal {
  return {
    id: 401,
    analysisRunId: 52,
    origin: 'AI',
    itemKind: 'DOCUMENT_LINK',
    revision: 1,
    payload: {
      linkTypeCode: 'EQUIPMENT',
      documentId: 'HN-0007',
      spanStart: 4,
      spanEnd: 7,
      mentionText: '3호기',
      targetObjectRef: 'equipment:P103',
      expressionKind: 'ESTIMATED',
    },
    evidence: {
      reason: "'3호기'를 설비 이름과 맞춰 equipment:P103에 잇는다.",
      originalText: note,
      observations: [
        {
          text: '진동이 커진 것 같다',
          spanStart: 8,
          spanEnd: 19,
          assertion: 'UNCERTAIN',
        },
        {
          text: '누유는 없음',
          spanStart: 21,
          spanEnd: 27,
          assertion: 'NEGATED',
        },
      ],
    },
    aiConfidence: 0.5,
    reviewStatus: 'PENDING',
    applyStatus: 'NOT_APPLIED',
    applyError: null,
    appliedAt: null,
    latestCheck: null,
    ...overrides,
  }
}

function option(optionNo: number, ref: string): ProposalOption {
  return {
    id: 900 + optionNo,
    optionNo,
    revision: 1,
    payload: { targetObjectRef: ref },
    evidence: {},
    aiConfidence: 0.2,
  }
}

function serve(proposal: Proposal, options: ProposalOption[] = []) {
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({ data: proposal }),
    ),
    http.get('/api/v1/proposals/:id/options', () =>
      HttpResponse.json({ data: options }),
    ),
  )
}

function renderDetail(embedded = false) {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/admin/rules/401']}>
        <Routes>
          <Route
            path="/admin/rules/:id"
            element={<ProposalDetail embeddedId={embedded ? 401 : undefined} />}
          />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('문서 후보의 기본 상세에서 펼치기 없이 내용·대상·판단 근거를 확인한다', async () => {
  const proposal = documentLink({
    evidence: {
      ...documentLink().evidence,
      documentLabel: '10/29 야간 인수인계',
      targetLabels: { 'equipment:P103': '1라인 3호 프레스' },
    },
  })
  serve(proposal)
  renderDetail(true)

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(result).toBeVisible()
  expect(result.closest('details')).toBeNull()
  expect(within(result).getByLabelText('원문')).toHaveTextContent(note)
  expect(within(result).getByText('1라인 3호 프레스 (P103)')).toBeVisible()
  expect(within(result).getByText('판단 근거')).toBeVisible()
  expect(
    within(result).getByText(String(proposal.evidence.reason)),
  ).toBeVisible()
  expect(screen.getByRole('region', { name: '제안 근거' })).toBeVisible()
  expect(screen.getByRole('region', { name: '정합성 검사' })).toBeVisible()
  expect(
    screen.queryByRole('heading', { name: '연결·변환 규칙' }),
  ).not.toBeInTheDocument()
  expect(
    screen.getAllByRole('region', { name: '정보 추출 결과' }),
  ).toHaveLength(1)
})

test('기본 상세는 마스킹된 응답을 그대로 표시하고 문서·원천을 별도 조회하지 않는다', async () => {
  const requests: string[] = []
  const trackRequest = ({ request }: { request: Request }) => {
    requests.push(new URL(request.url).pathname)
  }
  serve(
    documentLink({
      evidence: {
        originalText: '작업자 ***: 3호기 확인',
        reason: '작업자 ***의 메모에서 설비를 확인했습니다.',
        targetLabels: { 'equipment:P103': '***' },
      },
    }),
  )
  server.events.on('request:start', trackRequest)
  try {
    renderDetail(true)
    const result = await screen.findByRole('region', { name: '정보 추출 결과' })
    expect(within(result).getByLabelText('원문')).toHaveTextContent(
      '작업자 ***: 3호기 확인',
    )
    expect(within(result).getByText('*** (P103)')).toBeVisible()
    expect(
      within(result).getByText('작업자 ***의 메모에서 설비를 확인했습니다.'),
    ).toBeVisible()
    await screen.findByRole('button', { name: '승인' })
    expect(requests.length).toBeGreaterThan(0)
    expect(
      requests.every((path) =>
        /^\/api\/v1\/proposals\/401(?:\/options)?$/.test(path),
      ),
    ).toBe(true)
  } finally {
    server.events.removeListener('request:start', trackRequest)
  }
})

test('운영 응답 형태처럼 대상 이름·관찰·대안이 없어도 기본 상세에서 판단할 수 있다', async () => {
  // 운영 원문을 복사하지 않고 #901에서 확인한 필드 형태만 합성한다.
  const original = '새벽 3시쯤 JOB-TEST-1의 남은 작업을 주간조에 전달함.'
  const mention = 'JOB-TEST-1'
  const start = original.indexOf(mention)
  serve(
    documentLink({
      checkRequired: false,
      payload: {
        name: '시험 인수인계 → 작업지시 JOB-TEST-1',
        documentId: 'HN-TEST-1',
        mentionText: mention,
        spanStart: start,
        spanEnd: start + mention.length,
        linkTypeCode: 'ABOUT_WORK_ORDER',
        targetObjectRef: 'mes.work_order:JOB-TEST-1',
        expressionKind: 'OBSERVED',
      },
      evidence: {
        documentLabel: '시험 인수인계',
        originalText: original,
        reason: '메모에 작업지시 번호가 직접 적혀 있습니다.',
        observations: [],
        times: [
          {
            text: '새벽 3시쯤',
            basis: '글에 적힌 시각',
            spanStart: 0,
            spanEnd: 6,
            normalized: '2026-10-30T03:00:00',
          },
        ],
        holdRecommended: false,
        holdReason: null,
      },
    }),
    [],
  )
  renderDetail(true)

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(within(result).getByLabelText('원문')).toHaveTextContent(original)
  expect(within(result).getByText('mes.work_order:JOB-TEST-1')).toBeVisible()
  expect(
    within(result).getByText('메모에 작업지시 번호가 직접 적혀 있습니다.'),
  ).toBeVisible()
  expect(within(result).getByTitle('연결한 언급')).toHaveTextContent(mention)
  expect(within(result).getByText('확인된 언급')).toBeVisible()
  expect(
    within(screen.getByRole('region', { name: '정합성 검사' })).getByText(
      /검사 대상 아님: 이 종류는 원천 행으로 시험하지 않으며/,
    ),
  ).toBeVisible()
  expect(within(result).queryByText('미확정')).not.toBeInTheDocument()
  expect(
    within(result).queryByRole('list', { name: '후보 대상' }),
  ).not.toBeInTheDocument()
})

test.each([undefined, '', '   '])(
  '기본 상세에서 비어 있는 문서·대상·근거(%s)의 미제공을 명확하게 알린다',
  async (empty) => {
    serve(
      documentLink({
        payload: { targetObjectRef: empty },
        evidence: { originalText: empty, reason: empty },
      }),
    )
    renderDetail(true)

    const result = await screen.findByRole('region', { name: '정보 추출 결과' })
    expect(
      within(result).getByText('원문이 제공되지 않았습니다.'),
    ).toBeVisible()
    expect(
      within(result).getByText('연결 대상이 제공되지 않았습니다.'),
    ).toBeVisible()
    expect(
      within(result).getByText('판단 근거가 제공되지 않았습니다.'),
    ).toBeVisible()
  },
)

test('보류 후보도 기본 화면에서 미확정 이유와 복수 연결 대상을 확인한다', async () => {
  serve(
    documentLink({
      reviewStatus: 'HELD',
      evidence: {
        ...documentLink().evidence,
        holdRecommended: true,
        holdReason: '같은 별칭을 쓰는 설비가 둘입니다.',
        targetLabels: {
          'equipment:P103': '1라인 3호 프레스',
          'equipment:P203': '2라인 3호 프레스',
        },
      },
    }),
    [option(1, 'equipment:P103'), option(2, 'equipment:P203')],
  )
  renderDetail(true)

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(
    within(result).getByText('같은 별칭을 쓰는 설비가 둘입니다.'),
  ).toBeVisible()
  expect(within(result).getAllByText('미확정')).toHaveLength(2)
  const candidates = await within(result).findByRole('list', {
    name: '후보 대상',
  })
  expect(candidates).toBeVisible()
  expect(candidates).toHaveTextContent('1. 1라인 3호 프레스 (P103)')
  expect(candidates).toHaveTextContent('2. 2라인 3호 프레스 (P203)')
})

test('상세 조회가 거부되면 문서 정보를 표시하거나 후보 대상을 조회하지 않는다', async () => {
  let optionRequests = 0
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json(
        { error: { code: 'FORBIDDEN', message: '조회 권한이 없습니다.' } },
        { status: 403 },
      ),
    ),
    http.get('/api/v1/proposals/:id/options', () => {
      optionRequests += 1
      return HttpResponse.json({ data: [] })
    }),
  )
  renderDetail(true)

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '조회 권한이 없습니다.',
  )
  expect(screen.queryByLabelText('원문')).not.toBeInTheDocument()
  expect(optionRequests).toBe(0)
})

test('원문에서 연결한 언급과 추정·부정 표현을 나눠 보여준다', async () => {
  serve(documentLink())
  renderDetail()

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  const original = within(result).getByLabelText('원문')
  expect(original).toHaveTextContent(note)
  expect(within(original).getByTitle('연결한 언급')).toHaveTextContent('3호기')
  expect(within(original).getByTitle('추정')).toHaveTextContent(
    '진동이 커진 것 같다',
  )
  expect(within(original).getByTitle('부정')).toHaveTextContent('누유는 없음')
  expect(within(result).getByText('추정 표현')).toBeInTheDocument()
  expect(within(result).getByText('equipment:P103')).toBeInTheDocument()
  const observations = within(result).getByRole('list', { name: '관찰 표현' })
  expect(observations).toHaveTextContent('추정 진동이 커진 것 같다')
  expect(observations).toHaveTextContent('부정 누유는 없음')
  expect(within(result).queryByText('미확정')).not.toBeInTheDocument()
})

test('후보가 여럿인 보류 언급은 미확정으로 두고 후보 대상을 모두 보여준다', async () => {
  serve(
    documentLink({
      reviewStatus: 'HELD',
      payload: {
        ...documentLink().payload,
        expressionKind: 'OBSERVED',
      },
      evidence: {
        originalText: note,
        holdRecommended: true,
        holdReason: '후보 설비가 여럿',
      },
    }),
    [option(1, 'equipment:P103'), option(2, 'equipment:P203')],
  )
  renderDetail()

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(within(result).getByText('확인된 언급')).toBeInTheDocument()
  expect(within(result).getAllByText('미확정')).toHaveLength(2)
  expect(within(result).getByText('후보 설비가 여럿')).toBeInTheDocument()
  const candidates = await within(result).findByRole('list', {
    name: '후보 대상',
  })
  expect(within(candidates).getAllByRole('listitem')).toHaveLength(2)
  expect(candidates).toHaveTextContent('2. equipment:P203')
  expect(
    within(result).getByText('복수 후보 2개 · 하나로 정하지 못했습니다'),
  ).toBeInTheDocument()
})

test('메모·대상은 번호 대신 이름으로 보여준다', async () => {
  serve(
    documentLink({
      evidence: {
        ...documentLink().evidence,
        documentLabel: '10/29 야간 인수인계',
        targetLabels: {
          'equipment:P103': '1라인 3호 프레스',
          'equipment:P203': '2라인 3호 프레스',
        },
      },
    }),
  )
  renderDetail()

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(
    within(result).getByText('원문 · 10/29 야간 인수인계 (HN-0007)'),
  ).toBeInTheDocument()
  expect(
    within(result).getByText('1라인 3호 프레스 (P103)'),
  ).toBeInTheDocument()
  expect(within(result).queryByText('equipment:P103')).not.toBeInTheDocument()
})

test('보류 후보 목록도 대상 이름으로 보여준다', async () => {
  serve(
    documentLink({
      reviewStatus: 'HELD',
      evidence: {
        originalText: note,
        holdRecommended: true,
        targetLabels: {
          'equipment:P103': '1라인 3호 프레스',
          'equipment:P203': '2라인 3호 프레스',
        },
      },
    }),
    [option(1, 'equipment:P103'), option(2, 'equipment:P203')],
  )
  renderDetail()

  const candidates = await screen.findByRole('list', { name: '후보 대상' })
  expect(candidates).toHaveTextContent('1. 1라인 3호 프레스 (P103)')
  expect(candidates).toHaveTextContent('2. 2라인 3호 프레스 (P203)')
})

test('원문이 없으면 없다고 알리고 추출 결과만 보여준다', async () => {
  serve(documentLink({ evidence: {} }))
  renderDetail()

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(
    within(result).getByText('원문이 제공되지 않았습니다.'),
  ).toBeInTheDocument()
  expect(within(result).getByText('3호기')).toBeInTheDocument()
})

test('후보 대상을 불러오지 못하면 오류를 보여준다', async () => {
  serve(documentLink())
  server.use(
    http.get('/api/v1/proposals/:id/options', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '이 작업을 할 권한이 없습니다.',
            details: {},
            requestId: 'mock-proposal',
          },
        },
        { status: 403 },
      ),
    ),
  )
  renderDetail()

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(await within(result).findByRole('alert')).toHaveTextContent(
    '이 작업을 할 권한이 없습니다.',
  )
})

test('문서 연결이 아닌 제안에는 추출 결과를 그리지 않는다', async () => {
  serve(documentLink({ itemKind: 'LINK_RULE' }))
  renderDetail()

  await screen.findByRole('heading', { name: '제안 내용' })
  expect(
    screen.queryByRole('region', { name: '정보 추출 결과' }),
  ).not.toBeInTheDocument()
})

test('겹치거나 원문 밖에 있는 표시 구간은 건너뛴다', () => {
  expect(
    splitOriginal({
      originalText: '3호기 소음',
      spanStart: 0,
      spanEnd: 3,
      observations: [
        { text: '호기', spanStart: 1, spanEnd: 3, assertion: 'AFFIRMED' },
        { text: '밖', spanStart: 10, spanEnd: 12, assertion: 'NEGATED' },
      ],
      times: [],
      holdRecommended: false,
    }),
  ).toEqual([{ text: '3호기', mark: 'mention' }, { text: ' 소음' }])
})

test('글 속 시각 표현을 원문에 표시하고 정한 시각이나 못 정한 이유를 보여준다', async () => {
  serve(
    documentLink({
      evidence: {
        originalText: note,
        times: [
          {
            text: '야간',
            spanStart: 0,
            spanEnd: 2,
            normalized: null,
            basis: '기준 시각을 원천에서 알 수 없어 정하지 않음',
          },
          { text: '없는 위치', spanStart: 90, spanEnd: 95 },
        ],
      },
    }),
  )
  renderDetail()

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(
    within(within(result).getByLabelText('원문')).getByTitle('시각'),
  ).toHaveTextContent('야간')
  const times = within(result).getByRole('list', { name: '시각 표현' })
  expect(times).toHaveTextContent(
    '야간 → 정하지 않음 (기준 시각을 원천에서 알 수 없어 정하지 않음)',
  )
  // 원문 밖 위치는 원문에 표시하지 않는다
  expect(within(result).getByLabelText('원문')).toHaveTextContent(note)
})

test.each(['APPROVED', 'REJECTED'])(
  '보류 권고였어도 %s로 결정한 뒤에는 미확정으로 보이지 않는다',
  async (reviewStatus) => {
    serve(
      documentLink({
        reviewStatus,
        evidence: {
          originalText: note,
          holdRecommended: true,
          holdReason: '후보 설비가 여럿',
        },
      }),
    )
    renderDetail()

    const result = await screen.findByRole('region', {
      name: '정보 추출 결과',
    })
    expect(within(result).queryByText('미확정')).not.toBeInTheDocument()
    expect(within(result).getByText('equipment:P103')).toBeInTheDocument()
    expect(within(result).queryByText('미확정 이유')).not.toBeInTheDocument()
  },
)

test('검토 전 보류 권고는 미확정으로 둔다', async () => {
  serve(
    documentLink({
      evidence: { originalText: note, holdRecommended: true },
    }),
  )
  renderDetail()

  const result = await screen.findByRole('region', { name: '정보 추출 결과' })
  expect(within(result).getAllByText('미확정')).toHaveLength(2)
})

test('원문 위치는 글자(코드 포인트) 단위라 이모지 뒤 강조가 밀리지 않는다', () => {
  expect(
    splitOriginal({
      originalText: '🔧 3호기 진동 😥 심함',
      spanStart: 2,
      spanEnd: 5,
      observations: [
        { text: '심함', spanStart: 11, spanEnd: 13, assertion: 'AFFIRMED' },
      ],
      times: [],
      holdRecommended: false,
    }),
  ).toEqual([
    { text: '🔧 ' },
    { text: '3호기', mark: 'mention' },
    { text: ' 진동 😥 ' },
    { text: '심함', mark: 'AFFIRMED' },
  ])
})
