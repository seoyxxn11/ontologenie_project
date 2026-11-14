export type ObjectTypeItem = {
  id: number
  code: string
  name: string
  displayPropertyId: number | null
  // 조회 그룹. null이면 비공개(어느 역할에도 보이지 않음)
  accessGroup: string | null
  rowVersion: number
}

export type PropertyItem = {
  id: number
  code: string
  name: string
  sensitivity: 'NORMAL' | 'RESTRICTED'
}
