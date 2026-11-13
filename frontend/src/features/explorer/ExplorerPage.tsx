import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useState } from 'react'
import { useSearchParams } from 'react-router'
import Pagination from '../../components/Pagination'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { ApiError } from '../../api/client'
import {
  fetchModelGraph,
  fetchObject,
  fetchObjects,
  fetchObjectTypes,
  OBJECT_PAGE_SIZE,
  type ObjectFilters,
} from './api'
import type { ModelGraphView } from './ModelGraph'
import ObjectCard from './ObjectCard'
import ObjectDetail from './ObjectDetail'
import ObjectGraph from './ObjectGraph'
import ObjectSearchBar from './ObjectSearchBar'
import TypeRelations from './TypeRelations'
import { formatCount, formatObjectKey, withAndParticle } from './format'
import './explorer.css'

// 유형 관계도는 그래프 라이브러리(React Flow)가 커서, 처음 화면에 그릴 때 따로 받아 온다.
const ModelGraph = lazy(() => import('./ModelGraph'))

// 주소의 ?object= 값이 1 이상의 정수일 때만 객체 번호로 본다.
function parseObjectId(value: string | null) {
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : null
}

function ExplorerPage() {
  // 고른 객체는 주소의 ?object=에 둔다. Agent 답변의 [객체 열기], 새로고침, 뒤로 가기가 같은 객체를 연다(151).
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedId = parseObjectId(searchParams.get('object'))
  const [filters, setFilters] = useState<ObjectFilters>({})
  const [page, setPage] = useState(0)
  const [graphExpanded, setGraphExpanded] = useState(false)
  // 유형 관계도의 보기 방식. 객체를 골랐다가 전체 구조로 돌아와도 고른 보기를 유지한다.
  const [modelView, setModelView] = useState<ModelGraphView>('graph')

  // 객체를 고르면 주소에 번호를 쓰고, 선택을 풀면 지운다. 기록을 쌓지 않아 뒤로 가기는 이전 화면으로 간다.
  function selectObject(id: number | null) {
    setSearchParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        if (id === null) {
          next.delete('object')
        } else {
          next.set('object', String(id))
        }
        return next
      },
      { replace: true },
    )
  }

  const {
    data: objectPage,
    isPending,
    isFetching,
    isError,
    error,
    refetch: refetchObjects,
    isPlaceholderData,
  } = useQuery({
    queryKey: ['objects', filters, page],
    queryFn: () => fetchObjects(filters, page),
    placeholderData: keepPreviousData,
  })

  // 객체를 고르기 전 가운데에 보여 줄 객체 유형 관계도(042).
  // 관리자 전용 API의 403은 객체 선택 안내로 처리한다.
  const {
    data: modelGraph,
    isPending: isModelGraphPending,
    isFetching: isModelGraphFetching,
    error: modelGraphError,
    refetch: refetchModelGraph,
  } = useQuery({
    queryKey: ['ontology', 'model-graph'],
    queryFn: fetchModelGraph,
    // 권한이 없으면 다시 시도해도 같은 결과라, 기다리지 않고 바로 안내로 넘어간다.
    retry: false,
  })
  const isModelGraphForbidden =
    modelGraphError instanceof ApiError && modelGraphError.status === 403
  const showModelGraph =
    selectedId === null && Boolean(modelGraph) && !isModelGraphForbidden

  // 관계도 칸 제목에 쓸 고른 객체의 이름. 관계도·상세와 같은 캐시를 쓴다.
  const { data: selectedObject } = useQuery({
    queryKey: ['objects', selectedId],
    queryFn: () => fetchObject(selectedId as number),
    enabled: selectedId !== null,
  })
  const graphTitle = showModelGraph
    ? '객체 유형 간 관계'
    : selectedId !== null && selectedObject
      ? `${withAndParticle(selectedObject.displayName)} 연결된 정보`
      : '관계 탐색'

  const {
    data: objectTypes = [],
    error: objectTypesError,
    refetch: refetchObjectTypes,
  } = useQuery({
    queryKey: ['objects', 'types'],
    queryFn: fetchObjectTypes,
  })

  function handleSearch(nextFilters: ObjectFilters) {
    setFilters(nextFilters)
    setPage(0)
  }

  // 유형 카드를 누르면 검색어는 그대로 두고 객체 유형만 바꾼다.
  function handleSelectType(typeId: number) {
    handleSearch({ q: filters.q, objectTypeId: typeId })
  }

  const totalCount = objectPage?.meta?.totalElements ?? objectPage?.data.length
  const appliedTypeName = objectTypes.find(
    (type) => type.id === filters.objectTypeId,
  )?.name
  const appliedConditions = [
    appliedTypeName ?? '전체 유형',
    filters.q ? `이름·ID: ${filters.q}` : null,
    ...Object.values(filters.propertyValues ?? {}).map(
      (value) => `속성 값: ${value}`,
    ),
  ]
    .filter(Boolean)
    .join(' · ')

  if (
    !isFetching &&
    !isPlaceholderData &&
    !isError &&
    totalCount !== undefined
  ) {
    const lastPage = Math.max(0, Math.ceil(totalCount / OBJECT_PAGE_SIZE) - 1)
    if (page > lastPage) setPage(lastPage)
  }

  let listContent
  if (isPending) {
    listContent = <LoadingState message="불러오는 중…" />
  } else if (isError) {
    listContent = (
      <ErrorState error={error} onRetry={() => void refetchObjects()} />
    )
  } else if (objectPage.data.length === 0) {
    listContent = <EmptyState message="조건에 맞는 객체가 없습니다." />
  } else {
    listContent = objectPage.data.map((item) => (
      <ObjectCard
        key={item.id}
        code={formatObjectKey(item)}
        name={item.displayName}
        typeLabel={item.objectTypeName}
        selected={item.id === selectedId}
        onSelect={() => selectObject(item.id)}
      />
    ))
  }

  return (
    <div className="explorer-page">
      <PageHeader
        title="업무 데이터"
        description="객체를 찾고, 연결 관계와 원천 근거를 함께 확인하세요."
      />

      {/* 유형 카드로 조건이 바뀌면 검색 줄 칸도 그 조건으로 다시 채운다. */}
      <ObjectSearchBar
        key={JSON.stringify(filters)}
        filters={filters}
        objectTypes={objectTypes}
        onSearch={handleSearch}
        pending={isFetching}
      />
      {objectTypesError && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          객체 유형을 불러오지 못했습니다.{' '}
          <button
            type="button"
            onClick={() => void refetchObjectTypes()}
            className="underline"
          >
            유형 다시 불러오기
          </button>
        </p>
      )}

      <p className="explorer-applied-filters" aria-label="적용된 검색 조건">
        <span>적용 조건</span> {appliedConditions}
      </p>

      <div
        className={`explorer-workbench${graphExpanded ? ' is-expanded' : ''}`}
      >
        <section
          aria-label="객체 목록"
          className="explorer-panel explorer-list"
          hidden={graphExpanded}
        >
          <div className="explorer-panel-heading">
            <h2>객체 목록</h2>
            {totalCount !== undefined && (
              <span className="explorer-count">
                {formatCount(totalCount)}건
              </span>
            )}
            {isFetching && !isPending && (
              <span role="status" className="explorer-count">
                검색 중…
              </span>
            )}
          </div>
          <div className="explorer-object-list" aria-busy={isFetching}>
            {listContent}
          </div>

          {objectPage && objectPage.data.length > 0 && (
            <Pagination
              page={isPlaceholderData ? (objectPage.meta?.page ?? page) : page}
              size={OBJECT_PAGE_SIZE}
              totalElements={totalCount ?? 0}
              onChange={setPage}
              pending={isFetching}
              compact
            />
          )}
        </section>

        <section aria-label="관계도" className="explorer-panel explorer-graph">
          <div className="explorer-panel-heading">
            <h2>{graphTitle}</h2>
            <div className="explorer-panel-actions">
              {showModelGraph && (
                <div
                  role="group"
                  aria-label="보기 방식"
                  className="explorer-view-toggle"
                >
                  <button
                    type="button"
                    aria-pressed={modelView === 'graph'}
                    onClick={() => setModelView('graph')}
                  >
                    개요
                  </button>
                  <button
                    type="button"
                    aria-pressed={modelView === 'list'}
                    onClick={() => setModelView('list')}
                  >
                    유형 목록
                  </button>
                </div>
              )}
              {selectedId !== null && (
                <Button variant="text" onClick={() => selectObject(null)}>
                  ← 전체 구조로
                </Button>
              )}
              <Button
                variant="text"
                aria-pressed={graphExpanded}
                onClick={() => setGraphExpanded((expanded) => !expanded)}
              >
                {graphExpanded ? '분할 보기' : '관계도 확장'}
              </Button>
            </div>
          </div>
          <div className="explorer-panel-body">
            {selectedId === null &&
              modelGraphError &&
              !isModelGraphForbidden && (
                <div role="alert" className="mb-3 text-sm text-red-600">
                  <p>
                    유형 관계도를 불러오지 못했습니다. {modelGraphError.message}
                  </p>
                  <Button
                    variant="secondary"
                    pending={isModelGraphFetching}
                    onClick={() => void refetchModelGraph()}
                  >
                    {isModelGraphFetching ? '다시 불러오는 중…' : '다시 시도'}
                  </Button>
                </div>
              )}
            {selectedId !== null ? (
              <ObjectGraph objectId={selectedId} onSelect={selectObject} />
            ) : isModelGraphPending ? (
              <p className="text-slate-500">유형 관계도를 불러오는 중…</p>
            ) : modelGraph && !isModelGraphForbidden ? (
              <Suspense
                fallback={
                  <p className="text-slate-500">유형 관계도를 불러오는 중…</p>
                }
              >
                <ModelGraph
                  view={modelView}
                  graph={modelGraph}
                  selectedTypeId={filters.objectTypeId ?? null}
                  onSelect={handleSelectType}
                />
              </Suspense>
            ) : !modelGraphError || isModelGraphForbidden ? (
              <ObjectGraph objectId={null} onSelect={selectObject} />
            ) : null}
          </div>
        </section>

        <section
          aria-label="상세 정보"
          className="explorer-panel explorer-detail"
          hidden={graphExpanded}
        >
          <div className="explorer-panel-heading">
            <h2>상세 정보</h2>
            {selectedId !== null && (
              <Button variant="text" onClick={() => selectObject(null)}>
                선택 해제
              </Button>
            )}
          </div>
          <div
            key={selectedId ?? `type-${filters.objectTypeId ?? 'all'}`}
            className="explorer-panel-body explorer-detail-body"
          >
            {selectedId !== null || !modelGraph || isModelGraphForbidden ? (
              <ObjectDetail objectId={selectedId} />
            ) : filters.objectTypeId ? (
              <TypeRelations graph={modelGraph} typeId={filters.objectTypeId} />
            ) : (
              <p className="explorer-detail-hint">
                가운데에서 객체 유형을 고르거나 왼쪽 목록에서 객체를 선택해
                주세요.
              </p>
            )}
          </div>
        </section>
      </div>
    </div>
  )
}

export default ExplorerPage
