import type {
  AccountPurpose,
  CheckStatus,
  ConnectionStatus,
  SourceKind,
} from './types'

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

// 목업의 원천 유형 이름
export const sourceKindLabels: Record<SourceKind, string> = {
  POSTGRES: '구조화 데이터',
  CSV: '구조화 데이터',
  DOCUMENT: '문서·기록',
}

// 목업의 접속 확인 칸
export const connectionStatusLabels: Record<
  ConnectionStatus,
  { label: string; className: string; tone: Tone }
> = {
  UNVERIFIED: {
    label: '수집 확인 필요',
    className: 'text-amber-700',
    tone: 'warning',
  },
  // 읽기 전용 세션 접속과 SELECT 가능한 표만 확인한다(쓰기 권한은 검사하지 않음)
  CONNECTED: {
    label: '수집 접속 확인됨',
    className: 'text-slate-500',
    tone: 'success',
  },
  FAILED: {
    label: '수집 접속 실패',
    className: 'text-red-700',
    tone: 'danger',
  },
}

export const checkStatusLabels: Record<
  CheckStatus,
  { label: string; tone: Tone }
> = {
  QUEUED: { label: '대기 중', tone: 'info' },
  RUNNING: { label: '검사 중', tone: 'info' },
  SUCCEEDED: { label: '완료', tone: 'success' },
  FAILED: { label: '실패', tone: 'danger' },
}

export const purposeLabels: Record<AccountPurpose, string> = {
  SYNC: '수집 계정',
  AI: 'AI 분석 계정',
}
