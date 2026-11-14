import assert from 'node:assert/strict'
import { createServer as createHttpServer } from 'node:http'
import { once } from 'node:events'
import { createServer as createViteServer } from 'vite'

// 실제 업무 서버가 아닌 루프백 검증용 HTTP 서버다. 계정·원천 데이터를 쓰지 않는다.
const observed = []
const upstream = createHttpServer((request, response) => {
  observed.push({ url: request.url, cookie: request.headers.cookie })
  response.setHeader('Content-Type', 'application/json')
  if (request.url === '/api/v1/probe-denied') {
    response.writeHead(403)
    response.end(
      JSON.stringify({
        error: {
          code: 'FORBIDDEN',
          message: 'probe',
          details: {},
          requestId: 'proxy-probe',
        },
      }),
    )
  } else {
    response.end(JSON.stringify({ data: { origin: 'local-upstream-probe' } }))
  }
})
let vite
try {
  upstream.listen(0, '127.0.0.1')
  await once(upstream, 'listening')
  process.env.API_PROXY_TARGET = `http://127.0.0.1:${upstream.address().port}`
  process.env.VITE_API_MODE = 'real'
  vite = await createViteServer({
    mode: 'api',
    server: { host: '127.0.0.1', port: 0, strictPort: true },
  })
  await vite.listen()
  const base = `http://127.0.0.1:${vite.httpServer.address().port}`
  const success = await fetch(`${base}/api/v1/probe?from=421`, {
    headers: { Cookie: 'probe-cookie=fake' },
  })
  assert.equal(success.status, 200)
  assert.deepEqual(await success.json(), {
    data: { origin: 'local-upstream-probe' },
  })
  assert.deepEqual(observed[0], {
    url: '/api/v1/probe?from=421',
    cookie: 'probe-cookie=fake',
  })
  const denied = await fetch(`${base}/api/v1/probe-denied`)
  assert.equal(denied.status, 403)
  assert.equal((await denied.json()).error.code, 'FORBIDDEN')
  await new Promise((resolve) => upstream.close(resolve))
  const unavailable = await fetch(`${base}/api/v1/probe`)
  assert.ok(unavailable.status >= 500)
  assert.doesNotMatch(
    await unavailable.text(),
    /local-upstream-probe|<!doctype html>/i,
  )
  console.log(
    'PASS: 실제 HTTP 프록시 경로·쿠키·200/403 전달 및 서버 중단 시 5xx(모의 성공 대체 없음)',
  )
} finally {
  await vite?.close()
  upstream.closeAllConnections()
  if (upstream.listening)
    await new Promise((resolve) => upstream.close(resolve))
}
