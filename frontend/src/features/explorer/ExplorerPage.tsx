import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useState } from 'react'
import Pagination from '../../components/Pagination'
import {
  fetchModelGraph,
  fetchObjects,
  OBJECT_PAGE_SIZE,
  type ObjectFilters,
} from './api'
import ObjectCard from './ObjectCard'
import ObjectDetail from './ObjectDetail'
import ObjectGraph from './ObjectGraph'
import ObjectSearchBar from './ObjectSearchBar'
import TypeRelations from './TypeRelations'
import { formatObjectCode } from './format'

// 유형 관계도는 그래프 라이브러리(React Flow)가 커서, 처음 화면에 그릴 때 따로 받아 온다.
const ModelGraph = lazy(() => import('./ModelGraph'))

function ExplorerPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [filters, setFilters] = useState<ObjectFilters>({})
  const [page, setPage] = useState(0)

  const {
    data: objectPage,
    isPending,
    isFetching,
    isError,
    error,
  } = useQuery({
    queryKey: ['objects', filters, page],
    queryFn: () => fetchObjects(filters, page),
    placeholderData: keepPreviousData,
  })

  // 객체를 고르기 전 가운데에 보여 줄 객체 유형 관계도(042).
  // 지금 명세는 관리자 전용이라, 실패하면 예전처럼 객체 선택 안내만 보여 준다.
  const { data: modelGraph, isPending: isModelGraphPending } = useQuery({
    queryKey: ['ontology', 'model-graph'],
    queryFn: fetchModelGraph,
    // 권한이 없으면 다시 시도해도 같은 결과라, 기다리지 않고 바로 안내로 넘어간다.
    retry: false,
  })

  // 유형 선택지는 유형 관계도의 유형을 쓰고, 받지 못하면 조건 없이 받은 목록에 나온 유형으로 만든다.
  const { data: allObjects } = useQuery({
    queryKey: ['objects', {}, 0],
    queryFn: () => fetchObjects({}),
  })
  const objectTypes = modelGraph
    ? modelGraph.nodes.map((node) => ({ id: node.id, name: node.name }))
    : [
        ...new Map(
          allObjects?.data.map(
            (item) => [item.objectTypeId, item.objectTypeName] as const,
          ),
        ),
      ].map(([id, name]) => ({ id, name }))

  function handleSearch(nextFilters: ObjectFilters) {
    setFilters(nextFilters)
    setPage(0)
  }

  // 유형 카드를 누르면 검색어는 그대로 두고 객체 유형만 바꾼다.
  function handleSelectType(typeId: number) {
    handleSearch({ ...filters, objectTypeId: typeId })
  }

  const totalCount = objectPage?.meta?.totalElements ?? objectPage?.data.length

  let listContent
  if (isPending) {
    listContent = <p className="text-slate-500">불러오는 중…</p>
  } else if (isError) {
    listContent = <p className="text-red-600">{error.message}</p>
  } else if (objectPage.data.length === 0) {
    listContent = (
      <p className="text-sm text-slate-500">조건에 맞는 객체가 없습니다.</p>
    )
  } else {
    listContent = objectPage.data.map((item) => (
      <ObjectCard
        key={item.id}
        code={formatObjectCode(item.id)}
        name={item.displayName}
        typeLabel={item.objectTypeName}
        selected={item.id === selectedId}
        onSelect={() => setSelectedId(item.id)}
      />
    ))
  }

  return (
    <div>
      <header className="mb-6">
        <p className="text-xs font-semibold tracking-widest text-slate-500">
          WORKSPACE
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">
          업무 데이터
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          흩어진 업무 데이터를 연결하고, 객체 간 관계를 한눈에 탐색하세요.
        </p>
      </header>

      {/* 유형 카드로 조건이 바뀌면 검색 줄 칸도 그 조건으로 다시 채운다. */}
      <ObjectSearchBar
        key={JSON.stringify(filters)}
        filters={filters}
        objectTypes={objectTypes}
        onSearch={handleSearch}
      />

      <div className="mt-4 grid gap-6 xl:grid-cols-[minmax(200px,0.7fr)_minmax(480px,1.5fr)_minmax(280px,1fr)]">
        <section aria-label="객체 목록" className="flex flex-col gap-2">
          <div className="flex items-baseline gap-2">
            <h2 className="text-lg font-semibold text-slate-900">객체 목록</h2>
            {totalCount !== undefined && (
              <span className="text-sm text-slate-500">{totalCount}건</span>
            )}
            {isFetching && !isPending && (
              <span className="text-sm text-slate-400">검색 중…</span>
            )}
          </div>
          {listContent}

          {objectPage && objectPage.data.length > 0 && (
            <Pagination
              page={page}
              size={OBJECT_PAGE_SIZE}
              totalElements={totalCount ?? 0}
              onChange={setPage}
            />
          )}
        </section>

        <section
          aria-label="관계도"
          className="min-w-0 rounded-xl border border-slate-200 bg-white p-4"
        >
          {selectedId !== null ? (
            <>
              <button
                type="button"
                onClick={() => setSelectedId(null)}
                className="mb-3 rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
              >
                ← 전체 구조로
              </button>
              <ObjectGraph objectId={selectedId} onSelect={setSelectedId} />
            </>
          ) : isModelGraphPending ? (
            <p className="text-slate-500">유형 관계도를 불러오는 중…</p>
          ) : modelGraph ? (
            <Suspense
              fallback={
                <p className="text-slate-500">유형 관계도를 불러오는 중…</p>
              }
            >
              <ModelGraph
                graph={modelGraph}
                selectedTypeId={filters.objectTypeId ?? null}
                onSelect={handleSelectType}
              />
            </Suspense>
          ) : (
            <ObjectGraph objectId={null} onSelect={setSelectedId} />
          )}
        </section>

        <section className="rounded-xl border border-slate-200 bg-white p-6">
          {selectedId !== null || !modelGraph ? (
            <ObjectDetail objectId={selectedId} />
          ) : filters.objectTypeId ? (
            <TypeRelations graph={modelGraph} typeId={filters.objectTypeId} />
          ) : (
            <p className="text-slate-500">
              가운데에서 객체 유형을 고르거나 왼쪽 목록에서 객체를 선택해
              주세요.
            </p>
          )}
        </section>
      </div>
    </div>
  )
}

export default ExplorerPage
