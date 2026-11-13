import { formatKstDateTime } from '../../utils/dateTime'
import { formatPropertyValue, formatSourceKey } from './format'
import type { ObjectLink, ObjectPropertyDefinition } from './types'

export function detailValue(
  value: unknown,
  definition?: ObjectPropertyDefinition,
) {
  if (value == null) return '값 없음'
  if (typeof value === 'string' && !value.trim()) return '빈 문자열'
  if (Array.isArray(value) && value.length === 0) return '항목 없음'
  if (typeof value === 'object' && Object.keys(value).length === 0)
    return '내용 없음'
  if (typeof value === 'boolean') return value ? '예' : '아니요'
  if (
    definition?.dataType === 'DATETIME' &&
    typeof value === 'string' &&
    /(?:Z|[+-]\d{2}:\d{2})$/i.test(value)
  ) {
    return Number.isNaN(Date.parse(value)) ? value : formatKstDateTime(value)
  }
  const formatted = formatPropertyValue(value)
  const unit = definition?.unit?.trim()
  return typeof value === 'number' && Number.isFinite(value) && unit
    ? `${formatted} ${unit}`
    : formatted
}

// 078이 이미 공개한 근거만 요약한다. 다른 객체나 원문을 추가 조회하지 않는다.
export function linkContext(link: ObjectLink) {
  const evidence = link.evidence
  if (evidence.evidenceRestricted)
    return '연결 근거는 권한으로 가려져 있습니다.'
  const parts = [
    evidence.timeExpression?.trim(),
    evidence.event?.trim(),
  ].filter(Boolean)
  const sourceKey = Object.fromEntries(
    Object.entries(evidence.sourceKey ?? {}).filter(
      ([, value]) =>
        value != null &&
        ['string', 'number', 'boolean'].includes(typeof value) &&
        String(value).trim() !== '',
    ),
  )
  if (Object.keys(sourceKey).length)
    parts.push(`연결 원천 행: ${formatSourceKey(sourceKey)}`)
  return parts.join(' · ') || null
}
