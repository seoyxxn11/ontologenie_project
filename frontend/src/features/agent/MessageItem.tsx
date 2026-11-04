import {
  DATA_AS_OF_UNKNOWN,
  RESULT_FAILED_LABEL,
  resultCategoryLabels,
  stageLabels,
  syncStatusNotices,
} from './labels'
import type { AgentMessage, AgentResult } from './types'

function formatAsOf(value: string) {
  return new Date(value).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// 정보 하나의 결과 이름. 조회를 못 끝낸 항목은 "데이터 없음"과 구분한다.
function resultLabel(result: AgentResult) {
  if (result.status === 'FAILED' || !result.resultCategory) {
    return RESULT_FAILED_LABEL
  }
  return resultCategoryLabels[result.resultCategory]
}

function MessageItem({ message }: { message: AgentMessage }) {
  if (message.sender === 'USER') {
    return (
      <div className="flex justify-end">
        <p className="max-w-[80%] rounded-lg bg-slate-100 px-3 py-2 text-sm whitespace-pre-wrap text-slate-900">
          <span className="sr-only">나: </span>
          {message.content}
        </p>
      </div>
    )
  }

  if (message.status === 'RUNNING') {
    const stage = message.answerMeta.stage
    return (
      <p role="status" className="text-sm text-slate-600">
        답변 준비 중{stage ? ` · ${stageLabels[stage]}` : ''}…
      </p>
    )
  }

  const results = message.answerMeta.results ?? []
  const syncStatus = message.answerMeta.syncStatus
  const syncNotice = syncStatus ? syncStatusNotices[syncStatus] : undefined
  return (
    <article aria-label="Agent 답변" className="text-sm text-slate-900">
      {message.status === 'FAILED' && (
        <p role="alert" className="mb-1 font-medium text-red-700">
          답변을 끝내지 못했어요. {message.errorMessage}
        </p>
      )}
      <p className="whitespace-pre-wrap">{message.content}</p>
      {syncNotice && (
        <p className="mt-1 text-xs text-amber-700">{syncNotice}</p>
      )}
      {results.length > 0 && (
        <ul className="mt-2 space-y-1 rounded-lg border border-slate-200 p-2 text-xs">
          {results.map((result, index) => (
            <li
              key={`${result.label}-${index}`}
              className="flex flex-wrap gap-x-2"
            >
              <span className="font-medium">{result.label}</span>
              <span>
                {resultLabel(result)}
                {result.estimated ? ' · 추정' : ''}
              </span>
              <span className="text-slate-500">
                {result.dataAsOf
                  ? `기준 시점 ${formatAsOf(result.dataAsOf)}`
                  : DATA_AS_OF_UNKNOWN}
              </span>
            </li>
          ))}
        </ul>
      )}
    </article>
  )
}

export default MessageItem
