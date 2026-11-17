import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { expect, test } from 'vitest'
import MessageItem from './MessageItem'
import type { AgentMessage } from './types'

function renderMessage(
  content: string,
  sender: AgentMessage['sender'] = 'AGENT',
) {
  render(
    <MemoryRouter>
      <MessageItem
        message={{
          id: 1,
          conversationId: 1,
          sender,
          content,
          context: {},
          status: 'SUCCEEDED',
          errorMessage: null,
          answerMeta: {},
          createdAt: '2026-11-17T05:40:00Z',
        }}
      />
    </MemoryRouter>,
  )
}

test.each([
  [
    '멈춘 설비는 **P103(1라인 3호 프레스)**이며, 현재 **정비중**입니다.',
    'P103(1라인 3호 프레스)',
  ],
  ['설비는 **"P103"**입니다.', '"P103"'],
  ['설비는 **「P103」**입니다.', '「P103」'],
  ['설비는**(P103)**입니다.', '(P103)'],
  ['설비는**(P103) 정보**입니다.', '(P103) 정보'],
])('문장부호와 한글이 인접한 강조를 그린다: %s', (content, label) => {
  renderMessage(content)
  expect(screen.getByText(label).tagName).toBe('STRONG')
  expect(screen.getByRole('article')).not.toHaveTextContent('**')
})

test('한 문단의 여러 강조를 각각 그린다', () => {
  renderMessage('**P103(프레스)**이며 **P104(프레스)**도 정비중입니다.')
  expect(screen.getByText('P103(프레스)').tagName).toBe('STRONG')
  expect(screen.getByText('P104(프레스)').tagName).toBe('STRONG')
})

test.each([
  '`**P103(프레스)**이며`',
  '```text\n**P103(프레스)**이며\n```',
  '\\*\\*P103(프레스)\\*\\*이며',
  '&#42;&#42;P103(프레스)&#42;&#42;이며',
  '** P103(프레스)**이며',
  '**P103(프레스) **이며',
  '**P103(프레스)이며',
])('코드·이스케이프·불완전한 강조는 보존한다: %s', (content) => {
  renderMessage(content)
  expect(screen.getByRole('article').querySelector('strong')).toBeNull()
  expect(screen.getByRole('article')).toHaveTextContent('**')
})

test('사용자 메시지의 별표는 그대로 보인다', () => {
  renderMessage('**P103(프레스)**이며', 'USER')
  expect(screen.getByText('**P103(프레스)**이며')).toBeInTheDocument()
})

test.each([
  '멈춘 설비는 **P103(1라인 3호 프레스)**이며 A &amp; B를 확인하세요.',
  'A &amp; B 확인 후 **P103(1라인 3호 프레스)**이며',
  '별표 \\*를 참고하세요. **P103(1라인 3호 프레스)**이며',
  '**P103(1라인 3호 프레스)**이며 별표 \\*를 참고하세요.',
  '\\*\\*P103(1라인 3호 프레스)\\*\\*이며 실제 설비는 **P103(1라인 3호 프레스)**이며',
  '&#42;&#42;P103(1라인 3호 프레스)&#42;&#42;이며 실제 설비는 **P103(1라인 3호 프레스)**이며',
])('같은 문단의 문자 참조·이스케이프와 실제 강조를 구분한다: %s', (content) => {
  renderMessage(content)
  expect(screen.getByText('P103(1라인 3호 프레스)').tagName).toBe('STRONG')
  expect(screen.getByRole('article').querySelectorAll('strong')).toHaveLength(1)
})

test.each([
  '\\*\\*예시: **P103(프레스)**이며',
  '&#42;&#42;예시: **P103(프레스)**이며',
  '알 수 없는 &unknown; 참조와 **P103(프레스)**이며',
  '문자 &#128640;와 **P103(프레스)**이며',
  '첫 줄\r\n**P103(프레스)**이며',
])('변환된 문자가 실제 강조 앞에 있어도 원문 기호를 찾는다: %s', (content) => {
  renderMessage(content)
  expect(screen.getByText('P103(프레스)').tagName).toBe('STRONG')
  expect(screen.getByRole('article').querySelectorAll('strong')).toHaveLength(1)
})

test('강조 안의 문자 참조를 복원하고 문장 내용과 공백을 보존한다', () => {
  renderMessage('설비는 **P103(A &amp; B)**이며 정비중입니다.')
  expect(screen.getByText('P103(A & B)').tagName).toBe('STRONG')
  expect(screen.getByRole('article')).toHaveTextContent(
    '설비는 P103(A & B)이며 정비중입니다.',
  )
})

test('목록·표 안의 강조와 링크·코드·HTML 처리도 보존한다', () => {
  renderMessage(
    [
      '- **P103(프레스)**이며',
      '| 설비 | 상태 |\n| --- | --- |\n| **P104(프레스)**이며 | 정비중 |',
      '[설비](/explorer?object=1) [외부](https://example.com)',
      '`**P105(프레스)**이며` <b>원문</b>',
    ].join('\n\n'),
  )
  const answer = screen.getByRole('article')
  expect(screen.getByText('P103(프레스)').tagName).toBe('STRONG')
  expect(screen.getByText('P104(프레스)').tagName).toBe('STRONG')
  expect(screen.getByRole('table')).toBeInTheDocument()
  expect(screen.getByRole('link', { name: '설비' })).toHaveAttribute(
    'href',
    '/explorer?object=1',
  )
  expect(screen.queryByRole('link', { name: '외부' })).not.toBeInTheDocument()
  expect(answer.querySelector('code')).toHaveTextContent('**P105(프레스)**이며')
  expect(answer.querySelector('b')).toBeNull()
  expect(answer).toHaveTextContent('<b>원문</b>')
})
