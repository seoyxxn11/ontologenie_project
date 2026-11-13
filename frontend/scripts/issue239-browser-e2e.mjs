// 239 로컬 브라우저 검증: HEAD의 프론트에 239 작업 파일만 덧대어 production 빌드한다.
// 실행 중인 API의 ready.json은 backend/build에만 있고 정적 서버에서 제공하지 않는다.
import { execFileSync } from 'node:child_process'
import fs from 'node:fs'
import http from 'node:http'
import path from 'node:path'
import process from 'node:process'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const directory = path.join(root, 'backend/build/issue239-e2e')
const frontend = path.join(directory, 'frontend')
const site = path.join(directory, 'site')
fs.mkdirSync(frontend, { recursive: true })
const tracked = execFileSync(
  'git',
  ['ls-tree', '-r', '--name-only', 'HEAD', 'frontend'],
  { cwd: root, encoding: 'utf8' },
)
  .trim()
  .split('\n')
for (const file of tracked) {
  if (!file || file.startsWith('frontend/scripts/')) continue
  const output = path.join(directory, file)
  fs.mkdirSync(path.dirname(output), { recursive: true })
  fs.writeFileSync(
    output,
    execFileSync('git', ['show', `HEAD:${file}`], {
      cwd: root,
      maxBuffer: 20 * 1024 * 1024,
    }),
  )
}
for (const file of [
  'IssueManualLinkForm.tsx',
  'IssueManualLinkTargetPicker.tsx',
  'IssuesPanel.tsx',
  'api.ts',
  'IssueManualLinkForm.test.tsx',
  'IssuesPanel.test.tsx',
]) {
  const relative = `src/features/issues/${file}`
  fs.copyFileSync(
    path.join(root, 'frontend', relative),
    path.join(frontend, relative),
  )
}
const modules = path.join(frontend, 'node_modules')
if (!fs.existsSync(modules))
  fs.symlinkSync(path.join(root, 'frontend/node_modules'), modules, 'junction')
execFileSync(
  process.execPath,
  [
    path.join(root, 'frontend/node_modules/vite/bin/vite.js'),
    'build',
    '--outDir',
    site,
  ],
  { cwd: frontend, stdio: 'inherit' },
)
const ready = path.join(directory, 'ready.json')
if (!fs.existsSync(ready))
  throw new Error(
    '먼저 AXCORE_ISSUE_BROWSER_E2E=true로 브라우저 검증 테스트를 실행하세요.',
  )
const { backendPort } = JSON.parse(fs.readFileSync(ready, 'utf8'))
const types = {
  '.html': 'text/html',
  '.js': 'text/javascript',
  '.css': 'text/css',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.woff2': 'font/woff2',
}
const requests = []
const server = http.createServer((request, response) => {
  if (request.url.startsWith('/api/')) {
    const upstream = http.request(
      {
        hostname: '127.0.0.1',
        port: backendPort,
        path: request.url,
        method: request.method,
        headers: { ...request.headers, host: `127.0.0.1:${backendPort}` },
      },
      (result) => {
        requests.push({
          method: request.method,
          path: request.url,
          status: result.statusCode,
        })
        fs.writeFileSync(
          path.join(directory, 'requests.json'),
          JSON.stringify(requests, null, 2),
        )
        response.writeHead(result.statusCode, result.headers)
        result.pipe(response)
      },
    )
    upstream.on('error', () => {
      response.writeHead(502)
      response.end('Local test API unavailable')
    })
    request.pipe(upstream)
    return
  }
  let relative
  try {
    relative = decodeURIComponent(
      new URL(request.url, 'http://localhost').pathname,
    )
  } catch {
    response.writeHead(400)
    response.end()
    return
  }
  let file = path.resolve(site, `.${relative}`)
  if (
    !file.startsWith(`${site}${path.sep}`) ||
    !fs.existsSync(file) ||
    !fs.statSync(file).isFile()
  )
    file = path.join(site, 'index.html')
  response.writeHead(200, {
    'Content-Type': types[path.extname(file)] ?? 'application/octet-stream',
    'Cache-Control': 'no-store',
  })
  fs.createReadStream(file).pipe(response)
})
server.listen(0, '127.0.0.1', () => {
  const url = `http://localhost:${server.address().port}`
  fs.writeFileSync(
    path.join(directory, 'browser.json'),
    JSON.stringify({ url }),
  )
  console.log(`239 E2E browser: ${url}`)
})
