import { expect, test } from 'vitest'
import { server } from './node'
import {
  uiPreviewAccount,
  uiPreviewCase,
  uiPreviewExecutions,
  uiPreviewHandlers,
} from './uiPreviewData'

function request(path: string, init?: RequestInit) {
  return fetch(new URL(`/api/v1${path}`, window.location.origin), init)
}

test('미리보기 상태는 알려진 값만 사용하고 계정 이름·ID는 실제 100자 제한 안에 둔다', () => {
  expect(uiPreviewCase('not-a-case')).toBe('demo')
  expect(uiPreviewCase(null)).toBe('demo')
  const account = uiPreviewAccount('worst')
  expect(account.loginId.length).toBeLessThanOrEqual(100)
  expect(account.displayName.length).toBeLessThanOrEqual(100)
  expect(uiPreviewExecutions('empty')).toHaveLength(0)
  expect(uiPreviewExecutions('one')).toHaveLength(1)
  expect(uiPreviewExecutions('many')).toHaveLength(1284)
})

test('많은 실행 기록도 원래 목록처럼 20건씩 조회하고 마지막 페이지가 정확하다', async () => {
  server.use(...uiPreviewHandlers('many'))
  const first = await (await request('/audit/actions?page=0')).json()
  const last = await (await request('/audit/actions?page=64')).json()
  expect(first.data).toHaveLength(20)
  expect(first.meta).toMatchObject({ totalElements: 1284, hasNext: true })
  expect(last.data).toHaveLength(4)
  expect(last.meta.hasNext).toBe(false)
})

test('긴 값의 21개 기록은 페이지 크기 다음 1개를 남기며 모든 결과 상태를 포함한다', async () => {
  server.use(...uiPreviewHandlers('worst'))
  const page = await (await request('/audit/actions?page=1')).json()
  expect(page.data).toHaveLength(1)
  expect(page.meta.totalElements).toBe(21)
  expect(
    new Set(uiPreviewExecutions('worst').map((row) => row.status)).size,
  ).toBe(4)
})

test('시간대가 다른 기록도 같은 절대 시각으로 날짜 조건을 비교한다', async () => {
  server.use(...uiPreviewHandlers('worst'))
  const params = new URLSearchParams({
    from: '2026-01-01T00:00:00+09:00',
    to: '2026-01-02T00:00:00+09:00',
  })
  const result = await (await request(`/audit/actions?${params}`)).json()
  expect(result.data.map((item: { id: number }) => item.id)).toEqual([7000])
})

test('비교 화면은 API 쓰기를 차단하고 존재하지 않는 모의 상세를 404로 처리한다', async () => {
  server.use(...uiPreviewHandlers('demo'))
  const write = await request('/actions/CREATE_REASSIGNMENT_PLAN/execute', {
    method: 'POST',
  })
  expect(write.status).toBe(403)
  expect((await write.json()).error.message).toContain('로컬 비교 화면')
  expect((await request('/audit/actions/999999')).status).toBe(404)
})

test.each([
  ['error', 500],
  ['forbidden', 403],
] as const)(
  '%s는 성공 데이터 대신 지정된 모의 오류를 반환한다',
  async (selected, status) => {
    server.use(...uiPreviewHandlers(selected))
    expect((await request('/audit/actions')).status).toBe(status)
  },
)
