import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { delay, http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import App from '../../App'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import { server } from '../../mocks/node'
import type { ObjectTypeItem, PropertyItem } from './types'
import { resetMappingMocks } from '../../mocks/mappingHandlers'
import { apiGet } from '../../api/client'

const equipment: ObjectTypeItem = {
  id: 1,
  code: 'EQUIPMENT',
  name: '설비',
  displayPropertyId: null,
  accessGroup: null,
  rowVersion: 3,
}
const item: ObjectTypeItem = {
  id: 2,
  code: 'ITEM',
  name: '품목',
  displayPropertyId: 21,
  accessGroup: 'SALES_SUPPLY',
  rowVersion: 0,
}
const equipmentProperties: PropertyItem[] = [
  { id: 11, code: 'code', name: '설비 코드', sensitivity: 'NORMAL' },
  { id: 12, code: 'name', name: '설비명', sensitivity: 'NORMAL' },
  { id: 13, code: 'maker', name: '제조사', sensitivity: 'RESTRICTED' },
]
const itemProperties: PropertyItem[] = [
  { id: 21, code: 'item_name', name: '품목명', sensitivity: 'NORMAL' },
]

function serveTypes(types = [equipment, item]) {
  server.use(
    http.get('/api/v1/ontology/object-types', () =>
      HttpResponse.json({
        data: types,
        meta: { page: 0, size: 100, totalElements: types.length },
      }),
    ),
    http.get('/api/v1/ontology/object-types/:id/properties', ({ params }) =>
      HttpResponse.json({
        data: Number(params.id) === 1 ? equipmentProperties : itemProperties,
      }),
    ),
  )
}

function open() {
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
  window.history.pushState({}, '', '/admin/object-types')
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <App />
    </QueryClientProvider>,
  )
}

async function choose(name: string) {
  const user = userEvent.setup()
  const table = await screen.findByRole('table', { name: '객체 유형 목록' })
  await user.click(within(table).getByRole('button', { name }))
  return user
}

test('기본 로컬 모의 API로 대표 속성과 조회 그룹을 저장하고 다시 조회할 수 있다', async () => {
  resetMappingMocks()
  open()
  const user = await choose('품목')
  const select = await screen.findByLabelText('대표 속성')
  expect(select).toHaveValue('')
  await user.selectOptions(select, '601')
  await user.click(screen.getByRole('button', { name: '대표 속성 저장' }))
  await screen.findByText(/다시 읽도록 예약했습니다/)
  expect(screen.getByLabelText('대표 속성')).toHaveValue('601')
  expect(screen.getByRole('button', { name: '대표 속성 저장' })).toBeDisabled()
  await user.selectOptions(screen.getByLabelText('조회 그룹'), 'SALES_SUPPLY')
  await user.click(screen.getByRole('button', { name: '그룹 저장' }))
  await screen.findByText(/조회 그룹을 저장했습니다/)
  expect(screen.getByLabelText('대표 속성')).toHaveValue('601')
  expect(screen.getByLabelText('조회 그룹')).toHaveValue('SALES_SUPPLY')
  expect(screen.getByRole('button', { name: '그룹 저장' })).toBeDisabled()
  expect(
    (await apiGet<ObjectTypeItem[]>('/ontology/object-types')).data[0],
  ).toMatchObject({
    displayPropertyId: 601,
    accessGroup: 'SALES_SUPPLY',
    rowVersion: 2,
  })
})

test('대표 속성을 고르면 민감 속성을 빼고 보여 주고, 저장하면 024로 보내고 다시 읽기 안내를 띄운다', async () => {
  serveTypes()
  let body: Record<string, unknown> | undefined
  server.use(
    http.patch('/api/v1/ontology/object-types/:id', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>
      return HttpResponse.json({
        data: { ...equipment, displayPropertyId: 12, rowVersion: 4 },
      })
    }),
  )
  open()
  const table = await screen.findByRole('table', { name: '객체 유형 목록' })
  const rows = within(table).getAllByRole('row')
  expect(within(rows[1]).getByText('원천 키 사용')).toBeInTheDocument()
  expect(within(rows[2]).getByText('지정됨')).toBeInTheDocument()

  const user = await choose('설비')
  const select = await screen.findByLabelText('대표 속성')
  expect(
    within(select)
      .getAllByRole('option')
      .map((option) => option.textContent),
  ).toEqual([
    '지정하지 않음 (원천 키 값 사용)',
    '설비 코드 (code)',
    '설비명 (name)',
  ])
  expect(
    screen.getByText('민감 속성 1개는 대표 속성으로 고를 수 없습니다.'),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '대표 속성 저장' })).toBeDisabled()

  await user.selectOptions(select, '12')
  await user.click(screen.getByRole('button', { name: '대표 속성 저장' }))

  expect(
    await screen.findByText(/다시 읽도록 예약했습니다/),
  ).toBeInTheDocument()
  expect(body).toEqual({ displayPropertyId: 12, rowVersion: 3 })
  expect(screen.getByLabelText('대표 속성')).toHaveValue('12')
  expect(within(rows[1]).getByText('지정됨')).toBeInTheDocument()
})

test('지정하지 않음을 고르면 대표 속성을 비우도록 null을 보낸다', async () => {
  serveTypes()
  let body: Record<string, unknown> | undefined
  server.use(
    http.patch('/api/v1/ontology/object-types/:id', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>
      return HttpResponse.json({
        data: { ...item, displayPropertyId: null, rowVersion: 1 },
      })
    }),
  )
  open()
  const user = await choose('품목')
  expect(await screen.findByLabelText('대표 속성')).toHaveValue('21')

  await user.selectOptions(screen.getByLabelText('대표 속성'), '')
  await user.click(screen.getByRole('button', { name: '대표 속성 저장' }))

  await screen.findByText(/다시 읽도록 예약했습니다/)
  expect(body).toEqual({ displayPropertyId: null, rowVersion: 0 })
})

test('다른 사람이 먼저 바꿔 409가 오면 안내하고 목록을 다시 불러온다', async () => {
  serveTypes()
  server.use(
    http.patch('/api/v1/ontology/object-types/:id', () =>
      HttpResponse.json(
        {
          error: {
            code: 'VERSION_CONFLICT',
            message: '다른 사용자가 먼저 수정했습니다.',
          },
        },
        { status: 409 },
      ),
    ),
  )
  open()
  const user = await choose('설비')
  await user.selectOptions(await screen.findByLabelText('대표 속성'), '11')
  await user.click(screen.getByRole('button', { name: '대표 속성 저장' }))

  expect(
    await screen.findByText(/다른 사람이 먼저 이 유형을 바꿨습니다/),
  ).toBeInTheDocument()

  serveTypes([{ ...equipment, displayPropertyId: 11, rowVersion: 4 }, item])
  await user.click(screen.getByRole('button', { name: '목록 다시 불러오기' }))
  expect(await screen.findByLabelText('대표 속성')).toHaveValue('11')
  expect(
    screen.queryByText(/다른 사람이 먼저 이 유형을 바꿨습니다/),
  ).not.toBeInTheDocument()
})

test('목록을 불러오지 못하면 오류와 다시 불러오기를 보여 준다', async () => {
  server.use(
    http.get('/api/v1/ontology/object-types', () =>
      HttpResponse.json(
        { error: { code: 'INTERNAL_ERROR', message: '서버 오류' } },
        { status: 500 },
      ),
    ),
  )
  open()

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '서버 요청을 처리하지 못했습니다.',
  )
  expect(
    screen.getByRole('button', { name: '다시 불러오기' }),
  ).toBeInTheDocument()
})

test('속성 목록을 불러오는 동안과 실패했을 때를 따로 보여 준다', async () => {
  serveTypes()
  server.use(
    http.get('/api/v1/ontology/object-types/:id/properties', async () => {
      await delay(50)
      return HttpResponse.json(
        { error: { code: 'INTERNAL_ERROR', message: '서버 오류' } },
        { status: 500 },
      )
    }),
  )
  open()
  await choose('설비')

  expect(
    await screen.findByText('속성 목록을 불러오는 중…'),
  ).toBeInTheDocument()
  expect(await screen.findByRole('alert')).toHaveTextContent('서버 오류')
  expect(screen.queryByLabelText('대표 속성')).not.toBeInTheDocument()
})

test('조회 그룹을 고르면 024로 보내고, 목록에 그룹을 보여 준다', async () => {
  serveTypes()
  let body: Record<string, unknown> | undefined
  server.use(
    http.patch('/api/v1/ontology/object-types/:id', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>
      return HttpResponse.json({
        data: { ...equipment, accessGroup: 'PRODUCTION', rowVersion: 4 },
      })
    }),
  )
  open()
  const table = await screen.findByRole('table', { name: '객체 유형 목록' })
  const rows = within(table).getAllByRole('row')
  expect(within(rows[1]).getByText('비공개 (그룹 없음)')).toBeInTheDocument()
  expect(within(rows[2]).getByText('영업·구매·재고')).toBeInTheDocument()

  const user = await choose('설비')
  const select = await screen.findByLabelText('조회 그룹')
  expect(
    within(select)
      .getAllByRole('option')
      .map((option) => option.textContent),
  ).toEqual([
    '비공개 (그룹 없음)',
    '생산·설비 (PRODUCTION)',
    '영업·구매·재고 (SALES_SUPPLY)',
  ])
  expect(screen.getByRole('button', { name: '그룹 저장' })).toBeDisabled()

  await user.selectOptions(select, 'PRODUCTION')
  await user.click(screen.getByRole('button', { name: '그룹 저장' }))

  expect(
    await screen.findByText(/조회 그룹을 저장했습니다/),
  ).toBeInTheDocument()
  expect(body).toEqual({ accessGroup: 'PRODUCTION', rowVersion: 3 })
  expect(within(rows[1]).getByText('생산·설비')).toBeInTheDocument()
})

test('비공개를 고르면 null을 보낸다', async () => {
  serveTypes()
  let body: Record<string, unknown> | undefined
  server.use(
    http.patch('/api/v1/ontology/object-types/:id', async ({ request }) => {
      body = (await request.json()) as Record<string, unknown>
      return HttpResponse.json({
        data: { ...item, accessGroup: null, rowVersion: 1 },
      })
    }),
  )
  open()
  const user = await choose('품목')
  await user.selectOptions(await screen.findByLabelText('조회 그룹'), '')
  await user.click(screen.getByRole('button', { name: '그룹 저장' }))

  await screen.findByText(/조회 그룹을 저장했습니다/)
  expect(body).toEqual({ accessGroup: null, rowVersion: 0 })
})

test('정책을 어긴 매핑이 있어 거절되면 어긴 매핑을 보여 준다', async () => {
  serveTypes()
  server.use(
    http.patch('/api/v1/ontology/object-types/:id', () =>
      HttpResponse.json(
        {
          error: {
            code: 'BUSINESS_RULE_VIOLATION',
            message:
              '원천 데이터 정책을 어기는 매핑이 있어 그룹을 지정할 수 없습니다. 매핑을 고친 뒤 지정하세요.',
            details: {
              field: 'accessGroup',
              violations: [
                {
                  kind: 'FORBIDDEN_COLUMN',
                  resourceKey: 'erp.employee',
                  columnName: 'emp_name',
                  objectMappingId: 8,
                  propertyMappingId: 51,
                  propertyCode: 'emp_name',
                },
              ],
            },
          },
        },
        { status: 422 },
      ),
    ),
  )
  open()
  const user = await choose('설비')
  await user.selectOptions(
    await screen.findByLabelText('조회 그룹'),
    'PRODUCTION',
  )
  await user.click(screen.getByRole('button', { name: '그룹 저장' }))

  const list = await screen.findByRole('list', { name: '정책을 어긴 매핑' })
  expect(list).toHaveTextContent(
    'erp.employee.emp_name → emp_name · 금지 컬럼(수집하지 않음)',
  )
})

test('이름 외 셀을 눌러도 유형을 선택하고 키보드 선택도 유지한다', async () => {
  serveTypes()
  open()
  const user = userEvent.setup()
  const table = await screen.findByRole('table', { name: '객체 유형 목록' })
  const rows = within(table).getAllByRole('row')
  await user.click(within(rows[1]).getByText('원천 키 사용'))
  expect(await screen.findByLabelText('대표 속성')).toHaveValue('')
  expect(within(rows[1]).getByRole('button', { name: '설비' })).toHaveAttribute(
    'aria-pressed',
    'true',
  )
  const itemButton = within(rows[2]).getByRole('button', { name: '품목' })
  itemButton.focus()
  await user.keyboard('[Space]')
  expect(await screen.findByLabelText('대표 속성')).toHaveValue('21')
  expect(itemButton).toHaveFocus()
  expect(itemButton).toHaveAttribute('aria-pressed', 'true')
})
