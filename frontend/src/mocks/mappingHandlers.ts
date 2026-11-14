import { http, HttpResponse } from 'msw'
import { adminOnly, mockFailure, requireMockLogin } from './access'
import { getMockAccount } from './auth'
import { denseDemoProperties } from './denseExplorerDemo'
import { transformData } from './transformData'
import type {
  Definition,
  ObjectMapping,
  PropertyMapping,
} from '../features/mapping/api'

const types: Definition[] = [
  { id: 501, code: 'ITEM', name: '품목' },
  { id: 502, code: 'VENDOR', name: '거래처' },
]
const properties: Record<
  number,
  (Definition & { dataType?: string; sensitivity?: string })[]
> = {
  3: [
    { id: 31, code: 'equipment_code', name: '설비 코드', dataType: 'STRING' },
    { id: 32, code: 'process', name: '공정', dataType: 'STRING' },
    { id: 33, code: 'status', name: '상태', dataType: 'STRING' },
    { id: 34, code: 'maintenance_note', name: '정비 메모', dataType: 'STRING' },
    {
      id: 35,
      code: 'purchase_price',
      name: '구매 가격',
      dataType: 'DECIMAL',
      sensitivity: 'RESTRICTED',
    },
  ],
  4: [
    {
      id: 41,
      code: 'work_order_no',
      name: '작업지시 번호',
      dataType: 'STRING',
    },
    { id: 42, code: 'status', name: '상태', dataType: 'STRING' },
    { id: 43, code: 'process', name: '공정', dataType: 'STRING' },
    {
      id: 44,
      code: 'assigned_equipment',
      name: '배정 설비',
      dataType: 'STRING',
    },
    { id: 45, code: 'sales_order_no', name: '수주 번호', dataType: 'STRING' },
  ],
  5: [
    { id: 51, code: 'sales_order_no', name: '수주 번호', dataType: 'STRING' },
    { id: 52, code: 'status', name: '상태', dataType: 'STRING' },
  ],
  6: [
    { id: 61, code: 'note_id', name: '메모 번호', dataType: 'STRING' },
    { id: 62, code: 'content', name: '내용', dataType: 'STRING' },
  ],
  7: [
    { id: 71, code: 'log_id', name: '로그 번호', dataType: 'STRING' },
    { id: 72, code: 'equipment_code', name: '설비 코드', dataType: 'STRING' },
    { id: 73, code: 'logged_at', name: '기록 시각', dataType: 'DATETIME' },
    { id: 74, code: 'content', name: '내용', dataType: 'STRING' },
  ],
  8: [],
  501: [
    { id: 601, code: 'ITEM_NAME', name: '품목명' },
    { id: 602, code: 'SPEC', name: '규격' },
  ],
  502: [{ id: 603, code: 'VENDOR_NAME', name: '거래처명' }],
}
const initialObject: ObjectMapping = {
  id: 701,
  sourceResourceId: 11,
  objectTypeId: 501,
  keyColumns: ['item_code'],
  rowFilter: {},
  rowVersion: 2,
  origin: 'SEED',
  proposalItemId: null,
  isActive: true,
}
const initialProperty: PropertyMapping = {
  id: 801,
  sourceMappingId: 701,
  sourceResourceId: 11,
  propertyDefinitionId: 601,
  sourceColumnId: 1002,
  sourcePath: null,
  transformSteps: [{ op: 'TRIM', arg: null }],
  rowVersion: 3,
}
const objectPath = '/api/v1/ontology/object-mappings'
const propertyPath = '/api/v1/ontology/property-mappings'
let objectMappings: ObjectMapping[]
let propertyMappings: PropertyMapping[]
let nextPreviewId = 1
const previews = new Map<
  number,
  {
    sourceResourceId: number
    requestedAt: string
    expiresAt: string
    keyColumns: string[]
    rowLimit: number
    reads: number
  }
>()

export function resetMappingMocks() {
  objectMappings = [initialObject]
  propertyMappings = [initialProperty]
  previews.clear()
  nextPreviewId = 1
}
resetMappingMocks()

function paged<T>(data: T[], search: URLSearchParams) {
  const page = Number(search.get('page') ?? 0)
  const size = Number(search.get('size') ?? 20)
  if (
    !Number.isSafeInteger(page) ||
    page < 0 ||
    !Number.isSafeInteger(size) ||
    size < 1
  )
    return failure(400, 'INVALID_REQUEST', 'page와 size를 확인해 주세요.')
  return HttpResponse.json({
    data: data.slice(page * size, (page + 1) * size),
    meta: {
      page,
      size,
      totalElements: data.length,
      hasNext: (page + 1) * size < data.length,
    },
  })
}

function failure(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-mapping' } },
    { status },
  )
}

type ObjectWrite = Pick<
  ObjectMapping,
  'sourceResourceId' | 'objectTypeId' | 'keyColumns' | 'rowFilter' | 'isActive'
> & { rowVersion?: number }
type PropertyWrite = Pick<
  PropertyMapping,
  | 'sourceMappingId'
  | 'sourceResourceId'
  | 'propertyDefinitionId'
  | 'sourceColumnId'
  | 'transformSteps'
> & { rowVersion?: number }

export const mappingHandlers = [
  // 개발용 고정 표본이다. 브라우저에서 실제 변환 엔진을 실행하지 않는다.
  http.post(
    '/api/v1/ontology/source-resources/:id/mapping-previews',
    adminOnly(async ({ params, request }) => {
      const input = (await request.json()) as {
        mapping: { keyColumns: string[] }
        rowLimit?: number
      }
      const previewId = nextPreviewId++
      const requestedAt = new Date().toISOString()
      const expiresAt = new Date(Date.now() + 3600000).toISOString()
      previews.set(previewId, {
        sourceResourceId: Number(params.id),
        requestedAt,
        expiresAt,
        keyColumns: input.mapping.keyColumns,
        rowLimit: input.rowLimit ?? 20,
        reads: 0,
      })
      return HttpResponse.json(
        {
          data: {
            previewId,
            status: 'QUEUED',
            joined: false,
            requestedAt,
            expiresAt,
          },
        },
        { status: 202 },
      )
    }),
  ),
  http.get(
    '/api/v1/ontology/source-previews/:id',
    adminOnly(({ params }) => {
      const preview = previews.get(Number(params.id))
      if (!preview || Date.parse(preview.expiresAt) <= Date.now())
        return failure(404, 'RESOURCE_NOT_FOUND', '미리보기가 만료되었습니다.')
      const done = preview.reads++ > 0
      return HttpResponse.json({
        data: {
          id: Number(params.id),
          previewKind: 'MAPPING_PREVIEW',
          sourceResourceId: preview.sourceResourceId,
          status: done ? 'SUCCEEDED' : 'RUNNING',
          requestedAt: preview.requestedAt,
          startedAt: preview.requestedAt,
          finishedAt: done ? new Date().toISOString() : null,
          expiresAt: preview.expiresAt,
          errorMessage: null,
          result: done
            ? {
                objects: [
                  {
                    sourceKey: Object.fromEntries(
                      preview.keyColumns.map((key) => [key, 'MOCK-001']),
                    ),
                    displayName: '모의 표본',
                    properties: { MOCK_VALUE: '모의 변환 결과' },
                  },
                ],
                rowErrors:
                  preview.rowLimit > 1
                    ? [
                        {
                          rowIndex: 1,
                          column: 'mock_column',
                          transform: 'TO_INTEGER',
                          message: '모의 원천 값 변환 실패',
                        },
                      ]
                    : [],
              }
            : null,
        },
      })
    }),
  ),
  http.get(
    '/api/v1/ontology/object-types',
    adminOnly(({ request }) => {
      const search = new URL(request.url).searchParams
      const q = (search.get('q') ?? '').toLowerCase()
      return paged(
        types.filter((type) =>
          `${type.code} ${type.name}`.toLowerCase().includes(q),
        ),
        search,
      )
    }),
  ),
  // 026은 최신 서버에서 로그인 사용자에게 열리며 비관리자는 허용 유형·속성만 받는다.
  http.get('/api/v1/ontology/object-types/:id/properties', ({ params }) => {
    const denied = requireMockLogin()
    if (denied) return denied
    const id = Number(params.id)
    const dense = import.meta.env.DEV && import.meta.env.MODE === 'graph-demo'
    // 현재 모의 객체 선택지와 같은 범위다. 운영 역할 정책을 정의하지 않는다.
    const visible = dense ? id >= 1 && id <= 27 : id >= 3 && id <= 8
    if (getMockAccount()?.roleCode !== 'ADMIN' && !visible)
      return mockFailure(
        404,
        'RESOURCE_NOT_FOUND',
        '요청한 유형을 찾을 수 없습니다.',
      )
    const rows =
      dense && id >= 1 && id <= 27 ? denseDemoProperties : properties[id]
    if (!rows)
      return mockFailure(
        404,
        'RESOURCE_NOT_FOUND',
        '요청한 유형을 찾을 수 없습니다.',
      )
    return HttpResponse.json({
      data: rows
        .filter(
          (row) =>
            getMockAccount()?.roleCode === 'ADMIN' ||
            !('sensitivity' in row) ||
            row.sensitivity !== 'RESTRICTED',
        )
        .map((row) => ({
          ...row,
          objectTypeId: id,
          dataType: row.dataType ?? 'STRING',
          isRequired: false,
          isIdentifier: false,
          unit: null,
          constraints: {},
          sensitivity:
            'sensitivity' in row ? (row.sensitivity ?? 'NORMAL') : 'NORMAL',
          temporalRole: null,
          rowVersion: 0,
          origin: 'SEED',
          proposalItemId: null,
          description: null,
        })),
    })
  }),
  http.get(
    '/api/v1/ontology/transforms',
    adminOnly(() => HttpResponse.json({ data: transformData })),
  ),
  http.get(
    objectPath,
    adminOnly(({ request }) => {
      const search = new URL(request.url).searchParams
      const resourceId = search.get('sourceResourceId')
      return paged(
        objectMappings.filter(
          (mapping) =>
            resourceId === null ||
            mapping.sourceResourceId === Number(resourceId),
        ),
        search,
      )
    }),
  ),
  http.post(
    objectPath,
    adminOnly(async ({ request }) => {
      const input = (await request.json()) as ObjectWrite
      if (
        input.isActive &&
        objectMappings.some(
          (mapping) =>
            mapping.isActive &&
            mapping.sourceResourceId === input.sourceResourceId &&
            mapping.objectTypeId === input.objectTypeId,
        )
      )
        return failure(
          409,
          'CONFLICT',
          '같은 원천 자원과 객체 유형의 활성 매핑이 이미 있습니다.',
        )
      const data: ObjectMapping = {
        ...input,
        id: 701 + objectMappings.length,
        rowVersion: 0,
        origin: 'MANUAL',
        proposalItemId: null,
      }
      objectMappings.push(data)
      return HttpResponse.json({ data }, { status: 201 })
    }),
  ),
  http.patch(
    `${objectPath}/:id`,
    adminOnly(async ({ params, request }) => {
      const input = (await request.json()) as ObjectWrite
      const existing = objectMappings.find(({ id }) => id === Number(params.id))
      if (!existing)
        return failure(
          404,
          'RESOURCE_NOT_FOUND',
          '객체 매핑을 찾을 수 없습니다.',
        )
      if (input.rowVersion !== existing.rowVersion)
        return failure(
          409,
          'VERSION_CONFLICT',
          '다른 사용자가 매핑을 변경했습니다.',
        )
      const reactivated = !existing.isActive && input.isActive
      const identityChanged =
        input.sourceResourceId !== existing.sourceResourceId ||
        input.objectTypeId !== existing.objectTypeId ||
        JSON.stringify(input.keyColumns) !== JSON.stringify(existing.keyColumns)
      const reloadReason =
        input.isActive && identityChanged
          ? 'IDENTITY_CHANGED'
          : reactivated
            ? 'REACTIVATED'
            : null
      const data = {
        ...existing,
        ...input,
        rowVersion: existing.rowVersion + 1,
      }
      objectMappings = objectMappings.map((item) =>
        item.id === data.id ? data : item,
      )
      return HttpResponse.json({
        data: { ...data, reloadRequired: reloadReason !== null, reloadReason },
      })
    }),
  ),
  http.get(
    propertyPath,
    adminOnly(({ request }) => {
      const search = new URL(request.url).searchParams
      const mappingId = search.get('sourceMappingId')
      return paged(
        propertyMappings.filter(
          (mapping) =>
            mappingId === null || mapping.sourceMappingId === Number(mappingId),
        ),
        search,
      )
    }),
  ),
  http.post(
    propertyPath,
    adminOnly(async ({ request }) => {
      const input = (await request.json()) as PropertyWrite
      if (
        propertyMappings.some(
          (mapping) =>
            mapping.sourceMappingId === input.sourceMappingId &&
            mapping.propertyDefinitionId === input.propertyDefinitionId,
        )
      )
        return failure(409, 'CONFLICT', '이 속성은 이미 매핑돼 있습니다.')
      const data: PropertyMapping = {
        ...input,
        id: 801 + propertyMappings.length,
        rowVersion: 0,
        sourcePath: null,
      }
      propertyMappings.push(data)
      const reloadRequired = objectMappings.some(
        ({ id, isActive }) => id === data.sourceMappingId && isActive,
      )
      return HttpResponse.json(
        {
          data: {
            ...data,
            reloadRequired,
            reloadReason: reloadRequired ? 'PROPERTY_MAPPING_ADDED' : null,
          },
        },
        { status: 201 },
      )
    }),
  ),
  http.patch(
    `${propertyPath}/:id`,
    adminOnly(async ({ params, request }) => {
      const input = (await request.json()) as PropertyWrite
      const existing = propertyMappings.find(
        ({ id }) => id === Number(params.id),
      )
      if (!existing)
        return failure(
          404,
          'RESOURCE_NOT_FOUND',
          '속성 매핑을 찾을 수 없습니다.',
        )
      if (input.rowVersion !== existing.rowVersion)
        return failure(
          409,
          'VERSION_CONFLICT',
          '다른 사용자가 매핑을 변경했습니다.',
        )
      const data = {
        ...existing,
        ...input,
        rowVersion: existing.rowVersion + 1,
      }
      propertyMappings = propertyMappings.map((item) =>
        item.id === data.id ? data : item,
      )
      const reloadRequired = objectMappings.some(
        ({ id, isActive }) => id === data.sourceMappingId && isActive,
      )
      return HttpResponse.json({
        data: {
          ...data,
          reloadRequired,
          reloadReason: reloadRequired ? 'PROPERTY_MAPPING_CHANGED' : null,
        },
      })
    }),
  ),
]
