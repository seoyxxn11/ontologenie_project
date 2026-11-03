export function formatPropertyValue(value: unknown) {
  if (value === null || value === undefined) {
    return '-'
  }
  return String(value)
}

export function formatSourceKey(
  sourceKey: Record<string, unknown> | null | undefined,
) {
  if (!sourceKey) {
    return '-'
  }
  return Object.entries(sourceKey)
    .map(([column, value]) => `${column}: ${String(value)}`)
    .join(', ')
}

// 076·077·078에 업무 ID 필드가 생기기 전까지 내부 번호로 대신 보여 준다.
export function formatObjectCode(id: number) {
  return `#${id}`
}

// 마지막 글자에 받침이 있으면 '과', 없으면 '와'를 붙인다. 한글로 끝나지 않으면 둘 다 보여 준다.
export function withAndParticle(word: string) {
  const offset = word.charCodeAt(word.length - 1) - 0xac00
  if (offset < 0 || offset > 11171) {
    return `${word}와(과)`
  }
  return offset % 28 === 0 ? `${word}와` : `${word}과`
}
