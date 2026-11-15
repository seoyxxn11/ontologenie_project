import { formatKstDateTime } from '../../utils/dateTime'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate, useLocation } from 'react-router'
import Button from '../../components/Button'
import StatusBadge from '../../components/StatusBadge'
import { createConversation } from '../agent/api'
import type { AgentConversation, AgentEntryState } from '../agent/types'
import { fetchObject, fetchObjectProperties } from './api'
import EvidenceDialog from './EvidenceDialog'
import { formatObjectKey } from './format'
import { detailValue } from './detailPresentation'
import { statusLabels } from './labels'
import type { ObjectInstanceDetail } from './types'

function formatDateTime(value: string | null | undefined) {
  if (!value) {
    return '—'
  }
  return formatKstDateTime(value)
}

type ObjectDetailProps = {
  objectId: number | null
}

function ObjectDetail({ objectId }: ObjectDetailProps) {
  if (objectId === null) {
    return <p className="text-slate-500">왼쪽 목록에서 객체를 선택해 주세요.</p>
  }

  return <ObjectDetailContent key={objectId} objectId={objectId} />
}

type ObjectDetailContentProps = {
  objectId: number
}

function ObjectDetailContent({ objectId }: ObjectDetailContentProps) {
  const historyOrigin = useLocation()
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {
    data: object,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })
  const definitions = useQuery({
    queryKey: [
      'ontology',
      'object-properties',
      String(object?.objectTypeId ?? ''),
    ],
    queryFn: () => fetchObjectProperties(object!.objectTypeId),
    enabled: Boolean(object),
    retry: false,
    staleTime: 60_000,
  })

  // [질문하기]: 새 대화를 만들고, 이 객체와 미리 채울 질문을 들고 대화 화면으로 간다(152).
  const askMutation = useMutation<
    AgentConversation,
    Error,
    ObjectInstanceDetail
  >({
    mutationFn: () => createConversation(),
    onSuccess: (created, target) => {
      queryClient.setQueryData<AgentConversation[]>(
        ['agent', 'conversations'],
        (previous) => [created, ...(previous ?? [])],
      )
      const entry: AgentEntryState = {
        conversationId: created.id,
        referenceObjectId: target.id,
        referenceObjectName: target.displayName,
        referenceObjectTypeName: target.objectTypeName,
        draft: `${target.displayName}에 연결된 업무 정보를 알려줘`,
      }
      void navigate(`/agent?conversation=${created.id}`, { state: entry })
    },
  })

  if (isPending) {
    return <p className="text-slate-500">상세 정보를 불러오는 중…</p>
  }

  if (isError) {
    return <p className="text-red-600">{error.message}</p>
  }

  const source = object.sourceSystemName
    ? [object.sourceSystemName, object.sourceResourceKey]
        .filter(Boolean)
        .join(' · ')
    : '플랫폼 객체'
  const properties = Object.entries(object.properties)
  const restrictedCount = object.restrictedPropertyCodes.length
  const { propertyNames, restrictedPropertyCodes } = object
  function propertyRows(entries: [string, unknown][]) {
    return entries.map(([code, value]) => {
      const definition = definitions.data?.find((item) => item.code === code)
      return (
        <div key={code}>
          <dt>{propertyNames[code] ?? definition?.name ?? code}</dt>
          <dd
            className={
              restrictedPropertyCodes.includes(code)
                ? 'explorer-restricted'
                : undefined
            }
          >
            {restrictedPropertyCodes.includes(code)
              ? '권한 없음'
              : detailValue(value, definition)}
            {definition?.description?.trim() &&
              !restrictedPropertyCodes.includes(code) && (
                <p className="mt-1 text-xs text-slate-500">
                  {definition.description}
                </p>
              )}
          </dd>
        </div>
      )
    })
  }

  return (
    <div className="explorer-object-detail">
      <p className="explorer-object-meta">
        {[object.objectTypeName, formatObjectKey(object)]
          .filter(Boolean)
          .join(' / ')}
      </p>
      <h2 className="explorer-detail-title">{object.displayName}</h2>
      <div className="explorer-ai-handoff">
        <Button
          pending={askMutation.isPending}
          onClick={() => askMutation.mutate(object)}
        >
          AI에 질문
        </Button>
      </div>
      {askMutation.isError && (
        <p className="mb-3 text-sm text-red-600">대화를 만들지 못했어요.</p>
      )}
      {restrictedCount > 0 && (
        <p className="explorer-restricted-summary">
          권한 제한 속성 {restrictedCount}개
        </p>
      )}
      <h3 className="mt-4 mb-2 text-sm font-semibold text-slate-700">
        업무 요약
      </h3>
      {properties.length === 0 && <p>제공된 속성이 없습니다.</p>}
      {definitions.isError && (
        <p className="text-xs text-slate-500">
          속성 설명·단위를 확인할 수 없습니다. 값은 제공된 그대로 표시합니다.
        </p>
      )}
      <dl className="explorer-properties">
        {propertyRows(properties.slice(0, 4))}
      </dl>
      {properties.length > 4 && (
        <details className="explorer-detail-more">
          <summary>전체 속성 보기</summary>
          <dl className="explorer-properties">
            {propertyRows(properties.slice(4))}
          </dl>
        </details>
      )}
      <div className="explorer-detail-buttons">
        <Button variant="secondary" onClick={() => setEvidenceOpen(true)}>
          원본·연결 근거
        </Button>
        <Link
          to={`/audit?objectId=${object.id}`}
          state={{
            historyReturnTo:
              historyOrigin.pathname +
              historyOrigin.search +
              historyOrigin.hash,
          }}
          className="ui-button ui-button--secondary"
        >
          실행 이력
        </Link>
      </div>
      <details className="explorer-detail-more">
        <summary>수집 정보</summary>
        <p className="explorer-source-caption">{source}</p>
        <dl className="explorer-detail-times">
          <dt>기준 시점</dt>
          <dd>{formatDateTime(object.dataAsOf)}</dd>
          <dt>최근 동기화</dt>
          <dd>{formatDateTime(object.lastSyncedAt)}</dd>
        </dl>
      </details>
      {object.sourceSystemName && (
        <div className="explorer-source-status">
          <StatusBadge
            tone={object.status === 'ACTIVE' ? 'success' : 'warning'}
          >
            {statusLabels[object.status]}
          </StatusBadge>
        </div>
      )}

      {evidenceOpen && (
        <EvidenceDialog
          object={object}
          onClose={() => setEvidenceOpen(false)}
        />
      )}
    </div>
  )
}

export default ObjectDetail
