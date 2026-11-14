import { http, HttpResponse } from 'msw'
import { adminOnly, requireMockLogin, requireMockAdmin } from './access'

// 규칙 편집 검증용 정의. 운영 객체·관계 정의를 변경하지 않는다.
const endpoints = [
  { id: 904, code: 'WORK_ORDER', name: '작업지시' },
  { id: 903, code: 'EQUIPMENT', name: '설비' },
]
export const ruleCatalogHandlers = [
  http.get(
    '/api/v1/ontology/link-types',
    adminOnly(({ request }) => {
      const search = new URL(request.url).searchParams
      const page = Number(search.get('page') ?? 0)
      const size = Number(search.get('size') ?? 20)
      const rows = [
        {
          id: 2,
          code: 'WO_EQUIPMENT',
          name: '작업지시 배정 설비',
          sourceTypeId: 904,
          targetTypeId: 903,
          cardinality: 'MANY_TO_ONE',
          description: null,
          rowVersion: 0,
          origin: 'SEED',
          proposalItemId: null,
        },
        {
          id: 3,
          code: 'EQUIPMENT_ORDER',
          name: '설비 작업지시',
          sourceTypeId: 903,
          targetTypeId: 904,
          cardinality: 'ONE_TO_MANY',
          description: null,
          rowVersion: 0,
          origin: 'SEED',
          proposalItemId: null,
        },
      ].filter((row) =>
        `${row.name} ${row.code}`
          .toLowerCase()
          .includes((search.get('q') ?? '').toLowerCase()),
      )
      return HttpResponse.json({
        data: rows.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalElements: rows.length,
          hasNext: (page + 1) * size < rows.length,
        },
      })
    }),
  ),
  http.get('/api/v1/ontology/object-types/:id', ({ params }) => {
    const type = endpoints.find((item) => item.id === Number(params.id))
    if (!type) return
    const denied = requireMockLogin()
    return (
      denied ??
      HttpResponse.json({
        data: {
          ...type,
          displayPropertyId: null,
          accessGroup: null,
          rowVersion: 0,
        },
      })
    )
  }),
  http.get('/api/v1/ontology/object-types/:id/properties', ({ params }) => {
    const type = endpoints.find((item) => item.id === Number(params.id))
    if (!type) return
    const denied = requireMockAdmin()
    return (
      denied ??
      HttpResponse.json({
        data: [
          {
            id: type.id * 10,
            objectTypeId: type.id,
            code: type.id === 904 ? 'equipment_code' : 'code',
            name: '설비 코드',
            dataType: 'STRING',
            sensitivity: 'NORMAL',
            isRequired: true,
            isIdentifier: false,
            unit: null,
            constraints: {},
            temporalRole: null,
            rowVersion: 0,
            origin: 'SEED',
            proposalItemId: null,
            description: null,
          },
        ],
      })
    )
  }),
]
