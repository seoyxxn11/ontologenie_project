import { formatPropertyValue, formatSourceKey } from '../explorer/format'
import { changeKindLabels } from './labels'
import type { EntityChange } from './types'

type EntityChangeTableProps = {
  change: EntityChange
}

// 바뀐 행 하나의 변경 전후를 항목별로 나란히 보여 준다. (실행 이력·정의 변경 이력이 같이 쓴다)
function EntityChangeTable({ change }: EntityChangeTableProps) {
  const fields = [
    ...new Set([
      ...Object.keys(change.beforeData ?? {}),
      ...Object.keys(change.afterData ?? {}),
    ]),
  ]

  return (
    <div className="mt-2">
      <p className="text-xs text-slate-500">
        {`${change.tableName} · ${formatSourceKey(change.rowKey)} · ${changeKindLabels[change.changeKind]}`}
      </p>
      <table className="mt-1 w-full text-left text-xs">
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
                {formatPropertyValue(change.beforeData?.[field])}
              </td>
              <td className="py-1">
                {formatPropertyValue(change.afterData?.[field])}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

export default EntityChangeTable
