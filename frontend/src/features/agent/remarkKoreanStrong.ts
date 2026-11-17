import type { Paragraph, PhrasingContent, Root, Text } from 'mdast'
import type { Processor } from 'unified'
import type { Parent } from 'unist'

const hangul = /\p{Script=Hangul}/u
const punctuation = /[\p{P}\p{S}]/u

// CommonMark가 글자로 남긴 ** 중 문장부호와 한글이 인접한 강조만 복원한다.
// 파싱 뒤의 텍스트 노드만 다루므로 코드·HTML·링크 주소를 다시 해석하지 않는다.
export default function remarkKoreanStrong(this: Processor) {
  const parse = this.parse.bind(this)

  // 이스케이프·문자 참조로 만들어진 글자만 제외하고 원문 별표의 위치를 추적한다.
  function literalOffsets(raw: string, value: string) {
    const offsets: (number | null)[] = []
    let decoded = ''
    let cursor = 0
    const tokens =
      /\\[!-/:-@\u005b-\u0060{-~]|&(?:#[xX][\da-fA-F]{1,6}|#\d{1,7}|[a-zA-Z][a-zA-Z\d]{1,31});|\r\n/gu
    function appendLiteral(end: number) {
      for (; cursor < end; cursor++) {
        decoded += raw[cursor]
        offsets.push(cursor)
      }
    }
    for (const match of raw.matchAll(tokens)) {
      appendLiteral(match.index)
      const parsed = parse(`x${match[0]}x`) as Root
      const paragraph = parsed.children[0] as Paragraph
      const text = paragraph.children[0] as Text
      const tokenValue = text.value.slice(1, -1)
      if (tokenValue === match[0]) {
        appendLiteral(match.index + match[0].length)
      } else {
        decoded += tokenValue
        offsets.push(...Array<null>(tokenValue.length).fill(null))
        cursor = match.index + match[0].length
      }
    }
    appendLiteral(raw.length)
    return decoded === value ? offsets : null
  }

  return (tree: Root, file: { value: unknown }) => {
    const source = String(file.value)

    function split(node: Text): PhrasingContent[] {
      if (!node.value.includes('**')) return [node]
      const start = node.position?.start.offset
      const end = node.position?.end.offset
      if (start === undefined || end === undefined) return [node]
      const raw = source.slice(start, end)
      const offsets = literalOffsets(raw, node.value)
      if (!offsets) return [node]

      function isLiteralMarker(index: number) {
        const offset = offsets?.[index]
        return (
          offset != null &&
          offsets?.[index + 1] === offset + 1 &&
          raw.slice(offset, offset + 2) === '**'
        )
      }

      const children: PhrasingContent[] = []
      const pattern = /(?<!\*)\*\*([^\s*](?:[^*\n]*[^\s*])?)\*\*(?!\*)/gu
      let cursor = 0
      let match: RegExpExecArray | null
      while ((match = pattern.exec(node.value))) {
        if (
          !isLiteralMarker(match.index) ||
          !isLiteralMarker(match.index + match[0].length - 2)
        ) {
          pattern.lastIndex = match.index + 2
          continue
        }
        const label = match[1]
        const before = node.value.slice(0, match.index).at(-1) ?? ''
        const after = node.value[match.index + match[0].length] ?? ''
        if (
          !(hangul.test(before) && punctuation.test(label[0])) &&
          !(punctuation.test(label.at(-1) ?? '') && hangul.test(after))
        ) {
          pattern.lastIndex = match.index + 2
          continue
        }
        if (match.index > cursor) {
          children.push({
            type: 'text',
            value: node.value.slice(cursor, match.index),
          })
        }
        children.push({
          type: 'strong',
          children: [{ type: 'text', value: label }],
        })
        cursor = match.index + match[0].length
      }
      if (cursor === 0) return [node]
      if (cursor < node.value.length) {
        children.push({ type: 'text', value: node.value.slice(cursor) })
      }
      return children
    }

    function walk(parent: Parent) {
      parent.children = parent.children.flatMap((node) => {
        if (node.type === 'text') return split(node as Text)
        if ('children' in node) walk(node as Parent)
        return [node]
      })
    }
    walk(tree)
  }
}
