import { apiGet } from '../../api/client'
import type { ReassignmentPlan, ReassignmentPlanFilters } from './types'

export const PLAN_PAGE_SIZE = 20

// 093 저장된 재배정 계획 목록. 항목을 포함하며 서버에서 최신순으로 정렬한다.
export function fetchReassignmentPlans(
  filters: ReassignmentPlanFilters,
  page = 0,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(PLAN_PAGE_SIZE),
  })
  if (filters.fromEquipmentId)
    params.set('fromEquipmentId', String(filters.fromEquipmentId))
  if (filters.createdBy) params.set('createdBy', String(filters.createdBy))
  if (filters.createdByName) params.set('createdByName', filters.createdByName)
  return apiGet<ReassignmentPlan[]>(`/reassignment-plans?${params}`, { signal })
}

// 조회 조건·현재 페이지와 관계없이 저장된 계획에 등장한 기존 설비만 모은다.
export async function fetchSavedPlanEquipmentIds(signal?: AbortSignal) {
  const ids = new Set<number>()
  for (let page = 0; ; page += 1) {
    const result = await fetchReassignmentPlans({}, page, signal)
    for (const plan of result.data) ids.add(plan.fromEquipmentId)
    if (!result.meta?.hasNext || result.data.length === 0) break
  }
  return [...ids].sort((a, b) => a - b)
}

// 094 재배정 계획 상세 (옮길 작업 목록 포함)
export async function fetchReassignmentPlan(id: number, signal?: AbortSignal) {
  const body = await apiGet<ReassignmentPlan>(`/reassignment-plans/${id}`, {
    signal,
  })
  return body.data
}
