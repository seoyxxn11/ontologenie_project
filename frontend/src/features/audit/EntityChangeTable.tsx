import DataTable from '../../components/DataTable'
import { formatPropertyValue, formatSourceKey } from '../explorer/format'
import { changeKindLabels } from './labels'
import type { EntityChange } from './types'

type EntityChangeTableProps = {
  change: EntityChange
  // 권한으로 가린 속성 키. 이 키의 null은 "—" 대신 "권한으로 가림"으로 보여 준다.
  restrictedCodes?: string[]
}

// 바뀐 행 하나의 변경 전후를 항목별로 나란히 보여 준다. (실행 이력·정의 변경 이력이 같이 쓴다)
function EntityChangeTable({
  change,
  restrictedCodes = [],
}: EntityChangeTableProps) {
  const fields = [
    ...new Set([
      ...Object.keys(change.beforeData ?? {}),
      ...Object.keys(change.afterData ?? {}),
    ]),
  ]

  function formatValue(field: string, data?: Record<string, unknown> | null) {
    const value = data?.[field]
    if (data && value === null && restrictedCodes.includes(field)) {
      return '권한으로 가림'
    }
    return formatPropertyValue(value)
  }

  return (
    <div className="management-detail mt-2">
      <p className="text-sm text-slate-500">
        {`${change.tableName} · ${formatSourceKey(change.rowKey)} · ${changeKindLabels[change.changeKind]}`}
      </p>
      <DataTable label="변경 전후 비교" className="min-w-[480px]">
        <thead className="text-slate-500">
          <tr>
            <th className="py-1 pr-2 font-normal">항목</th>
            <th className="py-1 pr-2 font-normal">변경 전</th>
            <th className="py-1 font-normal">변경 후</th>
          </tr>
        </thead>
        <tbody>
          {fields.map((field) => (
            <tr key={field}>
              <td className="py-1 pr-2">{field}</td>
              <td className="py-1 pr-2">
                {formatValue(field, change.beforeData)}
              </td>
              <td className="py-1">{formatValue(field, change.afterData)}</td>
            </tr>
          ))}
        </tbody>
      </DataTable>
    </div>
  )
}

export default EntityChangeTable
