import type { AgentStage, ResultCategory, SyncStatus } from './types'

export const stageLabels: Record<AgentStage, string> = {
  INTERPRETING: '질문 이해 중',
  SYNCING: '최신 데이터 확인 중',
  QUERYING: '조회 중',
  ANSWERING: '답변 작성 중',
}

export const resultCategoryLabels: Record<ResultCategory, string> = {
  FOUND: '확인됨',
  NO_DATA: '데이터 없음',
  UNLINKED: '연결 안 됨',
  DENIED: '권한 없음',
}

export const RESULT_FAILED_LABEL = '확인하지 못함'

export const syncStatusNotices: Partial<Record<SyncStatus, string>> = {
  TIMED_OUT: '최신 데이터 반영 중',
  FAILED: '최신 데이터를 가져오지 못함',
}

export const NEW_CONVERSATION_TITLE = '새 대화'

export const DATA_AS_OF_UNKNOWN = '기준 시점 확인 불가'
