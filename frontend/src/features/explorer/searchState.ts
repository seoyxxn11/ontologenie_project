import type { ObjectFilters } from './api'

export function positiveId(value: string | null) {
  if (!value || !/^\d+$/.test(value)) return null
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

export function readSearchState(params: URLSearchParams) {
  const objectTypeId = positiveId(params.get('objectTypeId')) ?? undefined
  const propertyValues = Object.fromEntries(
    [...params.entries()]
      .flatMap(([key, value]) => {
        const code = /^propertyValues\[([^\]]+)\]$/.exec(key)?.[1]
        return code && objectTypeId ? [[code, value]] : []
      })
      .sort(([a], [b]) => a.localeCompare(b)),
  )
  const q = params.get('q')?.trim() || undefined
  return {
    filters: {
      ...(q ? { q } : {}),
      ...(objectTypeId ? { objectTypeId } : {}),
      ...(Object.keys(propertyValues).length ? { propertyValues } : {}),
    } as ObjectFilters,
    page: Math.min(positiveId(params.get('page')) ?? 0, 2147483647),
  }
}

export function writeSearchState(
  params: URLSearchParams,
  filters: ObjectFilters,
  page: number,
) {
  const next = new URLSearchParams(params)
  for (const key of [...next.keys()]) {
    if (
      ['q', 'objectTypeId', 'page'].includes(key) ||
      /^propertyValues\[/.test(key)
    )
      next.delete(key)
  }
  if (filters.q) next.set('q', filters.q)
  if (filters.objectTypeId)
    next.set('objectTypeId', String(filters.objectTypeId))
  for (const [code, value] of Object.entries(filters.propertyValues ?? {}))
    next.set(`propertyValues[${code}]`, value)
  if (page > 0) next.set('page', String(page))
  return next
}
