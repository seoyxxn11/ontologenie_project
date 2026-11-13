import { useMutationState } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import Button from '../../components/Button'
import { ApiError } from '../../api/errors'

// 요청 본문·자격 증명은 읽지 않는다. 현재 세션의 요청 상태와 이동 경로만 표시한다.
export default function FormSaveStatus() {
  const [dismissed, setDismissed] = useState<number[]>([])
  const requests = useMutationState({
    filters: { mutationKey: ['form-save'] },
    select: (mutation) => {
      const meta = mutation.meta?.formSave as
        { label: string; href: string } | undefined
      const data = mutation.state.data as { saved?: { id: number } } | undefined
      const error = mutation.state.error
      return {
        id: mutation.mutationId,
        key: JSON.stringify(mutation.options.mutationKey),
        meta,
        status: mutation.state.status,
        uncertain:
          error != null &&
          (!(error instanceof ApiError) ||
            ['network', 'server', 'http', 'invalid-response'].includes(
              error.kind,
            )),
        message: error instanceof Error ? error.message : '',
        href: data?.saved
          ? `/admin/sources?source=${data.saved.id}&tab=connection`
          : meta?.href,
      }
    },
  })
  const latest = requests.filter(
    (item, index) =>
      item.meta &&
      item.status !== 'idle' &&
      !requests.slice(index + 1).some((next) => next.key === item.key),
  )
  const visible = latest.filter((item) => !dismissed.includes(item.id))
  if (!visible.length) return null
  return (
    <section aria-label="저장 요청 상태" className="ui-feedback mb-5 space-y-3">
      <h2 className="text-base font-semibold leading-6">저장 요청 상태</h2>
      {visible.map((item) => (
        <div key={item.id} className="space-y-2">
          <p role={item.status === 'error' ? 'alert' : 'status'}>
            {item.meta!.label}:{' '}
            {item.status === 'pending'
              ? '저장 요청을 처리 중입니다. 창을 닫아도 요청은 취소되지 않습니다.'
              : item.status === 'success'
                ? '저장 응답을 받았습니다. 대상에서 반영 상태와 다음 행동을 확인하세요.'
                : item.uncertain
                  ? '저장 여부가 불확실합니다. 다시 저장하기 전에 대상 목록에서 확인하세요.'
                  : `저장하지 못했습니다. ${item.message}`}
          </p>
          <div className="flex flex-wrap items-center gap-3">
            <Link className="underline" to={item.href!}>
              저장 대상 확인
            </Link>
            {item.status !== 'pending' && (
              <Button
                variant="text"
                aria-label={`${item.meta!.label} 저장 안내 닫기`}
                onClick={() => setDismissed((before) => [...before, item.id])}
              >
                안내 닫기
              </Button>
            )}
          </div>
        </div>
      ))}
    </section>
  )
}
