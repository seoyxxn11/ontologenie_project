// 재배정 계획 (API 094). 플랫폼 안에 저장한 계획이며 실제 MES 배정은 바꾸지 않는다.
export type ReassignmentPlanItem = {
  id: number
  workOrderId: number
  // null이면 그 작업지시에서 정지 설비에 배정된 남은 공정 전체
  operationCode: string | null
  fromEquipmentId: number
  toEquipmentId: number
  // 계획 당시 원천 상태. 작업지시 번호(workOrderNo) 등이 들어 있다.
  sourceSnapshot: Record<string, unknown>
  restrictedPropertyCodes?: string[]
}

export type ReassignmentPlanFilters = {
  fromEquipmentId?: number
  createdBy?: number
  createdByName?: string
}

export type ReassignmentPlan = {
  id: number
  status: string
  fromEquipmentId: number
  selectionBasis: Record<string, unknown>
  dataAsOf: string
  actionExecutionId: number
  agentConversationId: number | null
  createdBy: number
  createdAt: string
  items: ReassignmentPlanItem[]
}
