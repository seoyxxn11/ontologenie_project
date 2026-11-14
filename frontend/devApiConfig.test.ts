import { expect, test } from 'vitest'
import { developmentProxy } from './devApiConfig'

test('실제 API 개발 모드에서만 같은 출처 /api/v1을 지정 서버로 전달한다', () => {
  expect(developmentProxy('serve', 'development', {})).toBeUndefined()
  expect(developmentProxy('serve', 'api', {})).toEqual({
    '/api/v1': { target: 'http://localhost:8080', changeOrigin: true },
  })
  expect(
    developmentProxy('serve', 'development', {
      VITE_API_MODE: 'real',
      API_PROXY_TARGET: 'https://localhost:8443',
    }),
  ).toEqual({
    '/api/v1': { target: 'https://localhost:8443', changeOrigin: true },
  })
  expect(
    developmentProxy('build', 'api', { VITE_API_MODE: 'mock' }),
  ).toBeUndefined()
})

test.each([
  'not-a-url',
  'file:///tmp',
  'http://name:pass@localhost',
  'http://localhost/path',
  'http://localhost?token=value',
  'http://localhost#hash',
])('잘못된 프록시 대상은 서버 시작 전에 거절한다: %s', (target) => {
  expect(() =>
    developmentProxy('serve', 'api', { API_PROXY_TARGET: target }),
  ).toThrow('API_PROXY_TARGET')
})
