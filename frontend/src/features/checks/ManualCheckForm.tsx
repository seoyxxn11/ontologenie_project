import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useRef, useState } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchRuleRevisions } from '../history/api'
import type { RuleRevision } from '../history/types'
import {
  requestManualCheck,
  type CheckRunAccepted,
  type ManualScope,
} from './api'

export default function ManualCheckForm({
  onAccepted,
}: {
  onAccepted: (run: CheckRunAccepted) => void
}) {
  const client = useQueryClient()
  const [all, setAll] = useState(true)
  const [code, setCode] = useState('')
  const [searchCode, setSearchCode] = useState('')
  const [selected, setSelected] = useState<RuleRevision[]>([])
  const submitting = useRef(false)
  const mounted = useRef(false)
  useEffect(() => {
    mounted.current = true
    return () => {
      mounted.current = false
    }
  }, [])
  const revisions = useQuery({
    queryKey: ['checks', 'rule-revisions', searchCode],
    queryFn: ({ signal }) => fetchRuleRevisions(searchCode, signal),
    enabled: !all && !!searchCode,
    retry: false,
  })
  const request = useMutation({
    mutationFn: (scope: ManualScope) => requestManualCheck(scope),
    retry: false,
    onSuccess: (run) => {
      void client.invalidateQueries({ queryKey: ['checks', 'runs'] })
      if (mounted.current) onAccepted(run)
    },
    onSettled: () => {
      submitting.current = false
    },
  })
  const valid = all || (selected.length > 0 && selected.length <= 100)
  const uncertain =
    request.error &&
    (!(request.error instanceof ApiError) ||
      ['network', 'server', 'invalid-response'].includes(request.error.kind))
  const active =
    revisions.data?.filter((rule) => rule.status === 'ACTIVE') ?? []

  return (
    <section aria-label="수동 검사 실행" className="management-panel space-y-4">
      <h2 className="font-semibold">수동 검사 실행</h2>
      <p className="text-sm text-slate-600">
        수집된 데이터를 기준으로 반영 중인 연결 규칙을 점검합니다. 승인 전 후보
        시험과 문제 상세의 재검증은 별도입니다.
      </p>
      <fieldset disabled={request.isPending} className="space-y-3">
        <legend className="mb-2 text-sm font-medium">검사 범위</legend>
        <div className="flex flex-wrap gap-4 text-sm">
          <label>
            <input
              type="radio"
              name="check-scope"
              checked={all}
              onChange={() => setAll(true)}
            />{' '}
            반영 중인 모든 규칙
          </label>
          <label>
            <input
              type="radio"
              name="check-scope"
              checked={!all}
              onChange={() => setAll(false)}
            />{' '}
            선택한 규칙
          </label>
        </div>
        {!all && (
          <div className="space-y-3">
            <form
              className="flex flex-wrap items-end gap-2"
              onSubmit={(event) => {
                event.preventDefault()
                if (code.trim()) {
                  if (searchCode === code.trim()) void revisions.refetch()
                  else setSearchCode(code.trim())
                }
              }}
            >
              <label className="flex flex-col gap-1 text-sm">
                연결 규칙 코드
                <input
                  value={code}
                  onChange={(event) => setCode(event.target.value)}
                  placeholder="규칙 이력에서 확인한 코드"
                />
              </label>
              <Button
                type="submit"
                variant="secondary"
                disabled={!code.trim()}
                pending={revisions.isFetching}
              >
                규칙 찾기
              </Button>
            </form>
            {searchCode && revisions.isPending && (
              <LoadingState message="규칙을 찾는 중…" />
            )}
            {revisions.isError && (
              <ErrorState
                error={revisions.error}
                onRetry={() => void revisions.refetch()}
              />
            )}
            {revisions.isSuccess && !active.length && (
              <EmptyState message="이 코드에 반영 중인 연결 규칙이 없습니다." />
            )}
            {revisions.isSuccess &&
              active.map((rule) => (
                <div
                  key={rule.id}
                  className="flex flex-wrap items-center gap-3 text-sm"
                >
                  <span>
                    {rule.code} · 개정 {rule.revision}
                    {rule.linkTypeName ? ` · ${rule.linkTypeName}` : ''}
                  </span>
                  <Button
                    variant="secondary"
                    disabled={
                      selected.some((item) => item.id === rule.id) ||
                      selected.length >= 100
                    }
                    onClick={() => setSelected((items) => [...items, rule])}
                  >
                    검사 대상에 추가
                  </Button>
                </div>
              ))}
            <p className="text-sm">선택한 규칙 {selected.length}/100개</p>
            <ul className="space-y-2">
              {selected.map((rule) => (
                <li
                  key={rule.id}
                  className="flex flex-wrap items-center gap-3 text-sm"
                >
                  {rule.code} · 개정 {rule.revision}
                  <Button
                    variant="secondary"
                    aria-label={`${rule.code} 검사 대상에서 제외`}
                    onClick={() =>
                      setSelected((items) =>
                        items.filter((item) => item.id !== rule.id),
                      )
                    }
                  >
                    제외
                  </Button>
                </li>
              ))}
            </ul>
          </div>
        )}
      </fieldset>
      <Button
        pending={request.isPending}
        disabled={!valid}
        onClick={() => {
          if (submitting.current || request.isPending || !valid) return
          submitting.current = true
          request.mutate(
            all
              ? { kind: 'ALL' }
              : {
                  kind: 'LINK_MAPPINGS',
                  linkMappingIds: selected.map((rule) => rule.id),
                },
          )
        }}
      >
        수동 검사 실행
      </Button>
      {request.error && <ErrorState error={request.error} />}
      {uncertain && (
        <p className="text-sm text-slate-600">
          접수 여부가 불확실합니다. 다시 요청하기 전에 창을 닫고 실행 목록을
          새로고침해 확인해 주세요. 요청을 자동으로 다시 보내지 않습니다.
        </p>
      )}
    </section>
  )
}
