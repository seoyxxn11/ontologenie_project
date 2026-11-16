import { useQuery } from '@tanstack/react-query'
import { startTransition, useEffect, useRef, useState } from 'react'
import { useLocation, useNavigate, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import StatusBadge from '../../components/StatusBadge'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import AnalysisTargetDialog from '../analysis/AnalysisTargetDialog'
import { analysisRunPath } from '../analysis/labels'
import MappingPanel from '../mapping/MappingPanel'
import { mappingPreviewApi } from '../mapping/previewApi'
import { fetchSource, fetchSources } from './api'
import ConnectionInfo from './ConnectionInfo'
import { connectionStatusLabels, sourceKindLabels } from './labels'
import ReadableScope from './ReadableScope'
import SourceFormDialog from './SourceFormDialog'
import SourceSyncRuns from './SourceSyncRuns'
import SourceSyncSummary from './SourceSyncSummary'
import SourceSamples from './SourceSamples'
import { sourceSampleApi } from './sampleApi'
import { sourceSyncApi } from './syncApi'
import './sources.css'
import FormSaveStatus from './FormSaveStatus'

const tabs = [
  { key: 'connection', label: '접속 정보' },
  { key: 'scope', label: '수집 대상' },
  { key: 'samples', label: '원천 표본' },
  { key: 'mapping', label: '원천 매핑' },
  { key: 'sync', label: '수집 이력' },
] as const

type TabKey = (typeof tabs)[number]['key']

// 데이터 연결: 원천 목록·등록·수정, 접속 정보·읽기 범위, 분석 대상 선택
function SourcesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  // 다른 화면의 [+ 원천 등록]은 ?register=1로 들어와 등록 창을 바로 연다.
  const location = useLocation()
  const [closedRegistration, setClosedRegistration] = useState<string>()
  const registering =
    searchParams.get('register') === '1' && closedRegistration !== location.key
  // 먼저 폼과 이탈 보호를 닫은 뒤 URL을 정리해 폐기 확인을 두 번 띄우지 않는다.
  useEffect(() => {
    if (closedRegistration === location.key && searchParams.has('register')) {
      const next = new URLSearchParams(searchParams)
      next.delete('register')
      setSearchParams(next, { replace: true })
    }
  }, [closedRegistration, location.key, searchParams, setSearchParams])
  const [createdId, setCreatedId] = useState<number>()
  const [analyzing, setAnalyzing] = useState(false)
  const navigate = useNavigate()
  const {
    data: sources,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({ queryKey: ['sources', 'list'], queryFn: fetchSources })

  const selectedId = Number(searchParams.get('source'))
  const selected =
    sources?.find((source) => source.id === selectedId) ?? sources?.[0]
  const detail = useQuery({
    queryKey: ['sources', selected?.id, 'detail'],
    queryFn: () => fetchSource(selected!.id),
    enabled: selected !== undefined,
  })
  const tab =
    tabs.find((item) => item.key === searchParams.get('tab'))?.key ??
    'connection'

  const optionsRef = useRef<HTMLDivElement>(null)
  useEffect(() => {
    const options = optionsRef.current
    if (!options) return
    const revealSelection = () => {
      if (!options.clientHeight) return
      const buttons = options.querySelectorAll<HTMLButtonElement>(
        '.sources-source-button',
      )
      const tallest = Array.from(buttons).reduce(
        (height, button) => Math.max(height, button.offsetHeight),
        0,
      )
      options.style.setProperty('--sources-option-height', `${tallest + 8}px`)
      const button = options.querySelector<HTMLButtonElement>(
        '[aria-pressed="true"]',
      )
      if (!button) return
      const bounds = options.getBoundingClientRect()
      const selectedBounds = button.getBoundingClientRect()
      if (selectedBounds.bottom > bounds.bottom - 4) {
        options.scrollTop += selectedBounds.bottom - bounds.bottom + 4
      } else if (selectedBounds.top < bounds.top + 4) {
        options.scrollTop += selectedBounds.top - bounds.top - 4
      }
    }
    revealSelection()
    document.fonts?.addEventListener('loadingdone', revealSelection)
    let width = options.clientWidth
    const resized = () => {
      if (options.clientWidth === width) return
      width = options.clientWidth
      revealSelection()
    }
    if (typeof ResizeObserver === 'undefined') {
      window.addEventListener('resize', resized)
      return () => {
        window.removeEventListener('resize', resized)
        document.fonts?.removeEventListener('loadingdone', revealSelection)
      }
    }
    const observer = new ResizeObserver(resized)
    observer.observe(options)
    return () => {
      observer.disconnect()
      document.fonts?.removeEventListener('loadingdone', revealSelection)
    }
  }, [selected?.id, sources, detail.data?.connectionStatus, isPending, isError])

  function select(sourceId: number, nextTab: TabKey = 'connection') {
    setSearchParams({ source: String(sourceId), tab: nextTab })
  }

  function register() {
    const next = new URLSearchParams(searchParams)
    next.set('register', '1')
    next.set('tab', 'connection')
    setSearchParams(next)
  }

  let list
  if (isPending) {
    list = <LoadingState message="원천 목록을 불러오는 중…" />
  } else if (isError) {
    list = (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  } else if (sources.length === 0) {
    list = <EmptyState message="등록된 원천이 없습니다." />
  } else {
    list = (
      <div
        ref={optionsRef}
        role="group"
        aria-label="등록된 원천"
        className="sources-options"
      >
        {sources.map((source) => {
          const active = source.id === selected?.id
          const status =
            connectionStatusLabels[
              active
                ? (detail.data?.connectionStatus ?? source.connectionStatus)
                : source.connectionStatus
            ]
          return (
            <Button
              key={source.id}
              variant="secondary"
              aria-label={source.name}
              aria-describedby={
                'source-kind-' + source.id + ' source-status-' + source.id
              }
              aria-pressed={active}
              aria-controls="source-detail"
              onClick={() => select(source.id)}
              className={
                'sources-source-button' +
                (source.name.length > 48 ? ' sources-source-button--long' : '')
              }
            >
              <span className="sources-source-name">{source.name}</span>
              <span id={'source-kind-' + source.id} className="sr-only">
                {sourceKindLabels[source.sourceKind]}
              </span>
              <span id={'source-status-' + source.id}>
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
              </span>
            </Button>
          )
        })}
      </div>
    )
  }

  return (
    <div className="sources-page">
      <PageHeader
        title="데이터 연결"
        description="원천별 접속 상태를 비교하고, 수집 대상·매핑·실행 이력을 확인하세요."
      />
      <FormSaveStatus />
      <div className="sources-toolbar">
        <section className="sources-selector" aria-label="원천 선택">
          <div className="sources-selector-label">
            <h2>원천</h2>
            {sources && (
              <span className="text-sm text-slate-500">
                {sources.length + '개'}
              </span>
            )}
          </div>
          {list}
          <Button
            variant="secondary"
            onClick={register}
            className="sources-register"
          >
            + 원천 등록
          </Button>
        </section>
        {selected && (
          <Button onClick={() => setAnalyzing(true)}>AI 관계 제안 받기</Button>
        )}
      </div>
      <div className="sources-workbench">
        {registering && (
          <SourceFormDialog
            onClose={(navigating) => {
              setAnalyzing(false)
              if (!navigating) setClosedRegistration(location.key)
            }}
            onSaved={(source) => {
              setAnalyzing(false)
              setCreatedId(source.id)
              select(source.id)
            }}
          />
        )}

        {selected && (
          <section
            id="source-detail"
            aria-label="원천 상세"
            className="sources-detail"
          >
            <div className="sources-detail-heading">
              <h2>{selected.name} 상세</h2>
            </div>
            <SourceSyncSummary
              key={selected.id}
              sourceId={selected.id}
              sourceName={selected.name}
              connectionStatus={
                detail.data?.connectionStatus ?? selected.connectionStatus
              }
            />
            <div role="group" aria-label="상세 항목" className="sources-tabs">
              {tabs.map((item) => (
                <button
                  key={item.key}
                  type="button"
                  aria-pressed={tab === item.key}
                  onClick={() => select(selected.id, item.key)}
                >
                  {item.label}
                </button>
              ))}
            </div>
            <div
              key={`${selected.id}:${tab}`}
              className="sources-detail-content"
            >
              {tab === 'connection' ? (
                <ConnectionInfo
                  key={selected.id}
                  sourceId={selected.id}
                  notice={
                    createdId === selected.id
                      ? '원천을 등록했습니다. 연결 다시 확인을 눌러 접속과 읽기 범위를 확인해 주세요.'
                      : undefined
                  }
                />
              ) : tab === 'scope' ? (
                <ReadableScope key={selected.id} source={selected} />
              ) : tab === 'sync' ? (
                <SourceSyncRuns
                  key={selected.id}
                  sourceId={selected.id}
                  api={sourceSyncApi}
                />
              ) : tab === 'samples' ? (
                selected.sourceKind === 'POSTGRES' ? (
                  <SourceSamples
                    key={selected.id}
                    sourceId={selected.id}
                    access={sourceSampleApi}
                  />
                ) : (
                  <EmptyState message="CSV·문서 원천의 표본 조회는 지원하지 않습니다." />
                )
              ) : (
                <MappingPanel
                  key={selected.id}
                  sourceId={selected.id}
                  previewAccess={mappingPreviewApi}
                />
              )}
            </div>
            {analyzing && !registering && (
              <AnalysisTargetDialog
                initialSourceId={selected.id}
                onClose={() => setAnalyzing(false)}
                onStarted={(run) => {
                  setAnalyzing(false)
                  void navigate(analysisRunPath(run))
                }}
                onEditScope={(sourceId) => {
                  setAnalyzing(false)
                  select(sourceId, 'scope')
                }}
                onRegister={() => {
                  startTransition(() => {
                    register()
                    setAnalyzing(false)
                  })
                }}
              />
            )}
          </section>
        )}
      </div>
    </div>
  )
}

export default SourcesPage
