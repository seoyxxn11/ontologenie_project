export function resolveApiMode(
  development: boolean,
  configured: string | undefined,
  mode: string,
): 'mock' | 'real' {
  if (!development) return 'real'
  const value = configured ?? (mode === 'api' ? 'real' : 'mock')
  if (value !== 'mock' && value !== 'real')
    throw new Error('VITE_API_MODE는 mock 또는 real이어야 합니다.')
  return value
}
