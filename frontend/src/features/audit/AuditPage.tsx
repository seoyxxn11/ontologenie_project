import './audit.css'
import { useQuery } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import HistoryBack from '../../components/HistoryBack'
import type { ReactNode } from 'react'
import PageHeader from '../../components/PageHeader'
import { fetchObject } from '../explorer/api'
import ExecutionHistory from './ExecutionHistory'

type ObjectHistoryProps = {
  objectId: number
  navigation: ReactNode
}

function ObjectHistory({ objectId, navigation }: ObjectHistoryProps) {
  const { data: object } = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })

  return (
    <ExecutionHistory
      fullPage
      navigation={navigation}
      objectId={objectId}
      targetLabel={object?.displayName ?? `객체 #${objectId}`}
    />
  )
}

function AuditPage() {
  const [searchParams] = useSearchParams()
  const objectParam = searchParams.get('objectId')
  const objectId = Number(objectParam)
  const hasObject =
    objectParam !== null &&
    /^\d+$/.test(objectParam) &&
    Number.isSafeInteger(objectId) &&
    objectId > 0

  const navigation = (
    <HistoryBack className="audit-back" dividerClassName="audit-nav-divider" />
  )
  return (
    <div className="management-page audit-page">
      <PageHeader
        title="실행 이력"
        description="실행 결과와 처리 내용, 데이터 변경 전후를 확인합니다."
      />
      <section className="audit-page-content">
        <div className="audit-page-inner">
          {objectParam !== null && !hasObject ? (
            <>
              <div className="audit-navigation">{navigation}</div>
              <p role="alert" className="text-sm text-red-600">
                올바른 객체 번호로 실행 이력을 조회해 주세요.
              </p>
            </>
          ) : hasObject ? (
            <ObjectHistory
              key={objectId}
              objectId={objectId}
              navigation={navigation}
            />
          ) : (
            <ExecutionHistory fullPage navigation={navigation} />
          )}
        </div>
      </section>
    </div>
  )
}

export default AuditPage
