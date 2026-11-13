import type {
  ModelGraph,
  ObjectInstance,
  ObjectLink,
  ObjectPropertyDefinition,
} from '../features/explorer/types'

// 관계도 가독성을 비교하는 로컬 전용 가상 데이터. 운영 레코드를 복사하지 않는다.
export const denseDemoProperties: ObjectPropertyDefinition[] = [
  { id: 1, code: 'demo_code', name: '예시 ID', dataType: 'STRING' },
  { id: 2, code: 'status', name: '상태', dataType: 'STRING' },
]

export function createDenseExplorerDemo() {
  const types: [string, string, number][] = [
    ['EQUIPMENT', '설비', 12],
    ['OPERATOR', '현장 작업자', 36],
    ['WORK_ASSIGNMENT', '작업지시 공정 배정', 571],
    ['WORK_ORDER', '작업지시', 139],
    ['SENSOR_READING', '설비 센서 측정', 645],
    ['ATTENDANCE', '일별 근태', 180],
    ['DEPARTMENT', '부서', 5],
    ['EMPLOYEE', '직원', 36],
    ['FIXED_ASSET', '고정자산', 18],
    ['GOODS_RECEIPT', '입고 실적', 120],
    ['ITEM', '품목', 72],
    ['PURCHASE_ORDER', '구매 발주', 60],
    ['PURCHASE_DETAIL', '구매 발주 상세', 120],
    ['SALES_ORDER', '수주', 48],
    ['SALES_DETAIL', '수주 상세', 120],
    ['SHIPMENT', '출하 실적', 110],
    ['INVENTORY', '창고별 품목 재고', 72],
    ['CUSTOMER', '거래처', 32],
    ['DEFECT', '불량 기록', 90],
    ['MOLD', '금형', 18],
    ['DOWNTIME', '설비 비가동 기록', 120],
    ['HANDOVER_NOTE', '교대 인수인계 메모', 80],
    ['QUALITY_INSPECTION', '품질 검사 기록', 90],
    ['MAINTENANCE', '정비 지시', 60],
    ['MATERIAL_INPUT', '자재 투입 실적', 120],
    ['PRODUCTION_RESULT', '공정 실적', 350],
    ['STANDARD_PROCESS', '제품별 표준 공정', 72],
  ]
  const nodes = types.map(([code, name], index) => ({
    id: index + 1,
    code,
    name,
  }))
  const byType = new Map<number, ObjectInstance[]>()
  const timestamp = '2026-11-14T00:00:00+09:00'
  for (const node of nodes) {
    byType.set(
      node.id,
      Array.from({ length: types[node.id - 1][2] }, (_, index) => {
        const code = `DEMO-${node.code}-${String(index + 1).padStart(3, '0')}`
        return {
          id: node.id * 100000 + index + 1,
          objectTypeId: node.id,
          objectTypeName: node.name,
          displayName: `${node.name} 예시 ${String(index + 1).padStart(3, '0')}`,
          properties: { demo_code: code, status: 'ACTIVE' },
          restrictedPropertyCodes: [],
          status: 'ACTIVE',
          sourceMappingId: null,
          sourceKey: { demo_code: code },
          firstCollectedAt: timestamp,
          lastSyncedAt: timestamp,
          dataAsOf: timestamp,
        }
      }),
    )
  }
  // 배포 화면에서 확인한 유형·연결 구조를 참고하되, 연결 건수와 객체는 전부 가상이다.
  const pairs: [number, number][] = [
    [6, 8],
    [7, 8],
    [8, 7],
    [10, 12],
    [10, 11],
    [12, 18],
    [13, 12],
    [13, 11],
    [14, 18],
    [15, 14],
    [15, 11],
    [16, 14],
    [16, 11],
    [23, 16],
    [17, 11],
    [19, 4],
    [19, 1],
    [19, 2],
    [21, 1],
    [22, 2],
    [23, 4],
    [25, 4],
    [25, 11],
    [26, 4],
    [26, 1],
    [26, 2],
    [27, 11],
    [5, 1],
    [4, 11],
    [4, 20],
    [4, 14],
    [3, 4],
    [3, 1],
    [10, 13],
    [16, 15],
    [10, 17],
    [19, 3],
    [26, 3],
    [24, 20],
    [24, 1],
  ]
  const edges: ModelGraph['edges'] = pairs.map(
    ([sourceTypeId, targetTypeId], i) => {
      const count = i % 13 === 12 ? 0 : byType.get(sourceTypeId)!.length
      return {
        id: i + 1,
        code: `DEMO_RELATION_${i + 1}`,
        name: `${nodes[sourceTypeId - 1].name} → ${nodes[targetTypeId - 1].name}`,
        sourceTypeId,
        targetTypeId,
        cardinality: 'MANY_TO_ONE',
        relationCount: count,
        activeLinkCount: count,
      }
    },
  )
  const links: Omit<ObjectLink, 'relatedObject'>[] = edges.flatMap((edge) => {
    const sources = byType.get(edge.sourceTypeId)!
    const targets = byType.get(edge.targetTypeId)!
    return sources.slice(0, edge.activeLinkCount).map((source, index) => ({
      id: edge.id * 100000 + index + 1,
      linkTypeId: edge.id,
      linkTypeName: edge.name,
      sourceObjectId: source.id,
      targetObjectId: targets[index % targets.length].id,
      origin: 'MANUAL',
      status: 'ACTIVE',
      linkMappingId: null,
      evidence: {},
      expressionKind: null,
      confirmedBy: null,
    }))
  })
  return {
    graph: { nodes, edges },
    objects: [...byType.values()].flat(),
    links,
  }
}
