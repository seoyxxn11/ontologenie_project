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

function renderDetail() {
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={['/admin/rules/401']}>
        <Routes>
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

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
