import { useRouteError } from 'react-router'
import Button from './Button'
import PageHeader from './PageHeader'

function isModuleLoadError(error: unknown): boolean {
  if (!(error instanceof Error)) return false
  return (
    /^(Failed to fetch dynamically imported module|error loading dynamically imported module|Importing a module script failed)(:|\.|$)/i.test(
      error.message,
    ) || /^Unable to preload CSS for /.test(error.message)
  )
}

function RouteErrorPage({
  reload = () => window.location.reload(),
}: {
  reload?: () => void
}) {
  const error = useRouteError()
  const moduleLoadFailed = isModuleLoadError(error)

  return (
    <main className="auth-utility-page">
      <section className="auth-utility-panel space-y-6" role="alert">
        <PageHeader
          variant="message"
          title={
            moduleLoadFailed
              ? '새 버전을 불러와 주세요'
              : '화면을 표시하지 못했어요'
          }
          description={
            moduleLoadFailed
              ? '새 버전 배포 또는 일시적인 연결 문제로 화면 파일을 불러오지 못했습니다. 새로고침해 다시 접속해 주세요.'
              : '일시적인 오류가 발생했습니다. 새로고침한 뒤 다시 시도해 주세요.'
          }
        />
        <p className="text-sm text-slate-600">
          새로고침하면 저장하지 않은 입력 내용이 사라질 수 있습니다.
        </p>
        <Button onClick={reload}>새로고침</Button>
      </section>
    </main>
  )
}

export default RouteErrorPage
