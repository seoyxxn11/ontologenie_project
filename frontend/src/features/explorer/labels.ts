import type { Cardinality, ObjectStatus } from './types'

export const statusLabels: Record<ObjectStatus, string> = {
  ACTIVE: '원천에 있음',
  DELETED_AT_SOURCE: '원천에서 삭제됨',
}

export const cardinalityLabels: Record<Cardinality, string> = {
  ONE_TO_ONE: '1:1',
  ONE_TO_MANY: '1:N',
  MANY_TO_ONE: 'N:1',
  MANY_TO_MANY: 'N:M',
}
