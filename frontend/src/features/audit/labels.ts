import type { EntityChange, Execution, ExecutionStatus } from './types'

export const executionStatusLabels: Record<ExecutionStatus, string> = {
  RUNNING: '진행 중',
  SUCCEEDED: '성공',
  BLOCKED: '차단',
  FAILED: '실패',
}

export const eventKindLabels: Record<Execution['eventKind'], string> = {
  ACTION: '업무 실행',
  SYNC_RUN: '데이터 수집',
  CHECK_RUN: '검사',
  ANALYSIS_RUN: '분석',
  SOURCE_CHECK: '원천 연결 검사',
  SOURCE_PREVIEW: '원천 미리보기',
  HOLD_AUTO_RESOLVE: '보류 자동 처리',
}

export const actorTypeLabels: Record<Execution['actorType'], string> = {
  USER: '사용자',
  AGENT: 'Agent 대리 실행',
  SYSTEM: '시스템',
}

export const changeKindLabels: Record<EntityChange['changeKind'], string> = {
  INSERT: '생성',
  UPDATE: '수정',
  DELETE: '삭제',
}
