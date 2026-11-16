import { delay, http, HttpResponse } from 'msw'
import type { Account } from '../features/auth/types'
import type { Execution, ExecutionDetail } from '../features/audit/types'
import type { ObjectInstanceDetail } from '../features/explorer/types'

export const uiPreviewCases = {
  demo: 'Demo · 기본',
  worst: 'Worst · 긴 값',
  empty: 'Empty · 0건',
  one: 'One · 1건',
  many: 'Many · 1,284건',
  error: 'Error · 실패',
  forbidden: '권한 제한',
  loading: 'Loading · 15초',
} as const
export type UiPreviewCase = keyof typeof uiPreviewCases

export function uiPreviewCase(value: string | null): UiPreviewCase {
  return value && Object.hasOwn(uiPreviewCases, value)
    ? (value as UiPreviewCase)
    : 'demo'
}

const longName =
  '광주 제2사업장 생산기획·설비정비 통합운영 담당 Aleksandra Wiśniewska-Kowalczyk'
const longTarget =
  '광주제2사업장_정밀가공사업부_자동차부품생산라인_프레스설비_야간교대작업지시_대체설비재배정검토대상_2026년하반기'
const report =
  '야간 교대 인수인계에서 설비 정지와 자재 입고 지연을 확인했습니다. 생산기획팀과 정비 담당자가 대체 설비의 가용 시간, 품질 검사 일정, 작업지시 우선순위를 다시 확인해야 합니다. '

export function uiPreviewAccount(selected: UiPreviewCase): Account {
  return {
    id: 7,
    loginId:
      selected === 'worst'
        ? 'gwangju_second_factory_production_planning_and_maintenance_2026'
        : 'demo-admin',
    displayName: selected === 'worst' ? longName : '로컬 체험 관리자',
    roleCode: 'ADMIN',
    status: 'ACTIVE',
    mustChangePassword: false,
    rowVersion: 0,
  }
}

export function uiPreviewObject(selected: UiPreviewCase): ObjectInstanceDetail {
  return {
    id: 990001,
    objectTypeId: 3,
    objectTypeName: '설비',
    displayName:
      selected === 'worst'
        ? 'GWANGJU_SECOND_FACTORY_PRODUCTION_LINE_A_PRESS_EQUIPMENT_REASSIGNMENT_REVIEW_2026_Q4'
        : '프레스 3호기',
    properties: {},
    propertyNames: {},
    restrictedPropertyCodes: [],
    status: 'ACTIVE',
    firstCollectedAt: '2026-11-16T09:00:00+09:00',
    sourceSystemName: selected === 'worst' ? longTarget : 'MES 운영 DB',
    sourceResourceKey: 'mes.equipment',
    sourceKey: {
      equipment_code:
        selected === 'worst'
          ? 'GWANGJU_FACTORY_SECOND_PRESS_LINE_A_EQUIPMENT_PR_03_2026'
          : 'PR-03',
    },
  }
}

export function uiPreviewExecutions(selected: UiPreviewCase): Execution[] {
  const count =
    selected === 'empty'
      ? 0
      : selected === 'one'
        ? 1
        : selected === 'many'
          ? 1284
          : selected === 'worst'
            ? 21
            : 4
  const names = [longName, 'J', 'Đặng Thị Ngọc Hân', '👩🏽‍💻 Priya', null]
  const statuses: Execution['status'][] = [
    'FAILED',
    'SUCCEEDED',
    'BLOCKED',
    'RUNNING',
  ]
  return Array.from({ length: count }, (_, index) => ({
    id: 7000 + index,
    requestId: `00000000-0000-4000-8000-${String(index).padStart(12, '0')}`,
    eventKind: 'ACTION',
    category: 'OPERATION',
    actionDefinitionId: 101,
    actionCode: 'CREATE_REASSIGNMENT_PLAN',
    actionName:
      selected === 'worst'
        ? '광주 제2사업장 긴급 설비 정지에 따른 작업지시·공정 대체 설비 재배정 계획 생성'
        : '재배정 계획 생성',
    actorType: 'USER',
    actorId: index === 0 ? 7 : 10 + index,
    actorName: selected === 'worst' ? names[index % names.length] : '박영희',
    actorRoleCode: 'PLANNER',
    entryPoint: 'WEB',
    targetType: 'object_instance',
    targetId: 301 + index,
    targetLabel:
      selected === 'worst'
        ? index === 1
          ? null
          : longTarget
        : `프레스 ${index + 1}호기`,
    status: statuses[index % statuses.length],
    requestedAt:
      selected === 'worst' && index === 0
        ? '2025-12-31T23:30:00-08:00'
        : '2026-11-16T09:00:00+09:00',
    finishedAt: index % 4 === 3 ? null : '2026-11-16T09:01:00+09:00',
    changeCount: 0,
  }))
}

export function uiPreviewDetail(
  item: Execution,
  selected: UiPreviewCase,
): ExecutionDetail {
  const worst = selected === 'worst'
  return {
    id: item.id,
    status: item.status,
    input: {
      fromEquipmentId: item.targetId,
      reason: worst ? report.repeat(12) : '정지 설비의 작업을 재검토합니다.',
      reference: worst
        ? 'https://example.com/workspaces/gwangju-second-factory/production-planning/reassignment-review/2026-q4?revision=12'
        : '현장 인수인계',
      note: worst ? '<b>원문 유지</b> &amp; **강조 표기**' : null,
      technician: null,
    },
    result: { itemCount: worst ? 1284 : 1 },
    failureReason:
      item.status === 'FAILED' ? (worst ? report.repeat(8) : report) : null,
    blockedReason:
      item.status === 'BLOCKED'
        ? '같은 작업지시의 재배정 계획이 이미 있습니다.'
        : null,
    restrictedPropertyCodes: worst ? ['technician'] : [],
  }
}

function previewError(status: number, message: string) {
  return HttpResponse.json(
    {
      error: {
        code: 'UI_PREVIEW',
        message,
        details: {},
        requestId: 'local-ui-qa',
      },
    },
    { status },
  )
}

export function uiPreviewHandlers(selected: UiPreviewCase) {
  const items = uiPreviewExecutions(selected)
  return [
    http.all('/api/v1/*', ({ request }) => {
      if (request.method !== 'GET')
        return previewError(
          403,
          '로컬 비교 화면에서는 저장·실행 요청을 보내지 않습니다.',
        )
    }),
    http.get('/api/v1/audit/actions', async ({ request }) => {
      if (selected === 'loading') await delay(15000)
      if (selected === 'error' || selected === 'forbidden')
        return previewError(
          selected === 'error' ? 500 : 403,
          '모의 오류 응답입니다.',
        )
      const params = new URL(request.url).searchParams
      const matched = items.filter(
        (item) =>
          (!params.get('status') || item.status === params.get('status')) &&
          (!params.get('category') ||
            item.category === params.get('category')) &&
          (!params.get('from') ||
            Date.parse(item.requestedAt) >= Date.parse(params.get('from')!)) &&
          (!params.get('to') ||
            Date.parse(item.requestedAt) < Date.parse(params.get('to')!)) &&
          (!params.get('targetType') ||
            item.targetType === params.get('targetType')) &&
          (!params.get('targetId') ||
            item.targetId === Number(params.get('targetId'))),
      )
      const page = Math.max(0, Math.floor(Number(params.get('page'))) || 0)
      const size = 20
      return HttpResponse.json({
        data: matched.slice(page * size, (page + 1) * size),
        meta: {
          page,
          size,
          totalElements: matched.length,
          hasNext: (page + 1) * size < matched.length,
          scope: 'ALL',
        },
      })
    }),
    http.get('/api/v1/audit/actions/:id', ({ params }) => {
      const item = items.find((item) => item.id === Number(params.id))
      return item
        ? HttpResponse.json({ data: uiPreviewDetail(item, selected) })
        : previewError(404, '모의 실행 기록을 찾을 수 없습니다.')
    }),
    http.get('/api/v1/audit/actions/:id/changes', () =>
      HttpResponse.json({ data: { changes: [], restrictedPropertyCodes: [] } }),
    ),
    http.get('/api/v1/objects/990001/links', () =>
      HttpResponse.json({
        data: [],
        meta: { page: 0, size: 100, totalElements: 0, hasNext: false },
      }),
    ),
  ]
}
