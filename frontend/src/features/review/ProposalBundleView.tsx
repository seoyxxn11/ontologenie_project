import DataTable from '../../components/DataTable'
import { accessGroupText } from '../ontology/accessGroups'
import BundleAccessEditor from './BundleAccessEditor'
import {
  conditionText,
  isBundle,
  isRow,
  transformText as steps,
  type Row,
} from './bundle'
import type { Proposal } from './types'

function text(value: unknown) {
  return typeof value === 'string' && value.trim() ? value : '-'
}

function rows(value: unknown): Row[] {
  return Array.isArray(value) ? value.filter(isRow) : []
}

// 속성별 제안 이유. 보류 권고가 있으면 이유를 앞에 붙인다.
function reason(part: Row | undefined) {
  const why = text(part?.reason)
  return part?.holdRecommended === true
    ? `보류 권고: ${text(part.holdReason)} · ${why}`
    : why
}

function verified(part: Row | undefined) {
  const items = [
    ...rows(part?.items),
    ...rows(isRow(part?.mapping) ? part.mapping.items : undefined),
  ]
  if (!items.length) return '-'
  return items.every((item) => item.verified === true) ? '확인' : '미확인'
}

const dataTypeLabels: Record<string, string> = {
  STRING: '문자',
  INTEGER: '정수',
  DECIMAL: '소수',
  DATE: '날짜',
  TIME: '시각',
  DATETIME: '일시',
  BOOLEAN: '예/아니요',
  JSON: 'JSON',
}

const sensitivityLabels: Record<string, string> = {
  NORMAL: '일반',
  RESTRICTED: '제한',
}

const cardinalityLabels: Record<string, string> = {
  ONE_TO_ONE: '1:1',
  ONE_TO_MANY: '1:N',
  MANY_TO_ONE: 'N:1',
  MANY_TO_MANY: 'N:N',
}

// 검토 결정 전(대기·보류) 후보만 그룹·민감도를 고칠 수 있다
function editable(proposal: Proposal) {
  return ['PENDING', 'HELD'].includes(proposal.reviewStatus)
}

function ObjectBundle({ proposal }: { proposal: Proposal }) {
  const { payload, evidence } = proposal
  const mapping = isRow(payload.objectMapping) ? payload.objectMapping : null
  const properties = rows(payload.properties)
  const parts = new Map(
    rows(evidence.properties).map((part) => [String(part.code), part]),
  )
  const keys = Array.isArray(mapping?.keyColumns) ? mapping.keyColumns : []
  const mappingWhy = isRow(evidence.objectMapping)
    ? text(evidence.objectMapping.reason)
    : '-'
  return (
    <section className="rounded-xl border border-slate-200 p-5">
      <h3 className="font-semibold">객체와 속성</h3>
      <p className="mt-2 text-sm text-slate-600">
        객체 유형과 속성, 원천 매핑을 한 건으로 검토합니다.
      </p>
      <dl className="mt-4 grid gap-2 text-sm sm:grid-cols-[120px_1fr]">
        <dt className="font-medium text-slate-600">원천 표</dt>
        <dd>
          {mapping
            ? `${text(mapping.sourceId)} · ${text(mapping.resourceKey)}`
            : '원천 매핑 없음'}
        </dd>
        <dt className="font-medium text-slate-600">행 구분 키</dt>
        <dd>{keys.length ? keys.join(', ') : '-'}</dd>
        <dt className="font-medium text-slate-600">매핑 이유</dt>
        <dd>{mappingWhy}</dd>
        <dt className="font-medium text-slate-600">속성 수</dt>
        <dd>{properties.length}개</dd>
        <dt className="font-medium text-slate-600">조회 그룹</dt>
        <dd>
          {accessGroupText(
            typeof payload.accessGroup === 'string'
              ? payload.accessGroup
              : null,
          )}
          {!payload.accessGroup && editable(proposal) && (
            <p className="mt-1 text-amber-700">
              그룹이 없으면 승인 뒤 어느 역할에도 이 유형의 객체가 보이지
              않습니다. 아래에서 정하거나 승인 뒤 객체 유형 화면에서 지정하세요.
            </p>
          )}
        </dd>
      </dl>
      <div className="mt-4">
        <DataTable label={`${text(payload.code)} 속성 목록`}>
          <thead className="bg-slate-50 text-xs text-slate-500">
            <tr>
              {[
                '속성',
                '이름',
                '자료형',
                '식별자',
                '필수',
                '민감도',
                '원천 컬럼',
                '제안 이유',
                '근거',
              ].map((label) => (
                <th key={label} className="px-3 py-2 font-normal">
                  {label}
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {properties.map((property, index) => {
              const column = isRow(property.sourceColumn)
                ? property.sourceColumn
                : null
              const part = parts.get(String(property.code))
              return (
                <tr key={`${index}:${String(property.code)}`}>
                  <td className="px-3 py-2 font-mono text-xs">
                    {text(property.code)}
                  </td>
                  <td className="px-3 py-2">{text(property.name)}</td>
                  <td className="px-3 py-2">
                    {dataTypeLabels[String(property.dataType)] ??
                      text(property.dataType)}
                  </td>
                  <td className="px-3 py-2">
                    {property.isIdentifier === true ? '예' : '-'}
                  </td>
                  <td className="px-3 py-2">
                    {property.isRequired === true ? '예' : '-'}
                  </td>
                  <td className="px-3 py-2">
                    {sensitivityLabels[String(property.sensitivity)] ??
                      text(property.sensitivity)}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">
                    {column
                      ? `${text(column.resourceKey)}.${text(column.column)}${steps(property.transformSteps)}`
                      : '-'}
                  </td>
                  <td className="px-3 py-2 text-xs">{reason(part)}</td>
                  <td className="px-3 py-2">{verified(part)}</td>
                </tr>
              )
            })}
          </tbody>
        </DataTable>
      </div>
      {editable(proposal) && (
        // 개정본을 저장하면 다시 조회되어 개정 번호가 바뀐다. 저장 완료 문구가 남도록 후보마다 한 번만 만든다
        <BundleAccessEditor key={proposal.id} proposal={proposal} />
      )}
    </section>
  )
}

function LinkBundle({ proposal }: { proposal: Proposal }) {
  const { payload } = proposal
  const linkType = isRow(payload.linkType) ? payload.linkType : {}
  const conditions = rows(payload.conditions)
  return (
    <section className="rounded-xl border border-slate-200 p-5">
      <h3 className="font-semibold">관계</h3>
      <p className="mt-2 text-sm text-slate-600">
        관계 유형과 연결 규칙을 한 건으로 검토합니다.
      </p>
      <dl className="mt-4 grid gap-2 text-sm sm:grid-cols-[120px_1fr]">
        <dt className="font-medium text-slate-600">관계</dt>
        <dd>
          {/* AI가 붙인 이름('출하 실적 → 품질 검사 기록'), 없으면 코드 */}
          {typeof linkType.name === 'string' && linkType.name.trim()
            ? linkType.name
            : `${text(linkType.sourceTypeCode)} → ${text(linkType.targetTypeCode)}`}
        </dd>
        <dt className="font-medium text-slate-600">개수 관계</dt>
        <dd>
          {cardinalityLabels[String(linkType.cardinality)] ??
            text(linkType.cardinality)}
        </dd>
        <dt className="font-medium text-slate-600">설명</dt>
        <dd>{text(linkType.description)}</dd>
        <dt className="font-medium text-slate-600">비교 조건</dt>
        <dd>
          <ul className="space-y-1 font-mono text-xs">
            {conditions.map((condition, index) => (
              <li key={index}>{conditionText(condition)}</li>
            ))}
          </ul>
        </dd>
      </dl>
    </section>
  )
}

export default function ProposalBundleView({
  proposal,
}: {
  proposal: Proposal
}) {
  if (!isBundle(proposal)) return null
  return proposal.itemKind === 'OBJECT_TYPE' ? (
    <ObjectBundle proposal={proposal} />
  ) : (
    <LinkBundle proposal={proposal} />
  )
}
