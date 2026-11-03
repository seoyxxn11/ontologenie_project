import type { RuleOrigin, RuleRevisionStatus } from './types'

// 044로 조회할 수 있는 정의 테이블 (ERD 기준)
export const definitionTableLabels: Record<string, string> = {
  object_type: '객체 유형',
  property_definition: '속성',
  link_type: '관계 유형',
  object_source_mapping: '객체 원천 매핑',
  property_source_mapping: '속성 원천 매핑',
}

export const ruleStatusLabels: Record<RuleRevisionStatus, string> = {
  ACTIVE: '적용 중',
  RETIRED: '퇴역',
}

export const ruleOriginLabels: Record<RuleOrigin, string> = {
  AI_PROPOSAL: 'AI 제안 승인',
  MANUAL: '직접 등록',
  SEED: '초기 데이터',
}
