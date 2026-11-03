type ObjectCardProps = {
  code: string
  name: string
  typeLabel: string
  selected: boolean
  onSelect: () => void
}

function ObjectCard({
  code,
  name,
  typeLabel,
  selected,
  onSelect,
}: ObjectCardProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={`block w-full rounded-lg border px-4 py-3 text-left ${
        selected
          ? 'border-slate-900 bg-slate-100'
          : 'border-slate-200 bg-white hover:bg-slate-50'
      }`}
    >
      <span className="flex items-center justify-between gap-2">
        <span className="text-xs font-semibold text-slate-500">{code}</span>
        <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">
          {typeLabel}
        </span>
      </span>
      <span className="mt-1 block font-medium text-slate-900">{name}</span>
    </button>
  )
}

export default ObjectCard
