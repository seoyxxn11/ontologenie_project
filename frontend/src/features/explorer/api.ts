import { apiGet } from '../../api/client'
import type {
  ModelGraph,
  ObjectInstance,
  ObjectInstanceDetail,
  ObjectLink,
} from './types'

export const OBJECT_PAGE_SIZE = 20

export type ObjectFilters = {
  q?: string
  objectTypeId?: number
}

// 076 권한 범위 객체 검색
export function fetchObjects(filters: ObjectFilters, page = 0) {
  const params = new URLSearchParams()
  if (filters.q) {
    params.set('q', filters.q)
  }
  if (filters.objectTypeId) {
    params.set('objectTypeId', String(filters.objectTypeId))
  }
  params.set('page', String(page))
  params.set('size', String(OBJECT_PAGE_SIZE))
  return apiGet<ObjectInstance[]>(`/objects?${params}`)
}

// 077 객체 상세 조회
export async function fetchObject(id: number) {
  const body = await apiGet<ObjectInstanceDetail>(`/objects/${id}`)
  return body.data
}

// 078 확정 관계·원천 근거 조회
export function fetchObjectLinks(id: number) {
  return apiGet<ObjectLink[]>(`/objects/${id}/links`)
}

// 042 객체 유형 관계도
export async function fetchModelGraph() {
  const body = await apiGet<ModelGraph>('/ontology/model-graph')
  return body.data
}
