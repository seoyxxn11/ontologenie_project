import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter } from 'react-router'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import ObjectDetail from './ObjectDetail'
import ObjectLinks from './ObjectLinks'
import RelatedObjectList from './RelatedObjectList'
import { detailValue, linkContext } from './detailPresentation'
import type { ObjectLink, ObjectPropertyDefinition } from './types'

const definition: ObjectPropertyDefinition = {
  id: 1,
  code: 'PRESS_TON',
  name: '압력',
  dataType: 'DECIMAL',
}
const link: ObjectLink = {
  id: 801,
  linkTypeId: 7,
  linkTypeName: '발생 설비',
  sourceObjectId: 401,
  targetObjectId: 301,
  origin: 'DOCUMENT',
  status: 'ACTIVE',
  relatedObject: {
    id: 401,
    objectTypeId: 19,
    objectTypeName: '불량 기록',
    displayName: '검사 기록',
  },
  evidence: {
    event: '가공 불량',
    timeExpression: '11월 14일 오전',
    sourceKey: {
      work_order_no: 'WO-101',
      at: '2026-11-14 08:00',
      hidden: null,
    },
  },
}

function mount(element: React.ReactNode) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const view = render(
    <QueryClientProvider client={client}>
      <MemoryRouter>{element}</MemoryRouter>
    </QueryClientProvider>,
  )
  return { client, ...view }
}

test('속성 이름에서 단위를 추측하지 않고 정의가 준 단위만 표시한다', () => {
  expect(detailValue(12, definition)).toBe('12')
  expect(detailValue(0, { ...definition, unit: 'kPa' })).toBe('0 kPa')
  expect(detailValue('P101', { ...definition, unit: 't' })).toBe('P101')
  expect(detailValue(null)).toBe('값 없음')
  expect(detailValue(' ')).toBe('빈 문자열')
  expect(detailValue(false)).toBe('아니요')
  expect(detailValue([])).toBe('항목 없음')
  expect(detailValue({})).toBe('내용 없음')
  expect(
    detailValue('2026-11-14T00:00:00Z', {
      ...definition,
      dataType: 'DATETIME',
    }),
  ).toBe('2026-11-14 09:00:00 KST')
  expect(
    detailValue('2026-11-14T00:00:00', { ...definition, dataType: 'DATETIME' }),
  ).toBe('2026-11-14T00:00:00')
})

test('업무 요약은 속성 설명 없이 값·확인된 단위만 표시하고 원천 기술 정보는 접는다', async () => {
  server.use(
    http.get('/api/v1/objects/301', () =>
      HttpResponse.json({
        data: {
          id: 301,
          objectTypeId: 3,
          objectTypeName: '설비',
          displayName: 'P101',
          properties: {
            PRESS_TON: 12,
            STATE: 'R',
            EMPTY: null,
            HIDDEN: 'must-not-display',
          },
          propertyNames: {
            PRESS_TON: '압력',
            STATE: '상태',
            EMPTY: '미입력',
            HIDDEN: '제한 속성',
          },
          restrictedPropertyCodes: ['HIDDEN'],
          status: 'ACTIVE',
          sourceSystemName: 'MES',
          sourceResourceKey: 'mes.equipment',
        },
      }),
    ),
    http.get('/api/v1/ontology/object-types/3/properties', () =>
      HttpResponse.json({
        data: [
          { ...definition, unit: 'kPa', description: '설비 센서 측정값' },
          {
            ...definition,
            code: 'STATE',
            description: 'R: 가동, S: 정지',
            dataType: 'STRING',
          },
        ],
      }),
    ),
  )
  mount(<ObjectDetail objectId={301} />)
  expect(
    await screen.findByRole('heading', { name: '업무 요약' }),
  ).toBeVisible()
  expect(await screen.findByText('12 kPa')).toBeVisible()
  expect(screen.queryByText('R: 가동, S: 정지')).not.toBeInTheDocument()
  expect(screen.queryByText('설비 센서 측정값')).not.toBeInTheDocument()
  expect(screen.getByText('값 없음')).toBeVisible()
  expect(screen.getByText('권한 없음')).toBeVisible()
  expect(screen.queryByText('must-not-display')).not.toBeInTheDocument()
  expect(screen.getByText('MES · mes.equipment')).not.toBeVisible()
  await userEvent
    .setup()
    .click(screen.getByText('수집 정보', { selector: 'summary' }))
  expect(screen.getByText('MES · mes.equipment')).toBeVisible()
})

test('조회 역할의 정의 조회가 거절돼도 객체 값은 보이고 단위를 덧붙이거나 재시도하지 않는다', async () => {
  setMockAccount({ ...demoAccount, roleCode: 'VIEWER' })
  const denied = vi.fn(() =>
    HttpResponse.json(
      { error: { code: 'FORBIDDEN', message: '권한 없음' } },
      { status: 403 },
    ),
  )
  server.use(http.get('/api/v1/ontology/object-types/3/properties', denied))
  const { client } = mount(<ObjectDetail objectId={301} />)
  expect(
    await screen.findByText(
      '속성 단위를 확인할 수 없습니다. 값은 제공된 그대로 표시합니다.',
    ),
  ).toBeVisible()
  expect(screen.getByRole('heading', { name: '프레스 3호기' })).toBeVisible()
  await waitFor(() => expect(client.isFetching()).toBe(0))
  expect(denied).toHaveBeenCalledTimes(1)
  expect(screen.getByText('권한 없음')).toBeInTheDocument()
})

test('동명 연결 대상은 객체 번호·제공된 시간·작업지시로 구분하고 가려진 근거는 요약하지 않는다', async () => {
  expect(linkContext(link)).toContain('work_order_no: WO-101')
  const hidden = {
    ...link,
    id: 802,
    relatedObject: { ...link.relatedObject, id: 402 },
    evidence: {
      ...link.evidence,
      evidenceRestricted: true,
      quote: 'do-not-show-quote',
      event: 'do-not-show-event',
    },
  }
  server.use(
    http.get('/api/v1/objects/301/links', () =>
      HttpResponse.json({
        data: [link, hidden],
        meta: { page: 0, size: 100, totalElements: 2, hasNext: false },
      }),
    ),
  )
  mount(<ObjectLinks objectId={301} />)
  expect(await screen.findByText('불량 기록 · 객체 #401')).toBeVisible()
  expect(screen.getByText('불량 기록 · 객체 #402')).toBeVisible()
  expect(
    screen.getByText(/11월 14일 오전 · 가공 불량 · 연결 원천 행:/),
  ).toBeVisible()
  expect(
    screen.getByText('연결 근거는 권한으로 가려져 있습니다.'),
  ).toBeVisible()
  expect(screen.queryByText(/do-not-show/)).not.toBeInTheDocument()
})

test('관계 묶음의 목록 카드도 시간·원천 행 문맥을 표시한다', async () => {
  server.use(
    http.get('/api/v1/objects/301/links', () =>
      HttpResponse.json({
        data: [link],
        meta: { page: 0, size: 8, totalElements: 1, hasNext: false },
      }),
    ),
  )
  mount(
    <RelatedObjectList
      objectId={301}
      anchorName="P101"
      group={{
        key: '7:in',
        linkTypeId: 7,
        linkTypeName: '발생 설비',
        relatedTypeName: '불량 기록',
        direction: 'IN',
        outgoing: false,
        linkCount: 1,
      }}
      selectedId={null}
      vanished={false}
      onSelect={vi.fn()}
      onReturn={vi.fn()}
    />,
  )
  const card = await screen.findByRole('button', { name: /검사 기록/ })
  expect(within(card).getByText(/work_order_no: WO-101/)).toBeVisible()
})

const longName = '가'.repeat(120)
const ruleLink: ObjectLink = {
  id: 811,
  linkTypeId: 3,
  linkTypeName: '생산 품목',
  sourceObjectId: 301,
  targetObjectId: 412,
  origin: 'RULE',
  status: 'ACTIVE',
  linkMappingId: 9,
  relatedObject: {
    id: 412,
    objectTypeId: 7,
    objectTypeName: '품목',
    displayName: 'CL-590',
  },
  evidence: {
    comparisons: [
      {
        sequenceNo: 1,
        sourceProperty: 'item_code',
        sourceValue: 'CL-590',
        targetProperty: 'item_code',
        targetValue: 'CL-590',
        matchedValue: 'CL-590',
      },
    ],
  },
}

async function comparisonHeaders(item: ObjectLink, objectTypeName?: string) {
  server.use(
    http.get('/api/v1/objects/301/links', () =>
      HttpResponse.json({
        data: [item],
        meta: { page: 0, size: 100, totalElements: 1, hasNext: false },
      }),
    ),
  )
  mount(<ObjectLinks objectId={301} objectTypeName={objectTypeName} />)
  await userEvent.setup().click(await screen.findByText('상세 근거'))
  return within(screen.getByRole('table', { name: '비교 조건' })).getAllByRole(
    'columnheader',
  )
}

test('양쪽 값이 같은 규칙 연결도 머리글로 어느 객체의 값인지 구분한다', async () => {
  const headers = await comparisonHeaders(ruleLink, '작업지시')
  expect(headers.map((cell) => cell.textContent)).toEqual([
    '이 객체 · 작업지시',
    'CL-590 · 품목',
  ])
  expect(screen.getAllByText('item_code = CL-590')).toHaveLength(2)
  expect(screen.queryByText('출발 원본')).not.toBeInTheDocument()
})

test('보고 있는 객체의 유형 이름을 모르면 이 객체로만 표시한다', async () => {
  const headers = await comparisonHeaders(ruleLink)
  expect(headers.map((cell) => cell.textContent)).toEqual([
    '이 객체',
    'CL-590 · 품목',
  ])
})

test('연결된 객체 이름이 길어도 머리글 칸 안에서 줄바꿈한다', async () => {
  const headers = await comparisonHeaders(
    {
      ...ruleLink,
      relatedObject: { ...ruleLink.relatedObject, displayName: longName },
    },
    '작업지시',
  )
  expect(headers[1]).toHaveTextContent(`${longName} · 품목`)
  expect(headers[1]).toHaveClass('[overflow-wrap:anywhere]')
})
