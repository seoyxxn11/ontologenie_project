const labels: Record<string, string> = {
  planId: '재배정 계획 번호',
  itemCount: '계획 항목 수',
  proposalCount: '생성 제안 수',
  targetCount: '분석 대상 수',
  rowCount: '표본 행 수',
  objects: '객체 수',
  rowErrors: '행 오류 수',
  collected: '수집 건수',
  updated: '수정 여부',
  workOrderNo: '작업지시 번호',
  toEquipment: '도착 설비',
  equipmentCode: '설비 코드',
  fromEquipmentId: '출발 설비 번호',
  toEquipmentId: '도착 설비 번호',
  workOrderId: '작업지시 객체 번호',
  sourceName: '원천 이름',
  name: '이름',
  decision: '검토 결정',
  reason: '사유',
  status: '처리 상태',
  joined: '기존 작업 합류',
  revision: '개정 번호',
  linkMappingId: '연결 매핑 번호',
  checkRunId: '검사 실행 번호',
  dataSourceId: '원천 번호',
  accountId: '계정 번호',
  actionDefinitionId: '실행 정의 번호',
  proposalItemId: '제안 항목 번호',
  summary: '결과 요약',
  errorCode: '오류 코드',
  connectionStatus: '연결 상태',
  message: '안내',
}
const states: Record<string, string> = {
  QUEUED: '대기',
  RUNNING: '진행 중',
  SUCCEEDED: '성공',
  COMPLETED: '완료',
  FAILED: '실패',
  BLOCKED: '차단',
  PASSED: '검사 통과',
  APPROVE: '승인',
  REJECT: '반려',
  APPROVED: '승인됨',
  REJECTED: '반려됨',
  CREATED: '생성됨',
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
              Object.hasOwn(states, value)
            ? states[value]
            : value}
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
  if (!entries.length) return <p>기록된 항목 없음</p>
  return (
    <dl className="space-y-2 text-sm">
      {entries.slice(0, 20).map(([code, value]) => (
        <div key={code} className="[overflow-wrap:anywhere]">
          <dt className="font-medium text-slate-600">
            {Object.hasOwn(labels, code) ? labels[code] : code}
          </dt>
          <dd className="mt-1 pl-3 text-slate-900">
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
