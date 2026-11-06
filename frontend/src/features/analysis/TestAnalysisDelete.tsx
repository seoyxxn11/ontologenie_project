import { useMutation, useQueryClient } from '@tanstack/react-query'
import { apiDelete } from '../../api/client'
import Button from '../../components/Button'

// 테스트로 돌린 분석과 그 제안을 한 번에 지우는 임시 버튼(S15P31C106-307). 시연 전 없앤다.
// 없앨 때는 이 파일과 AnalysisPanel의 import·<TestAnalysisDelete> 한 줄, 백엔드 TestAnalysisCleanup*을 지운다.
export default function TestAnalysisDelete({
  onDeleted,
}: {
  onDeleted: () => void
}) {
  const queryClient = useQueryClient()
  const remove = useMutation({
    mutationFn: () => apiDelete('/dev/test-analyses'),
    onSuccess: () => {
      onDeleted()
      void queryClient.invalidateQueries({ queryKey: ['analyses'] })
      void queryClient.invalidateQueries({ queryKey: ['proposals'] })
    },
  })
  return (
    <span className="inline-flex flex-col items-end gap-1">
      <Button
        variant="secondary"
        pending={remove.isPending}
        onClick={() => {
          if (
            window.confirm(
              '끝난 분석과 그 제안을 모두 지웁니다. 되돌릴 수 없습니다. 테스트 데이터만 있는 서버가 맞나요?',
            )
          )
            remove.mutate()
        }}
      >
        테스트 분석 모두 지우기
      </Button>
      {remove.isError && (
        <span role="alert" className="text-xs text-red-700">
          {remove.error instanceof Error
            ? remove.error.message
            : '지우지 못했습니다.'}
        </span>
      )}
    </span>
  )
}
