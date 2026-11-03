import type { Source } from './types'

// "host:port/database" 한 줄을 접속 설정으로 나눈다. 포트를 빼면 5432.
export function parseEndpoint(
  value: string,
): Source['connectionConfig'] | null {
  const match = /^([^:/\s]+)(?::(\d{1,5}))?\/([^/\s]+)$/.exec(value.trim())
  if (!match) return null
  const port = match[2] ? Number(match[2]) : 5432
  if (port < 1 || port > 65535) return null
  return { host: match[1], port, database: match[3] }
}
