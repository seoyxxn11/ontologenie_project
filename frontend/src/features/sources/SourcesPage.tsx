import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import AnalysisTargetDialog from '../analysis/AnalysisTargetDialog'
import { analysisRunPath } from '../analysis/labels'
import { fetchSources } from './api'
import ConnectionInfo from './ConnectionInfo'
import { connectionStatusLabels, sourceKindLabels } from './labels'
import ReadableScope from './ReadableScope'
import SourceFormDialog from './SourceFormDialog'

// 수집 이력 탭은 수동 수집(S15P31C106-120)에서 더한다.
const tabs = [
  { key: 'connection', label: '접속 정보' },
  { key: 'scope', label: '수집 대상' },
] as const

type TabKey = (typeof tabs)[number]['key']

// 데이터 연결: 원천 목록·등록·수정(S15P31C106-105)과 접속 정보·읽기 범위(S15P31C106-106),
// 분석 대상 선택(S15P31C106-107)
function SourcesPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [registering, setRegistering] = useState(false)
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
  const tab: TabKey =
    searchParams.get('tab') === 'scope' ? 'scope' : 'connection'

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
      <DataTable label="등록된 원천 목록">
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
            <th scope="col" className="px-4 py-3 font-normal">
              수집 상태
            </th>
            <th scope="col" className="px-4 py-3 font-normal">
              마지막 수집
            </th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100">
          {sources.map((source) => {
            const active = source.id === selected?.id
            const status = connectionStatusLabels[source.connectionStatus]
            return (
              <tr key={source.id} className={active ? 'bg-slate-50' : ''}>
                <th scope="row" className="px-4 py-3 text-left">
                  <button
                    type="button"
                    aria-pressed={active}
                    aria-controls="source-detail"
                    onClick={() => select(source.id)}
                    className={`font-semibold hover:underline ${active ? 'text-slate-900' : 'text-slate-700'}`}
                  >
                    {source.name}
                  </button>
                </th>
                <td className="px-4 py-3">
                  {sourceKindLabels[source.sourceKind]}
                </td>
                <td className={`px-4 py-3 ${status.className}`}>
                  {status.label}
                </td>
                {/* 수집 상태·시각은 수집 실행 이력(S15P31C106-120)과 연결한다. */}
                <td className="px-4 py-3 text-slate-400">-</td>
                <td className="px-4 py-3 text-slate-400">-</td>
              </tr>
            )
          })}
        </tbody>
      </DataTable>
    )
  }

  return (
    <div>
      <header className="mb-6">
        <p className="text-xs font-semibold tracking-widest text-slate-500">
          SOURCES
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">
          데이터 연결
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          원천을 연결하고 수집 대상을 저장한 뒤 관계 분석에 사용하세요.
        </p>
      </header>

      <section className="space-y-3 rounded-xl border border-slate-200 bg-white p-6">
        <div className="flex items-center justify-between gap-2">
          <div className="flex items-baseline gap-2">
            <h2 className="text-lg font-semibold text-slate-900">
              등록된 원천
            </h2>
            {sources && (
              <span className="text-sm text-slate-500">{`${sources.length}개`}</span>
            )}
          </div>
          <Button variant="secondary" onClick={() => setRegistering(true)}>
            + 원천 등록
          </Button>
        </div>
        <div className="text-sm">{list}</div>
      </section>
      {registering && (
        <SourceFormDialog
          onClose={() => setRegistering(false)}
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
          className="mt-6 space-y-5 rounded-xl border border-slate-200 bg-white p-6"
        >
          <h2 className="text-lg font-semibold text-slate-900">
            {selected.name} 상세
          </h2>
          <div role="group" aria-label="상세 항목" className="flex gap-2">
            {tabs.map((item) => (
              <button
                key={item.key}
                type="button"
                aria-pressed={tab === item.key}
                onClick={() => select(selected.id, item.key)}
                className={`rounded-lg border px-3 py-1.5 text-sm ${
                  tab === item.key
                    ? 'border-slate-900 bg-slate-900 text-white'
                    : 'border-slate-300 text-slate-700 hover:border-slate-900'
                }`}
              >
                {item.label}
              </button>
            ))}
          </div>
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
          ) : (
            <ReadableScope key={selected.id} sourceId={selected.id} />
          )}
          <div className="border-t border-slate-100 pt-4">
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
  )
}

export default SourcesPage
