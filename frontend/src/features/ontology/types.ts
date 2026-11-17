export type ObjectTypeItem = {
  id: number
  code: string
  name: string
  displayPropertyId: number | null
  // 조회 그룹. null이면 비공개(관리자에게만 보이고 업무 역할·AI 업무 도우미에는 보이지 않음)
  accessGroup: string | null
  rowVersion: number
}

export type PropertyItem = {
  id: number
  code: string
  name: string
  sensitivity: 'NORMAL' | 'RESTRICTED'
}
