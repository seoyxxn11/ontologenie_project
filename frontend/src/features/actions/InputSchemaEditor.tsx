import { useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import {
  emptyRow,
  fieldTypeLabels,
  itemTypeLabels,
  parseSchema,
  rangeLabel,
  rowsToSchema,
  tableRows,
  type FieldRow,
  type FieldType,
  type ItemType,
  type SchemaDraft,
} from './schemaFields'

const inputClass = 'w-full rounded-lg border p-2'

function FieldRowEditor({
  row,
  index,
  onChange,
  onRemove,
}: {
  row: FieldRow
  index: number
  onChange: (row: FieldRow) => void
  onRemove: () => void
}) {
  const number = index + 1
  const range = rangeLabel(row.type)
  return (
    <tr className="align-top">
      <td className="px-2 py-2">
        <input
          className={`${inputClass} font-mono`}
          aria-label={`${number}번째 항목 이름`}
          value={row.name}
          onChange={(event) => onChange({ ...row, name: event.target.value })}
          autoComplete="off"
        />
      </td>
      <td className="px-2 py-2">
        <select
          className={inputClass}
          aria-label={`${number}번째 항목 자료형`}
          value={row.type}
          onChange={(event) =>
            onChange({
              ...row,
              type: event.target.value as FieldType,
              min: '',
              max: '',
            })
          }
        >
          {Object.entries(fieldTypeLabels).map(([value, label]) => (
            <option key={value} value={value}>
              {label}
            </option>
          ))}
        </select>
        {row.type === 'array' && (
          <select
            className={`${inputClass} mt-2`}
            aria-label={`${number}번째 항목 목록 안 자료형`}
            value={row.itemType}
            onChange={(event) =>
              onChange({ ...row, itemType: event.target.value as ItemType })
            }
          >
            {Object.entries(itemTypeLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label} 목록
              </option>
            ))}
          </select>
        )}
      </td>
      <td className="px-2 py-2 text-center">
        <input
          type="checkbox"
          aria-label={`${number}번째 항목 필수`}
          checked={row.required}
          onChange={(event) =>
            onChange({ ...row, required: event.target.checked })
          }
        />
      </td>
      <td className="px-2 py-2">
        {range ? (
          <div className="flex gap-2">
            <input
              className={inputClass}
              inputMode="decimal"
              aria-label={`${number}번째 항목 최소 ${range}`}
              placeholder={`최소 ${range}`}
              value={row.min}
              onChange={(event) =>
                onChange({ ...row, min: event.target.value })
              }
            />
            <input
              className={inputClass}
              inputMode="decimal"
              aria-label={`${number}번째 항목 최대 ${range}`}
              placeholder={`최대 ${range}`}
              value={row.max}
              onChange={(event) =>
                onChange({ ...row, max: event.target.value })
              }
            />
          </div>
        ) : (
          <span className="text-slate-400">-</span>
        )}
      </td>
      <td className="px-2 py-2">
        <input
          className={inputClass}
          aria-label={`${number}번째 항목 설명`}
          value={row.description}
          onChange={(event) =>
            onChange({ ...row, description: event.target.value })
          }
        />
      </td>
      <td className="px-2 py-2">
        <Button
          variant="secondary"
          className="whitespace-nowrap"
          aria-label={`${number}번째 항목 삭제`}
          onClick={onRemove}
        >
          삭제
        </Button>
      </td>
    </tr>
  )
}

export default function InputSchemaEditor({
  value,
  onChange,
}: {
  value: SchemaDraft
  onChange: (value: SchemaDraft) => void
}) {
  const [switchError, setSwitchError] = useState('')

  // 입력을 고치면 앞서 실패한 전환 안내는 더 맞지 않으므로 지운다.
  function edit(next: SchemaDraft) {
    setSwitchError('')
    onChange(next)
  }

  function toJson() {
    if (value.mode !== 'table') return
    const result = rowsToSchema(value.rows)
    if (result.error !== undefined) {
      setSwitchError(result.error)
      return
    }
    setSwitchError('')
    onChange({ mode: 'json', text: JSON.stringify(result.schema, null, 2) })
  }

  function toTable() {
    if (value.mode !== 'json') return
    const schema = parseSchema(value.text)
    const result = schema
      ? tableRows(schema)
      : { reason: 'JSON 객체 형식이 아니라 표로 바꿀 수 없습니다.' }
    if (result.rows === undefined) {
      setSwitchError(result.reason)
      return
    }
    setSwitchError('')
    onChange({ mode: 'table', rows: result.rows })
  }

  return (
    <section className="space-y-3 text-sm" aria-label="입력 형식">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="font-medium">입력 형식</h2>
        {value.mode === 'table' ? (
          <Button variant="secondary" onClick={toJson}>
            JSON으로 편집
          </Button>
        ) : (
          <Button variant="secondary" onClick={toTable}>
            표로 편집
          </Button>
        )}
      </div>
      <details className="actions-disclosure">
        <summary>입력 형식 안내</summary>
        <p className="text-slate-500">
          이 기능을 실행할 때 받을 값입니다. Agent가 설명을 보고 값을 채웁니다.
          모르면 비워 두세요. 개발자가 구현할 때 정할 수 있습니다.
        </p>
      </details>
      {value.mode === 'table' ? (
        <>
          {value.rows.length ? (
            <DataTable label="입력 항목" className="min-w-[760px]">
              <thead className="whitespace-nowrap bg-slate-50 text-left text-sm text-slate-500">
                <tr>
                  <th className="px-2 py-2">이름</th>
                  <th className="px-2 py-2">자료형</th>
                  <th className="px-2 py-2 text-center">필수</th>
                  <th className="px-2 py-2">범위</th>
                  <th className="px-2 py-2">설명</th>
                  <th className="px-2 py-2">
                    <span className="sr-only">삭제</span>
                  </th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100">
                {value.rows.map((row, index) => (
                  <FieldRowEditor
                    key={row.key}
                    row={row}
                    index={index}
                    onChange={(next) =>
                      edit({
                        mode: 'table',
                        rows: value.rows.map((item) =>
                          item.key === row.key ? next : item,
                        ),
                      })
                    }
                    onRemove={() =>
                      edit({
                        mode: 'table',
                        rows: value.rows.filter((item) => item.key !== row.key),
                      })
                    }
                  />
                ))}
              </tbody>
            </DataTable>
          ) : (
            <p className="text-slate-500">입력 항목이 없습니다.</p>
          )}
          <Button
            variant="secondary"
            onClick={() =>
              edit({ mode: 'table', rows: [...value.rows, emptyRow()] })
            }
          >
            입력 항목 추가
          </Button>
        </>
      ) : (
        <label className="flex flex-col gap-2">
          JSON Schema
          <textarea
            className="min-h-40 rounded-lg border p-2 font-mono text-sm"
            value={value.text}
            onChange={(event) =>
              edit({ mode: 'json', text: event.target.value })
            }
            spellCheck={false}
            aria-invalid={!parseSchema(value.text) || undefined}
          />
          {!parseSchema(value.text) && (
            <span className="text-red-700">JSON 객체 형식이 아닙니다.</span>
          )}
        </label>
      )}
      {switchError && (
        <p role="alert" className="text-red-700">
          {switchError}
        </p>
      )}
    </section>
  )
}
