type PaginationProps = {
  page: number
  size: number
  totalElements: number
  onChange: (page: number) => void
  pending?: boolean
  compact?: boolean
  showSummary?: boolean
  showSinglePage?: boolean
}

function Pagination({
  page,
  size,
  totalElements,
  onChange,
  pending = false,
  compact = false,
  showSummary = true,
  showSinglePage = false,
}: PaginationProps) {
  const totalPages = size > 0 ? Math.ceil(totalElements / size) : 0
  const currentPage = Math.max(0, Math.min(page, totalPages - 1))

  useEffect(() => {
    if (!pending && page !== currentPage) onChange(currentPage)
  }, [page, currentPage, totalPages, pending, onChange])

  if (totalPages === 0 || (totalPages === 1 && !showSinglePage)) {
    return showSummary ? (
      <p className="ui-pagination-empty" aria-live="polite" aria-atomic="true">
        {totalPages > 0 ? `총 ${totalElements}건` : '0건'}
      </p>
    ) : null
  }
  const start = Math.max(0, Math.min(currentPage - 2, totalPages - 5))
  const pages = Array.from(
    { length: Math.min(5, totalPages) },
    (_, index) => start + index,
  )

  function goTo(nextPage: number) {
    if (
      !pending &&
      nextPage >= 0 &&
      nextPage < totalPages &&
      nextPage !== currentPage
    ) {
      onChange(nextPage)
    }
  }

  return (
    <nav
      aria-label="페이지 이동"
      className="ui-pagination"
      aria-busy={pending || undefined}
    >
      <div className="ui-pagination-controls">
        <button
          type="button"
          aria-label="첫 페이지"
          disabled={pending || currentPage === 0}
          onClick={() => goTo(0)}
        >
          {compact ? '«' : '처음'}
        </button>
        <button
          type="button"
          onClick={() => goTo(currentPage - 1)}
          disabled={pending || currentPage === 0}
          aria-label="이전 페이지"
        >
          {compact ? '‹' : '이전'}
        </button>
        {pages.map((number) => (
          <button
            key={number}
            type="button"
            aria-label={`${number + 1}페이지로 이동`}
            aria-current={number === currentPage ? 'page' : undefined}
            disabled={pending || number === currentPage}
            onClick={() => goTo(number)}
          >
            {number + 1}
          </button>
        ))}
        <button
          type="button"
          onClick={() => goTo(currentPage + 1)}
          disabled={pending || currentPage + 1 >= totalPages}
          aria-label="다음 페이지"
        >
          {compact ? '›' : '다음'}
        </button>
        <button
          type="button"
          aria-label="마지막 페이지"
          disabled={pending || currentPage + 1 >= totalPages}
          onClick={() => goTo(totalPages - 1)}
        >
          {compact ? '»' : '마지막'}
        </button>
      </div>
      {showSummary && (
        <p
          className="ui-pagination-summary"
          aria-live="polite"
          aria-atomic="true"
        >
          {currentPage + 1} / {totalPages}쪽 · {totalElements}건
        </p>
      )}
    </nav>
  )
}

export default Pagination
import { useEffect } from 'react'
