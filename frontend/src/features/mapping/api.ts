import { apiGet, apiPatch, apiPost } from '../../api/client'
import { ApiError } from '../../api/errors'
import type { ApiResponse } from '../../api/types'
import { fetchResourcePage } from '../sources/api'

type ObjectInput = {
  sourceResourceId: number
  objectTypeId: number
  keyColumns: string[]
  isActive: boolean
}

export type ObjectMapping = ObjectInput & {
  id: number
  rowVersion: number
  rowFilter: Record<string, unknown>
  origin: 'MANUAL' | 'AI_PROPOSAL' | 'SEED'
  proposalItemId: number | null
}

export type TransformStep = { op: string; arg: unknown }

export type PropertyMapping = {
  id: number
  sourceMappingId: number
  sourceResourceId: number
  propertyDefinitionId: number
  sourceColumnId: number | null
  sourcePath: string | null
  transformSteps: TransformStep[]
  rowVersion: number
}

type PropertyInput = Pick<
  PropertyMapping,
  | 'sourceMappingId'
  | 'sourceResourceId'
  | 'propertyDefinitionId'
  | 'transformSteps'
> & { sourceColumnId: number }

type Reload = { reloadRequired: boolean; reloadReason: string | null }

export type Definition = {
  id: number
  name: string
  code: string
  // 민감 속성은 변환 미리보기에서 값을 읽지 않는다(384). 없으면 일반으로 본다.
  sensitivity?: 'NORMAL' | 'RESTRICTED'
}

/**
 * 변환 미리보기에 보낼 속성 매핑. 민감 속성의 매핑은 뺀다.
 * 서버는 민감 속성이 하나라도 섞이면 요청 전체를 거절한다. 저장된 매핑을 모두 보내면
 * 민감 속성이 있는 유형은 일반 속성도 미리볼 수 없다.
 */
export function previewableMappings<
  T extends Pick<PropertyMapping, 'propertyDefinitionId'>,
>(mappings: T[], definitions: Definition[]) {
  const restricted = new Set(
    definitions
      .filter((definition) => definition.sensitivity === 'RESTRICTED')
      .map((definition) => definition.id),
  )
  return mappings.filter(
    (mapping) => !restricted.has(mapping.propertyDefinitionId),
  )
}

export type TransformFunction = {
  op: string
  label: string
  description: string
  argument: { type: 'string' | 'integer' | 'object'; label: string } | null
}

export const mappingPageSize = 20
const objectPath = '/ontology/object-mappings'
const propertyPath = '/ontology/property-mappings'

function writeData<T>(body: ApiResponse<T> | undefined) {
  if (!body?.data)
    throw new ApiError('매핑 저장 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  return body.data
}

export async function allPages<T>(
  fetchPage: (page: number) => Promise<ApiResponse<T[]>>,
) {
  const values: T[] = []
  for (let page = 0; ; page += 1) {
    const response = await fetchPage(page)
    values.push(...response.data)
    if (!response.meta?.hasNext) return values
  }
}

export function fetchMappingResources(sourceId: number) {
  return allPages((page) => fetchResourcePage(sourceId, page, 200))
}

export function fetchObjectTypes() {
  return allPages((page) =>
    apiGet<Definition[]>(`/ontology/object-types?page=${page}&size=100`),
  )
}

export async function fetchPropertyDefinitions(typeId: number) {
  return (
    await apiGet<Definition[]>(`/ontology/object-types/${typeId}/properties`)
  ).data
}

export async function fetchTransformFunctions() {
  return (await apiGet<TransformFunction[]>('/ontology/transforms')).data
}

export function fetchObjectMappings(resourceId: number, page: number) {
  return apiGet<ObjectMapping[]>(
    `${objectPath}?sourceResourceId=${resourceId}&page=${page}&size=${mappingPageSize}`,
  )
}

export function fetchPropertyMappings(mappingId: number, page: number) {
  return apiGet<PropertyMapping[]>(
    `${propertyPath}?sourceMappingId=${mappingId}&page=${page}&size=${mappingPageSize}`,
  )
}

export function fetchAllPropertyMappings(mappingId: number) {
  return allPages((page) => fetchPropertyMappings(mappingId, page))
}

export async function saveObjectMapping(
  input: ObjectInput,
  existing?: ObjectMapping,
) {
  const body = { ...input, rowFilter: {} }
  if (existing) {
    return writeData(
      await apiPatch<ObjectMapping & Reload>(`${objectPath}/${existing.id}`, {
        ...body,
        rowVersion: existing.rowVersion,
      }),
    )
  }
  const created = writeData(await apiPost<ObjectMapping>(objectPath, body))
  return { ...created, reloadRequired: created.isActive, reloadReason: null }
}

export async function savePropertyMapping(
  input: PropertyInput,
  existing?: PropertyMapping,
) {
  if (existing) {
    return writeData(
      await apiPatch<PropertyMapping & Reload>(
        `${propertyPath}/${existing.id}`,
        { ...input, rowVersion: existing.rowVersion },
      ),
    )
  }
  return writeData(await apiPost<PropertyMapping & Reload>(propertyPath, input))
}

export function mappingSaveMessage(result: Reload) {
  if (!result.reloadRequired) return '매핑을 저장했습니다.'
  const reasons: Record<string, string> = {
    IDENTITY_CHANGED: '객체 식별 기준 변경',
    REACTIVATED: '객체 매핑 재활성화',
    PROPERTY_MAPPING_ADDED: '속성 매핑 등록',
    PROPERTY_MAPPING_CHANGED: '속성 매핑 변경',
  }
  const reason = result.reloadReason ? reasons[result.reloadReason] : undefined
  return `매핑을 저장했습니다. 다음 수집에서 원천을 처음부터 다시 읽습니다.${reason ? ` (${reason})` : ''}`
}
