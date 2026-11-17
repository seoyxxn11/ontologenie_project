import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { fetchObject } from '../explorer/api'
import { formatObjectKey } from '../explorer/format'

export default function PlanObjectLink({ objectId }: { objectId: number }) {
  const object = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
    staleTime: 60_000,
    retry: false,
  })
  const label = object.data
    ? (formatObjectKey(object.data) ?? object.data.displayName)
    : `#${objectId}`
  return (
    <Link to={`/explorer?object=${objectId}`} title={object.data?.displayName}>
      {label}
    </Link>
  )
}
