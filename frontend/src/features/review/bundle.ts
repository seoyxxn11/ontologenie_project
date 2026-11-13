import type { Proposal } from './types'

// AI 묶음 후보(API-105): 객체 후보는 속성·원천 매핑을, 관계 후보는 관계 유형을 함께 담는다.
// 상세 화면은 이 칸들을 표로 보여 주고, "제안 내용"·"제안 근거"에서는 뺀다.
// conditions는 관계 표(LinkBundle)가 보여 주므로 제안 내용에서 다시 늘어놓지 않는다
export const BUNDLE_PAYLOAD_KEYS = [
  'objectMapping',
  'properties',
  'linkType',
  'conditions',
]
export const BUNDLE_EVIDENCE_KEYS = ['objectMapping', 'properties']
// 재분석·과거 결정 대조와 내부 감사에 쓰는 값. 검토 화면에 보여 주지 않고 검색 대상에서도 뺀다.
export const INTERNAL_EVIDENCE_KEYS = [
  'candidateId',
  'fingerprint',
  'confidenceItems',
  'confidenceCap',
  'method',
  'title',
  // 문서 연결 화면이 제목·원문 옆에 따로 보인다
  'documentLabel',
  'targetLabels',
]

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

function label(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : '-'
}

// 변환 단계: ' → UPPER → PAD_LEFT(6)'
export function transformText(value: unknown) {
  const list = (Array.isArray(value) ? value.filter(isRow) : []).map((step) =>
    step.arg === null || step.arg === undefined
      ? label(step.op)
      : `${label(step.op)}(${JSON.stringify(step.arg)})`,
  )
  return list.length ? ` → ${list.join(' → ')}` : ''
}

// 비교 조건 한 줄: 'WORK_ORDER.equipment_code → UPPER = EQUIPMENT.code'
export function conditionText(condition: Row) {
  return `${label(condition.sourceProperty)}${transformText(condition.sourceTransforms)} = ${label(condition.targetProperty)}${transformText(condition.targetTransforms)}`
}
