import { delay, http, HttpResponse } from 'msw'
import { actionHandlers } from './actionHandlers'
import { checkHandlers } from './checkHandlers'
import { agentHandlers } from './agentHandlers'
import { analysisHandlers } from './analysisHandlers'
import { authHandlers, getMockAccount } from './auth'
import { accountHandlers } from './accountHandlers'
import { proposalHandlers } from './proposalHandlers'
import { reassignmentHandlers } from './reassignmentHandlers'
import { issueHandlers } from './issueHandlers'
import { ruleCatalogHandlers } from './ruleCatalogHandlers'
import { mappingHandlers } from './mappingHandlers'
import { sourceHandlers } from './sourceHandlers'
import {
  createDenseExplorerDemo,
  denseDemoProperties,
} from './denseExplorerDemo'
import type {
  EntityChange,
  Execution,
  ExecutionDetail,
} from '../features/audit/types'
import type {
  DefinitionChange,
  RuleRevision,
  RuleSummary,
} from '../features/history/types'
import type {
  ModelGraph,
  ObjectInstance,
  ObjectLink,
  ObjectLinkGroup,
} from '../features/explorer/types'

const denseDemo =
  import.meta.env.DEV && import.meta.env.MODE === 'graph-demo'
    ? createDenseExplorerDemo()
    : null

const baseObjects: ObjectInstance[] = [
  {
    id: 301,
    objectTypeId: 3,
    objectTypeName: '설비',
    displayName: '프레스 3호기',
    properties: {
      equipment_code: 'PR-03',
      process: '프레스',
      status: '정지',
      maintenance_note: null,
      purchase_price: null,
    },
    restrictedPropertyCodes: ['purchase_price'],
    status: 'ACTIVE',
    sourceMappingId: 21,
    sourceKey: { equipment_code: 'PR-03' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 302,
    objectTypeId: 3,
    objectTypeName: '설비',
    displayName: '프레스 1호기',
    properties: {
      equipment_code: 'PR-01',
      process: '프레스',
      status: '가동',
      maintenance_note: null,
      purchase_price: null,
    },
    restrictedPropertyCodes: ['purchase_price'],
    status: 'ACTIVE',
    sourceMappingId: 21,
    sourceKey: { equipment_code: 'PR-01' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 1101,
    objectTypeId: 4,
    objectTypeName: '작업지시',
    displayName: '프레스 작업',
    properties: {
      work_order_no: 'WO-101',
      process: '프레스',
      assigned_equipment: 'PR-03',
      sales_order_no: 'SO-101',
      status: '미착수',
    },
    restrictedPropertyCodes: [],
    status: 'ACTIVE',
    sourceMappingId: 22,
    sourceKey: { work_order_no: 'WO-101' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 1201,
    objectTypeId: 5,
    objectTypeName: '수주',
    displayName: '생산 수주',
    properties: {
      sales_order_no: 'SO-101',
      status: '진행 중',
    },
    restrictedPropertyCodes: [],
    status: 'ACTIVE',
    sourceMappingId: 31,
    sourceKey: { sales_order_no: 'SO-101' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-27T09:00:00+09:00',
  },
  {
    id: 2001,
    objectTypeId: 6,
    objectTypeName: '메모',
    displayName: '야간 인수인계',
    properties: {
      note_id: 'NOTE-01',
      content: '야간에 3호기 진동이 커졌다.',
    },
    restrictedPropertyCodes: [],
    status: 'ACTIVE',
    sourceMappingId: 41,
    sourceKey: { note_id: 'NOTE-01' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: null,
  },
  {
    id: 2101,
    objectTypeId: 7,
    objectTypeName: '정비 로그',
    displayName: '3호기 진동 점검',
    properties: {
      log_id: 'MAINT-01',
      equipment_code: 'PR-03',
      logged_at: '2026-10-20 22:00',
      content: '진동 증가에 따른 설비 정지 및 점검',
    },
    restrictedPropertyCodes: [],
    status: 'ACTIVE',
    sourceMappingId: 51,
    sourceKey: { log_id: 'MAINT-01' },
    firstCollectedAt: '2026-10-26T09:00:00+09:00',
    lastSyncedAt: '2026-10-27T09:01:00+09:00',
    dataAsOf: '2026-10-20T22:00:00+09:00',
  },
]

const objects = denseDemo?.objects ?? baseObjects

type SourceLocation = {
  systemName: string
  resourceKey: string
}

// 원천 매핑 ID → 원천 시스템·테이블 (077 응답의 원천 위치를 만들 때 사용)
const sourceMappings: Record<number, SourceLocation> = {
  21: { systemName: 'MES', resourceKey: 'mes.equipment' },
  22: { systemName: 'MES', resourceKey: 'mes.work_order' },
  31: { systemName: 'ERP', resourceKey: 'erp.sales_order' },
  41: { systemName: '현장 자료', resourceKey: 'docs.handover_note' },
  51: { systemName: '정비 로그', resourceKey: 'maint.maintenance_log' },
}

// 객체 유형 ID → 속성 코드별 표시 이름 (077 응답의 propertyNames를 만들 때 사용)
const propertyNamesByType: Record<number, Record<string, string>> = {
  3: {
    equipment_code: '설비 코드',
    process: '공정',
    status: '상태',
    maintenance_note: '정비 메모',
    purchase_price: '구매 가격',
  },
  4: {
    work_order_no: '작업지시 번호',
    process: '공정',
    assigned_equipment: '배정 설비',
    sales_order_no: '수주 번호',
    status: '상태',
  },
  5: { sales_order_no: '수주 번호', status: '상태' },
  6: { note_id: '메모 번호', content: '내용' },
  7: {
    log_id: '로그 번호',
    equipment_code: '설비 코드',
    logged_at: '기록 시각',
    content: '내용',
  },
}

// 078 응답의 연결 (relatedObject는 조회한 객체에 따라 달라서 응답할 때 채운다)
const baseLinks: Omit<ObjectLink, 'relatedObject'>[] = [
  {
    id: 22,
    linkTypeId: 2,
    linkTypeName: '현재 배정 설비',
    sourceObjectId: 1101,
    targetObjectId: 301,
    origin: 'RULE',
    status: 'ACTIVE',
    linkMappingId: 610,
    evidence: {
      sourceKey: { work_order_no: 'WO-101' },
      rawValue: 'PR03',
      transformedValue: 'PR-03',
      comparisons: [
        {
          sequenceNo: 1,
          sourceProperty: 'equipment_id',
          sourceValue: 'PR03',
          targetProperty: 'equipment_code',
          targetValue: 'PR-03',
          matchedValue: 'PR-03',
        },
      ],
    },
    expressionKind: null,
    confirmedBy: null,
  },
  {
    id: 23,
    linkTypeId: 3,
    linkTypeName: '생산 대상 수주',
    sourceObjectId: 1101,
    targetObjectId: 1201,
    origin: 'RULE',
    status: 'ACTIVE',
    linkMappingId: 611,
    evidence: {
      sourceKey: { work_order_no: 'WO-101' },
      rawValue: 'SO-101',
      transformedValue: 'SO-101',
      comparisons: [
        {
          sequenceNo: 1,
          sourceProperty: 'sales_order_id',
          sourceValue: 'SO-101',
          targetProperty: 'sales_order_id',
          targetValue: 'SO-101',
          matchedValue: 'SO-101',
        },
      ],
    },
    expressionKind: null,
    confirmedBy: null,
  },
  {
    id: 35,
    linkTypeId: 7,
    linkTypeName: '언급된 설비',
    sourceObjectId: 2001,
    targetObjectId: 301,
    origin: 'DOCUMENT',
    status: 'ACTIVE',
    linkMappingId: null,
    evidence: {
      quote: '야간에 3호기 진동이 커졌다.',
      event: '진동 증가',
      timeExpression: '야간',
    },
    expressionKind: 'ESTIMATED',
    confirmedBy: 1,
  },
  {
    id: 41,
    linkTypeId: 8,
    linkTypeName: '대체 가능 설비',
    sourceObjectId: 1101,
    targetObjectId: 302,
    origin: 'MANUAL',
    status: 'ACTIVE',
    linkMappingId: null,
    evidence: {},
    expressionKind: null,
    confirmedBy: 1,
  },
]

const links = denseDemo?.links ?? baseLinks

// 객체 번호 → 078 relatedObject 모양의 요약
function objectSummary(id: number) {
  const object = objects.find((item) => item.id === id)
  return {
    id,
    displayName: object?.displayName ?? '',
    objectTypeId: object?.objectTypeId ?? 0,
    objectTypeName: object?.objectTypeName ?? '',
  }
}

// 가짜 로그인 사용자. 096은 역할이 ADMIN이면 전체, 아니면 본인 실행만 돌려준다고 가정한다.
// 관리자 화면을 보려면 roleCode를 'ADMIN'으로 바꾼다.
export const mockUser = { accountId: 7, roleCode: 'PLANNER' }

// 080·096 응답의 실행 이력 (최근 실행이 앞에 오도록 적어 둔다)
type MockExecution = Omit<Execution, 'changeCount'> & {
  input: Record<string, unknown>
  result: Record<string, unknown>
  blockedReason: string | null
  entityChanges: EntityChange[]
  restrictedPropertyCodes?: string[]
}
const executions: MockExecution[] = [
  {
    id: 9005,
    requestId: '66666666-6666-4666-8666-666666666666',
    eventKind: 'ACTION',
    category: 'CHANGE',
    actionDefinitionId: 2,
    actionCode: 'APPROVE_PROPOSAL',
    actionName: '연결 규칙 승인',
    actorType: 'USER',
    actorId: 1,
    actorName: '관리자',
    actorRoleCode: 'ADMIN',
    checkedPermissionCode: 'REVIEW_DECIDE',
    entryPoint: 'WEB',
    targetType: 'proposal_item',
    targetId: 300,
    targetLabel: '작업지시의 설비 코드로 설비 연결',
    input: { decision: 'APPROVE', reason: '설비 코드 대문자 변환 확인' },
    status: 'SUCCEEDED',
    blockedReason: null,
    result: { linkMappingId: 12, revision: 2 },
    requestedAt: '2026-10-28T11:00:00+09:00',
    finishedAt: '2026-10-28T11:00:01+09:00',
    entityChanges: [],
  },
  {
    id: 9004,
    requestId: '55555555-5555-4555-8555-555555555555',
    eventKind: 'ACTION',
    category: 'CHANGE',
    actionDefinitionId: 1,
    actionCode: 'UPDATE_OBJECT_TYPE',
    actionName: '객체 유형 수정',
    actorType: 'USER',
    actorId: 1,
    actorName: '관리자',
    actorRoleCode: 'ADMIN',
    checkedPermissionCode: 'ONTOLOGY_MANAGE',
    entryPoint: 'WEB',
    targetType: 'object_type',
    targetId: 3,
    targetLabel: '프레스 설비',
    input: { name: '프레스 설비' },
    status: 'SUCCEEDED',
    blockedReason: null,
    result: { updated: true },
    requestedAt: '2026-10-28T10:00:00+09:00',
    finishedAt: '2026-10-28T10:00:01+09:00',
    entityChanges: [
      {
        tableName: 'object_type',
        rowKey: { id: 3 },
        changeKind: 'UPDATE',
        beforeData: { name: '설비' },
        afterData: { name: '프레스 설비' },
      },
    ],
  },
  {
    id: 9003,
    requestId: '44444444-4444-4444-8444-444444444444',
    eventKind: 'SYNC_RUN',
    category: 'DATA_JOB',
    actionDefinitionId: null,
    actorType: 'USER',
    actorId: 7,
    actorName: '김계획',
    actorRoleCode: 'PLANNER',
    checkedPermissionCode: 'SYNC_REQUEST',
    entryPoint: 'WEB',
    targetType: 'sync_run',
    targetId: 77,
    targetLabel: null,
    input: { sourceName: 'MES' },
    status: 'SUCCEEDED',
    blockedReason: null,
    result: { collected: 120 },
    requestedAt: '2026-10-28T08:00:00+09:00',
    finishedAt: '2026-10-28T08:03:00+09:00',
    entityChanges: [],
  },
  {
    id: 9002,
    requestId: '11111111-1111-4111-8111-111111111111',
    eventKind: 'ACTION',
    category: 'OPERATION',
    actionDefinitionId: 3,
    actionCode: 'CREATE_REASSIGNMENT_PLAN',
    actionName: '재배정 계획 생성',
    actorType: 'USER',
    actorId: 7,
    actorName: '김계획',
    actorRoleCode: 'PLANNER',
    checkedPermissionCode: 'ACTION_EXECUTE',
    entryPoint: 'WEB',
    targetType: 'object_instance',
    targetId: 301,
    targetLabel: '프레스 3호기',
    input: { workOrderNo: 'WO-101', toEquipment: 'PR-01' },
    status: 'SUCCEEDED',
    blockedReason: null,
    result: { planId: 501, message: '재배정 계획을 만들었습니다.' },
    requestedAt: '2026-10-27T09:10:00+09:00',
    finishedAt: '2026-10-27T09:10:02+09:00',
    entityChanges: [
      {
        tableName: 'reassignment_plan',
        rowKey: { id: 501 },
        changeKind: 'INSERT',
        beforeData: null,
        afterData: {
          status: 'DRAFT',
          fromEquipment: 'PR-03',
          toEquipment: 'PR-01',
        },
      },
    ],
  },
  {
    id: 9001,
    requestId: '22222222-2222-4222-8222-222222222222',
    eventKind: 'ACTION',
    category: 'OPERATION',
    actionDefinitionId: 3,
    actionCode: 'CREATE_REASSIGNMENT_PLAN',
    actionName: '재배정 계획 생성',
    actorType: 'USER',
    actorId: 7,
    actorName: '김계획',
    actorRoleCode: 'PLANNER',
    checkedPermissionCode: 'ACTION_EXECUTE',
    entryPoint: 'WEB',
    targetType: 'object_instance',
    targetId: 301,
    targetLabel: '프레스 3호기',
    input: { workOrderNo: 'WO-101', toEquipment: 'PR-02', unitCost: null },
    status: 'BLOCKED',
    blockedReason: '대체 설비 PR-02는 프레스 공정을 처리할 수 없습니다.',
    result: {},
    requestedAt: '2026-10-26T18:00:00+09:00',
    finishedAt: '2026-10-26T18:00:01+09:00',
    entityChanges: [],
    restrictedPropertyCodes: ['unitCost'],
  },
  {
    id: 9000,
    requestId: '33333333-3333-4333-8333-333333333333',
    eventKind: 'ACTION',
    category: 'OPERATION',
    actionDefinitionId: 5,
    actionCode: 'REQUEST_MAINTENANCE',
    actionName: '정비 요청',
    actorType: 'AGENT',
    actorId: 7,
    actorName: '김계획',
    actorRoleCode: 'PLANNER',
    checkedPermissionCode: 'ACTION_EXECUTE',
    entryPoint: 'AGENT',
    targetType: 'object_instance',
    targetId: 301,
    targetLabel: '프레스 3호기',
    input: { equipmentCode: 'PR-03', reason: '진동 증가' },
    status: 'FAILED',
    blockedReason: null,
    result: { errorMessage: '정비 시스템 응답 시간이 초과되었습니다.' },
    requestedAt: '2026-10-25T14:00:00+09:00',
    finishedAt: '2026-10-25T14:00:30+09:00',
    entityChanges: [],
  },
]

function findVisibleExecution(id: number) {
  const account = getMockAccount()
  const status = !account ? 401 : account.mustChangePassword ? 403 : 404
  const found = executions.find((execution) => execution.id === id)
  if (
    account &&
    !account.mustChangePassword &&
    found &&
    (account.roleCode === 'ADMIN' ||
      mockUser.roleCode === 'ADMIN' ||
      found.actorId === account.id)
  ) {
    return found
  }
  return HttpResponse.json(
    {
      error: {
        code:
          status === 401
            ? 'UNAUTHENTICATED'
            : status === 403
              ? 'FORBIDDEN'
              : 'RESOURCE_NOT_FOUND',
        message:
          status === 401
            ? '로그인이 필요합니다.'
            : status === 403
              ? '비밀번호를 변경한 뒤 이용해 주세요.'
              : '대상을 찾을 수 없습니다.',
        details: {},
        requestId: 'mock-audit-detail',
      },
    },
    { status },
  )
}

// 080·096 조회 조건(status, from, to, 096의 category·targetType·targetId)으로 실행 이력을 거른다.
function filterExecutions(
  items: MockExecution[],
  searchParams: URLSearchParams,
) {
  const status = searchParams.get('status')
  const from = searchParams.get('from')
  const to = searchParams.get('to')
  const category = searchParams.get('category')
  const targetType = searchParams.get('targetType')
  const targetId = Number(searchParams.get('targetId'))
  return items
    .filter((item) => !category || item.category === category)
    .filter(
      (item) =>
        !targetType ||
        (item.targetType === targetType &&
          (!targetId || item.targetId === targetId)),
    )
    .filter((item) => !status || item.status === status)
    .filter((item) => !from || new Date(item.requestedAt) >= new Date(from))
    .filter((item) => !to || new Date(item.requestedAt) < new Date(to))
}

function executionPage(items: MockExecution[], searchParams: URLSearchParams) {
  const page = Number(searchParams.get('page') ?? 0)
  const size = Number(searchParams.get('size') ?? 20)
  return {
    data: items.slice(page * size, (page + 1) * size).map((item) => {
      const summary: Execution = {
        id: item.id,
        requestId: item.requestId,
        eventKind: item.eventKind,
        category: item.category,
        actionDefinitionId: item.actionDefinitionId,
        actionCode: item.actionCode,
        actionName: item.actionName,
        actorType: item.actorType,
        actorId: item.actorId,
        actorName: item.actorName,
        actorRoleCode: item.actorRoleCode,
        checkedPermissionCode: item.checkedPermissionCode,
        entryPoint: item.entryPoint,
        targetType: item.targetType,
        targetId: item.targetId,
        targetLabel: item.targetLabel,
        status: item.status,
        requestedAt: item.requestedAt,
        finishedAt: item.finishedAt,
        changeCount: item.entityChanges.length,
      }
      return summary
    }),
    meta: {
      page,
      size,
      totalElements: items.length,
      hasNext: (page + 1) * size < items.length,
    },
  }
}

// 042 객체 유형 관계도 응답 (노드는 객체 유형, 선은 관계 유형)
// 관계 유형 번호·이름과 연결 수는 078 목의 연결(objectLinks)과 맞춘다.
const baseModelGraph: ModelGraph = {
  nodes: [
    { id: 3, code: 'EQUIPMENT', name: '설비' },
    { id: 4, code: 'WORK_ORDER', name: '작업지시' },
    { id: 5, code: 'SALES_ORDER', name: '수주' },
    { id: 6, code: 'HANDOVER_NOTE', name: '메모' },
    { id: 7, code: 'MAINTENANCE_LOG', name: '정비 로그' },
    { id: 8, code: 'INSPECTION_REQUEST', name: '점검 요청' },
  ],
  edges: [
    {
      id: 2,
      code: 'ASSIGNED_EQUIPMENT',
      name: '현재 배정 설비',
      sourceTypeId: 4,
      targetTypeId: 3,
      cardinality: 'MANY_TO_ONE',
      relationCount: 2,
      activeLinkCount: 1,
    },
    {
      id: 3,
      code: 'PRODUCTION_ORDER',
      name: '생산 대상 수주',
      sourceTypeId: 4,
      targetTypeId: 5,
      cardinality: 'MANY_TO_ONE',
      relationCount: 1,
      activeLinkCount: 1,
    },
    {
      id: 7,
      code: 'MENTIONED_EQUIPMENT',
      name: '언급된 설비',
      sourceTypeId: 6,
      targetTypeId: 3,
      cardinality: 'MANY_TO_MANY',
      relationCount: 1,
      activeLinkCount: 1,
    },
    {
      id: 8,
      code: 'ALTERNATIVE_EQUIPMENT',
      name: '대체 가능 설비',
      sourceTypeId: 4,
      targetTypeId: 3,
      cardinality: 'MANY_TO_MANY',
      relationCount: 1,
      activeLinkCount: 1,
    },
    {
      id: 9,
      code: 'MAINTAINED_EQUIPMENT',
      name: '정비 대상 설비',
      sourceTypeId: 7,
      targetTypeId: 3,
      cardinality: 'MANY_TO_ONE',
      relationCount: 0,
      activeLinkCount: 0,
    },
  ],
}

const modelGraph = denseDemo?.graph ?? baseModelGraph

// 044 정의 변경 이력 응답 (최근 변경이 앞에 오도록 적어 둔다)
// 객체 유형 이름 변경은 096 목의 관리자 실행(9004)과 같은 기록이다.
const definitionChanges: DefinitionChange[] = [
  {
    id: 703,
    actionExecutionId: 9004,
    tableName: 'object_type',
    rowKey: { id: 3 },
    changeKind: 'UPDATE',
    beforeData: { name: '설비', row_version: 1 },
    afterData: { name: '프레스 설비', row_version: 2 },
    actorId: 1,
    actorType: 'USER',
    requestedAt: '2026-10-28T10:00:00+09:00',
    actionCode: 'UPDATE_OBJECT_TYPE',
  },
  {
    id: 702,
    actionExecutionId: 8802,
    tableName: 'property_definition',
    rowKey: { id: 31 },
    changeKind: 'INSERT',
    beforeData: null,
    afterData: {
      code: 'vibration_level',
      name: '진동 수준',
      data_type: 'NUMBER',
    },
    actorId: 1,
    actorType: 'USER',
    requestedAt: '2026-10-27T15:00:00+09:00',
    actionCode: 'CREATE_PROPERTY',
  },
  {
    id: 701,
    actionExecutionId: 8801,
    tableName: 'link_type',
    rowKey: { id: 2 },
    changeKind: 'UPDATE',
    beforeData: { name: '배정 설비' },
    afterData: { name: '현재 배정 설비' },
    actorId: 1,
    actorType: 'USER',
    requestedAt: '2026-10-26T11:00:00+09:00',
    actionCode: 'UPDATE_LINK_TYPE',
  },
]

// 046 연결 규칙 개정 이력 응답 (규칙 코드별)
const ruleRevisions: Record<string, RuleRevision[]> = {
  NOTE_EQUIPMENT_MENTION: [
    {
      id: 20,
      code: 'NOTE_EQUIPMENT_MENTION',
      revision: 1,
      linkTypeId: 7,
      linkTypeName: '언급된 설비',
      status: 'ACTIVE',
      conditions: [
        {
          sequenceNo: 1,
          sourcePropertyId: 61,
          targetPropertyId: 21,
          sourcePropertyName: '언급 설비 코드',
          targetPropertyName: '설비 코드',
          operator: 'EQ',
          sourceTransforms: [],
          targetTransforms: [],
        },
      ],
      sourceFilter: {},
      targetFilter: {},
      retiredAt: null,
      description: '인수인계 메모에 언급된 설비 연결',
      createdAt: '2026-10-25T13:00:00+09:00',
      createdBy: 1,
      origin: 'MANUAL',
      proposalItemId: null,
    },
  ],
  WO_EQUIPMENT_CODE: [
    {
      id: 10,
      code: 'WO_EQUIPMENT_CODE',
      revision: 1,
      linkTypeId: 2,
      linkTypeName: '현재 배정 설비',
      status: 'RETIRED',
      conditions: [
        {
          sequenceNo: 1,
          sourcePropertyId: 31,
          targetPropertyId: 21,
          sourcePropertyName: '배정 설비 코드',
          targetPropertyName: '설비 코드',
          operator: 'EQ',
          sourceTransforms: [],
          targetTransforms: [],
        },
      ],
      sourceFilter: {},
      targetFilter: {},
      retiredAt: '2026-10-27T09:00:00+09:00',
      description: '작업지시의 설비 코드로 설비 연결(초판)',
      createdAt: '2026-10-24T09:00:00+09:00',
      createdBy: 1,
      origin: 'AI_PROPOSAL',
      proposalItemId: 280,
    },
    {
      id: 12,
      code: 'WO_EQUIPMENT_CODE',
      revision: 2,
      linkTypeId: 2,
      linkTypeName: '현재 배정 설비',
      status: 'ACTIVE',
      conditions: [
        {
          sequenceNo: 1,
          sourcePropertyId: 31,
          targetPropertyId: 21,
          sourcePropertyName: '배정 설비 코드',
          targetPropertyName: '설비 코드',
          operator: 'EQ',
          sourceTransforms: [{ function: 'UPPER' }],
          targetTransforms: [],
        },
      ],
      sourceFilter: {},
      targetFilter: {},
      retiredAt: null,
      description: '작업지시의 설비 코드로 설비 연결',
      createdAt: '2026-10-27T09:00:00+09:00',
      createdBy: 1,
      origin: 'AI_PROPOSAL',
      proposalItemId: 300,
    },
  ],
}

// 연결 규칙 목록 응답 (백엔드 신설 예정): 규칙 코드별 최신 개정본
function ruleSummaries(): RuleSummary[] {
  return Object.values(ruleRevisions).flatMap((revisions) => {
    const [first, ...rest] = revisions
    if (!first) return []
    const latest = rest.reduce(
      (a, b) => (a.revision > b.revision ? a : b),
      first,
    )
    return {
      code: latest.code,
      revision: latest.revision,
      linkTypeId: latest.linkTypeId,
      linkTypeName: latest.linkTypeName ?? '',
      status: latest.status,
      description: latest.description,
    }
  })
}

export const handlers = [
  ...accountHandlers,
  ...actionHandlers,
  ...checkHandlers,
  ...proposalHandlers,
  ...issueHandlers,
  ...authHandlers,
  ...sourceHandlers,
  ...ruleCatalogHandlers,
  ...mappingHandlers,
  ...analysisHandlers,
  ...agentHandlers,
  ...reassignmentHandlers,
  // 042 객체 유형 관계도
  http.get('/api/v1/ontology/model-graph', async () => {
    await delay()
    const account = getMockAccount()
    if (
      !account ||
      account.roleCode !== 'ADMIN' ||
      account.mustChangePassword
    ) {
      return HttpResponse.json(
        {
          error: {
            code: account ? 'FORBIDDEN' : 'UNAUTHENTICATED',
            message: account
              ? '요청을 실행할 권한이 없습니다.'
              : '로그인이 필요합니다.',
            details: account ? { requiredPermission: 'ADMIN' } : {},
            requestId: 'mock-model-graph',
          },
        },
        { status: account ? 403 : 401 },
      )
    }
    return HttpResponse.json({ data: modelGraph })
  }),

  // 044 정의 변경 이력 조회
  http.get('/api/v1/ontology/changes', async ({ request }) => {
    await delay()
    const params = new URL(request.url).searchParams
    const tableName = params.get('tableName')
    const from = params.get('from')
    const to = params.get('to')
    const matched = definitionChanges
      .filter((item) => !tableName || item.tableName === tableName)
      .filter((item) => !from || new Date(item.requestedAt) >= new Date(from))
      .filter((item) => !to || new Date(item.requestedAt) < new Date(to))
    return HttpResponse.json({
      data: matched,
      meta: {
        page: 0,
        size: 20,
        totalElements: matched.length,
        hasNext: false,
      },
    })
  }),

  // 연결 규칙 목록 (백엔드 신설 예정)
  http.get('/api/v1/ontology/rules', async ({ request }) => {
    await delay()
    const params = new URL(request.url).searchParams
    const linkTypeId = Number(params.get('linkTypeId'))
    const status = params.get('status')
    const matched = ruleSummaries()
      .filter((rule) => !linkTypeId || rule.linkTypeId === linkTypeId)
      .filter((rule) => !status || rule.status === status)
    return HttpResponse.json({ data: matched })
  }),

  // 046 연결 규칙 개정 이력 조회
  http.get(
    '/api/v1/ontology/rules/:code/revisions',
    async ({ params, request }) => {
      await delay()
      const search = new URL(request.url).searchParams
      const page = Number(search.get('page') ?? 0)
      const size = Number(search.get('size') ?? 20)
      const revisions = ruleRevisions[String(params.code)] ?? []
      return HttpResponse.json({
        data: revisions.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalElements: revisions.length,
          hasNext: (page + 1) * size < revisions.length,
        },
      })
    },
  ),

  // 076 권한 범위 객체 검색
  http.get('/api/v1/objects', async ({ request }) => {
    await delay()
    const params = new URL(request.url).searchParams
    const q = (params.get('q') ?? '').trim().toLowerCase()
    const page = Number(params.get('page') ?? 0)
    const size = Number(params.get('size') ?? 20)
    const objectTypeId = Number(params.get('objectTypeId'))
    const propertyValues = [...params].flatMap(([key, value]) => {
      const match = /^propertyValues\[(.+)\]$/.exec(key)
      return match ? [[match[1], value]] : []
    })
    const matched = objects.filter((item) => {
      const numbers = Object.values(item.sourceKey ?? {})
        .filter((value) => value !== null && value !== undefined)
        .map(String)
      const matchesType = !objectTypeId || item.objectTypeId === objectTypeId
      return (
        matchesType &&
        propertyValues.every(
          ([code, value]) =>
            item.properties[code] != null &&
            String(item.properties[code]) === value,
        ) &&
        [item.displayName, ...numbers].some((text) =>
          text.toLowerCase().includes(q),
        )
      )
    })
    const start = page * size
    return HttpResponse.json({
      data: matched.slice(start, start + size),
      meta: {
        page,
        size,
        totalElements: matched.length,
        hasNext: start + size < matched.length,
      },
    })
  }),

  // 347 권한 범위 객체 유형 선택지
  http.get('/api/v1/objects/types', () =>
    HttpResponse.json({ data: modelGraph.nodes }),
  ),

  // 077 객체 상세 조회
  http.get('/api/v1/objects/:id', async ({ params }) => {
    await delay()
    const object = objects.find((item) => item.id === Number(params.id))
    if (!object) {
      return HttpResponse.json(
        {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: '요청한 항목을 찾을 수 없습니다.',
            details: { resource: 'object' },
            requestId: 'mock-request-id',
          },
        },
        { status: 404 },
      )
    }

    const source = object.sourceMappingId
      ? sourceMappings[object.sourceMappingId]
      : undefined
    return HttpResponse.json({
      data: {
        ...object,
        propertyNames: denseDemo
          ? Object.fromEntries(
              denseDemoProperties.map((property) => [
                property.code,
                property.name,
              ]),
            )
          : (propertyNamesByType[object.objectTypeId] ?? {}),
        sourceSystemName: source?.systemName ?? null,
        sourceResourceKey: source?.resourceKey ?? null,
      },
    })
  }),
  // 078 확정 관계·원천 근거 조회
  http.get('/api/v1/objects/:id/links', async ({ params, request }) => {
    await delay()
    const id = Number(params.id)
    const url = new URL(request.url)
    const page = Number(url.searchParams.get('page') ?? 0)
    const size = Number(url.searchParams.get('size') ?? 20)
    const linkTypeId = url.searchParams.get('linkTypeId')
    const direction = url.searchParams.get('direction') ?? 'BOTH'
    const matched = links
      .filter(
        (link) =>
          (direction !== 'IN' && link.sourceObjectId === id) ||
          (direction !== 'OUT' && link.targetObjectId === id),
      )
      .filter(
        (link) => linkTypeId === null || link.linkTypeId === Number(linkTypeId),
      )
      .map((link) => {
        const relatedId =
          link.sourceObjectId === id ? link.targetObjectId : link.sourceObjectId
        return { ...link, relatedObject: objectSummary(relatedId) }
      })
    return HttpResponse.json({
      data: matched.slice(page * size, (page + 1) * size),
      meta: {
        page,
        size,
        totalElements: matched.length,
        hasNext: (page + 1) * size < matched.length,
      },
    })
  }),

  // 관계 유형·방향별 연결 건수 (관계도 첫 화면)
  http.get('/api/v1/objects/:id/link-groups', async ({ params }) => {
    await delay()
    const id = Number(params.id)
    const groups = new Map<string, ObjectLinkGroup>()
    for (const link of links) {
      if (link.sourceObjectId !== id && link.targetObjectId !== id) {
        continue
      }
      const outgoing = link.sourceObjectId === id
      const related = objectSummary(
        outgoing ? link.targetObjectId : link.sourceObjectId,
      )
      const key = `${link.linkTypeId}:${outgoing}`
      const group = groups.get(key)
      if (group) {
        group.linkCount += 1
      } else {
        groups.set(key, {
          linkTypeId: link.linkTypeId,
          linkTypeName: link.linkTypeName,
          direction: outgoing ? 'OUT' : 'IN',
          relatedObjectTypeId: related.objectTypeId,
          relatedObjectTypeName: related.objectTypeName,
          linkCount: 1,
        })
      }
    }
    return HttpResponse.json({
      data: [...groups.values()].sort((a, b) => b.linkCount - a.linkCount),
    })
  }),

  // 080 객체 관련 실행 이력 조회
  http.get('/api/v1/objects/:id/executions', async ({ params, request }) => {
    await delay()
    const account = getMockAccount()
    if (!account || account.mustChangePassword) {
      return HttpResponse.json(
        {
          error: {
            code: account ? 'FORBIDDEN' : 'UNAUTHENTICATED',
            message: account
              ? '비밀번호를 변경한 뒤 이용해 주세요.'
              : '로그인이 필요합니다.',
            details: {},
            requestId: 'mock-object-executions',
          },
        },
        { status: account ? 403 : 401 },
      )
    }
    if (!objects.some((object) => object.id === Number(params.id))) {
      return HttpResponse.json(
        {
          error: {
            code: 'RESOURCE_NOT_FOUND',
            message: '대상을 찾을 수 없습니다.',
            details: { resource: 'object_instance' },
            requestId: 'mock-object-executions',
          },
        },
        { status: 404 },
      )
    }
    const objectExecutions = executions.filter(
      (item) =>
        item.targetType === 'object_instance' &&
        item.targetId === Number(params.id) &&
        (account.roleCode === 'ADMIN' || item.actorId === account.id),
    )
    const searchParams = new URL(request.url).searchParams
    const matched = filterExecutions(objectExecutions, searchParams)
    return HttpResponse.json(executionPage(matched, searchParams))
  }),

  // 097·098: 일반 사용자도 본인 기록을 조회하고, 범위 밖은 404다.
  http.get('/api/v1/audit/actions/:id/changes', async ({ params }) => {
    await delay()
    const found = findVisibleExecution(Number(params.id))
    if (found instanceof HttpResponse) return found
    return HttpResponse.json({
      data: {
        changes: found.entityChanges,
        restrictedPropertyCodes: found.restrictedPropertyCodes ?? [],
      },
    })
  }),
  http.get('/api/v1/audit/actions/:id', async ({ params }) => {
    await delay()
    const found = findVisibleExecution(Number(params.id))
    if (found instanceof HttpResponse) return found
    const data: ExecutionDetail = {
      id: found.id,
      status: found.status,
      input: found.input,
      result: found.result,
      blockedReason: found.blockedReason,
      failureReason:
        found.status === 'FAILED'
          ? String(found.result.errorMessage ?? found.result.reason ?? '') ||
            null
          : null,
      restrictedPropertyCodes: found.restrictedPropertyCodes ?? [],
    }
    return HttpResponse.json({ data })
  }),

  // 096 실행·감사 이력 (관리자면 전체, 아니면 본인 실행)
  http.get('/api/v1/audit/actions', async ({ request }) => {
    await delay()
    const isAdmin = mockUser.roleCode === 'ADMIN'
    const visible = isAdmin
      ? executions
      : executions.filter(
          (item) =>
            item.actorId === mockUser.accountId && item.actorType !== 'SYSTEM',
        )
    const searchParams = new URL(request.url).searchParams
    const matched = filterExecutions(visible, searchParams)
    const result = executionPage(matched, searchParams)
    return HttpResponse.json({
      ...result,
      meta: {
        ...result.meta,
        scope: isAdmin ? 'ALL' : 'MINE',
      },
    })
  }),
]
