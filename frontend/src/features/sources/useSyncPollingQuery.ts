import { useQuery, type QueryKey } from '@tanstack/react-query'
import { useState } from 'react'
import { ApiError } from '../../api/errors'
import { SYNC_ACTIVE_POLL_MS, SYNC_IDLE_POLL_MS } from './syncApi'

export function isTransientSyncError(error: Error) {
  return (
    !(error instanceof ApiError) ||
    error.kind === 'network' ||
    error.kind === 'server' ||
    error.status === 429
  )
}

export default function useSyncPollingQuery<T>({
  queryKey,
  queryFn,
  isActive,
  enabled = true,
  pollWhenIdle = true,
}: {
  queryKey: QueryKey
  queryFn: (signal: AbortSignal) => Promise<T>
  isActive: (data: T | undefined) => boolean
  enabled?: boolean
  pollWhenIdle?: boolean
}) {
  const [failures, setFailures] = useState<Record<string, number>>({})
  const key = JSON.stringify(queryKey)
  const query = useQuery({
    queryKey,
    enabled,
    queryFn: async ({ signal }) => {
      try {
        const result = await queryFn(signal)
        if (!signal.aborted)
          setFailures((current) =>
            current[key] ? { ...current, [key]: 0 } : current,
          )
        return result
      } catch (error) {
        if (
          !signal.aborted &&
          error instanceof Error &&
          isTransientSyncError(error)
        ) {
          setFailures((current) => ({
            ...current,
            [key]: (current[key] ?? 0) + 1,
          }))
        }
        throw error
      }
    },
    retry: false,
    refetchIntervalInBackground: false,
    refetchInterval: (current) => {
      if (current.state.error)
        return isTransientSyncError(current.state.error)
          ? SYNC_IDLE_POLL_MS
          : false
      return isActive(current.state.data)
        ? SYNC_ACTIVE_POLL_MS
        : pollWhenIdle
          ? SYNC_IDLE_POLL_MS
          : false
    },
  })
  const unavailable =
    query.error instanceof ApiError &&
    [401, 403, 404].includes(query.error.status ?? 0)
  const transientError = query.isError && isTransientSyncError(query.error)
  return {
    query,
    unavailable,
    transientError,
    refreshDelayed: transientError && (failures[key] ?? 0) >= 3,
  }
}
