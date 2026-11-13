import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import AnalysisTargetDialog from '../analysis/AnalysisTargetDialog'
import { analysisRunPath } from '../analysis/labels'
import MappingPanel from '../mapping/MappingPanel'
import { mappingPreviewApi } from '../mapping/previewApi'
import { fetchSources } from './api'
import ConnectionInfo from './ConnectionInfo'
import { connectionStatusLabels, sourceKindLabels } from './labels'
import ReadableScope from './ReadableScope'
import SourceFormDialog from './SourceFormDialog'
import SourceSyncRuns from './SourceSyncRuns'
import SourceSyncSummary from './SourceSyncSummary'
import SourceSamples from './SourceSamples'
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
  const [registering, setRegistering] = useState(
    () => searchParams.get('register') === '1',
  )
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
  const tab =
    tabs.find((item) => item.key === searchParams.get('tab'))?.key ??
    'connection'

  function select(sourceId: number, nextTab: TabKey = 'connection') {
    setSearchParams({ source: String(sourceId), tab: nextTab })
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
      <DataTable label="등록된 원천 목록" className="sources-table">
        <thead className="bg-slate-50 text-xs text-slate-500">
          <tr>
            <th scope="col" className="px-4 py-3 font-normal">
              원천
            </th>
            <th scope="col" className="px-4 py-3 font-normal">
              유형
            </th>
            <th scope="col" className="px-4 py-3 font-normal">
              접속 확인
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {sources.map((source) => {
            const active = source.id === selected?.id
            const status = connectionStatusLabels[source.connectionStatus]
            return (
              <tr key={source.id} data-selected={active || undefined}>
                <th scope="row" className="px-4 py-3 text-left">
                  <Button
                    variant="text"
                    aria-pressed={active}
                    aria-controls="source-detail"
                    onClick={() => select(source.id)}
                    className="sources-select"
                  >
                    {source.name}
                  </Button>
                </th>
                <td className="px-4 py-3">
                  {sourceKindLabels[source.sourceKind]}
                </td>
                <td className={`px-4 py-3 ${status.className}`}>
                  {status.label}
                </td>
              </tr>
            )
          })}
        </tbody>
      </DataTable>
    )
  }

  return (
    <div className="sources-page">
      <PageHeader
        title="데이터 연결"
        description="원천별 접속 상태를 비교하고, 수집 대상·매핑·실행 이력을 확인하세요."
        action={
          <Button variant="secondary" onClick={() => setRegistering(true)}>
            + 원천 등록
          </Button>
        }
      />
      <FormSaveStatus />
      <div className="sources-workbench">
        <section className="sources-list" aria-label="원천 선택">
          <div className="sources-panel-heading">
            <div className="flex items-baseline gap-2">
              <h2 className="text-lg font-semibold text-slate-900">
                등록된 원천
              </h2>
              {sources && (
                <span className="text-sm text-slate-500">{`${sources.length}개`}</span>
              )}
            </div>
          </div>
          {list}
          <div className="sources-list-note">
            <p>
              접속 확인과 수집 실행 상태는 별도입니다. 최근 상태·시각은 우측
              요약에서, 전체 실행 기록은 선택한 원천의 수집 이력에서 확인하세요.
            </p>
            {selected && (
              <Button
                variant="text"
                onClick={() => select(selected.id, 'sync')}
              >
                수집 이력 보기
              </Button>
            )}
          </div>
        </section>
        {registering && (
          <SourceFormDialog
            onClose={(navigating) => {
              setRegistering(false)
              if (!navigating && searchParams.has('register')) {
                const next = new URLSearchParams(searchParams)
                next.delete('register')
                setSearchParams(next, { replace: true })
              }
            }}
            onSaved={(source) => {
              setRegistering(false)
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
            <h2 className="sources-detail-heading">{selected.name} 상세</h2>
            <section className="sources-summary" aria-label="최근 수집 요약">
              <DataTable label="선택한 원천 수집 요약">
                <thead>
                  <tr>
                    <th scope="col">최근 수집 실행</th>
                    <th scope="col">수집 시각</th>
                  </tr>
                </thead>
                <tbody>
                  <tr>
                    <SourceSyncSummary
                      key={selected.id}
                      sourceId={selected.id}
                      sourceName={selected.name}
                    />
                  </tr>
                </tbody>
              </DataTable>
              <p className="mt-3 text-sm text-slate-500">
                전체 읽기는 가장 최근에 접수한 실행 기준입니다. 실패한 실행의
                종료 시각은 성공 수집 시각이 아닙니다. CDC 반영은 마지막 반영한
                원천 트랜잭션의 커밋 시각이며, 현재 CDC 정상 여부나 반영 지연을
                뜻하지 않습니다.
              </p>
            </section>
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
                <SourceSamples key={selected.id} sourceId={selected.id} />
              ) : (
                <MappingPanel
                  key={selected.id}
                  sourceId={selected.id}
                  previewAccess={mappingPreviewApi}
                />
              )}
            </div>
            <div className="sources-detail-footer">
              <Button onClick={() => setAnalyzing(true)}>
                이 원천으로 AI 관계 제안 받기
              </Button>
            </div>
            {analyzing && (
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
                  setAnalyzing(false)
                  setRegistering(true)
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
