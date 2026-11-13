import { useQuery } from '@tanstack/react-query'
import { fetchRules } from '../history/api'

// 목록 API의 현재 규칙 설명·관계 이름만 사용한다. 내부 코드를 임의 번역하지 않는다.
export default function IssueRuleName({
  code,
  mappingId,
}: {
  code?: string
  mappingId?: number | null
}) {
  const query = useQuery({
    queryKey: ['ontology', 'rules'],
    queryFn: fetchRules,
    enabled: !!code,
    retry: false,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  if (!code)
    return (
      <span>
        {mappingId != null ? `연결 매핑 #${mappingId}` : '관련 규칙 미제공'}
      </span>
    )
  const rule = Array.isArray(query.data)
    ? query.data.find((item) => item?.code === code)
    : undefined
  const name =
    (typeof rule?.description === 'string' && rule.description.trim()) ||
    (typeof rule?.linkTypeName === 'string' && rule.linkTypeName.trim())
  const label = query.isError
    ? '규칙 이름 확인 불가'
    : query.isPending
      ? '규칙 이름 조회 중…'
      : name || '규칙 이름 미제공'
  return (
    <span>
      {label}
      {mappingId != null && ` (연결 매핑 #${mappingId})`}
    </span>
  )
}
