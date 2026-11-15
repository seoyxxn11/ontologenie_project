import DataTable from '../../components/DataTable'
import { isRow, transformText } from './bundle'
import { transformLabels } from './ruleDraft'
import type { Proposal } from './types'

function readableSteps(value: unknown) {
  const steps = Array.isArray(value) ? value.filter(isRow) : []
  return steps.length
    ? steps
        .map((step) => {
          const name =
            transformLabels[String(step.op)] ??
            String(step.op ?? '알 수 없는 처리')
          return step.arg == null
            ? name
            : `${name} (${typeof step.arg === 'string' ? step.arg : JSON.stringify(step.arg)})`
        })
        .join(' → ')
    : '변환 없이 그대로 사용'
}

export default function RuleContent({ proposal }: { proposal: Proposal }) {
  const { payload } = proposal
  const properties = Array.isArray(payload.properties)
    ? payload.properties.filter(isRow)
    : []
  const conditions = Array.isArray(payload.conditions)
    ? payload.conditions.filter(isRow)
    : []
  return (
    <section className="rule-content">
      <h3>연결·변환 규칙</h3>
      {properties.length > 0 ? (
        <DataTable label="연결·변환 규칙">
          <thead>
            <tr>
              {['원천 컬럼', '값 처리', '저장할 속성'].map((label) => (
                <th key={label} scope="col">
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {properties.map((property, index) => {
              const column = isRow(property.sourceColumn)
                ? property.sourceColumn
                : {}
              return (
                <tr key={index}>
                  <td>{String(column.column ?? '—')}</td>
                  <td>{readableSteps(property.transformSteps)}</td>
                  <td>{String(property.name ?? property.code ?? '—')}</td>
                </tr>
              )
            })}
          </tbody>
        </DataTable>
      ) : conditions.length > 0 ? (
        <ol className="rule-conditions" aria-label="연결 조건">
          {conditions.map((condition, index) => (
            <li key={index} className="rule-condition">
              <p className="rule-condition-summary">
                {conditions.length > 1 && <span>조건 {index + 1} · </span>}
                {condition.operator === 'EQ'
                  ? '아래 처리를 거친 두 값이 같으면 연결합니다.'
                  : `비교 연산: ${String(condition.operator ?? '제공되지 않음')}`}
              </p>
              <div className="rule-condition-values">
                <dl>
                  <dt>출발 속성</dt>
                  <dd className="rule-property-code">
                    {String(condition.sourceProperty ?? '제공되지 않음')}
                  </dd>
                  <dt className="sr-only">출발 값 처리</dt>
                  <dd>{readableSteps(condition.sourceTransforms)}</dd>
                </dl>
                <span className="rule-condition-operator">
                  {condition.operator === 'EQ'
                    ? '같음 (=)'
                    : String(condition.operator ?? '—')}
                </span>
                <dl>
                  <dt>대상 속성</dt>
                  <dd className="rule-property-code">
                    {String(condition.targetProperty ?? '제공되지 않음')}
                  </dd>
                  <dt className="sr-only">대상 값 처리</dt>
                  <dd>{readableSteps(condition.targetTransforms)}</dd>
                </dl>
              </div>
              <details className="rule-condition-expression">
                <summary>원본 조건식</summary>
                <code>
                  {String(condition.sourceProperty ?? '—')}
                  {transformText(condition.sourceTransforms)}{' '}
                  {condition.operator === 'EQ'
                    ? '='
                    : String(condition.operator ?? '?')}{' '}
                  {String(condition.targetProperty ?? '—')}
                  {transformText(condition.targetTransforms)}
                </code>
              </details>
            </li>
          ))}
        </ol>
      ) : (
        <p>
          연결·변환 조건이 제공되지 않았습니다. 후보 상세 정의에서 제안 내용을
          확인하세요.
        </p>
      )}
    </section>
  )
}
