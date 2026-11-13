import { formatKstDateTime } from '../../utils/dateTime'
export const runTime = (value: string | null) =>
  value ? formatKstDateTime(value) : '—'
