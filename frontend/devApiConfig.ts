import type { ProxyOptions } from 'vite'
import { resolveApiMode } from './src/apiMode.ts'

export function developmentProxy(
  command: 'build' | 'serve',
  mode: string,
  env: Record<string, string | undefined>,
): Record<string, ProxyOptions> | undefined {
  if (resolveApiMode(command === 'serve', env.VITE_API_MODE, mode) !== 'real')
    return undefined
  if (command !== 'serve') return undefined
  // VITE_ 접두사가 없는 이 값은 브라우저 번들에 포함하지 않는다.
  const target = env.API_PROXY_TARGET ?? 'http://localhost:8080'
  let url: URL
  try {
    url = new URL(target)
  } catch {
    throw new Error('API_PROXY_TARGET에 유효한 백엔드 주소를 지정하세요.')
  }
  if (
    !['http:', 'https:'].includes(url.protocol) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash ||
    url.pathname !== '/'
  )
    throw new Error(
      'API_PROXY_TARGET은 계정·경로·쿼리 없는 HTTP(S) 주소여야 합니다.',
    )
  return { '/api/v1': { target: url.origin, changeOrigin: true } }
}
