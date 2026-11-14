import { apiGet, apiPatch, ApiError } from '../../api/client'
import { allPages } from '../mapping/api'
import type { ObjectTypeItem, PropertyItem } from './types'

export const objectTypesKey = ['ontology', 'object-types'] as const

// 021 객체 유형 목록. 운영 유형 수가 적어 모든 페이지를 한 번에 읽는다.
export function fetchObjectTypeList() {
  return allPages((page) =>
    apiGet<ObjectTypeItem[]>(`/ontology/object-types?page=${page}&size=100`),
  )
}

// 026 객체 유형의 속성 목록
export async function fetchTypeProperties(typeId: number) {
  return (
    await apiGet<PropertyItem[]>(`/ontology/object-types/${typeId}/properties`)
  ).data
}

// 024 대표 속성 지정. null이면 대표 속성을 비운다.
export async function saveDisplayProperty(
  type: ObjectTypeItem,
  displayPropertyId: number | null,
) {
  const response = await apiPatch<ObjectTypeItem>(
    `/ontology/object-types/${type.id}`,
    { displayPropertyId, rowVersion: type.rowVersion },
  )
  if (!response?.data)
    throw new ApiError(
      '저장 응답을 확인할 수 없습니다. 목록을 다시 불러와 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}

// 024 조회 그룹 지정. null이면 비공개로 돌린다. 켜진 매핑이 원천 데이터 정책을 어기면 422(402)
export async function saveAccessGroup(
  type: ObjectTypeItem,
  accessGroup: string | null,
) {
  const response = await apiPatch<ObjectTypeItem>(
    `/ontology/object-types/${type.id}`,
    { accessGroup, rowVersion: type.rowVersion },
  )
  if (!response?.data)
    throw new ApiError(
      '저장 응답을 확인할 수 없습니다. 목록을 다시 불러와 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}
