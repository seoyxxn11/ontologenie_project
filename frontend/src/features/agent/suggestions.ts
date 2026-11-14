import type { Account } from '../auth/types'
import type { ReferenceObject } from './types'

// 추천 질문(150). 문장은 화면에서 만든다(11-10 결정, API 086은 MVP에서 쓰지 않음).
// 참고 객체가 있으면 그 객체 이름으로 채운 문장을, 없으면 로그인한 사용자 역할에 맞는 고정 문장 5개를 보여 준다.
// 권한 없는 기능은 추천하지 않는다.
export type SuggestedQuestion = {
  // 버튼에 보일 짧은 이름
  label: string
  // 누르면 입력란에 채워질 문장
  text: string
}

type RoleCode = Account['roleCode']

// 재배정 계획은 설비에서만 의미가 있다.
const EQUIPMENT_TYPE_NAME = '설비'

// 재배정 계획 생성 권한(PLAN_CREATE)이 있는 역할. 화면은 역할만 알고 세부 권한은 받지 않으므로, 서버의 역할별 권한
// (V10__add_action_definitions.sql)과 같게 맞춘다. 서버에서 바꾸면 여기도 함께 바꾼다.
const PLAN_CREATE_ROLES: readonly RoleCode[] = ['ADMIN', 'PLANNER']

// 버튼에는 짧은 이름을 보여 한 줄에 들어가게 하고, 누르면 문장 전체를 입력란에 채운다.
// 문장은 배포한 시연 데이터(가상 프레스 데이터)의 코드로 쓰고, 지금 Agent가 답할 수 있는 질문만 둔다(2026-11-11 배포 사이트 확인).
// - 종류 전체 목록("진행 중인 작업지시")은 목록 조회 도구(359)와 해석 규칙(360)이 들어와 다시 넣었다.
// - "최근"처럼 범위가 정해지지 않은 기간은 매번 되묻게 되어 쓰지 않는다.
// - "비가동 이력"이라고 하면 실행 이력을 조회해 "없음"으로 답해, 관계를 따라가도록 "연결된 비가동 기록"이라고 쓴다.
const DOWNTIME_RECORDS: SuggestedQuestion = {
  label: 'P103 비가동',
  text: 'P103에 연결된 비가동 기록 보여줘',
}
const LINKED_OBJECTS: SuggestedQuestion = {
  label: 'P103 연결',
  text: 'P103에 연결된 정보는 뭐가 있어?',
}
const WORK_ORDER_PROGRESS: SuggestedQuestion = {
  label: '작업지시 진행',
  text: 'JB2610-0138 작업지시 진행 상황 알려줘',
}
// 목록 질문(360). 저장된 상태 값은 "진행"·"정비중"이지만 사용자가 쓰는 말로 묻는다.
const IN_PROGRESS_WORK_ORDERS: SuggestedQuestion = {
  label: '진행 중 작업지시',
  text: '진행 중인 작업지시 목록 보여줘',
}
const MAINTENANCE_EQUIPMENT: SuggestedQuestion = {
  label: '정비 중 설비',
  text: '지금 정비 중인 설비 있어?',
}
const REASSIGNMENT_PLAN: SuggestedQuestion = {
  label: '재배정 계획',
  text: 'P103에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘',
}

// 조회만 하는 역할의 문장. 생산(PRODUCTION) 데이터만 묻는다.
const VIEW_ONLY_QUESTIONS = [
  DOWNTIME_RECORDS,
  LINKED_OBJECTS,
  WORK_ORDER_PROGRESS,
  IN_PROGRESS_WORK_ORDERS,
  MAINTENANCE_EQUIPMENT,
]

// 참고 객체가 없을 때 역할별 고정 문장 5개. 정비 담당은 MVP에서 조회 사용자와 권한이 같다(V10 주석).
const GENERAL_QUESTIONS: Record<RoleCode, SuggestedQuestion[]> = {
  VIEWER: VIEW_ONLY_QUESTIONS,
  MAINTENANCE: VIEW_ONLY_QUESTIONS,
  PLANNER: [
    DOWNTIME_RECORDS,
    LINKED_OBJECTS,
    WORK_ORDER_PROGRESS,
    IN_PROGRESS_WORK_ORDERS,
    REASSIGNMENT_PLAN,
  ],
  // 고정자산은 원천 데이터 정책상 들이지 않는 표라 추천하지 않는다(397)
  ADMIN: [
    DOWNTIME_RECORDS,
    LINKED_OBJECTS,
    WORK_ORDER_PROGRESS,
    IN_PROGRESS_WORK_ORDERS,
    REASSIGNMENT_PLAN,
  ],
}

export function suggestedQuestions(
  reference: ReferenceObject | null,
  roleCode: RoleCode | null,
): SuggestedQuestion[] {
  if (!reference) {
    // 역할을 모르면 조회만 하는 문장을 보여 준다.
    return GENERAL_QUESTIONS[roleCode ?? 'VIEWER']
  }
  const { name } = reference
  const questions: SuggestedQuestion[] = [
    { label: '상태 확인', text: `${name}의 현재 상태와 주요 속성을 알려줘` },
    { label: '연결 관계', text: `${name}에 연결된 객체와 관계를 알려줘` },
    { label: '원천 근거', text: `${name}의 원천 근거를 알려줘` },
    { label: '실행 이력', text: `${name}의 최근 실행 이력을 알려줘` },
  ]
  if (
    reference.typeName === EQUIPMENT_TYPE_NAME &&
    roleCode !== null &&
    PLAN_CREATE_ROLES.includes(roleCode)
  ) {
    questions.push({
      label: '재배정 계획',
      text: `${name}에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘`,
    })
  }
  return questions
}
