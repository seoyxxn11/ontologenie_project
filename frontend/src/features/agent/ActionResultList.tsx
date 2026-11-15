import { useState } from 'react'
import Button from '../../components/Button'
import StatusBadge from '../../components/StatusBadge'
import ReassignmentPlanDetails from '../reassignment/ReassignmentPlanDetails'
import { actionStatusLabels, MES_UNCHANGED_NOTICE } from './labels'
import type { AgentAction, AgentActionStatus } from './types'

const REASSIGNMENT_ACTION_CODE = 'CREATE_REASSIGNMENT_PLAN'
const actionTones = {
  SUCCEEDED: 'success',
  RUNNING: 'info',
  BLOCKED: 'warning',
  FAILED: 'danger',
  CONFIRMATION_REQUIRED: 'warning',
  NOT_RUN: 'neutral',
} as const satisfies Record<AgentActionStatus, string>

// 재배정 계획을 실제로 만들었을 때만 그 계획 번호를 돌려준다. 막히거나 실패하면 null이다.
function createdPlanId(action: AgentAction) {
  const planId = action.result.planId
  return action.code === REASSIGNMENT_ACTION_CODE &&
    action.status === 'SUCCEEDED' &&
    typeof planId === 'number'
    ? planId
    : null
}

// 결과 옆에 붙일 설명: 막힌 이유, 실패 이유, 만든 계획
function actionDetail(action: AgentAction, planId: number | null) {
  if (action.status === 'BLOCKED') {
    return action.blockedReason
  }
  if (action.status === 'FAILED') {
    const message = action.result.errorMessage
    return typeof message === 'string' ? message : null
  }
  if (planId !== null) {
    return `계획 #${planId} · ${MES_UNCHANGED_NOTICE}`
  }
  return null
}

// Agent가 이 답변에서 실행한 작업 목록(160). 재배정 계획을 만들었으면 [계획 펼치기]로 답변 안에 계획을 펼친다.
function ActionResultList({ actions }: { actions: AgentAction[] }) {
  // 펼친 계획 번호. 한 번에 하나만 펼치고, 같은 버튼을 다시 누르면 접는다.
  const [openPlanId, setOpenPlanId] = useState<number | null>(null)

  return (
    <div className="agent-actions">
      <h3>실행 결과</h3>
      <ul aria-label="실행한 작업">
        {actions.map((action, index) => {
          const planId = createdPlanId(action)
          const detail = actionDetail(action, planId)
          const open = planId !== null && planId === openPlanId
          return (
            <li key={`${action.code}-${index}`}>
              <div className="agent-action-heading">
                <strong>{action.name}</strong>
                <StatusBadge tone={actionTones[action.status]}>
                  {actionStatusLabels[action.status]}
                </StatusBadge>
              </div>
              {detail && <p className="agent-action-detail">{detail}</p>}
              {planId !== null && (
                <Button
                  variant="text"
                  className="agent-plan-toggle"
                  aria-expanded={open}
                  onClick={() => setOpenPlanId(open ? null : planId)}
                >
                  {open ? '계획 접기' : '계획 펼치기'}
                </Button>
              )}
              {planId !== null && open && (
                <div className="basis-full">
                  <ReassignmentPlanDetails planId={planId} />
                </div>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}

export default ActionResultList
