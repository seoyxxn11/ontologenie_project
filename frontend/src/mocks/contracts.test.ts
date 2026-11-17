import { beforeEach, expect, test } from 'vitest'
import { apiGet, apiPatch, apiPost } from '../api/client'
import { demoAccount, setMockAccount } from './auth'
import { resetMappingMocks } from './mappingHandlers'
import { resetSourceMocks } from './sourceHandlers'
import type { ObjectTypeItem } from '../features/ontology/types'

beforeEach(() => {
  resetMappingMocks()
  resetSourceMocks()
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
})

test('객체 유형 모의 저장은 대표 속성·조회 그룹·버전을 목록과 상세에 유지한다', async () => {
  const path = '/ontology/object-types/501'
  const initial = (await apiGet<ObjectTypeItem[]>('/ontology/object-types'))
    .data[0]
  expect(initial).toMatchObject({
    displayPropertyId: null,
    accessGroup: null,
    rowVersion: 0,
  })
  const saved = await apiPatch<ObjectTypeItem>(path, {
    displayPropertyId: 601,
    rowVersion: 0,
  })
  expect(saved?.data).toMatchObject({
    displayPropertyId: 601,
    accessGroup: null,
    rowVersion: 1,
  })
  await apiPatch(path, { accessGroup: 'SALES_SUPPLY', rowVersion: 1 })
  const expected = {
    displayPropertyId: 601,
    accessGroup: 'SALES_SUPPLY',
    rowVersion: 2,
  }
  expect((await apiGet<ObjectTypeItem>(path)).data).toMatchObject(expected)
  expect(
    (await apiGet<ObjectTypeItem[]>('/ontology/object-types')).data[0],
  ).toMatchObject(expected)
  await apiPatch(path, { displayPropertyId: null, rowVersion: 2 })
  await apiPatch(path, { accessGroup: null, rowVersion: 3 })
  expect((await apiGet<ObjectTypeItem>(path)).data).toMatchObject({
    displayPropertyId: null,
    accessGroup: null,
    rowVersion: 4,
  })
})

test('객체 유형 모의 저장은 버전 충돌과 없는 대상을 구분하고 거절 시 값을 보존한다', async () => {
  const path = '/ontology/object-types/501'
  await apiPatch(path, { displayPropertyId: 601, rowVersion: 0 })
  await expect(
    apiPatch(path, { displayPropertyId: null, rowVersion: 0 }),
  ).rejects.toMatchObject({ status: 409, code: 'VERSION_CONFLICT' })
  await expect(
    apiPatch('/ontology/object-types/999', { rowVersion: 0 }),
  ).rejects.toMatchObject({ status: 404, code: 'RESOURCE_NOT_FOUND' })
  expect((await apiGet<ObjectTypeItem>(path)).data).toMatchObject({
    displayPropertyId: 601,
    rowVersion: 1,
  })
})

test('같은 버전의 객체 유형 모의 저장이 겹치면 하나만 반영한다', async () => {
  const results = await Promise.allSettled([
    apiPatch('/ontology/object-types/501', {
      displayPropertyId: 601,
      rowVersion: 0,
    }),
    apiPatch('/ontology/object-types/501', {
      displayPropertyId: 602,
      rowVersion: 0,
    }),
  ])
  expect(
    results.filter((result) => result.status === 'fulfilled'),
  ).toHaveLength(1)
  expect(
    results.filter((result) => result.status === 'rejected'),
  ).toMatchObject([{ reason: { status: 409, code: 'VERSION_CONFLICT' } }])
  expect(
    (await apiGet<ObjectTypeItem>('/ontology/object-types/501')).data
      .rowVersion,
  ).toBe(1)
})

test('객체 유형 모의 저장은 잘못된 입력과 다른 유형 속성을 거절한다', async () => {
  const path = '/ontology/object-types/501'
  for (const input of [
    [],
    null,
    { displayPropertyId: 601 },
    { rowVersion: -1 },
    { displayPropertyId: 0, rowVersion: 0 },
    { displayPropertyId: '601', rowVersion: 0 },
    { accessGroup: 1, rowVersion: 0 },
    { accessGroup: 'x'.repeat(101), rowVersion: 0 },
  ]) {
    await expect(apiPatch(path, input)).rejects.toMatchObject({
      status: 400,
      code: 'INVALID_REQUEST',
    })
  }
  for (const displayPropertyId of [603, 999]) {
    await expect(
      apiPatch(path, { displayPropertyId, rowVersion: 0 }),
    ).rejects.toMatchObject({ status: 422, code: 'BUSINESS_RULE_VIOLATION' })
  }
  expect((await apiGet<ObjectTypeItem>(path)).data).toMatchObject({
    displayPropertyId: null,
    rowVersion: 0,
  })
})

test.each([null, 'VIEWER', 'PLANNER', 'MAINTENANCE'] as const)(
  '객체 유형 모의 저장은 %s 요청을 거절한다',
  async (roleCode) => {
    setMockAccount(roleCode ? { ...demoAccount, roleCode } : null)
    await expect(
      apiPatch('/ontology/object-types/501', {
        displayPropertyId: 601,
        rowVersion: 0,
      }),
    ).rejects.toMatchObject({ status: roleCode ? 403 : 401 })
    setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
    expect(
      (await apiGet<ObjectTypeItem[]>('/ontology/object-types')).data[0],
    ).toMatchObject({ displayPropertyId: null, rowVersion: 0 })
  },
)

const adminPaths = [
  '/ontology/object-types',
  '/ontology/transforms',
  '/ontology/object-mappings',
  '/ontology/property-mappings',
  '/ontology/source-previews/1',
]

test('관리 API는 비로그인 401, 조회 역할·임시 비밀번호는 403이며 쓰기도 차단된다', async () => {
  for (const account of [
    null,
    { ...demoAccount, roleCode: 'VIEWER' as const },
    { ...demoAccount, roleCode: 'ADMIN' as const, mustChangePassword: true },
  ]) {
    setMockAccount(account)
    const expected = {
      status: account ? 403 : 401,
      code: account ? 'FORBIDDEN' : 'UNAUTHENTICATED',
    }
    for (const path of adminPaths)
      await expect(apiGet(path)).rejects.toMatchObject(expected)
    await expect(
      apiPost('/ontology/object-mappings', {}),
    ).rejects.toMatchObject(expected)
    await expect(
      apiPatch('/ontology/property-mappings/801', {}),
    ).rejects.toMatchObject(expected)
    await expect(
      apiPatch('/ontology/object-types/501', {
        displayPropertyId: 601,
        rowVersion: 0,
      }),
    ).rejects.toMatchObject(expected)
    await expect(
      apiPost('/ontology/source-resources/11/mapping-previews', {}),
    ).rejects.toMatchObject(expected)
  }
  // 거절된 쓰기가 가상 자료를 바꾸지 않았는지도 확인한다.
  setMockAccount({ ...demoAccount, roleCode: 'ADMIN' })
  expect(
    (await apiGet<unknown[]>('/ontology/object-mappings')).data,
  ).toHaveLength(1)
})

test.each(['VIEWER', 'PLANNER', 'MAINTENANCE'] as const)(
  '026은 %s에게 허용 유형의 비민감 속성만 제공한다',
  async (roleCode) => {
    setMockAccount({ ...demoAccount, roleCode })
    const { data } = await apiGet<{ code: string; sensitivity: string }[]>(
      '/ontology/object-types/3/properties',
    )
    expect(data.some((item) => item.code === 'equipment_code')).toBe(true)
    expect(data.some((item) => item.sensitivity === 'RESTRICTED')).toBe(false)
    await expect(
      apiGet('/ontology/object-types/501/properties'),
    ).rejects.toMatchObject({ status: 404, code: 'RESOURCE_NOT_FOUND' })
  },
)

test('026 관리 응답은 민감도 등 실제 필드를 가지며 로그인 없이 반환하지 않는다', async () => {
  const { data } = await apiGet<Record<string, unknown>[]>(
    '/ontology/object-types/3/properties',
  )
  expect(data.find((item) => item.code === 'purchase_price')).toMatchObject({
    sensitivity: 'RESTRICTED',
    objectTypeId: 3,
    unit: null,
    constraints: {},
    rowVersion: 0,
  })
  await expect(
    apiGet('/ontology/object-types/999/properties'),
  ).rejects.toMatchObject({ status: 404, code: 'RESOURCE_NOT_FOUND' })
  setMockAccount(null)
  await expect(
    apiGet('/ontology/object-types/3/properties'),
  ).rejects.toMatchObject({ status: 401 })
})

test.each(['ADMIN', 'VIEWER'] as const)(
  '기존 객체 선택지의 모든 유형은 %s 속성 조회에서 404가 되지 않는다',
  async (roleCode) => {
    setMockAccount({ ...demoAccount, roleCode })
    const { data: types } = await apiGet<{ id: number }[]>('/objects/types')
    for (const type of types) {
      const response = await apiGet<unknown[]>(
        `/ontology/object-types/${type.id}/properties`,
      )
      expect(Array.isArray(response.data)).toBe(true)
    }
  },
)

test('041은 서버의 11개 변환과 인자·자료형·예시를 제공한다', async () => {
  type Entry = {
    op: string
    argument: { name: string } | null
    inputTypes: string[]
    outputType: string
    example: {
      step: { op: string; arg: unknown }
      input: string
      output: string
    }
  }
  const { data } = await apiGet<Entry[]>('/ontology/transforms')
  expect(new Set(data.map((item) => item.op))).toEqual(
    new Set([
      'STRIP_PREFIX',
      'ADD_PREFIX',
      'PAD_ZEROS',
      'UPPER',
      'TRIM',
      'EXTRACT_NUMBER',
      'TO_INTEGER',
      'TO_DECIMAL',
      'TO_DATE',
      'MAP_VALUE',
      'MAP_KNOWN',
    ]),
  )
  expect(data).toHaveLength(11)
  for (const item of data) {
    expect(item.inputTypes.length).toBeGreaterThan(0)
    expect(typeof item.outputType).toBe('string')
    expect(item.example.step.op).toBe(item.op)
    expect(item.example).toHaveProperty('input')
    expect(item.example).toHaveProperty('output')
    if (item.argument) expect(item.argument.name.length).toBeGreaterThan(0)
    else expect(item.example.step.arg).toBeNull()
  }
})

test('페이지는 실제 데이터를 나누고 검색·빈 쪽·잘못된 페이지 메타를 구분한다', async () => {
  const first = await apiGet<{ id: number }[]>(
    '/ontology/object-types?page=0&size=1',
  )
  const second = await apiGet<{ id: number }[]>(
    '/ontology/object-types?page=1&size=1',
  )
  expect(first.meta).toEqual({
    page: 0,
    size: 1,
    totalElements: 2,
    hasNext: true,
  })
  expect(second.meta).toEqual({
    page: 1,
    size: 1,
    totalElements: 2,
    hasNext: false,
  })
  expect(first.data[0].id).not.toBe(second.data[0].id)
  expect((await apiGet('/ontology/object-types?q=vendor')).data).toEqual([
    expect.objectContaining({ code: 'VENDOR' }),
  ])
  expect((await apiGet('/ontology/object-types?page=2&size=1')).data).toEqual(
    [],
  )
  await expect(apiGet('/ontology/object-types?page=-1')).rejects.toMatchObject({
    status: 400,
    code: 'INVALID_REQUEST',
  })
  await expect(apiGet('/ontology/object-types?size=0')).rejects.toMatchObject({
    status: 400,
  })
})

test('서버의 버전 충돌·대상 없음 코드와 공통 오류 필드를 재현한다', async () => {
  await expect(
    apiPatch('/ontology/object-mappings/701', { rowVersion: 0 }),
  ).rejects.toMatchObject({
    status: 409,
    code: 'VERSION_CONFLICT',
    requestId: 'mock-mapping',
  })
  await expect(
    apiPatch('/ontology/object-mappings/999', {}),
  ).rejects.toMatchObject({ status: 404, code: 'RESOURCE_NOT_FOUND' })
})

test('같은 102 경로에서 SAMPLE과 MAPPING_PREVIEW가 서로를 가리지 않고 조회된다', async () => {
  const sample = await apiPost<{ previewId: number }>(
    '/sources/1/resources/11/samples',
    { rowLimit: 1 },
  )
  const mapping = await apiPost<{ previewId: number }>(
    '/ontology/source-resources/11/mapping-previews',
    { mapping: { keyColumns: ['item_code'] }, rowLimit: 1 },
  )
  expect(sample).toBeDefined()
  expect(mapping).toBeDefined()
  expect(
    (
      await apiGet<{ previewKind: string }>(
        `/ontology/source-previews/${sample!.data.previewId}`,
      )
    ).data.previewKind,
  ).toBe('SAMPLE')
  const path = `/ontology/source-previews/${mapping!.data.previewId}`
  expect((await apiGet<{ status: string }>(path)).data.status).toBe('RUNNING')
  expect((await apiGet<{ status: string }>(path)).data.status).toBe('SUCCEEDED')
  setMockAccount({ ...demoAccount, roleCode: 'VIEWER' })
  await expect(apiGet(path)).rejects.toMatchObject({ status: 403 })
  await expect(
    apiGet(`/ontology/source-previews/${sample!.data.previewId}`),
  ).rejects.toMatchObject({ status: 403 })
})
