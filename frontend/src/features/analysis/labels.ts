import type { AnalysisStatus } from './types'

type Tone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

// 목업의 분석 실행 번호 표기 (예: ANALYSIS-002)
export function analysisCode(id: number) {
  return `ANALYSIS-${String(id).padStart(3, '0')}`
}

export const analysisStatusLabels: Record<
  AnalysisStatus,
  { label: string; tone: Tone }
> = {
  QUEUED: { label: '대기 중', tone: 'info' },
  RUNNING: { label: '분석 중', tone: 'info' },
  SUCCEEDED: { label: '완료', tone: 'success' },
  FAILED: { label: '실패', tone: 'danger' },
}

export function isAnalysisRunning(status: AnalysisStatus) {
  return status === 'QUEUED' || status === 'RUNNING'
}

// 분석을 맡긴 뒤 진행 상태와 후보를 보는 연결 규칙 화면 주소
export function analysisRunPath(run: { id: number; joined?: boolean }) {
  return `/admin/rules?analysis=${run.id}${run.joined ? '&joined=1' : ''}`
}
