import type { Execution } from './types'

type Values = Record<string, unknown>

// 화면에 풀어 쓰지 않는 내부 값. 기술 정보 JSON에는 그대로 남는다.
const internalKeys = ['rowVersion']

// 대상 유형별로 입력·결과에서 대상 번호를 담는 항목 이름
const targetIdKeys: Record<string, string> = {
  data_source: 'dataSourceId',
  account: 'accountId',
  action_definition: 'actionDefinitionId',
  check_run: 'checkRunId',
  link_mapping: 'linkMappingId',
  proposal_item: 'proposalItemId',
}

export function targetIdKey(targetType?: string | null) {
  return targetType && Object.hasOwn(targetIdKeys, targetType)
    ? targetIdKeys[targetType]
    : undefined
}

function sameValue(left: unknown, right: unknown) {
  return JSON.stringify(left) === JSON.stringify(right)
}

// 입력값·처리 결과에서 화면에 이미 있는 내용을 뺀다.
// 내부 값, 대상 줄에 나온 대상 번호, 입력값과 똑같은 결과 항목이 대상이다.
export function summarizeExecutionValues(
  execution: Pick<Execution, 'targetType' | 'targetId'>,
  input: Values | null,
  result: Values | null,
) {
  const targetKey = targetIdKey(execution.targetType)
  const shown = (code: string, value: unknown) =>
    !internalKeys.includes(code) &&
    !(
      code === targetKey &&
      execution.targetId != null &&
      value === execution.targetId
    )
  const original = input ?? {}
  return {
    input: Object.fromEntries(
      Object.entries(original).filter(([code, value]) => shown(code, value)),
    ),
    result: Object.fromEntries(
      Object.entries(result ?? {}).filter(
        ([code, value]) =>
          shown(code, value) &&
          !(Object.hasOwn(original, code) && sameValue(original[code], value)),
      ),
    ),
  }
}
