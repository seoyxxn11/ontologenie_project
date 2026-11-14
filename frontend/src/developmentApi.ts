import { resolveApiMode } from './apiMode'

// false면 워커 해제 후 새 문서로 이동 중이므로 현재 문서에서는 앱을 시작하지 않는다.
export async function prepareDevelopmentApi(
  reload = () => window.location.reload(),
): Promise<boolean> {
  if (!import.meta.env.DEV) return true
  const mode = resolveApiMode(
    true,
    import.meta.env.VITE_API_MODE,
    import.meta.env.MODE,
  )
  if (mode === 'mock') {
    const { worker } = await import('./mocks/browser')
    await worker.start({
      onUnhandledFrame: ({ frame, defaults }) => {
        if (frame.protocol !== 'http') return
        const { request } = frame.data as { request: Request }
        // 폰트·Vite 모듈은 통과시키되, 없는 API를 실제 서버로 보내지 않는다.
        if (new URL(request.url).pathname.startsWith('/api/')) defaults.error()
      },
      quiet: true,
    })
    return true
  }

  if (!('serviceWorker' in navigator)) return true
  const expected = new URL('/mockServiceWorker.js', window.location.origin).href
  const registrations = await navigator.serviceWorker.getRegistrations()
  for (const registration of registrations) {
    if (
      [registration.active, registration.waiting, registration.installing].some(
        (worker) => worker?.scriptURL === expected,
      )
    ) {
      if (!(await registration.unregister()))
        throw new Error('개발용 모의 API 워커를 해제하지 못했습니다.')
    }
  }
  // unregister만으로 현재 문서의 controller는 사라지지 않는다.
  if (navigator.serviceWorker.controller?.scriptURL === expected) {
    reload()
    return false
  }
  return true
}
