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
        {code && <span title={code}>{code}</span>}
        <span title={typeLabel}>{typeLabel}</span>
      </span>
      <span className="explorer-object-name" title={name}>
        {name}
      </span>
      {context && (
        <span className="explorer-object-context" title={context}>
          {context}
        </span>
      )}
    </button>
  )
}

export default ObjectCard
