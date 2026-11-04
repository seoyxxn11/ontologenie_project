export type Proposal = {
  id: number
  analysisRunId: number | null
  origin: string
  itemKind: string
  revision: number
  payload: Record<string, unknown>
  evidence: Record<string, unknown>
  aiConfidence: number | null
  reviewStatus: string
  applyStatus: string
  applyError: string | null
  appliedAt: string | null
  latestCheck: {
    checkRunId: number
    status: string
    matchedCount: number | null
    totalCount: number | null
    matchRate: number | null
  } | null
}

export type ProposalFilters = {
  origin?: string
  itemKind?: string
  reviewStatus?: string
  applyStatus?: string
}

export type ProposalOption = {
  id: number
  optionNo: number
  revision: number
  payload: Record<string, unknown>
  evidence: Record<string, unknown>
  aiConfidence: number | null
}

export type ProposalWriteResult = Omit<Proposal, 'latestCheck'> & {
  checkRunId: number
}
