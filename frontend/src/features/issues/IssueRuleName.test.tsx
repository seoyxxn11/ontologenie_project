import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { delay, http, HttpResponse } from 'msw'
import { expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import IssueRuleName from './IssueRuleName'

const key = ['ontology', 'rules']
const rule = {
  code: 'INTERNAL_CODE',
  description: '작업지시 수주 번호로 수주 연결',
  linkTypeName: '생산 대상 수주',
}
function open(client = new QueryClient()) {
  render(
    <QueryClientProvider client={client}>
      <IssueRuleName code={rule.code} mappingId={12} />
      <IssueRuleName code="SECOND_RULE" mappingId={13} />
    </QueryClientProvider>,
  )
}

test('규칙 설명을 표시하고 여러 행·개정에서 이름 목록 요청을 공유한다', async () => {
  const get = vi.fn(() =>
    HttpResponse.json({
      data: [
        rule,
        { ...rule, code: 'SECOND_RULE', description: '두 번째 연결 규칙' },
      ],
    }),
  )
  server.use(http.get('/api/v1/ontology/rules', get))
  open()
  expect(
    await screen.findByText('작업지시 수주 번호로 수주 연결 (연결 매핑 #12)'),
  ).toBeVisible()
  expect(
    await screen.findByText('두 번째 연결 규칙 (연결 매핑 #13)'),
  ).toBeVisible()
  expect(
    screen.queryByText(/INTERNAL_CODE|SECOND_RULE/),
  ).not.toBeInTheDocument()
  expect(get).toHaveBeenCalledTimes(1)
})

test.each([
  [{ ...rule, description: null }, '생산 대상 수주'],
  [{ ...rule, description: ' ', linkTypeName: '' }, '규칙 이름 미제공'],
  [
    { ...rule, description: { invalid: true }, linkTypeName: null },
    '규칙 이름 미제공',
  ],
  [{ ...rule, code: 'DELETED_RULE' }, '규칙 이름 미제공'],
])(
  '설명 미제공·삭제는 허용된 관계 이름 또는 미제공 안내로 표시한다 %#',
  async (entry, expected) => {
    server.use(
      http.get('/api/v1/ontology/rules', () =>
        HttpResponse.json({ data: [entry] }),
      ),
    )
    open()
    expect(await screen.findByText(`${expected} (연결 매핑 #12)`)).toBeVisible()
    expect(screen.queryByText(/INTERNAL_CODE/)).not.toBeInTheDocument()
  },
)

test.each([403, 404, 500])(
  '이름 조회 %s 실패는 캐시 이름과 내부 코드를 노출하지 않는다',
  async (status) => {
    const client = new QueryClient()
    client.setQueryData(key, [rule])
    server.use(
      http.get('/api/v1/ontology/rules', () =>
        HttpResponse.json(
          { error: { code: 'LOOKUP_ERROR', message: '조회 실패' } },
          { status },
        ),
      ),
    )
    open(client)
    expect(
      await screen.findByText('규칙 이름 확인 불가 (연결 매핑 #12)'),
    ).toBeVisible()
    expect(
      screen.queryByText(/작업지시 수주 번호로 수주 연결|INTERNAL_CODE/),
    ).not.toBeInTheDocument()
  },
)

test('이름 조회 중임을 표시하고 코드가 없는 매핑은 추가 조회하지 않는다', async () => {
  const get = vi.fn(async () => {
    await delay(60)
    return HttpResponse.json({ data: [rule] })
  })
  server.use(http.get('/api/v1/ontology/rules', get))
  render(
    <QueryClientProvider client={new QueryClient()}>
      <IssueRuleName mappingId={12} />
    </QueryClientProvider>,
  )
  expect(screen.getByText('연결 매핑 #12')).toBeVisible()
  expect(get).not.toHaveBeenCalled()
  open()
  expect(screen.getByText('규칙 이름 조회 중… (연결 매핑 #12)')).toBeVisible()
  expect(
    await screen.findByText('작업지시 수주 번호로 수주 연결 (연결 매핑 #12)'),
  ).toBeVisible()
})
