import { expect, test } from 'vitest'
import { resolveApiMode } from './apiMode'

test('일반 개발과 대량 예시는 mock, api 모드는 real이며 명시한 설정이 우선한다', () => {
  expect(resolveApiMode(true, undefined, 'development')).toBe('mock')
  expect(resolveApiMode(true, undefined, 'graph-demo')).toBe('mock')
  expect(resolveApiMode(true, undefined, 'api')).toBe('real')
  expect(resolveApiMode(true, 'real', 'development')).toBe('real')
  expect(resolveApiMode(true, 'mock', 'api')).toBe('mock')
})

test('잘못된 개발 설정은 조용히 모의 응답으로 넘어가지 않고 실패한다', () => {
  expect(() => resolveApiMode(true, 'false', 'development')).toThrow(
    'VITE_API_MODE',
  )
  expect(resolveApiMode(false, 'mock', 'api')).toBe('real')
  expect(resolveApiMode(false, 'wrong', 'production')).toBe('real')
})
