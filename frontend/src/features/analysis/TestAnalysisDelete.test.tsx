import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { afterEach, expect, test, vi } from 'vitest'
import { server } from '../../mocks/node'
import TestAnalysisDelete from './TestAnalysisDelete'

// 임시 기능(S15P31C106-307). 기능을 없앨 때 이 테스트도 함께 지운다.
afterEach(() => {
  vi.restoreAllMocks()
})

function show() {
  const onDeleted = vi.fn()
  render(
    <QueryClientProvider
      client={
        new QueryClient({ defaultOptions: { queries: { retry: false } } })
      }
    >
      <TestAnalysisDelete onDeleted={onDeleted} />
    </QueryClientProvider>,
  )
  return onDeleted
}

test('확인하면 끝난 테스트 분석을 모두 지운다', async () => {
  const sent = vi.fn()
  server.use(
    http.delete('/api/v1/dev/test-analyses', () => {
      sent()
      return HttpResponse.json({
        data: { deletedRuns: [1, 2], deletedProposals: 108, skippedRuns: [3] },
      })
    }),
  )
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const user = userEvent.setup()
  const onDeleted = show()

  await user.click(
    screen.getByRole('button', { name: '테스트 분석 모두 지우기' }),
  )

  await vi.waitFor(() => expect(onDeleted).toHaveBeenCalledTimes(1))
  expect(sent).toHaveBeenCalledTimes(1)
  // 지운 건수는 화면에 따로 보여 주지 않는다
  expect(screen.queryByRole('status')).not.toBeInTheDocument()
})

test('확인을 취소하면 요청하지 않는다', async () => {
  const sent = vi.fn(() => HttpResponse.json({ data: {} }))
  server.use(http.delete('/api/v1/dev/test-analyses', sent))
  vi.spyOn(window, 'confirm').mockReturnValue(false)
  const user = userEvent.setup()
  show()

  await user.click(
    screen.getByRole('button', { name: '테스트 분석 모두 지우기' }),
  )

  expect(sent).not.toHaveBeenCalled()
})

test('서버가 거절하면 이유를 보여 준다', async () => {
  server.use(
    http.delete('/api/v1/dev/test-analyses', () =>
      HttpResponse.json(
        {
          error: {
            code: 'FORBIDDEN',
            message: '요청을 실행할 권한이 없습니다.',
            details: {},
            requestId: 'r',
          },
        },
        { status: 403 },
      ),
    ),
  )
  vi.spyOn(window, 'confirm').mockReturnValue(true)
  const user = userEvent.setup()
  show()

  await user.click(
    screen.getByRole('button', { name: '테스트 분석 모두 지우기' }),
  )

  expect(await screen.findByRole('alert')).toHaveTextContent(
    '요청을 실행할 권한이 없습니다.',
  )
})
