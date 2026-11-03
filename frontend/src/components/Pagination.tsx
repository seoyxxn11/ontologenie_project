type PaginationProps = {
  page: number
  size: number
  totalElements: number
  onChange: (page: number) => void
}

function Pagination({ page, size, totalElements, onChange }: PaginationProps) {
  const totalPages = Math.max(1, Math.ceil(totalElements / size))

  return (
    <nav
      aria-label="페이지 이동"
      className="flex items-center justify-center gap-3 text-sm text-slate-600"
    >
      <button
        type="button"
        onClick={() => onChange(page - 1)}
        disabled={page === 0}
        aria-label="이전 페이지"
        className="rounded px-2 py-1 hover:bg-slate-100 disabled:text-slate-300"
      >
        ←
      </button>
      <span>
        {page + 1} / {totalPages}쪽 · {totalElements}건
      </span>
      <button
        type="button"
        onClick={() => onChange(page + 1)}
        disabled={page + 1 >= totalPages}
        aria-label="다음 페이지"
        className="rounded px-2 py-1 hover:bg-slate-100 disabled:text-slate-300"
      >
        →
      </button>
    </nav>
  )
}

export default Pagination
