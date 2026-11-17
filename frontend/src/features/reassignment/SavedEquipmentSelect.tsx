import { useQuery } from '@tanstack/react-query'
import Button from '../../components/Button'
import { fetchObject } from '../explorer/api'
import { formatObjectKey } from '../explorer/format'
import { positiveId } from '../explorer/searchState'
import { fetchSavedPlanEquipmentIds } from './api'

function EquipmentOption({ id }: { id: number }) {
  const object = useQuery({
    queryKey: ['objects', id],
    queryFn: () => fetchObject(id),
    staleTime: 60_000,
    retry: false,
  })
  const code = object.data && formatObjectKey(object.data)
  return (
    <option value={id}>
      {object.data
        ? [code, object.data.displayName].filter(Boolean).join(' · ')
        : `#${id}`}
    </option>
  )
}

export default function SavedEquipmentSelect({
  value,
  onChange,
}: {
  value?: number
  onChange: (id: number | undefined) => void
}) {
  const equipment = useQuery({
    queryKey: ['reassignment-plans', 'saved-equipment'],
    queryFn: ({ signal }) => fetchSavedPlanEquipmentIds(signal),
    staleTime: 60_000,
    retry: false,
  })
  return (
    <div className="plan-equipment-field">
      <label>
        기존 설비
        <select
          value={value ?? ''}
          disabled={equipment.isPending || equipment.isError}
          aria-describedby={
            equipment.isError ? 'plan-equipment-error' : undefined
          }
          onChange={(event) =>
            onChange(positiveId(event.target.value) ?? undefined)
          }
        >
          <option value="">
            {equipment.isPending ? '불러오는 중…' : '전체'}
          </option>
          {value && !equipment.data?.includes(value) && (
            <option value={value} disabled>
              #{value} · 저장된 계획에서 확인되지 않는 설비
            </option>
          )}
          {equipment.data?.map((id) => (
            <EquipmentOption key={id} id={id} />
          ))}
        </select>
      </label>
      {equipment.isError && (
        <div
          className="plan-equipment-error"
          id="plan-equipment-error"
          role="status"
        >
          <span>설비 목록을 불러오지 못했습니다.</span>
          <Button
            variant="text"
            pending={equipment.isFetching}
            onClick={() => void equipment.refetch()}
          >
            설비 목록 다시 불러오기
          </Button>
        </div>
      )}
    </div>
  )
}
