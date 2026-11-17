import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import ExecutionDetail from './ExecutionDetail'
import ExecutionValues from './ExecutionValues'
import type { Execution } from './types'

const execution: Execution = {
  id: 888,
  requestId: 'local-readability',
  eventKind: 'ACTION',
  actionName: '재배정 계획 생성',
  actorType: 'USER',
  entryPoint: 'WEB',
  status: 'SUCCEEDED',
  requestedAt: '2026-11-14T00:00:00Z',
  changeCount: 0,
  targetLabel: 'P101',
}
function mount(item: Execution = execution, modal = false) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  render(
    <QueryClientProvider client={client}>
      <ExecutionDetail execution={item} modal={modal} />
    </QueryClientProvider>,
  )
  return client
}
function response(status = 'SUCCEEDED') {
  return {
    id: 888,
    status,
    input: {
      workOrderNo: 'WO-101',
      nested: { COST: null },
      empty: null,
      enabled: false,
      zero: 0,
    },
    result: { planId: 501, itemCount: 2, status: 'CREATED' },
    restrictedPropertyCodes: ['COST'],
    blockedReason: null,
    failureReason: null,
  }
}

test('JSON 대신 처리 결과·입력 요약을 먼저 보여주고 기술 JSON은 접힌 채 가림을 유지한다', async () => {
  server.use(
    http.get('/api/v1/audit/actions/888', () =>
      HttpResponse.json({ data: response() }),
    ),
  )
  mount()
  await userEvent
    .setup()
    .click(screen.getByText('내용 보기', { selector: 'summary' }))
  expect(await screen.findByText('재배정 계획 생성')).toBeVisible()
  expect(screen.getByText('성공')).toBeVisible()
  expect(screen.getByText('재배정 계획 번호')).toBeVisible()
  expect(screen.getByText('501')).toBeVisible()
  expect(screen.getByText('생성됨')).toBeVisible()
  expect(screen.getByText('WO-101')).toBeVisible()
  expect(screen.getByText('권한으로 가림')).toBeVisible()
  expect(screen.getByText('값 없음')).toBeVisible()
  expect(screen.getByText('아니요')).toBeVisible()
  expect(screen.getByText('0')).toBeVisible()
  const json = screen.getByText(/"COST": "\(권한으로 가림\)"/)
  expect(json).not.toBeVisible()
  await userEvent
    .setup()
    .click(screen.getByText('기술 정보 · JSON', { selector: 'summary' }))
  expect(json).toBeVisible()
})

test('실패 이유만 요약하고 실패 결과의 내부 errorMessage·reason을 다시 노출하지 않는다', async () => {
  server.use(
    http.get('/api/v1/audit/actions/888', () =>
      HttpResponse.json({
        data: {
          ...response('FAILED'),
          failureReason: 'TIMEOUT',
          result: {
            errorMessage: 'hidden-error-sentinel',
            reason: 'hidden-reason-sentinel',
            rowErrors: 2,
          },
        },
      }),
    ),
  )
  mount()
  await userEvent
    .setup()
    .click(screen.getByText('내용 보기', { selector: 'summary' }))
  expect(await screen.findByText('실행 시간이 초과되었습니다.')).toBeVisible()
  expect(screen.getByText('행 오류 수')).toBeVisible()
  expect(
    screen.queryByText(/hidden-error|hidden-reason/),
  ).not.toBeInTheDocument()
})

test('상세가 거절되면 요약이나 JSON을 추측하지 않고 재시도 버튼을 제공한다', async () => {
  const denied = vi.fn(() =>
    HttpResponse.json(
      { error: { code: 'FORBIDDEN', message: '열람할 수 없습니다.' } },
      { status: 403 },
    ),
  )
  server.use(http.get('/api/v1/audit/actions/888', denied))
  const client = mount()
  await userEvent
    .setup()
    .click(screen.getByText('내용 보기', { selector: 'summary' }))
  expect(await screen.findByText('열람할 수 없습니다.')).toBeVisible()
  await waitFor(() => expect(client.isFetching()).toBe(0))
  expect(denied).toHaveBeenCalledTimes(1)
  expect(screen.queryByText('기술 정보 · JSON')).not.toBeInTheDocument()
})

test('미확인 코드·새 필드는 원래 의미를 보존하고 큰 결과는 개수를 안내한다', () => {
  render(
    <ExecutionValues
      values={{
        status: '__proto__',
        constructor: 'provided',
        unknown: { code: 'X1' },
        list: Array.from({ length: 12 }, (_, i) => i),
        blank: ' ',
        empty: [],
      }}
    />,
  )
  expect(screen.getByText('__proto__')).toBeVisible()
  expect(screen.getByText('constructor')).toBeVisible()
  expect(screen.getByText('X1')).toBeVisible()
  expect(screen.getByText(/외 2개 항목/)).toBeVisible()
  expect(screen.getByText('빈 문자열')).toBeVisible()
  expect(screen.getByText('항목 없음')).toBeVisible()
})

test('수집과 적용 결과의 공통 필드를 미리보기나 검사 결과로 한정하지 않는다', () => {
  render(<ExecutionValues values={{ objects: 12, summary: { created: 3 } }} />)
  expect(screen.getByText('객체 수')).toBeVisible()
  expect(screen.getByText('결과 요약')).toBeVisible()
  expect(screen.queryByText('미리보기 객체 수')).not.toBeInTheDocument()
  expect(screen.queryByText('검사 집계')).not.toBeInTheDocument()
})

test('팝업은 중복·내부 값을 빼고 대상 번호를 대상 줄에 합쳐 보여준다', async () => {
  server.use(
    http.get('/api/v1/audit/actions/888', () =>
      HttpResponse.json({
        data: {
          id: 888,
          status: 'SUCCEEDED',
          input: {
            rowVersion: 6,
            syncEnabled: false,
            dataSourceId: 1,
            syncIntervalMinutes: null,
          },
          result: { dataSourceId: 1 },
          restrictedPropertyCodes: [],
          blockedReason: null,
          failureReason: null,
        },
      }),
    ),
  )
  mount(
    {
      ...execution,
      actionName: '원천별 수집 주기 설정',
      actorName: '홍길동',
      targetType: 'data_source',
      targetId: 1,
      targetLabel: 'ERP 운영 DB',
    },
    true,
  )
  await userEvent
    .setup()
    .click(screen.getByRole('button', { name: '내용 보기' }))
  const dialog = within(
    await screen.findByRole('dialog', { name: '원천별 수집 주기 설정' }),
  )
  expect(await dialog.findByText('자동 수집 사용')).toBeVisible()
  expect(dialog.getByText('아니요')).toBeVisible()
  expect(dialog.getByText('수집 주기(분)')).toBeVisible()
  expect(dialog.getByText('값 없음')).toBeVisible()
  expect(dialog.getByText('ERP 운영 DB')).toBeVisible()
  expect(dialog.getByText('· 원천 번호 1')).toBeVisible()
  expect(dialog.getByText('홍길동')).toBeVisible()
  expect(dialog.getByText('기록된 변경 없음')).toBeVisible()
  expect(dialog.queryByText('처리 결과')).not.toBeInTheDocument()
  expect(dialog.queryByText('rowVersion')).not.toBeInTheDocument()
  expect(dialog.queryByText('원천 번호')).not.toBeInTheDocument()
  expect(dialog.getByText(/"rowVersion": 6/)).not.toBeVisible()
})

test('진행 중인 기록은 처리 결과 칸 없이 진행 안내와 입력 내용을 보여준다', async () => {
  server.use(
    http.get('/api/v1/audit/actions/888', () =>
      HttpResponse.json({
        data: {
          ...response('RUNNING'),
          input: { workOrderNo: 'W'.repeat(200) },
          result: null,
        },
      }),
    ),
  )
  mount({ ...execution, status: 'RUNNING' })
  await userEvent
    .setup()
    .click(screen.getByText('내용 보기', { selector: 'summary' }))
  expect(
    await screen.findByText('진행 중입니다. 완료된 처리 결과가 아직 없습니다.'),
  ).toBeVisible()
  expect(screen.getByText('진행 중')).toBeVisible()
  expect(screen.queryByText('처리 결과')).not.toBeInTheDocument()
  expect(
    screen.getByText('W'.repeat(200)).closest('.audit-values'),
  ).not.toBeNull()
})

test('실제 기록에 오는 항목 이름과 역할·기준 시점 값을 화면 표현으로 보여준다', () => {
  render(
    <ExecutionValues
      values={{
        items: [{ workOrderId: 731, toEquipmentId: 2, operationCode: '20' }],
        dataAsOf: '2026-11-17T22:44:13.005793+09:00',
        selectionBasis: { reason: '필요한 능력을 충족합니다.' },
        loginId: 'maintenance',
        roleCode: 'MAINTENANCE',
        displayName: '이민수',
      }}
    />,
  )
  for (const label of [
    '옮길 작업',
    '공정 코드',
    '기준 시점',
    '선정 근거',
    '계정 ID',
    '역할',
    '표시 이름',
  ])
    expect(screen.getByText(label)).toBeVisible()
  expect(screen.getByText('2026-11-17 22:44:13 KST')).toBeVisible()
  expect(screen.getByText('정비 담당')).toBeVisible()
  expect(screen.queryByText('MAINTENANCE')).not.toBeInTheDocument()
})

test('모르는 역할 코드는 원래 값을 그대로 둔다', () => {
  render(<ExecutionValues values={{ roleCode: 'constructor' }} />)
  expect(screen.getByText('constructor')).toBeVisible()
})

test.each(['확인 중', '2026', 'plan 3', '2026-11-17 22:44', '2026-11-17'])(
  '시간대가 붙은 시각이 아닌 기준 시점 %s는 원래 값을 그대로 둔다',
  (value) => {
    render(<ExecutionValues values={{ dataAsOf: value }} />)
    expect(screen.getByText(value)).toBeVisible()
  },
)

test('UTC로 온 기준 시점도 한국 시각으로 보여준다', () => {
  render(<ExecutionValues values={{ dataAsOf: '2026-11-17T13:44:13Z' }} />)
  expect(screen.getByText('2026-11-17 22:44:13 KST')).toBeVisible()
})
