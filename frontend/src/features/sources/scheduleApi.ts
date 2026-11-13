import { apiGet, apiPut } from '../../api/client'
import { ApiError, isRecord } from '../../api/errors'

export type SyncSchedule = {
  syncEnabled: boolean
  syncIntervalMinutes: number | null
  rowVersion: number
}

function readSchedule(value: unknown): SyncSchedule {
  if (
    !isRecord(value) ||
    typeof value.syncEnabled !== 'boolean' ||
    !Number.isSafeInteger(value.rowVersion) ||
    (value.rowVersion as number) < 0 ||
    !(
      value.syncIntervalMinutes === null ||
      (Number.isInteger(value.syncIntervalMinutes) &&
        (value.syncIntervalMinutes as number) > 0 &&
        (value.syncIntervalMinutes as number) <= 2147483647)
    ) ||
    (value.syncEnabled && value.syncIntervalMinutes === null)
  )
    throw new ApiError('수집 주기 응답을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  return {
    syncEnabled: value.syncEnabled,
    syncIntervalMinutes: value.syncIntervalMinutes as number | null,
    rowVersion: value.rowVersion as number,
  }
}

export async function fetchSyncSchedule(
  sourceId: number,
  signal?: AbortSignal,
) {
  return readSchedule(
    (await apiGet<unknown>(`/sources/${sourceId}/sync-schedule`, { signal }))
      .data,
  )
}

export async function saveSyncSchedule(
  sourceId: number,
  schedule: SyncSchedule,
) {
  return readSchedule(
    (await apiPut<unknown>(`/sources/${sourceId}/sync-schedule`, schedule))
      ?.data,
  )
}
