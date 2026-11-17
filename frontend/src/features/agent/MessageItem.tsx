import { formatKstDateTime } from '../../utils/dateTime'
import Markdown, { type Components, type Options } from 'react-markdown'
import { Link, useLocation } from 'react-router'
import remarkGfm from 'remark-gfm'
import DataTable from '../../components/DataTable'
import { LoadingState } from '../../components/Feedback'
import { formatObjectCode } from '../explorer/format'
import ActionResultList from './ActionResultList'
import {
  ANSWER_SNAPSHOT_NOTICE,
  DATA_AS_OF_UNKNOWN,
  RESULT_FAILED_LABEL,
  RESULT_RETRIED_LABEL,
  unconfirmedNotice,
  resultCategoryLabels,
  stageLabels,
  syncStatusNotices,
} from './labels'
import type { AgentMessage, AgentResult } from './types'
import remarkKoreanStrong from './remarkKoreanStrong'

// 실행 이력 도구의 결과면 그 객체의 실행 이력으로도 이동한다(080).
const EXECUTION_TOOL_CODE = 'OBJECT_EXECUTIONS'

// 표·취소선(~~)은 GFM으로 그린다. 물결표 하나는 취소선으로 보지 않는다. "3~5건", "11/03~11/09"처럼 범위를 쓴 답변이
// 취소선으로 그어지지 않게 하기 위해서다.
const answerPlugins: Options['remarkPlugins'] = [
  [remarkGfm, { singleTilde: false }],
  remarkKoreanStrong,
]

// AI 답변은 마크다운으로 온다. Tailwind가 목록 기호 같은 기본 모양을 지우므로 태그마다 모양을 정한다.
// 문단 안의 한 줄 바꿈도 줄을 나누도록 문단에만 pre-line을 준다. 목록에 주면 항목 사이 줄바꿈 글자가 빈 줄이 된다.
// 원본 HTML은 react-markdown 기본대로 요소로 만들지 않고 글자로 보인다.
const answerMarkdown: Components = {
  p: ({ children }) => <p className="whitespace-pre-line">{children}</p>,
  ul: ({ children }) => <ul>{children}</ul>,
  ol: ({ children }) => <ol>{children}</ol>,
  h1: ({ children }) => <h3>{children}</h3>,
  h2: ({ children }) => <h4>{children}</h4>,
  h3: ({ children }) => <h5>{children}</h5>,
  h4: ({ children }) => <h6>{children}</h6>,
  h5: ({ children }) => <h6>{children}</h6>,
  h6: ({ children }) => <h6>{children}</h6>,
  strong: ({ children }) => (
    <strong className="font-semibold">{children}</strong>
  ),
  // 앱 안 주소("/"로 시작)만 링크로 만든다. 외부 주소는 글자만 보인다. 원천 데이터에 섞인 지시를 AI가 따라
  // 조회 값을 실은 외부 주소를 쓰면, 누르는 것만으로 그 값이 밖으로 나가기 때문이다. 위험한 주소가 지워져 비어도 글자로 둔다.
  a: ({ href, title, children }) =>
    href && href.startsWith('/') && !href.startsWith('//') ? (
      <Link to={href} title={title} className="agent-inline-link">
        {children}
      </Link>
    ) : (
      <span>{children}</span>
    ),
  // 이미지는 그리지 않고 대체 글자만 보인다. 그리면 답변이 화면에 뜨는 것만으로 브라우저가 이미지 주소로 요청을 보낸다.
  // 원천 데이터에 섞인 지시를 AI가 따라 조회 값을 실은 주소를 쓰면 그 값이 밖으로 나간다.
  img: ({ alt }) => <span>{alt}</span>,
  // 긴 코드 블록이 답변 상자 밖으로 넘치지 않게 가로 스크롤을 준다.
  pre: ({ children }) => (
    <pre tabIndex={0} role="region" aria-label="답변 코드">
      {children}
    </pre>
  ),
  table: ({ children }) => <DataTable label="답변 표">{children}</DataTable>,
  // 표의 열 정렬(:---:)은 style로 넘어온다.
  th: ({ style, children }) => <th style={style}>{children}</th>,
  td: ({ style, children }) => <td style={style}>{children}</td>,
}

function formatAsOf(value: string) {
  return formatKstDateTime(value)
}

// 정보 하나의 결과 이름. 조회를 못 끝낸 항목은 "데이터 없음"과 구분한다.
function resultLabel(result: AgentResult) {
  if (result.status === 'FAILED' && result.retried) {
    return RESULT_RETRIED_LABEL
  }
  if (result.status === 'FAILED' || !result.resultCategory) {
    return RESULT_FAILED_LABEL
  }
  return resultCategoryLabels[result.resultCategory]
}

// 결과의 출처 중 객체만 골라, 같은 객체는 한 번만 보여 준다.
function sourceObjectIds(result: AgentResult) {
  return [
    ...new Set(
      result.sources.flatMap((source) =>
        source.objectId === undefined ? [] : [source.objectId],
      ),
    ),
  ]
}

function MessageItem({ message }: { message: AgentMessage }) {
  const historyOrigin = useLocation()
  if (message.sender === 'USER') {
    return (
      <div className="agent-user-message">
        <p>
          <span className="sr-only">나: </span>
          {message.content}
        </p>
      </div>
    )
  }

  if (message.status === 'RUNNING') {
    const stage = message.answerMeta.stage
    return (
      <div className="agent-message-progress">
        <LoadingState
          message={`답변 준비 중${stage ? ` · ${stageLabels[stage]}` : ''}…`}
        />
      </div>
    )
  }

  const results = message.answerMeta.results ?? []
  const actions = message.answerMeta.actions ?? []
  const syncStatus = message.answerMeta.syncStatus
  const syncNotice = syncStatus ? syncStatusNotices[syncStatus] : undefined
  // 펼치지 않아도 보이게 본문 바로 아래에 알린다. 모델이 답에 밝히는지에 맡기지 않는다(449).
  const unconfirmed = message.answerMeta.unconfirmedLookups ?? 0
  const evidenceWarnings = [
    {
      label: '권한 제한',
      count: results.filter((result) => result.resultCategory === 'DENIED')
        .length,
    },
    {
      label: '미확인',
      // 다시 조회해 확인한 실패는 미확인으로 세지 않는다(449).
      count: results.filter(
        (result) =>
          (result.status === 'FAILED' && !result.retried) ||
          (result.status !== 'FAILED' && !result.resultCategory),
      ).length,
    },
    {
      label: '미연결',
      count: results.filter((result) => result.resultCategory === 'UNLINKED')
        .length,
    },
    {
      label: '추정',
      count: results.filter((result) => result.estimated).length,
    },
  ]
    .filter(({ count }) => count > 0)
    .map(({ label, count }) => `${label} ${count}건`)
    .join(' · ')
  return (
    <article aria-label="Agent 답변" className="agent-answer">
      {message.status === 'FAILED' && (
        <p role="alert" className="agent-failure">
          답변을 끝내지 못했어요. {message.errorMessage}
        </p>
      )}
      <div className="agent-answer-body">
        <Markdown remarkPlugins={answerPlugins} components={answerMarkdown}>
          {message.content}
        </Markdown>
      </div>
      {syncNotice && <p className="agent-sync-notice">{syncNotice}</p>}
      {unconfirmed > 0 && (
        <p role="note" className="agent-unconfirmed-notice">
          {unconfirmedNotice(unconfirmed)}
        </p>
      )}
      {actions.length > 0 && <ActionResultList actions={actions} />}
      {results.length > 0 && (
        <details className="agent-evidence group">
          <summary>
            <svg
              aria-hidden="true"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.5"
              className="h-3.5 w-3.5 shrink-0 transition-transform group-open:rotate-90 motion-reduce:transition-none"
            >
              <path d="m9 5 7 7-7 7" />
            </svg>
            <span>출처 및 조회 상태</span>{' '}
            <span className="agent-evidence-count">{results.length}건</span>{' '}
            {evidenceWarnings && (
              <span className="agent-evidence-warning">{evidenceWarnings}</span>
            )}
          </summary>
          <div className="agent-evidence-table">
            <DataTable label="정보별 결과">
              <thead className="bg-slate-50 text-slate-600">
                <tr>
                  <th className="px-3 py-2">정보</th>
                  <th className="px-3 py-2">결과</th>
                  <th className="px-3 py-2">기준 시점</th>
                  <th className="px-3 py-2">바로 가기</th>
                </tr>
              </thead>
              <tbody>
                {results.map((result, index) => (
                  <tr
                    key={`${result.label}-${index}`}
                    className="border-t border-slate-100"
                  >
                    <td className="px-3 py-2 font-medium">{result.label}</td>
                    <td className="px-3 py-2">
                      <span
                        className="agent-result-status"
                        data-caution={
                          (result.status === 'FAILED' && !result.retried) ||
                          result.resultCategory !== 'FOUND' ||
                          result.estimated ||
                          undefined
                        }
                      >
                        {resultLabel(result)}
                        {result.estimated ? ' · 추정' : ''}
                      </span>
                    </td>
                    <td className="px-3 py-2 agent-evidence-asof">
                      {result.dataAsOf
                        ? formatAsOf(result.dataAsOf)
                        : DATA_AS_OF_UNKNOWN}
                    </td>
                    <td className="px-3 py-2">
                      {sourceObjectIds(result).map((objectId) => (
                        <span key={objectId} className="agent-source-links">
                          <Link
                            to={`/explorer?object=${objectId}`}
                            className="agent-inline-link"
                          >
                            {formatObjectCode(objectId)} 열기 ↗
                          </Link>
                          {result.toolCode === EXECUTION_TOOL_CODE && (
                            <Link
                              to={`/audit?objectId=${objectId}`}
                              state={{
                                historyReturnTo:
                                  historyOrigin.pathname +
                                  historyOrigin.search +
                                  historyOrigin.hash,
                              }}
                              className="agent-inline-link"
                            >
                              실행 이력 ↗
                            </Link>
                          )}
                        </span>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </DataTable>
          </div>
          <p className="agent-evidence-notice">{ANSWER_SNAPSHOT_NOTICE}</p>
        </details>
      )}
    </article>
  )
}

export default MessageItem
