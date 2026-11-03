import type { ComponentProps } from 'react'

type DataTableProps = ComponentProps<'table'> & { label: string }

function DataTable({
  label,
  className = '',
  children,
  ...props
}: DataTableProps) {
  return (
    <div className="overflow-x-auto rounded-lg border border-slate-200">
      <table
        {...props}
        aria-label={label}
        className={`w-full text-left text-sm ${className}`}
      >
        {children}
      </table>
    </div>
  )
}

export default DataTable
