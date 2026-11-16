import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { expect, test } from 'vitest'
import { server } from '../../mocks/node'
import RestrictedValueCheck from './RestrictedValueCheck'

function renderCheck(proposalId = 31) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <RestrictedValueCheck proposalId={proposalId} />
    </QueryClientProvider>,
  )
}

const column = {
  sourceResourceId: 5,
  sourceName: 'ERP 운영 DB',
  resourceKey: 'erp.vendor',
  column: 'contact_name',
}

test('제한 컬럼이 없으면 아무것도 그리지 않는다', async () => {
  server.use(
    http.get('/api/v1/proposals/31/restricted-columns', () =>
      HttpResponse.json({ data: [] }),
    ),
  )
  const { container } = renderCheck()
  await new Promise((resolve) => setTimeout(resolve, 20))
  expect(container).toBeEmptyDOMElement()
})

test('실제 값 확인을 누르면 접수하고, 결과를 POST로 읽어 값과 건수를 보여 준다', async () => {
  const reads: string[] = []
  let posted: unknown
  let polls = 0
  server.use(
    http.get('/api/v1/proposals/31/restricted-columns', () =>
      HttpResponse.json({ data: [column] }),
    ),
    http.post('/api/v1/proposals/31/restricted-values', async ({ request }) => {
      posted = await request.json()
      return HttpResponse.json(
        {
          data: {
            requestId: 77,
            status: 'QUEUED',
            joined: false,
            requestedAt: '2026-11-16T12:00:00Z',
            expiresAt: '2026-11-16T12:10:00Z',
          },
        },
        { status: 202 },
      )
    }),
    http.post(
      '/api/v1/proposals/31/restricted-values/77/result',
      ({ request }) => {
        reads.push(request.method)
        polls += 1
        if (polls === 1)
          return HttpResponse.json({
            data: {
              requestId: 77,
              sourceResourceId: 5,
              column: 'contact_name',
              status: 'QUEUED',
            },
          })
        return HttpResponse.json({
          data: {
            requestId: 77,
            sourceResourceId: 5,
            column: 'contact_name',
            status: 'SUCCEEDED',
            values: ['담당-A', '담당-B'],
            totalCount: 6,
            emptyCount: 1,
            distinctCount: 4,
          },
        })
      },
    ),
  )
  renderCheck()

  const section = await screen.findByRole('region', {
    name: '제한 컬럼 실제 값 확인',
  })
  expect(
    within(section).getByText('erp.vendor.contact_name'),
  ).toBeInTheDocument()
  expect(section).toHaveTextContent('열람 기록이 남고')

  await userEvent.click(
    within(section).getByRole('button', { name: '실제 값 확인' }),
  )

  const values = await screen.findByLabelText(
    'erp.vendor.contact_name 실제 값',
    {},
    { timeout: 3000 },
  )
  expect(within(values).getByText('담당-A')).toBeInTheDocument()
  expect(within(values).getByText('담당-B')).toBeInTheDocument()
  expect(values).toHaveTextContent(
    '전체 6행 · 빈 값 1행 · 서로 다른 값 4개 중 2개',
  )
  expect(posted).toEqual({ sourceResourceId: 5, column: 'contact_name' })
  expect(reads).toEqual(['POST', 'POST'])
  expect(
    within(section).getByRole('button', { name: '다시 확인' }),
  ).toBeInTheDocument()
})

test('서버가 실패를 돌려주면 이유를 보여 준다', async () => {
  server.use(
    http.get('/api/v1/proposals/31/restricted-columns', () =>
      HttpResponse.json({ data: [column] }),
    ),
    http.post('/api/v1/proposals/31/restricted-values', () =>
      HttpResponse.json(
        {
          data: {
            requestId: 78,
            status: 'QUEUED',
            joined: false,
            requestedAt: '2026-11-16T12:00:00Z',
            expiresAt: '2026-11-16T12:10:00Z',
          },
        },
        { status: 202 },
      ),
    ),
    http.post('/api/v1/proposals/31/restricted-values/78/result', () =>
      HttpResponse.json({
        data: {
          requestId: 78,
          sourceResourceId: 5,
          column: 'contact_name',
          status: 'FAILED',
          errorMessage:
            '원천 데이터 정책상 값 확인 대상(제한 컬럼)이 아닙니다.',
        },
      }),
    ),
  )
  renderCheck()

  await userEvent.click(
    await screen.findByRole('button', { name: '실제 값 확인' }),
  )

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '원천 데이터 정책상 값 확인 대상(제한 컬럼)이 아닙니다.',
  )
})
