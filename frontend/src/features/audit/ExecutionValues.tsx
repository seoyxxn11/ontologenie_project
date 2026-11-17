import './executionDetail.css'
import { formatKstDateTime } from '../../utils/dateTime'
import { roleLabels } from '../auth/access'
import { valueLabels, valueStates } from './valueLabels'

// 시간대가 붙은 ISO 시각만 시각으로 본다. 2026 같은 숫자나 시간대 없는 값은 바꾸지 않는다.
const isoDateTime =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d+)?)?(Z|[+-]\d{2}:\d{2})$/

// 서버 형식 그대로 오는 값 중 화면 표현이 정해진 것만 바꾼다. 모르는 값은 그대로 둔다.
function displayText(code: string, value: string) {
  if (code === 'roleCode' && Object.hasOwn(roleLabels, value))
    return roleLabels[value as keyof typeof roleLabels]
  if (
    code === 'dataAsOf' &&
    isoDateTime.test(value) &&
    !Number.isNaN(Date.parse(value))
  )
    return formatKstDateTime(value)
  return value
}

function Value({
  value,
  code,
  restricted,
  depth,
}: {
  value: unknown
  code: string
  restricted: string[]
  depth: number
}) {
  if (value == null)
    return <>{restricted.includes(code) ? '권한으로 가림' : '값 없음'}</>
  if (typeof value === 'boolean') return <>{value ? '예' : '아니요'}</>
  if (typeof value === 'string') {
    return (
      <>
        {!value.trim()
          ? '빈 문자열'
          : ['status', 'decision', 'connectionStatus'].includes(code) &&
              Object.hasOwn(valueStates, value)
            ? valueStates[value]
            : displayText(code, value)}
      </>
    )
  }
  if (Array.isArray(value)) {
    if (!value.length) return <>항목 없음</>
    if (depth >= 3) return <>{value.length}개 항목</>
    return (
      <ol className="list-decimal pl-5">
        {value.slice(0, 10).map((item, index) => (
          <li key={index}>
            <Value
              value={item}
              code={code}
              restricted={restricted}
              depth={depth + 1}
            />
          </li>
        ))}
        {value.length > 10 && (
          <li>외 {value.length - 10}개 항목 · 기술 정보에서 전체 확인</li>
        )}
      </ol>
    )
  }
  if (typeof value === 'object') {
    const entries = Object.entries(value)
    if (!entries.length) return <>내용 없음</>
    if (depth >= 3) return <>{entries.length}개 항목 · 기술 정보에서 확인</>
    return (
      <ExecutionValues
        values={value as Record<string, unknown>}
        restricted={restricted}
        depth={depth + 1}
      />
    )
  }
  return <>{String(value)}</>
}

export default function ExecutionValues({
  values,
  restricted = [],
  depth = 0,
}: {
  values: Record<string, unknown> | null
  restricted?: string[]
  depth?: number
}) {
  const entries = Object.entries(values ?? {})
  if (!entries.length)
    return <p className="audit-values-empty">기록된 항목 없음</p>
  return (
    <dl className="audit-values">
      {entries.slice(0, 20).map(([code, value]) => (
        <div key={code}>
          <dt>{Object.hasOwn(valueLabels, code) ? valueLabels[code] : code}</dt>
          <dd>
            <Value
              value={value}
              code={code}
              restricted={restricted}
              depth={depth}
            />
          </dd>
        </div>
      ))}
      {entries.length > 20 && (
        <div>
          <dt>추가 항목</dt>
          <dd>{entries.length - 20}개 · 기술 정보에서 전체 확인</dd>
        </div>
      )}
    </dl>
  )
}
