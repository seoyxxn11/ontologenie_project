export function formatPropertyValue(value: unknown) {
  if (value === null || value === undefined) {
    return '—'
  }
  if (typeof value === 'object') {
    return JSON.stringify(value)
  }
  return String(value)
}

export function formatSourceKey(
  sourceKey: Record<string, unknown> | null | undefined,
) {
  if (!sourceKey) {
    return '—'
  }
  return Object.entries(sourceKey)
    .map(([column, value]) => `${column}: ${String(value)}`)
    .join(', ')
}

const countFormat = new Intl.NumberFormat('ko-KR')

// 건수를 천 단위 쉼표로 보여 준다. 예: 5064 → 5,064
export function formatCount(value: number): string {
  return countFormat.format(value)
}

// 076·077·078에 업무 ID 필드가 생기기 전까지 내부 번호로 대신 보여 준다.
export function formatObjectCode(id: number) {
  return `#${id}`
}

// 원천 키 값을 ' / '로 잇는다. 권한으로 가린 값(null)은 빼고, 남는 값이 없으면 내부 번호다.
// 대표 속성이 없으면 표시 이름도 원천 키 값을 이은 것이라 한 번만 보이도록 null이다.
// sourceKey는 jsonb라 칼럼 순서가 표시 이름과 다를 수 있어 값 묶음으로 비교한다.
export function formatObjectKey(object: {
  id: number
  displayName: string
  sourceKey?: Record<string, unknown> | null
}) {
  const values = Object.values(object.sourceKey ?? {})
    .filter((value) => value !== null && value !== undefined)
    .map((value) => String(value))
    .filter((value) => value.trim() !== '')
  if (values.length === 0) {
    const code = formatObjectCode(object.id)
    return code === object.displayName ? null : code
  }
  return sameValues(values, object.displayName.split(' / '))
    ? null
    : values.join(' / ')
}

function sameValues(left: string[], right: string[]) {
  if (left.length !== right.length) return false
  const counts = new Map<string, number>()
  for (const value of left) counts.set(value, (counts.get(value) ?? 0) + 1)
  return right.every((value) => {
    const count = counts.get(value) ?? 0
    if (count === 0) return false
    counts.set(value, count - 1)
    return true
  })
}

// 마지막 글자에 받침이 있으면 '과', 없으면 '와'를 붙인다. 한글로 끝나지 않으면 둘 다 보여 준다.
export function withAndParticle(word: string) {
  const offset = word.charCodeAt(word.length - 1) - 0xac00
  if (offset < 0 || offset > 11171) {
    return `${word}와(과)`
  }
  return offset % 28 === 0 ? `${word}와` : `${word}과`
}
