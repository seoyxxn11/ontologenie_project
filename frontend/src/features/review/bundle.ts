import type { Proposal } from './types'

// AI 묶음 후보(API-105): 객체 후보는 속성·원천 매핑을, 관계 후보는 관계 유형을 함께 담는다.
// 상세 화면은 이 칸들을 표로 보여 주고, "제안 내용"·"제안 근거"에서는 뺀다.
export const BUNDLE_PAYLOAD_KEYS = ['objectMapping', 'properties', 'linkType']
export const BUNDLE_EVIDENCE_KEYS = ['objectMapping', 'properties']

export type Row = Record<string, unknown>

export function isRow(value: unknown): value is Row {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

export function isBundle(proposal: Proposal) {
  return (
    (proposal.itemKind === 'OBJECT_TYPE' &&
      Array.isArray(proposal.payload.properties)) ||
    (proposal.itemKind === 'LINK_RULE' &&
      isRow(proposal.payload.linkType) &&
      // 다른 관계 유형으로 바꾼 규칙에 예전 관계 유형이 남아 있으면 묶음으로 보지 않는다
      proposal.payload.linkType.code === proposal.payload.linkTypeCode &&
      proposal.payload.linkTypeId == null)
  )
}

export function withoutBundleKeys(value: Row, keys: string[]) {
  return Object.fromEntries(
    Object.entries(value).filter(([key]) => !keys.includes(key)),
  )
}
