import { expect, test } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../../mocks/node'
import { ApiError } from '../../api/client'
import { demoAccount, setMockAccount } from '../../mocks/auth'
import {
  fetchModelGraph,
  fetchObjects,
  fetchObjectTypes,
  fetchObjectProperties,
} from './api'

test('속성 조건의 공백·특수문자·빈 문자열을 보존해 전송한다', async () => {
  let captured: URLSearchParams | undefined
  server.use(
    http.get('/api/v1/objects', ({ request }) => {
      captured = new URL(request.url).searchParams
      return HttpResponse.json({
        data: [],
        meta: { page: 0, size: 20, totalElements: 0, hasNext: false },
      })
    }),
  )
  await fetchObjects({
    objectTypeId: 3,
    propertyValues: { code: ' A+B & 한글 ', empty: '' },
  })
  expect(captured?.get('propertyValues[code]')).toBe(' A+B & 한글 ')
  expect(captured?.get('propertyValues[empty]')).toBe('')
  expect(captured?.has('propertyValues[empty]')).toBe(true)
  expect(captured?.get('objectTypeId')).toBe('3')
  expect(captured?.get('size')).toBe('8')
})

test('객체가 없는 유형도 선택지로 받고 유형별 속성 정의를 조회한다', async () => {
  const types = await fetchObjectTypes()
  expect(types).toContainEqual({
    id: 8,
    code: 'INSPECTION_REQUEST',
    name: '점검 요청',
  })
  expect(await fetchObjectProperties(3)).toContainEqual(
    expect.objectContaining({ code: 'status', dataType: 'STRING' }),
  )
})

test('042 ADMIN 응답은 객체 수 없이 노드와 전체·활성 연결 건수를 반환한다', async () => {
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
  const graph = await fetchModelGraph()
  expect(graph.nodes.length).toBeGreaterThan(0)
  for (const node of graph.nodes) {
    expect(Object.keys(node).sort()).toEqual(['code', 'id', 'name'])
  }
  expect(graph.edges).toEqual(
    expect.arrayContaining([
      expect.objectContaining({ relationCount: 2, activeLinkCount: 1 }),
      expect.objectContaining({ relationCount: 0, activeLinkCount: 0 }),
    ]),
  )
})

test.each(['PLANNER', 'MAINTENANCE', 'VIEWER'] as const)(
  '042는 %s에 403을 반환한다',
  async (roleCode) => {
    setMockAccount({ ...demoAccount, roleCode })
    await expect(fetchModelGraph()).rejects.toMatchObject({
      status: 403,
      code: 'FORBIDDEN',
    })
  },
)

test('042는 로그인하지 않으면 401을 반환한다', async () => {
  setMockAccount(null)
  await expect(fetchModelGraph()).rejects.toBeInstanceOf(ApiError)
  await expect(fetchModelGraph()).rejects.toMatchObject({
    status: 401,
    code: 'UNAUTHENTICATED',
  })
})

test('042는 비밀번호 변경이 필요한 ADMIN에 403을 반환한다', async () => {
  setMockAccount({
    ...demoAccount,
    roleCode: 'ADMIN',
    mustChangePassword: true,
  })
  await expect(fetchModelGraph()).rejects.toMatchObject({ status: 403 })
})
