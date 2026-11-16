import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createRoot } from 'react-dom/client'
import { delay, http, HttpResponse } from 'msw'
import { setupWorker } from 'msw/browser'
import SourceSyncSummary from '../features/sources/SourceSyncSummary'
import type { SyncRun } from '../features/sources/syncApi'
import '../index.css'
import '../features/sources/sources.css'
import './sourceSummaryQa.css'

const cases = {
  demo: 'Demo data',
  worst: 'Worst case',
  empty: 'Empty · 이력 없음 / CDC 있음',
  queued: '대기',
  running: '실행 중',
  missing: '완료 시각 누락',
  forbidden: '권한 없음',
  error: '조회 실패',
  invalid: '잘못된 응답',
  loading: '불러오는 중',
} as const
type Case = keyof typeof cases

async function start() {
  const params = new URLSearchParams(location.search)
  const key = params.get('data') ?? 'demo'
  const selected: Case = Object.hasOwn(cases, key) ? (key as Case) : 'demo'
  const sourceName =
    selected === 'worst'
      ? '광주광역시제2사업장_정밀가공사업부_ERP생산관리및자재조달통합운영시스템_설비정비이력및품질검사데이터수집원천_스마트팩토리전환프로젝트_외부협력사연동전용_야간배치수집및주간조회분리운영_장기보관자료통합조회지원_2026년하반기운영환경_전사데이터플랫폼이관검증대상_제조실행계획및물류입출고연동데이터베이스_생산기획팀과정비팀공동운영_읽기전용수집계정_승인된원천접속구성_복구절차검증과통합모니터링업무용'.slice(
          0,
          200,
        )
      : 'ERP 운영 DB'
  const finishedAt = '2025-12-31T23:30:00-08:00'
  const status: SyncRun['status'] =
    selected === 'worst'
      ? 'FAILED'
      : selected === 'queued'
        ? 'QUEUED'
        : selected === 'running'
          ? 'RUNNING'
          : 'SUCCEEDED'
  const worker = setupWorker(
    http.get('/api/v1/sources/1/sync-runs', async () => {
      if (selected === 'loading') await delay(15000)
      if (selected === 'forbidden' || selected === 'error') {
        return new HttpResponse(null, {
          status: selected === 'forbidden' ? 403 : 500,
        })
      }
      const run: SyncRun = {
        id: 1,
        syncRunId: 1,
        dataSourceId: 1,
        triggerKind: 'RECOVERY',
        status,
        requestedBy: null,
        requestedAt: '2025-12-31T22:00:00-08:00',
        startedAt: null,
        finishedAt: ['missing', 'queued', 'running'].includes(selected)
          ? null
          : finishedAt,
        errorMessage: null,
        resources: [],
      }
      return HttpResponse.json({
        data: selected === 'empty' ? [] : [run],
        meta: {
          page: 0,
          size: 1,
          totalElements: selected === 'empty' ? 0 : 1,
          hasNext: false,
          cdcLastAppliedAt:
            selected === 'worst'
              ? null
              : selected === 'invalid'
                ? 'not-a-date'
                : finishedAt,
        },
      })
    }),
  )
  await worker.start()
  if (params.get('zoom') === '2') document.documentElement.style.zoom = '2'
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  createRoot(document.getElementById('root')!).render(
    <QueryClientProvider client={client}>
      <main className="summary-qa-page">
        <p>개발 전용 · 가상 데이터 · {cases[selected]}</p>
        <section className="summary-qa-panel">
          <h1>{sourceName} 상세</h1>
          <SourceSyncSummary
            sourceId={1}
            sourceName={sourceName}
            connectionStatus={
              selected === 'worst'
                ? 'FAILED'
                : selected === 'empty'
                  ? 'UNVERIFIED'
                  : 'CONNECTED'
            }
          />
        </section>
      </main>
      <div className="summary-qa-toolbar">
        <label htmlFor="summary-qa-data">QA 데이터</label>
        <select
          id="summary-qa-data"
          value={selected}
          onChange={(event) => {
            const next = new URL(location.href)
            next.searchParams.set('data', event.target.value)
            location.href = next.href
          }}
        >
          {Object.entries(cases).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
      </div>
    </QueryClientProvider>,
  )
}

if (import.meta.env.DEV) void start()
else document.getElementById('root')!.textContent = '개발 전용 QA 화면입니다.'
