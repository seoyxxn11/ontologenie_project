import type { ReassignmentPlanItem } from './types'
import { formatKstDateTime } from '../../utils/dateTime'

export function formatPlanDateTime(value: string) {
  return formatKstDateTime(value).replace(/ KST$/, '')
}

export function workOrderLabel(item: ReassignmentPlanItem) {
  const value = item.sourceSnapshot.workOrderNo
  return typeof value === 'string' && value.trim()
    ? value
    : `#${item.workOrderId}`
}

export function operationLabel(item: ReassignmentPlanItem) {
  if (item.operationCode !== null) return item.operationCode
  // 가린 값과 전체 공정을 뜻하는 null을 구분할 근거가 부족하면 범위를 단정하지 않는다.
  return item.restrictedPropertyCodes?.length
    ? '공정 정보 조회 제한'
    : '남은 공정 전체'
}

export function planStatusLabel(status: string) {
  return status === 'CREATED' ? '생성됨' : status
}
