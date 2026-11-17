import type {
  AgentActionStatus,
  AgentStage,
  ResultCategory,
  SyncStatus,
} from './types'

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

// 실패했지만 같은 조회를 다시 해 확인한 결과(449)
export const RESULT_RETRIED_LABEL = '다시 조회해 확인함'

// 다시 조회해 확인하지 못한 조회가 있을 때 답변 아래에 보이는 안내(449)
export function unconfirmedNotice(count: number) {
  return `일부 정보를 확인하지 못했어요(조회 ${count}건). 답변에 빠진 내용이 있을 수 있어요.`
}

export const syncStatusNotices: Partial<Record<SyncStatus, string>> = {
  TIMED_OUT: '최신 데이터 반영 중',
  FAILED: '최신 데이터를 가져오지 못함',
}

export const NEW_CONVERSATION_TITLE = '새 대화'

export const DATA_AS_OF_UNKNOWN = '기준 시점 확인 불가'

export const ANSWER_SNAPSHOT_NOTICE =
  '응답 시점의 값 · 이후 수정은 다시 질문해 확인'

// Agent가 부른 Action의 결과 문구(160). 성공이 아닌 결과를 성공처럼 보여 주지 않는다.
export const actionStatusLabels: Record<AgentActionStatus, string> = {
  SUCCEEDED: '완료',
  RUNNING: '처리 중',
  BLOCKED: '실행하지 않음',
  FAILED: '실패',
  CONFIRMATION_REQUIRED: '확인이 필요해 실행하지 않음',
  NOT_RUN: '실행하지 못함',
}

export const MES_UNCHANGED_NOTICE = '실제 MES 배정은 바뀌지 않아요'
