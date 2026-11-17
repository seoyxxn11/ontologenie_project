import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import ProposalDetail from './ProposalDetail'
import type { Proposal } from './types'

const base = {
  analysisRunId: 9,
  origin: 'AI',
  revision: 1,
  aiConfidence: 0.5,
  reviewStatus: 'PENDING',
  applyStatus: 'NOT_APPLIED',
  applyError: null,
  appliedAt: null,
  latestCheck: null,
}

// API-105 묶음 후보: 표 하나 = 객체 후보 한 건
const objectBundle: Proposal = {
  ...base,
  id: 801,
  itemKind: 'OBJECT_TYPE',
  payload: {
    code: 'WORK_ORDER',
    name: '작업지시',
    description: '원천 mes.work_order의 한 행',
    objectMapping: {
      sourceId: 'MES_MAIN',
      resourceKey: 'mes.work_order',
      keyColumns: ['wo_no'],
    },
    properties: [
      {
        code: 'wo_no',
        name: '작업지시 번호',
        dataType: 'STRING',
        isIdentifier: true,
        isRequired: true,
        sensitivity: 'NORMAL',
        sourceColumn: {
          sourceId: 'MES_MAIN',
          resourceKey: 'mes.work_order',
          table: 'work_order',
          column: 'wo_no',
        },
        transformSteps: [],
      },
      {
        code: 'worker_name',
        name: '작업자 이름',
        dataType: 'STRING',
        isIdentifier: false,
        isRequired: false,
        sensitivity: 'RESTRICTED',
        sourceColumn: {
          sourceId: 'MES_MAIN',
          resourceKey: 'mes.work_order',
          table: 'work_order',
          column: 'worker_name',
        },
        transformSteps: [{ op: 'TRIM', arg: null }],
      },
    ],
  },
  evidence: {
    reason: '원천 표 mes.work_order의 한 행을 객체 하나로 본다.',
    objectMapping: { reason: '기본키로 행 구분', items: [] },
    properties: [
      {
        code: 'wo_no',
        reason: '식별 키',
        items: [{ locator: 'mes.work_order.wo_no', verified: true }],
        mapping: { items: [{ verified: true }] },
      },
      {
        code: 'worker_name',
        reason: '개인정보',
        holdRecommended: true,
        holdReason: '개인정보 확인 필요',
        items: [{ locator: 'mes.work_order.worker_name', verified: false }],
      },
    ],
  },
}

// 관계 하나 = 관계 후보 한 건 (관계 유형을 payload.linkType에 함께 담음)
const linkBundle: Proposal = {
  ...base,
  id: 802,
  itemKind: 'LINK_RULE',
  aiConfidence: 0.79,
  payload: {
    code: 'WORK_ORDER_SO_REF_TO_SALES_ORDER',
    linkTypeCode: 'WORK_ORDER_SO_REF_TO_SALES_ORDER',
    pattern: 'transform',
    conditions: [
      {
        sourceProperty: 'WORK_ORDER.so_ref',
        targetProperty: 'SALES_ORDER.so_no',
        operator: 'EQ',
        sourceTransforms: [{ op: 'EXTRACT_NUMBER', arg: null }],
        targetTransforms: [{ op: 'EXTRACT_NUMBER', arg: null }],
      },
    ],
    linkType: {
      code: 'WORK_ORDER_SO_REF_TO_SALES_ORDER',
      name: 'WORK_ORDER → SALES_ORDER',
      sourceTypeCode: 'WORK_ORDER',
      targetTypeCode: 'SALES_ORDER',
      cardinality: 'MANY_TO_ONE',
      description: 'WORK_ORDER가 SALES_ORDER를 가리킨다.',
    },
  },
  evidence: { reason: '수주 참조 번호가 ERP 수주번호로 이어진다.' },
}

function open(proposal: Proposal) {
  server.use(
    http.get('/api/v1/proposals/:id', () =>
      HttpResponse.json({ data: proposal }),
    ),
  )
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <MemoryRouter initialEntries={[`/admin/rules/${proposal.id}`]}>
        <Routes>
          <Route path="/admin/rules/:id" element={<ProposalDetail />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

test('객체 후보는 속성과 원천 컬럼을 표로 보여 주고 같은 내용을 JSON으로 다시 늘어놓지 않는다', async () => {
  open(objectBundle)

  const table = await screen.findByRole('table', {
    name: 'WORK_ORDER 속성 목록',
  })
  const rows = within(table).getAllByRole('row')
  expect(rows).toHaveLength(3) // 머리글 + 속성 2개
  expect(rows[1]).toHaveTextContent('wo_no')
  expect(rows[1]).toHaveTextContent('문자') // 자료형 STRING을 우리말로
  expect(rows[1]).toHaveTextContent('mes.work_order.wo_no')
  expect(rows[1]).toHaveTextContent('확인')
  expect(rows[2]).toHaveTextContent('제한')
  expect(rows[2]).toHaveTextContent('mes.work_order.worker_name → TRIM')
  expect(rows[2]).toHaveTextContent('미확인')
  // 속성별 제안 이유와 보류 권고가 표에 남는다
  expect(rows[1]).toHaveTextContent('식별 키')
  expect(rows[2]).toHaveTextContent('보류 권고: 개인정보 확인 필요 · 개인정보')
  expect(screen.getByText('기본키로 행 구분')).toBeInTheDocument()
  expect(screen.getByText('MES_MAIN · mes.work_order')).toBeInTheDocument()
  expect(screen.getByText('2개')).toBeInTheDocument()
  // 제안 내용·근거에는 묶음 칸 없이 객체 유형 칸만 남는다
  expect(screen.queryByText('properties')).not.toBeInTheDocument()
  expect(screen.queryByText('objectMapping')).not.toBeInTheDocument()
  expect(screen.getByText('원천 mes.work_order의 한 행')).toBeInTheDocument()
})

test('그룹이 없으면 비공개로 들어간다고 알리고, 그룹·민감도를 고쳐 개정본으로 저장한다', async () => {
  let body: Record<string, unknown> | undefined
  server.use(
    http.get('/api/v1/proposals/:id/options', () =>
      HttpResponse.json({ data: [] }),
    ),
    http.post('/api/v1/proposals/:id/revisions', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>
      return HttpResponse.json(
        {
          data: {
            ...objectBundle,
            revision: 2,
            payload: body.payload,
            checkRunId: null,
          },
        },
        { status: 201 },
      )
    }),
  )
  open(objectBundle)

  expect(
    await screen.findByText(/승인 뒤 이 유형의 객체는 관리자에게만 보입니다/),
  ).toBeInTheDocument()
  const form = screen.getByRole('form', { name: '조회 그룹과 민감도 고치기' })
  const user = userEvent.setup()
  const save = within(form).getByRole('button', { name: '개정본으로 저장' })
  expect(save).toBeDisabled()
  // 식별 속성은 민감으로 둘 수 없다
  const identifier = within(form).getByLabelText('wo_no 민감도')
  expect(
    within(identifier).getByRole('option', { name: '제한(민감)' }),
  ).toBeDisabled()

  await user.selectOptions(
    within(form).getByLabelText('조회 그룹'),
    'PRODUCTION',
  )
  await user.selectOptions(
    within(form).getByLabelText('worker_name 민감도'),
    'NORMAL',
  )
  await user.click(save)

  expect(await within(form).findByRole('status')).toHaveTextContent(
    '개정본을 저장했습니다(개정 2).',
  )
  expect(body?.baseRevision).toBe(1)
  expect(body?.options).toEqual([])
  expect(body?.evidence).toEqual(objectBundle.evidence)
  const payload = body?.payload as Record<string, unknown>
  expect(payload.accessGroup).toBe('PRODUCTION')
  expect(
    (payload.properties as { code: string; sensitivity: string }[]).map(
      (property) => [property.code, property.sensitivity],
    ),
  ).toEqual([
    ['wo_no', 'NORMAL'],
    ['worker_name', 'NORMAL'],
  ])
  expect(payload.objectMapping).toEqual(objectBundle.payload.objectMapping)
})

test('결정한 후보는 그룹·민감도를 고칠 수 없다', async () => {
  open({
    ...objectBundle,
    id: 805,
    reviewStatus: 'APPROVED',
    payload: { ...objectBundle.payload, accessGroup: 'PRODUCTION' },
  })

  expect(await screen.findByText('생산·설비')).toBeInTheDocument()
  expect(
    screen.queryByRole('form', { name: '조회 그룹과 민감도 고치기' }),
  ).not.toBeInTheDocument()
})

test('관계 후보는 관계 유형과 비교 조건을 함께 보여 준다', async () => {
  open(linkBundle)

  const section = (
    await screen.findByRole('heading', { name: '관계' })
  ).closest('section')!
  expect(section).toHaveTextContent('WORK_ORDER → SALES_ORDER')
  expect(section).toHaveTextContent('N:1')
  expect(section).toHaveTextContent(
    'WORK_ORDER.so_ref → EXTRACT_NUMBER = SALES_ORDER.so_no → EXTRACT_NUMBER',
  )
  expect(screen.queryByText('linkType')).not.toBeInTheDocument()
  // 규칙 수정은 지금처럼 연결 규칙 후보에서 열린다
  expect(
    screen.getByRole('link', { name: '규칙 후보 수정' }),
  ).toBeInTheDocument()
})

test('다른 관계 유형으로 바꾼 규칙은 남은 예전 관계 유형을 묶음으로 보여 주지 않는다', async () => {
  open({
    ...linkBundle,
    id: 804,
    payload: { ...linkBundle.payload, linkTypeCode: 'OTHER_RELATION' },
  })

  expect(await screen.findByText('제안 내용')).toBeInTheDocument()
  expect(
    screen.queryByRole('heading', { name: '관계' }),
  ).not.toBeInTheDocument()
})

test('묶음 칸이 없는 예전 후보와 직접 추가한 규칙은 그대로 보여 준다', async () => {
  open({
    ...linkBundle,
    id: 803,
    origin: 'MANUAL',
    payload: { ...linkBundle.payload, linkType: undefined, linkTypeId: 2 },
  })

  expect(await screen.findByText('제안 내용')).toBeInTheDocument()
  expect(
    screen.queryByRole('heading', { name: '관계' }),
  ).not.toBeInTheDocument()
})
