import { rm } from 'node:fs/promises'
import { resolve } from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { loadEnv, type Plugin } from 'vite'
import { defineConfig } from 'vitest/config'
import { developmentProxy } from './devApiConfig.ts'

// 개발용 MSW 워커(public/mockServiceWorker.js)는 public 폴더라 빌드 결과에 그대로 복사된다.
// 운영에서는 쓰지 않으므로 빌드가 끝나면 결과 폴더에서 지운다. 개발 서버(npm run dev)에는 영향이 없다.
function excludeMockServiceWorker(): Plugin {
  let outDir = ''
  return {
    name: 'exclude-mock-service-worker',
    apply: 'build',
    configResolved(config) {
      outDir = resolve(config.root, config.build.outDir)
    },
    async closeBundle() {
      await rm(resolve(outDir, 'mockServiceWorker.js'), { force: true })
    },
  }
}

// https://vite.dev/config/
export default defineConfig(({ command, mode }) => ({
  plugins: [react(), tailwindcss(), excludeMockServiceWorker()],
  server: {
    proxy: developmentProxy(command, mode, loadEnv(mode, process.cwd(), '')),
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    coverage: {
      include: ['src/**/*.{ts,tsx}'],
    },
    // 테스트 하나의 기본 한도 5초는 검사 서버가 바쁠 때 화면이 다 뜨기 전에 끊긴다(src/test/setup.ts 참고)
    testTimeout: 30000,
  },
}))
