import { apiGet } from '../../api/client'
import type { Execution, ExecutionPageMeta, ExecutionStatus } from './types'

export type ExecutionFilters = {
  from?: string
  to?: string
  status?: ExecutionStatus
}

// 080·096이 같이 쓰는 조회 조건을 주소 뒤의 ?from=…&status=… 글자로 만든다.
function toQuery(filters: ExecutionFilters) {
  const params = new URLSearchParams()
  if (filters.from) {
    params.set('from', filters.from)
  }
  if (filters.to) {
    params.set('to', filters.to)
  }
  if (filters.status) {
    params.set('status', filters.status)
  }
  return params.toString() ? `?${params}` : ''
}

// 080 객체 관련 실행 이력 조회
export function fetchObjectExecutions(id: number, filters: ExecutionFilters) {
  return apiGet<Execution[], ExecutionPageMeta>(
    `/objects/${id}/executions${toQuery(filters)}`,
  )
}

// 096 실행·감사 이력. 관리자면 전체, 그 밖의 역할은 본인 실행을 서버가 골라 준다고 가정한다(백엔드 확인 필요).
export function fetchAuditExecutions(filters: ExecutionFilters) {
  return apiGet<Execution[], ExecutionPageMeta>(
    `/audit/actions${toQuery(filters)}`,
  )
}
