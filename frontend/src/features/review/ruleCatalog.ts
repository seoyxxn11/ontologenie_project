import { apiGet } from '../../api/client'
import type { PropertyItem } from '../ontology/types'

export type RuleRelation = {
  id: number
  name: string
  code: string
  sourceTypeId: number
  targetTypeId: number
  rowVersion: number
}
export type RuleEndpoint = {
  id: number
  name: string
  code: string
  properties: PropertyItem[]
}

export async function fetchRuleRelations(signal?: AbortSignal) {
  const rows: RuleRelation[] = []
  for (let page = 0; ; page += 1) {
    const response = await apiGet<RuleRelation[]>(
      `/ontology/link-types?page=${page}&size=100`,
      { signal },
    )
    rows.push(...response.data)
    if (!response.meta?.hasNext) return rows
  }
}

export async function fetchRuleEndpoint(id: number, signal?: AbortSignal) {
  const [type, properties] = await Promise.all([
    apiGet<Omit<RuleEndpoint, 'properties'>>(`/ontology/object-types/${id}`, {
      signal,
    }),
    apiGet<PropertyItem[]>(`/ontology/object-types/${id}/properties`, {
      signal,
    }),
  ])
  return { ...type.data, properties: properties.data }
}

export function matchesRuleProperty(value: string, endpoint: RuleEndpoint) {
  return endpoint.properties.some(
    (property) =>
      property.sensitivity !== 'RESTRICTED' &&
      (value === property.code ||
        value === `${endpoint.code}.${property.code}`),
  )
}
