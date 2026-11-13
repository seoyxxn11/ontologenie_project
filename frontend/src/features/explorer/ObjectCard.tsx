type ObjectCardProps = {
  code: string | null
  name: string
  typeLabel: string
  selected: boolean
  onSelect: () => void
  context?: string | null
}

function ObjectCard({
  code,
  name,
  typeLabel,
  selected,
  onSelect,
  context,
}: ObjectCardProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className="explorer-object-row"
    >
      <span className="explorer-object-meta">
        {code && <span>{code}</span>}
        <span>{typeLabel}</span>
      </span>
      <span className="explorer-object-name">{name}</span>
      {context && (
        <span className="block text-xs text-slate-500 [overflow-wrap:anywhere]">
          {context}
        </span>
      )}
    </button>
  )
}

export default ObjectCard
