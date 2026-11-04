import Button from '../../components/Button'
import { transformLabels, type TransformDraft } from './ruleDraft'

export default function TransformEditor({
  label,
  steps,
  onChange,
}: {
  label: string
  steps: TransformDraft[]
  onChange: (steps: TransformDraft[]) => void
}) {
  function replace(index: number, step: TransformDraft) {
    onChange(
      steps.map((value, position) => (position === index ? step : value)),
    )
  }
  return (
    <fieldset className="min-w-0 space-y-3 rounded-lg bg-slate-50 p-3">
      <legend className="text-sm font-medium">{label}</legend>
      {!steps.length && (
        <p className="text-xs text-slate-500">
          변환 없이 원래 값을 비교합니다.
        </p>
      )}
      {steps.map((step, index) => (
        <div
          key={index}
          className="space-y-2 rounded border border-slate-200 bg-white p-3"
        >
          <label className="block text-sm">
            {label} {index + 1} 함수
            <select
              className="mt-1 w-full rounded border p-2"
              value={step.op}
              onChange={(event) =>
                replace(index, { op: event.target.value, arg: '' })
              }
            >
              {Object.entries(transformLabels).map(([value, text]) => (
                <option key={value} value={value}>
                  {text}
                </option>
              ))}
            </select>
          </label>
          {['STRIP_PREFIX', 'ADD_PREFIX', 'PAD_ZEROS', 'MAP_VALUE'].includes(
            step.op,
          ) && (
            <label className="block text-sm">
              {step.op === 'MAP_VALUE'
                ? '값 대응표'
                : step.op === 'PAD_ZEROS'
                  ? '자릿수'
                  : '접두어'}
              <input
                className="mt-1 w-full rounded border p-2"
                value={step.arg}
                onChange={(event) =>
                  replace(index, { ...step, arg: event.target.value })
                }
                placeholder={
                  step.op === 'MAP_VALUE' ? '{"PR03":"PR-03"}' : undefined
                }
              />
            </label>
          )}
          <div className="flex flex-wrap gap-2">
            <Button
              variant="secondary"
              disabled={index === 0}
              onClick={() => {
                const next = [...steps]
                ;[next[index - 1], next[index]] = [next[index], next[index - 1]]
                onChange(next)
              }}
            >
              위로
            </Button>
            <Button
              variant="secondary"
              disabled={index === steps.length - 1}
              onClick={() => {
                const next = [...steps]
                ;[next[index], next[index + 1]] = [next[index + 1], next[index]]
                onChange(next)
              }}
            >
              아래로
            </Button>
            <Button
              variant="secondary"
              onClick={() =>
                onChange(steps.filter((_, position) => position !== index))
              }
            >
              변환 삭제
            </Button>
          </div>
        </div>
      ))}
      <Button
        variant="secondary"
        onClick={() => onChange([...steps, { op: 'TRIM', arg: '' }])}
      >
        {label} 추가
      </Button>
    </fieldset>
  )
}
