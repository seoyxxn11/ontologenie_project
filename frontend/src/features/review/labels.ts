import type { Proposal } from './types'

export const originLabels: Record<string, string> = {
  AI: 'AI 분석',
  MANUAL: '직접 추가',
}
export const kindLabels: Record<string, string> = {
  OBJECT_TYPE: '객체 유형',
  PROPERTY: '속성',
  LINK_TYPE: '관계 유형',
  OBJECT_MAPPING: '객체 매핑',
  PROPERTY_MAPPING: '속성 매핑',
  LINK_RULE: '연결 규칙',
  DOCUMENT_LINK: '문서 연결',
}
export const reviewLabels: Record<string, string> = {
  PENDING: '검토 대기',
  APPROVED: '승인',
  REJECTED: '거절',
  HELD: '보류',
}
export const applyLabels: Record<string, string> = {
  NOT_APPLIED: '미반영',
  APPLIED: '반영 완료',
  FAILED: '반영 실패',
}

export function proposalTitle(proposal: Proposal) {
  const name = proposal.payload.name
  const code = proposal.payload.code
  return typeof name === 'string' && name.trim()
    ? name
    : typeof code === 'string' && code.trim()
      ? code
      : `${kindLabels[proposal.itemKind] ?? proposal.itemKind} 제안 #${proposal.id}`
}

export function confidence(value: number | null) {
  return value !== null && Number.isFinite(value) && value >= 0 && value <= 1
    ? `${(value * 100).toFixed(1)}%`
    : '제공되지 않음'
}
