// 객체 유형 조회 그룹(object_type.access_group, 백엔드 V13·V21·V29). 그룹이 없는 유형은 관리자에게만 보인다.
// FINANCE·HR은 지금 어느 역할에도 열려 있지 않아 고를 수 없게 두고, 이미 지정된 값은 그대로 보여 준다.
export const accessGroupLabels: Record<string, string> = {
  PRODUCTION: '생산·설비',
  SALES_SUPPLY: '영업·구매·재고',
  FINANCE: '재무',
  HR: '인사',
}

export const selectableAccessGroups = ['PRODUCTION', 'SALES_SUPPLY'] as const

export function accessGroupText(group: string | null | undefined) {
  if (!group) return '비공개 (그룹 없음)'
  return accessGroupLabels[group] ?? group
}

// 024가 공개 전 검사(402)로 거절할 때 details.violations에 담는 항목
export type PolicyViolation = {
  kind: string
  resourceKey: string
  columnName: string
  propertyCode?: string | null
}

export const violationKindLabels: Record<string, string> = {
  FORBIDDEN_COLUMN: '금지 컬럼(수집하지 않음)',
  RESTRICTED_KEY: '민감 컬럼을 키로 씀',
  RESTRICTED_UNDER_CLASSIFIED: '민감 컬럼을 일반 속성이 담음',
}

export function policyViolations(details: Record<string, unknown>) {
  const list = details.violations
  if (!Array.isArray(list)) return []
  return list.filter(
    (item): item is PolicyViolation =>
      typeof item === 'object' &&
      item !== null &&
      typeof (item as PolicyViolation).kind === 'string' &&
      typeof (item as PolicyViolation).resourceKey === 'string' &&
      typeof (item as PolicyViolation).columnName === 'string',
  )
}
